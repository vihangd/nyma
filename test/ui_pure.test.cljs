(ns ui-pure.test
  (:require ["bun:test" :refer [describe it expect]]
            ["react" :refer [createElement]]
            ["./agent/ui/chat_view.jsx" :refer [role_prefix role_color]]
            ["./agent/ui/editor.jsx" :refer [editor_prefix]]
            ["./agent/ui/app.jsx" :refer [safe_react_child]]))

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

;;; ─── safe-react-child ────────────────────────────────────

(describe "safe-react-child" (fn []
                               (it "passes nil through as nil"
                                   (fn [] (-> (expect (safe_react_child nil "test")) (.toBeNull))))

                               (it "passes strings through"
                                   (fn [] (-> (expect (safe_react_child "hello" "test")) (.toBe "hello"))))

                               (it "passes numbers through"
                                   (fn [] (-> (expect (safe_react_child 42 "test")) (.toBe 42))))

                               (it "returns nil for booleans"
                                   (fn [] (-> (expect (safe_react_child true "test")) (.toBeNull))))

                               (it "passes a valid React element through"
                                   (fn []
                                     (let [el (createElement "div" nil "text")]
                                       (-> (expect (safe_react_child el "test")) (.toBe el)))))

                               (it "returns nil and warns for a plain object (theme map)"
                                   (fn []
                                     (let [theme #js {:colors #js {:primary "#7aa2f7"} :icons #js {:user "❯"}}
                                           result (safe_react_child theme "test-slot")]
                                       (-> (expect result) (.toBeNull)))))

                               (it "passes an array of React elements through"
                                   (fn []
                                     (let [arr #js [(createElement "div" nil "a") (createElement "span" nil "b")]
                                           result (safe_react_child arr "test-slot")]
                                       (-> (expect result) (.toBe arr)))))

                               (it "returns nil and warns for an array containing a plain object"
                                   (fn []
                                     (let [theme #js {:colors #js {:primary "#7aa2f7"} :icons #js {}}
                                           arr   #js [theme]
                                           result (safe_react_child arr "test-slot")]
                                       (-> (expect result) (.toBeNull)))))

                               (it "passes an array mixing elements and strings through"
                                   (fn []
                                     (let [arr #js [(createElement "div" nil "x") "plain-string"]
                                           result (safe_react_child arr "test")]
                                       (-> (expect result) (.toBe arr)))))))
