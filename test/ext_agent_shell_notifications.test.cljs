(ns ext-agent-shell-notifications.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.notifications :as notifications]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- make-mock-conn
  "Create a mock connection with capturing emit and prompt-state."
  [agent-key]
  (let [emitted (atom [])]
    {:agent-key    agent-key
     :prompt-state (atom {:text "" :tool-calls []})
     :emit         (fn [event data] (swap! emitted conj {:event event :data data}))
     :_emitted     emitted}))

(defn- make-parsed
  "Build a mock parsed message for session/update."
  [session-update-type update-data]
  #js {:method "session/update"
       :params #js {:update (let [base (clj->js update-data)]
                              (set! (.-sessionUpdate base) session-update-type)
                              base)}})

(defn- make-mock-api []
  (let [commands (atom {})
        notifications (atom [])]
    #js {:registerCommand   (fn [name opts] (swap! commands assoc name opts))
         :unregisterCommand (fn [name] (swap! commands dissoc name))
         :ui                #js {:available true
                                 :notify    (fn [msg _level] (swap! notifications conj msg))}
         :_commands         commands
         :_notifications    notifications}))

(beforeEach
 (fn []
   (reset! shared/active-agent nil)
   (reset! shared/connections {})
   (reset! shared/agent-state {})
   (reset! shared/stream-callback nil)))

;;; ─── Message chunk ──────────────────────────────────────────

(describe "notifications:message-chunk" (fn []
                                          (it "accumulates text content to prompt-state"
                                              (fn []
                                                (let [conn   (make-mock-conn "claude")
                                                      api    (make-mock-api)
                                                      parsed (make-parsed "agent_message_chunk"
                                                                          {:content {:type "text" :text "Hello "}})]
                                                  (notifications/dispatch-notification conn parsed api)
                                                  (-> (expect (:text @(:prompt-state conn))) (.toBe "Hello ")))))

                                          (it "accumulates multiple chunks"
                                              (fn []
                                                (let [conn (make-mock-conn "claude")
                                                      api  (make-mock-api)]
                                                  (notifications/dispatch-notification conn
                                                                                       (make-parsed "agent_message_chunk" {:content {:type "text" :text "Hello "}}) api)
                                                  (notifications/dispatch-notification conn
                                                                                       (make-parsed "agent_message_chunk" {:content {:type "text" :text "world"}}) api)
                                                  (-> (expect (:text @(:prompt-state conn))) (.toBe "Hello world")))))

                                          (it "invokes stream-callback with text delta"
                                              (fn []
                                                (let [conn    (make-mock-conn "claude")
                                                      api     (make-mock-api)
                                                      chunks  (atom [])
                                                      _       (reset! shared/stream-callback (fn [text] (swap! chunks conj text)))]
                                                  (notifications/dispatch-notification conn
                                                                                       (make-parsed "agent_message_chunk" {:content {:type "text" :text "hi"}}) api)
                                                  (-> (expect (count @chunks)) (.toBe 1))
                                                  (-> (expect (first @chunks)) (.toBe "hi")))))))

;;; ─── Thought chunk ──────────────────────────────────────────

(describe "notifications:thought-chunk" (fn []
                                          (it "emits acp_thought event with text"
                                              (fn []
                                                (let [conn   (make-mock-conn "claude")
                                                      api    (make-mock-api)
                                                      parsed (make-parsed "agent_thought_chunk"
                                                                          {:content {:type "text" :text "thinking..."}})]
                                                  (notifications/dispatch-notification conn parsed api)
                                                  (let [events @(:_emitted conn)]
                                                    (-> (expect (count events)) (.toBe 1))
                                                    (-> (expect (:event (first events))) (.toBe "acp_thought"))))))

                                          (it "handles string content directly"
                                              (fn []
                                                (let [conn   (make-mock-conn "claude")
                                                      api    (make-mock-api)
                                                      parsed (make-parsed "agent_thought_chunk"
                                                                          {:content "direct string thought"})]
                                                  (notifications/dispatch-notification conn parsed api)
                                                  (-> (expect (count @(:_emitted conn))) (.toBe 1)))))))

;;; ─── Tool call ──────────────────────────────────────────────

(describe "notifications:tool-call" (fn []
                                      (it "tracks tool call in prompt-state"
                                          (fn []
                                            (let [conn   (make-mock-conn "claude")
                                                  api    (make-mock-api)
                                                  parsed (make-parsed "tool_call"
                                                                      {:toolCallId "tc-1" :title "Read file" :kind "fs" :status "running"})]
                                              (notifications/dispatch-notification conn parsed api)
                                              (-> (expect (count (:tool-calls @(:prompt-state conn)))) (.toBe 1))
                                              (-> (expect (:id (first (:tool-calls @(:prompt-state conn))))) (.toBe "tc-1")))))

                                      (it "emits acp_tool_start event"
                                          (fn []
                                            (let [conn   (make-mock-conn "claude")
                                                  api    (make-mock-api)
                                                  parsed (make-parsed "tool_call"
                                                                      {:toolCallId "tc-2" :title "Bash" :kind "terminal" :status "running"})]
                                              (notifications/dispatch-notification conn parsed api)
                                              (-> (expect (:event (first @(:_emitted conn)))) (.toBe "acp_tool_start")))))))

;;; ─── Tool call update ───────────────────────────────────────

(describe "notifications:tool-call-update" (fn []
                                             (it "updates tool call status in prompt-state"
                                                 (fn []
                                                   (let [conn   (make-mock-conn "claude")
                                                         api    (make-mock-api)]
        ;; First add a tool call
                                                     (notifications/dispatch-notification conn
                                                                                          (make-parsed "tool_call" {:toolCallId "tc-3" :title "Bash" :kind "terminal" :status "running"}) api)
        ;; Then update it
                                                     (notifications/dispatch-notification conn
                                                                                          (make-parsed "tool_call_update" {:toolCallId "tc-3" :status "completed"}) api)
                                                     (-> (expect (:status (first (:tool-calls @(:prompt-state conn))))) (.toBe "completed")))))

                                             (it "emits acp_tool_update event"
                                                 (fn []
                                                   (let [conn (make-mock-conn "claude")
                                                         api  (make-mock-api)]
                                                     (notifications/dispatch-notification conn
                                                                                          (make-parsed "tool_call" {:toolCallId "tc-4" :title "Read" :kind "fs" :status "running"}) api)
                                                     (notifications/dispatch-notification conn
                                                                                          (make-parsed "tool_call_update" {:toolCallId "tc-4" :status "done"}) api)
                                                     (let [updates (filterv #(= (:event %) "acp_tool_update") @(:_emitted conn))]
                                                       (-> (expect (count updates)) (.toBe 1))))))))

;;; ─── Plan ───────────────────────────────────────────────────

(describe "notifications:plan" (fn []
                                 (it "stores plan entries in agent-state"
                                     (fn []
                                       (let [conn   (make-mock-conn "claude")
                                             api    (make-mock-api)
                                             parsed (make-parsed "plan"
                                                                 {:entries [{:content "Step 1" :priority "high" :status "active"}
                                                                            {:content "Step 2" :priority "low" :status "pending"}]})]
                                         (notifications/dispatch-notification conn parsed api)
                                         (let [plan (shared/get-agent-state "claude" :plan)]
                                           (-> (expect (count plan)) (.toBe 2))
                                           (-> (expect (:content (first plan))) (.toBe "Step 1"))))))

                                 (it "emits acp_plan event"
                                     (fn []
                                       (let [conn   (make-mock-conn "claude")
                                             api    (make-mock-api)
                                             parsed (make-parsed "plan"
                                                                 {:entries [{:content "Do thing" :priority "high" :status "active"}]})]
                                         (notifications/dispatch-notification conn parsed api)
                                         (-> (expect (:event (first @(:_emitted conn)))) (.toBe "acp_plan")))))))

;;; ─── Commands update ────────────────────────────────────────

(describe "notifications:commands-update" (fn []
                                            (it "registers agent commands via api.registerCommand"
                                                (fn []
                                                  (let [conn   (make-mock-conn "claude")
                                                        api    (make-mock-api)
                                                        parsed (make-parsed "available_commands_update"
                                                                            {:commands [{:name "help" :description "Show help"}
                                                                                        {:name "clear" :description "Clear screen"}]})]
                                                    (notifications/dispatch-notification conn parsed api)
                                                    (-> (expect (contains? @(.-_commands api) "help")) (.toBe true))
                                                    (-> (expect (contains? @(.-_commands api) "clear")) (.toBe true)))))

                                            (it "tags registered commands with :forward-to \"agent-shell\" and :agent-key"
                                                (fn []
                                                  (let [conn   (make-mock-conn "qwen")
                                                        api    (make-mock-api)
                                                        parsed (make-parsed "available_commands_update"
                                                                            {:commands [{:name "plan" :description "Agent plan"}]})]
                                                    (notifications/dispatch-notification conn parsed api)
                                                    (let [opts (get @(.-_commands api) "plan")]
                                                      (-> (expect (some? opts)) (.toBe true))
          ;; #js objects round-trip to keyword access in Squint — if
          ;; this ever breaks the fallback would be (.-forwardTo opts).
                                                      (-> (expect (or (:forward-to opts) (.-forward-to opts))) (.toBe "agent-shell"))
                                                      (-> (expect (or (:agent-key opts)  (.-agent-key opts)))  (.toBe "qwen"))))))

                                            (it "unregisters previous dynamic commands before registering new ones"
                                                (fn []
                                                  (let [conn (make-mock-conn "claude")
                                                        api  (make-mock-api)]
        ;; Register first batch
                                                    (notifications/dispatch-notification conn
                                                                                         (make-parsed "available_commands_update" {:commands [{:name "old-cmd" :description "Old"}]}) api)
                                                    (-> (expect (contains? @(.-_commands api) "old-cmd")) (.toBe true))
        ;; Register second batch — old should be removed
                                                    (notifications/dispatch-notification conn
                                                                                         (make-parsed "available_commands_update" {:commands [{:name "new-cmd" :description "New"}]}) api)
                                                    (-> (expect (contains? @(.-_commands api) "old-cmd")) (.toBe false))
                                                    (-> (expect (contains? @(.-_commands api) "new-cmd")) (.toBe true)))))))

;;; ─── Mode update ────────────────────────────────────────────

(describe "notifications:mode-update" (fn []
                                        (it "stores mode in agent-state"
                                            (fn []
                                              (let [conn   (make-mock-conn "claude")
                                                    api    (make-mock-api)
                                                    parsed (make-parsed "current_mode_update" {:modeId "plan"})]
                                                (notifications/dispatch-notification conn parsed api)
                                                (-> (expect (shared/get-agent-state "claude" :mode)) (.toBe "plan")))))

                                        (it "emits acp_mode_change event"
                                            (fn []
                                              (let [conn   (make-mock-conn "claude")
                                                    api    (make-mock-api)
                                                    parsed (make-parsed "current_mode_update" {:modeId "yolo"})]
                                                (notifications/dispatch-notification conn parsed api)
                                                (-> (expect (:event (first @(:_emitted conn)))) (.toBe "acp_mode_change")))))))

;;; ─── Config update ──────────────────────────────────────────

(describe "notifications:config-update" (fn []
                                          (it "extracts model list from config options"
                                              (fn []
                                                (let [conn   (make-mock-conn "claude")
                                                      api    (make-mock-api)
                                                      parsed (make-parsed "config_option_update"
                                                                          {:configOptions [{:configId "model"
                                                                                            :value    "opus-4"
                                                                                            :values   [{:value "opus-4" :displayName "Opus 4"}
                                                                                                       {:value "sonnet-4" :displayName "Sonnet 4"}]}]})]
                                                  (notifications/dispatch-notification conn parsed api)
                                                  (let [models (shared/get-agent-state "claude" :models)]
                                                    (-> (expect (count models)) (.toBe 2)))
                                                  (-> (expect (shared/get-agent-state "claude" :model)) (.toBe "opus-4")))))))

;;; ─── Usage update ───────────────────────────────────────────

(describe "notifications:usage-update" (fn []
                                         (it "stores usage in agent-state"
                                             (fn []
                                               (let [conn   (make-mock-conn "claude")
                                                     api    (make-mock-api)
                                                     parsed (make-parsed "usage_update"
                                                                         {:used 50000 :size 200000 :cost {:amount 0.15 :currency "USD"}})]
                                                 (notifications/dispatch-notification conn parsed api)
                                                 (let [usage (shared/get-agent-state "claude" :usage)]
                                                   (-> (expect (:used usage)) (.toBe 50000))
                                                   (-> (expect (:size usage)) (.toBe 200000))
                                                   (-> (expect (:amount (:cost usage))) (.toBe 0.15))))))

                                         (it "emits acp_usage event"
                                             (fn []
                                               (let [conn   (make-mock-conn "claude")
                                                     api    (make-mock-api)
                                                     parsed (make-parsed "usage_update" {:used 1000 :size 10000})]
                                                 (notifications/dispatch-notification conn parsed api)
                                                 (-> (expect (:event (first @(:_emitted conn)))) (.toBe "acp_usage")))))))

;;; ─── Session info ───────────────────────────────────────────

(describe "notifications:session-info" (fn []
                                         (it "stores session title in agent-state"
                                             (fn []
                                               (let [conn   (make-mock-conn "claude")
                                                     api    (make-mock-api)
                                                     parsed (make-parsed "session_info_update" {:title "Fix auth bug"})]
                                                 (notifications/dispatch-notification conn parsed api)
                                                 (-> (expect (shared/get-agent-state "claude" :session-title)) (.toBe "Fix auth bug")))))))

;;; ─── Dispatch routing ───────────────────────────────────────

(describe "notifications:dispatch-routing" (fn []
                                             (it "ignores user_message_chunk"
                                                 (fn []
                                                   (let [conn   (make-mock-conn "claude")
                                                         api    (make-mock-api)
                                                         parsed (make-parsed "user_message_chunk" {:content {:type "text" :text "echo"}})]
                                                     (notifications/dispatch-notification conn parsed api)
        ;; Should not accumulate or emit anything
                                                     (-> (expect (:text @(:prompt-state conn))) (.toBe ""))
                                                     (-> (expect (count @(:_emitted conn))) (.toBe 0)))))

                                             (it "ignores unknown session update types"
                                                 (fn []
                                                   (let [conn   (make-mock-conn "claude")
                                                         api    (make-mock-api)
                                                         parsed (make-parsed "unknown_type_xyz" {:data "stuff"})]
                                                     (notifications/dispatch-notification conn parsed api)
                                                     (-> (expect (count @(:_emitted conn))) (.toBe 0)))))

                                             (it "ignores non-session/update methods"
                                                 (fn []
                                                   (let [conn   (make-mock-conn "claude")
                                                         api    (make-mock-api)
                                                         parsed #js {:method "other/method" :params #js {}}]
                                                     (notifications/dispatch-notification conn parsed api)
                                                     (-> (expect (count @(:_emitted conn))) (.toBe 0)))))))
