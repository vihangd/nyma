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
        (-> (expect (lastFrame)) (.toContain "ctrl+c exit")))))

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
        (-> (expect (lastFrame)) (.toBeDefined)))))))
