(ns permission-prompt-original-command.test
  "The permission prompt shows the command the user wrote, not bash_suite's
   `unset LD_PRELOAD …` wrapper around it.

   Observed on the binary: `$ unset LD_PRELOAD LD_LIBRARY_PATH DYLD_INSERT_…`
   truncated before the user's own command ever appeared. before_tool_call
   rewrote the args first; the gate prompted on the rewritten ones."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [before-hook-compat-enter resolve-ask reset-session-allows!]]))

(def ^:private preamble "unset LD_PRELOAD LD_LIBRARY_PATH DYLD_INSERT_LIBRARIES 2>/dev/null ; ")

(defn- rewriting-events []
  (let [events (create-event-bus)]
    ((:on events) "before_tool_call"
                  (fn [data]
                    #js {:args #js {:command (str preamble (.. data -args -command))}}))
    events))

(describe "permission prompt shows the original command"
          (fn []
            (it "before_tool_call keeps the pre-rewrite args beside the rewritten ones"
                (^:async fn []
                  (let [ctx (js-await (before-hook-compat-enter (rewriting-events)
                                                                {:tool-name "bash" :args {:command "touch perm-one.txt"}}))]
                    (-> (expect (str (aget (:args ctx) "command"))) (.toContain "LD_PRELOAD"))
                    (-> (expect (:command (:original-args ctx))) (.toBe "touch perm-one.txt")))))

            (it "the ask prompt contains the user's command and not the wrapper"
                (^:async fn []
                  (reset-session-allows!)
                  (let [seen (atom nil)
                        ui   #js {:available true
                                  :select (fn [q _o] (reset! seen q) (js/Promise.resolve "Allow once"))}
                        ctx  (js-await (before-hook-compat-enter (rewriting-events)
                                                                 {:tool-name "bash" :args {:command "touch perm-one.txt"}}))
                        out  (js-await (resolve-ask nil ctx ui "bash" nil))]
                    (-> (expect (:cancelled out)) (.toBeFalsy))
                    (-> (expect @seen) (.toContain "touch perm-one.txt"))
                    (-> (expect @seen) (.not.toContain "LD_PRELOAD")))))

            (it "a middleware rewrite earlier in the chain (cwd-manager's cd) stays the original"
                (^:async fn []
                  (let [seen (atom nil)
                        events (rewriting-events)
                        _   ((:on events) "before_tool_call" (fn [d] (reset! seen (some-> d .-originalArgs .-command)) nil) 200)
                        ctx (js-await (before-hook-compat-enter events
                                                                {:tool-name "bash"
                                                                 :args {:command "cd '/x' && ls"}
                                                                 :original-args {:command "ls"}}))]
                    (-> (expect (:command (:original-args ctx))) (.toBe "ls"))
                    ;; handlers see it too: the classifier keys by it
                    (-> (expect @seen) (.toBe "ls")))))))
