(ns chat-renderer.test
  "Tests for render-message — every role path must produce non-empty
   string[] output and must not throw (catches scope/variable bugs like
   the 'muted is not defined' regression in the info role)."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.chat-renderer :refer [render-message]]))

;;; ─── helpers ──────────────────────────────────────────────────────────────

(def ^:private theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"
            :error     "#f7768e"
            :warning   "#e0af68"}})

(defn- strip-ansi [s]
  (.replace s (js/RegExp. "\u001b\\[[0-9;]*m" "g") ""))

(defn- render [msg]
  (render-message {:msg msg :width 80 :theme theme :md-cache nil}))

(defn- visible-text [lines]
  (strip-ansi (.join (to-array lines) "\n")))

;;; ─── user ─────────────────────────────────────────────────────────────────

(describe "chat-renderer/user" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "user" :content "hello" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes the content"
              (fn []
                (let [text (visible-text (render {:role "user" :content "hello world" :id "1"}))]
                  (-> (expect (.includes text "hello world")) (.toBe true)))))

          (it "includes the ❯ prompt marker"
              (fn []
                (let [text (visible-text (render {:role "user" :content "hi" :id "1"}))]
                  (-> (expect (.includes text "❯")) (.toBe true))))))

;;; ─── assistant ────────────────────────────────────────────────────────────

(describe "chat-renderer/assistant" (fn [])
          (it "returns at least the ● header line"
              (fn []
                (let [lines (render {:role "assistant" :content "" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes the ● bullet"
              (fn []
                (let [text (visible-text (render {:role "assistant" :content "hi" :id "1"}))]
                  (-> (expect (.includes text "●")) (.toBe true)))))

          (it "renders content"
              (fn []
                (let [text (visible-text (render {:role "assistant" :content "some answer" :id "1"}))]
                  (-> (expect (.includes text "some answer")) (.toBe true)))))

          (it "strips think tags from visible text"
              (fn []
                (let [text (visible-text (render {:role "assistant"
                                                  :content "<think>hidden</think>visible"
                                                  :id "1"}))]
                  (-> (expect (.includes text "visible")) (.toBe true))))))

;;; ─── tool-start ───────────────────────────────────────────────────────────

(describe "chat-renderer/tool-start" (fn [])
          (it "returns exactly one line"
              (fn []
                (let [lines (render {:role "tool-start" :tool-name "read"
                                     :args {:path "/foo.txt"} :id "1"})]
                  (-> (expect (count lines)) (.toBe 1)))))

          (it "includes tool name"
              (fn []
                (let [text (visible-text (render {:role "tool-start" :tool-name "read"
                                                  :args {:path "/foo.txt"} :id "1"}))]
                  (-> (expect (.includes text "read")) (.toBe true)))))

          (it "includes ⚙ icon"
              (fn []
                (let [text (visible-text (render {:role "tool-start" :tool-name "bash"
                                                  :args {:command "ls"} :id "1"}))]
                  (-> (expect (.includes text "⚙")) (.toBe true))))))

;;; ─── tool-end ─────────────────────────────────────────────────────────────

(describe "chat-renderer/tool-end" (fn [])
          (it "returns exactly one line"
              (fn []
                (let [lines (render {:role "tool-end" :tool-name "read"
                                     :args {:path "/foo.txt"} :result "line1\nline2" :id "1"})]
                  (-> (expect (count lines)) (.toBe 1)))))

          (it "includes ✓ icon"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "edit"
                                                  :args {:path "/f"} :result "" :id "1"}))]
                  (-> (expect (.includes text "✓")) (.toBe true)))))

          (it "shows duration when present"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "bash"
                                                  :args {} :result "" :duration 1500 :id "1"}))]
                  (-> (expect (.includes text "1.5s")) (.toBe true))))))

;;; ─── error ────────────────────────────────────────────────────────────────

(describe "chat-renderer/error" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "error" :content "boom" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes ✗ prefix"
              (fn []
                (let [text (visible-text (render {:role "error" :content "boom" :id "1"}))]
                  (-> (expect (.includes text "✗")) (.toBe true)))))

          (it "includes the message"
              (fn []
                (let [text (visible-text (render {:role "error" :content "network timeout" :id "1"}))]
                  (-> (expect (.includes text "network timeout")) (.toBe true))))))

;;; ─── thinking ─────────────────────────────────────────────────────────────

(describe "chat-renderer/thinking" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "thinking" :content "reasoning..." :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes │ prefix"
              (fn []
                (let [text (visible-text (render {:role "thinking" :content "step 1" :id "1"}))]
                  (-> (expect (.includes text "│")) (.toBe true))))))

;;; ─── plan ─────────────────────────────────────────────────────────────────

(describe "chat-renderer/plan" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "plan" :content "do X then Y" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes 📋 prefix"
              (fn []
                (let [text (visible-text (render {:role "plan" :content "step 1" :id "1"}))]
                  (-> (expect (.includes text "📋")) (.toBe true))))))

;;; ─── info (regression: muted-not-defined) ────────────────────────────────

(describe "chat-renderer/info" (fn [])
          (it "does not throw (regression: muted is not defined)"
              (fn []
                (let [lines (render {:role "info" :content "Role: plan → provider/model" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes the content"
              (fn []
                (let [text (visible-text (render {:role "info" :content "Role switched" :id "1"}))]
                  (-> (expect (.includes text "Role switched")) (.toBe true)))))

          (it "includes ℹ prefix"
              (fn []
                (let [text (visible-text (render {:role "info" :content "ok" :id "1"}))]
                  (-> (expect (.includes text "ℹ")) (.toBe true))))))

;;; ─── widget (verbatim lines) ──────────────────────────────────────────────

(describe "chat-renderer/widget" (fn [])
          (it "returns lines split on newline"
              (fn []
                (let [lines (render {:role "widget" :content "line1\nline2\nline3" :id "1"})]
                  (-> (expect (count lines)) (.toBe 3)))))

          (it "preserves ANSI codes verbatim"
              (fn []
                (let [content "💭 Thinking (1.2k tokens)\n│ step 1"
                      lines   (render {:role "widget" :content content :id "1"})]
                  (-> (expect (first lines)) (.toBe "💭 Thinking (1.2k tokens)")))))

          (it "returns empty array for empty content"
              (fn []
                (let [lines (render {:role "widget" :content "" :id "1"})]
                  (-> (expect (count lines)) (.toBe 0))))))

;;; ─── fallback ─────────────────────────────────────────────────────────────

(describe "chat-renderer/fallback" (fn [])
          (it "handles unknown roles without throwing"
              (fn []
                (let [lines (render {:role "unknown-role" :content "data" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes role name in fallback output"
              (fn []
                (let [text (visible-text (render {:role "custom" :content "val" :id "1"}))]
                  (-> (expect (.includes text "custom")) (.toBe true))))))

;;; ─── nil/missing content guard ────────────────────────────────────────────

(describe "chat-renderer/nil-safety" (fn [])
          (it "handles nil content without throwing"
              (fn []
                (doseq [role ["user" "assistant" "error" "thinking" "plan" "info"]]
                  (let [lines (render {:role role :content nil :id "1"})]
                    (-> (expect (vector? lines)) (.toBe true))))))

          (it "handles missing content key"
              (fn []
                (let [lines (render {:role "user" :id "1"})]
                  (-> (expect (vector? lines)) (.toBe true))))))
