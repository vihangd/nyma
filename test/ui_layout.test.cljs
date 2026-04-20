(ns ui-layout.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Box Text]]
            ["./agent/ui/editor.jsx" :refer [Editor editor-prefix]]
            ["./agent/ui/header.jsx" :refer [Header]]
            ["./agent/ui/footer.jsx" :refer [Footer]]
            ["./agent/ui/chat_view.jsx" :refer [ChatView]]))

(def test-theme
  {:colors {:primary "#7aa2f7" :secondary "#9ece6a" :error "#f7768e"
            :warning "#e0af68" :success "#9ece6a" :muted "#565f89"
            :border "#3b4261"}})

(afterEach (fn [] (cleanup)))

;;; ─── Editor component ─────────────────────────────────────────

(describe "Editor - layout" (fn []
                              (it "renders input box when not hidden"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Editor {:onSubmit       (fn [_])
                                                                             :editorValue    ""
                                                                             :setEditorValue (fn [_])
                                                                             :hidden         false
                                                                             :overlay        false
                                                                             :streaming      false
                                                                             :steerAcked     false
                                                                             :theme          test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "❯")))))

                              (it "returns nil when hidden"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Box {}
                                                                     [Editor {:onSubmit       (fn [_])
                                                                              :editorValue    ""
                                                                              :setEditorValue (fn [_])
                                                                              :hidden         true
                                                                              :overlay        false
                                                                              :streaming      false
                                                                              :steerAcked     false
                                                                              :theme          test-theme}]])]
        ;; When hidden, editor returns nil — should not contain the prompt marker
                                      (-> (expect (.includes (lastFrame) "❯")) (.toBe false)))))

                              (it "shows streaming prefix when streaming"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Editor {:onSubmit       (fn [_])
                                                                             :editorValue    ""
                                                                             :setEditorValue (fn [_])
                                                                             :hidden         false
                                                                             :overlay        false
                                                                             :streaming      true
                                                                             :steerAcked     false
                                                                             :theme          test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "steer❯")))))

                              (it "shows steer-acked prefix when acknowledged"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Editor {:onSubmit       (fn [_])
                                                                             :editorValue    ""
                                                                             :setEditorValue (fn [_])
                                                                             :hidden         false
                                                                             :overlay        false
                                                                             :streaming      false
                                                                             :steerAcked     true
                                                                             :theme          test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "↳ queued")))))))

;;; ─── editor-prefix pure function ───────────────────────────────

(describe "editor-prefix" (fn []
                            (it "returns normal prefix when idle"
                                (fn []
                                  (-> (expect (editor-prefix false false)) (.toBe "❯ "))))

                            (it "returns steer prefix when streaming"
                                (fn []
                                  (-> (expect (editor-prefix true false)) (.toBe "steer❯ "))))

                            (it "returns queued prefix when steer-acked (takes priority over streaming)"
                                (fn []
                                  (-> (expect (editor-prefix true true)) (.toBe "↳ queued "))))))

;;; ─── Header component ─────────────────────────────────────────

(describe "Header - layout" (fn []
                              (it "renders with title and model"
                                  (fn []
                                    (let [agent {:config {:model #js {:modelId "test-model"}}}
                                          {:keys [lastFrame]} (render
                                                               #jsx [Header {:agent agent
                                                                             :resources {}
                                                                             :theme test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "nyma"))
                                      (-> (expect (lastFrame)) (.toContain "test-model")))))))

;;; ─── Footer component ─────────────────────────────────────────

(describe "Footer - layout" (fn []
                              (it "renders help text and version"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Footer {:agent {} :theme test-theme :statuses {}}])]
                                      (-> (expect (lastFrame)) (.toContain "nyma v0.1.0"))
        ;; Footer hints come from the keybinding registry (or the nil-agent
        ;; fallback in footer.cljs). Both emit a visible "help" label.
                                      (-> (expect (lastFrame)) (.toContain "help")))))

                              (it "renders status items"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Footer {:agent {} :theme test-theme
                                                                             :statuses {"s1" "Active"}}])]
                                      (-> (expect (lastFrame)) (.toContain "Active")))))))

;;; ─── ChatView component ────────────────────────────────────────

(describe "ChatView - layout" (fn []
                                (it "renders messages"
                                    (fn []
                                      (let [messages [{:role "user" :content "Hello"}
                                                      {:role "assistant" :content "Hi there"}]
                                            {:keys [lastFrame]} (render
                                                                 #jsx [ChatView {:messages messages :theme test-theme}])]
                                        (-> (expect (lastFrame)) (.toContain "Hello"))
                                        (-> (expect (lastFrame)) (.toContain "Hi there")))))

                                (it "renders empty state with no messages"
                                    (fn []
                                      (let [{:keys [lastFrame]} (render
                                                                 #jsx [ChatView {:messages [] :theme test-theme}])]
        ;; Should not crash
                                        (-> (expect (lastFrame)) (.toBeDefined)))))

                                (it "streaming=true marks last message as live (no crash with :streaming prop)"
                                    (fn []
                                      (let [messages [{:role "user"      :content "q"}
                                                      {:role "assistant" :content "answer in progress"}]
                                            {:keys [lastFrame]} (render
                                                                 #jsx [ChatView {:messages  messages
                                                                                 :theme     test-theme
                                                                                 :streaming true}])]
                                        (-> (expect (lastFrame)) (.toContain "answer in progress")))))

                                (it "multiple turns all appear in frame history"
    ;; Encodes the scrollback-preservation contract.
    ;; Currently all turns are in lastFrame (no Static yet).
    ;; After Static (Step 7) earlier turns move to scrollback frames only.
    ;; Either way they must appear *somewhere* in the frame log.
                                    (fn []
                                      (let [t1 [{:role "user"      :content "TURN1_Q"}
                                                {:role "assistant" :content "TURN1_A"}]
                                            t2 (into t1 [{:role "user"      :content "TURN2_Q"}
                                                         {:role "assistant" :content "TURN2_A"}])
                                            t3 (into t2 [{:role "user"      :content "TURN3_Q"}
                                                         {:role "assistant" :content "TURN3_A"}])
                                            {:keys [frames rerender]}
                                            (render #jsx [ChatView {:messages t1 :theme test-theme}])]
                                        (rerender #jsx [ChatView {:messages t2 :theme test-theme}])
                                        (rerender #jsx [ChatView {:messages t3 :theme test-theme}])
        ;; Every turn's answer must appear at least once across all frames
                                        (-> (expect (boolean (.some frames #(.includes % "TURN1_A")))) (.toBe true))
                                        (-> (expect (boolean (.some frames #(.includes % "TURN2_A")))) (.toBe true))
                                        (-> (expect (boolean (.some frames #(.includes % "TURN3_A")))) (.toBe true)))))))
