(ns ui-pure.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/chat_view.jsx" :refer [role_prefix role_color]]
            ["./agent/ui/editor.jsx" :refer [editor_prefix]]))

(def test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"}})

(describe "role-prefix" (fn []
  (it "returns ❯ for user"
    (fn [] (-> (expect (role_prefix "user")) (.toBe "❯ "))))

  (it "returns ● for assistant"
    (fn [] (-> (expect (role_prefix "assistant")) (.toBe "● "))))

  (it "returns ⚙ for tool-call"
    (fn [] (-> (expect (role_prefix "tool-call")) (.toBe "⚙ "))))

  (it "returns ✗ for error"
    (fn [] (-> (expect (role_prefix "error")) (.toBe "✗ "))))

  (it "returns two spaces for unknown roles"
    (fn [] (-> (expect (role_prefix "system")) (.toBe "  "))))))

(describe "role-color" (fn []
  (it "returns primary for user"
    (fn [] (-> (expect (role_color test-theme "user")) (.toBe "#7aa2f7"))))

  (it "returns secondary for assistant"
    (fn [] (-> (expect (role_color test-theme "assistant")) (.toBe "#9ece6a"))))

  (it "returns muted for tool-call"
    (fn [] (-> (expect (role_color test-theme "tool-call")) (.toBe "#565f89"))))

  (it "returns error for error"
    (fn [] (-> (expect (role_color test-theme "error")) (.toBe "#f7768e"))))

  (it "returns nil for unknown roles"
    (fn [] (-> (expect (role_color test-theme "system")) (.toBeUndefined))))))

(describe "editor-prefix" (fn []
  (it "returns ❯ when idle"
    (fn [] (-> (expect (editor_prefix false false)) (.toBe "❯ "))))

  (it "returns steer❯ when streaming"
    (fn [] (-> (expect (editor_prefix true false)) (.toBe "steer❯ "))))

  (it "returns ↳ queued when steer acknowledged"
    (fn [] (-> (expect (editor_prefix true true)) (.toBe "↳ queued "))))

  (it "steerAcked takes priority over streaming"
    (fn [] (-> (expect (editor_prefix false true)) (.toBe "↳ queued "))))))
