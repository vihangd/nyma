(ns session-allows-scope-test
  "\"Allow for this session\" lasts for THIS agent's session — no longer.

   The grant set was one process-wide atom that nothing ever reset: a grant
   outlived /new and /resume, and in the gateway (one process, many
   conversations) one chat's grant silently applied to every other chat."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]))

(defn- agent-with-prompt-counter
  "A real agent whose permission gate always asks; the UI answers
   'Allow for this session' and counts how often it is asked."
  [prompts]
  (let [a (create-agent {:model #js {:modelId "test"} :system-prompt "s"})]
    (set! (.-extension-api a)
          #js {:ui #js {:available true
                        :select (fn [_q _o] (swap! prompts inc)
                                  (js/Promise.resolve "Allow for this session"))}})
    ((:on (:events a)) "permission_request" (fn [_] #js {:decision "ask"}))
    a))

(defn- ^:async run! [a]
  (js-await ((:execute (:middleware a)) "bash"
                                        #js {:execute (fn [_] "ok")}
                                        {:command "ls"})))

(defn ^:async test-scope []
  (let [p1 (atom 0) p2 (atom 0)
        a1 (agent-with-prompt-counter p1)
        a2 (agent-with-prompt-counter p2)]
    (js-await (run! a1))
    (js-await (run! a1))
    ;; granted once, then remembered for this session
    (-> (expect @p1) (.toBe 1))
    ;; …but not for a different agent (another gateway conversation)
    (js-await (run! a2))
    (-> (expect @p2) (.toBe 1))
    ;; a new session (/new, /resume, /fork) asks again
    ((:emit (:events a1)) "session_start" {:reason "new"})
    (js-await (run! a1))
    (-> (expect @p1) (.toBe 2))
    ;; and so does a branch switch
    ((:emit (:events a1)) "session_switch" {})
    (js-await (run! a1))
    (-> (expect @p1) (.toBe 3))))

(describe "session allow scope"
          (fn []
            (it "is per agent, and ends with the session" test-scope)))
