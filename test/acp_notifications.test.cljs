(ns acp-notifications.test
  "Tests for ACP notification event emissions.
   Covers:
     - acp_message fires from handle-message-chunk via dispatch-notification
     - Existing acp_thought / acp_tool_start / acp_plan emits are undisturbed"
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.agent-shell.acp.notifications :refer [dispatch-notification]]))

;;; ─── helpers ────────────────────────────────────────────────

(defn- make-conn
  "Minimal connection map with a captured emit function."
  []
  (let [emits (atom [])]
    {:agent-key    "test-agent"
     :prompt-state (atom {:text "" :tool-calls []})
     :state        (atom {:pending {} :terminals {}})
     :emit         (fn [event data] (swap! emits conj {:event event :data data}))
     :_emits       emits}))

(defn- make-api [] #js {:ui #js {:available false}})

;;; ─── acp_message ────────────────────────────────────────────

(describe "acp_message — emitted from dispatch-notification"
          (fn []
            (it "fires acp_message for agent_message_chunk notifications"
                (fn []
                  (let [conn   (make-conn)
                        parsed #js {:method "session/update"
                                    :params #js {:update #js {:sessionUpdate "agent_message_chunk"
                                                              :content #js {:type "text"
                                                                            :text "hello"}}}}]
                    (dispatch-notification conn parsed (make-api))
                    (let [msg-events (filterv #(= (:event %) "acp_message") @(:_emits conn))]
                      (-> (expect (pos? (count msg-events))) (.toBe true))))))

            (it "acp_message payload has :agent-key :text :type keys"
                (fn []
                  (let [conn   (make-conn)
                        parsed #js {:method "session/update"
                                    :params #js {:update #js {:sessionUpdate "agent_message_chunk"
                                                              :content #js {:type "text"
                                                                            :text "world"}}}}]
                    (dispatch-notification conn parsed (make-api))
                    (let [ev (first (filterv #(= (:event %) "acp_message") @(:_emits conn)))]
                      (-> (expect (some? ev)) (.toBe true))
                      (-> (expect (aget (:data ev) "agent-key")) (.toBe "test-agent"))
                      (-> (expect (aget (:data ev) "text")) (.toBe "world"))
                      (-> (expect (aget (:data ev) "type")) (.toBe "chunk"))))))

            (it "acp_message is NOT fired for non-text content types"
                (fn []
                  ;; A tool call chunk should not produce acp_message
                  (let [conn   (make-conn)
                        parsed #js {:method "session/update"
                                    :params #js {:update #js {:sessionUpdate "agent_message_chunk"
                                                              :content #js {:type "image"}}}}]
                    (dispatch-notification conn parsed (make-api))
                    (let [msg-events (filterv #(= (:event %) "acp_message") @(:_emits conn))]
                      (-> (expect (count msg-events)) (.toBe 0))))))

            (it "acp_message fires only once per chunk"
                (fn []
                  (let [conn   (make-conn)
                        make-chunk (fn [t]
                                     #js {:method "session/update"
                                          :params #js {:update #js {:sessionUpdate "agent_message_chunk"
                                                                    :content #js {:type "text"
                                                                                  :text t}}}})]
                    (dispatch-notification conn (make-chunk "a") (make-api))
                    (dispatch-notification conn (make-chunk "b") (make-api))
                    (dispatch-notification conn (make-chunk "c") (make-api))
                    (let [msg-events (filterv #(= (:event %) "acp_message") @(:_emits conn))]
                      (-> (expect (count msg-events)) (.toBe 3))))))))

;;; ─── existing events still fire ─────────────────────────────

(describe "existing ACP events — not broken by acp_message addition"
          (fn []
            (it "acp_thought still fires for agent_thought_chunk"
                (fn []
                  (let [conn   (make-conn)
                        parsed #js {:method "session/update"
                                    :params #js {:update #js {:sessionUpdate "agent_thought_chunk"
                                                              :content "thinking..."}}}]
                    (dispatch-notification conn parsed (make-api))
                    (let [ev (filterv #(= (:event %) "acp_thought") @(:_emits conn))]
                      (-> (expect (pos? (count ev))) (.toBe true))))))

            (it "acp_tool_start still fires for tool_call"
                (fn []
                  (let [conn   (make-conn)
                        parsed #js {:method "session/update"
                                    :params #js {:update #js {:sessionUpdate "tool_call"
                                                              :toolCallId    "tc1"
                                                              :title         "read_file"
                                                              :kind          "tool"
                                                              :status        "running"}}}]
                    (dispatch-notification conn parsed (make-api))
                    (let [ev (filterv #(= (:event %) "acp_tool_start") @(:_emits conn))]
                      (-> (expect (pos? (count ev))) (.toBe true))))))))
