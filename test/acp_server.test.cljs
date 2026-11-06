(ns acp-server-test
  "`nyma --mode acp`: nyma as an Agent Client Protocol server.

   nyma could drive other agents over ACP but could not be driven: Zed,
   JetBrains and the other ACP editors had no way to run it."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.modes.acp :as acp]
            [test-util.fake-model-server :as fms]))

(describe "acp mapping"
          (fn []
            (it "tool kinds and titles"
                (fn []
                  (-> (expect (acp/tool-kind "bash")) (.toBe "execute"))
                  (-> (expect (acp/tool-kind "edit")) (.toBe "edit"))
                  (-> (expect (acp/tool-kind "token-suite__multi_edit")) (.toBe "edit"))
                  (-> (expect (acp/tool-kind "mcp__x")) (.toBe "other"))
                  (-> (expect (acp/tool-title "bash" {:command "ls -la"})) (.toBe "bash: ls -la"))))
            (it "prompt blocks become content"
                (fn []
                  (-> (expect (acp/prompt->content #js [#js {:type "text" :text "hi"}])) (.toBe "hi"))
                  (let [c (acp/prompt->content #js [#js {:type "text" :text "see"}
                                                    #js {:type "resource" :resource #js {:uri "file:///a/b.txt" :text "B"}}
                                                    #js {:type "image" :data "AQID" :mimeType "image/png"}])]
                    (-> (expect (.-text (aget c 0))) (.toContain "<file path=\"/a/b.txt\">\nB\n</file>"))
                    (-> (expect (.-type (aget c 1))) (.toBe "image")))))))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn ^:async wait-for [pred ms]
  (let [end (+ (js/Date.now) ms)]
    (loop []
      (cond (pred) true
            (> (js/Date.now) end) false
            :else (do (js-await (js/Bun.sleep 50)) (recur))))))

(defn ^:async pump!
  "Call `on-msg` with each JSON line of `stream`, until it ends."
  [stream on-msg]
  (let [dec (js/TextDecoder.) buf (atom "")]
    (loop [it (.getReader stream)]
      (let [r (js-await (.read it))]
        (when-not (.-done r)
          (swap! buf str (.decode dec (.-value r) #js {:stream true}))
          (let [parts (.split @buf "\n")]
            (reset! buf (.pop parts))
            (doseq [l parts]
              (when (seq l) (on-msg (js/JSON.parse l)))))
          (recur it))))))

(defn ^:async test-session []
  (let [srv   (fms/start!)
        home  (fms/temp-home! (:base-url srv))
        msgs  (atom [])
        proc  (js/Bun.spawn #js ["bun" cli "--mode" "acp" "--model" "faketest/m1"]
                            #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "pipe"
                                 :env (js/Object.assign #js {} js/process.env
                                                        #js {"HOME" home "NYMA_NO_MODEL_DISCOVERY" "1"})})
        send! (fn [o] (.write (.-stdin proc) (str (js/JSON.stringify (clj->js o)) "\n")) (.flush (.-stdin proc)))
        ;; read stdout lines; answer permission requests with "Allow once"
        _     (pump! (.-stdout proc)
                     (fn [m]
                       (swap! msgs conj m)
                       (when (= "session/request_permission" (.-method m))
                         (send! {:jsonrpc "2.0" :id (.-id m)
                                 :result {:outcome {:outcome "selected" :optionId "0"}}}))))
        by-id (fn [id] (first (filter #(= id (.-id %)) @msgs)))
        updates (fn [] (keep #(when (= "session/update" (.-method %)) (.. % -params -update)) @msgs))]
    ((:script! srv) {:tool-call {:name "write" :args {:path "acp.txt" :content "from-acp"}}}
                    {:text "all done"})
    (try
      (send! {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion 1 :clientCapabilities {}}})
      (-> (expect (js-await (wait-for #(by-id 1) 15000))) (.toBe true))
      (-> (expect (.. (by-id 1) -result -protocolVersion)) (.toBe 1))
      (send! {:jsonrpc "2.0" :id 2 :method "session/new" :params {:cwd home :mcpServers []}})
      (js-await (wait-for #(by-id 2) 5000))
      (let [sid (.. (by-id 2) -result -sessionId)]
        (-> (expect (.. (by-id 2) -result -modes -currentModeId)) (.toBe "default"))
        (send! {:jsonrpc "2.0" :id 3 :method "session/prompt"
                :params {:sessionId sid :prompt [{:type "text" :text "run it"}]}})
        (-> (expect (js-await (wait-for #(by-id 3) 20000))) (.toBe true))
        (-> (expect (.. (by-id 3) -result -stopReason)) (.toBe "end_turn"))
        ;; the editor was asked, and the tool then ran and reported back
        (-> (expect (some #(= "session/request_permission" (.-method %)) @msgs)) (.toBe true))
        (let [us (updates)
              tc (first (filter #(= "tool_call" (.-sessionUpdate %)) us))
              tu (first (filter #(= "tool_call_update" (.-sessionUpdate %)) us))]
          (-> (expect (.-kind tc)) (.toBe "edit"))
          (-> (expect (str/ends-with? (.-path (aget (.-locations tc) 0)) "/acp.txt")) (.toBe true))
          (-> (expect (.-status tu)) (.toBe "completed"))
          ;; the model's text, not the terminal rendering
          (-> (expect (.includes (.. tu -content (at 0) -content -text) "\u001b")) (.toBe false))
          (-> (expect (fs/readFileSync (path/join home "acp.txt") "utf8")) (.toBe "from-acp"))
          (-> (expect (str/join "" (keep #(when (= "agent_message_chunk" (.-sessionUpdate %)) (.. % -content -text)) us)))
              (.toContain "all done")))
        ;; set_mode goes through /mode: plan mode is ENTERED, not just labelled
        (send! {:jsonrpc "2.0" :id 4 :method "session/set_mode" :params {:sessionId sid :modeId "plan"}})
        (js-await (wait-for #(by-id 4) 5000))
        (-> (expect (.-error (by-id 4))) (.toBeUndefined))
        (-> (expect (some #(and (= "current_mode_update" (.-sessionUpdate %)) (= "plan" (.-currentModeId %))) (updates)))
            (.toBe true))
        ;; another folder is refused: trust and settings belong to this one
        (send! {:jsonrpc "2.0" :id 5 :method "session/new" :params {:cwd (js/process.cwd) :mcpServers []}})
        (js-await (wait-for #(by-id 5) 5000))
        (-> (expect (.. (by-id 5) -error -message)) (.toContain "serves the folder it was started in"))
        (send! {:jsonrpc "2.0" :id 6 :method "session/new" :params {:cwd home :mcpServers []}})
        (js-await (wait-for #(by-id 6) 5000))
        (-> (expect (.. (by-id 6) -result -sessionId)) (.not.toBe sid)))
      (finally
        (.kill proc)
        (js-await (.-exited proc))
        ((:stop srv))
        (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _ nil))))))

((.skipIf describe (not (fs/existsSync cli))) "acp server end to end"
                                              (fn [] (it "initialize → session/new → prompt with a permission round trip" test-session 45000)))
