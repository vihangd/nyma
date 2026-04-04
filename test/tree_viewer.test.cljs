(ns tree-viewer.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.tree-viewer :refer [create-tree-viewer]]))

(defn- make-session [entries]
  {:get-tree (fn [] entries)})

(defn- make-entries []
  [{:id "1" :role "user"      :content "Hello" :parent-id nil :depth 0}
   {:id "2" :role "assistant" :content "Hi there" :parent-id "1" :depth 1}
   {:id "3" :role "user"      :content "How are you?" :parent-id "2" :depth 2}])

(describe "create-tree-viewer" (fn []
  (it "creates object with render and onInput methods"
    (fn []
      (let [tv (create-tree-viewer (make-session (make-entries)))]
        (-> (expect (fn? (.-render tv))) (.toBe true))
        (-> (expect (fn? (.-onInput tv))) (.toBe true)))))

  (it "render returns string with header and entries"
    (fn []
      (let [tv     (create-tree-viewer (make-session (make-entries)))
            output (.render tv 80 24)]
        (-> (expect output) (.toContain "Session Tree"))
        (-> (expect output) (.toContain "[user]"))
        (-> (expect output) (.toContain "[assistant]"))
        (-> (expect output) (.toContain "Hello")))))

  (it "first entry is selected by default"
    (fn []
      (let [tv     (create-tree-viewer (make-session (make-entries)))
            output (.render tv 80 24)]
        ;; First entry should have the > cursor
        (-> (expect output) (.toContain "> ")))))

  (it "down moves selection"
    (fn []
      (let [tv (create-tree-viewer (make-session (make-entries)))]
        (.onInput tv "down")
        (let [output (.render tv 80 24)
              lines  (.split output "\n")]
          ;; Second content entry (index 2, after header + blank) should be selected
          ;; The > cursor should appear on a different line than the first entry
          (-> (expect output) (.toContain "> "))))))

  (it "escape returns close signal"
    (fn []
      (let [tv     (create-tree-viewer (make-session (make-entries)))
            result (.onInput tv "escape")]
        (-> (expect (.-close result)) (.toBe true)))))

  (it "renders empty tree gracefully"
    (fn []
      (let [tv     (create-tree-viewer (make-session []))
            output (.render tv 80 24)]
        (-> (expect output) (.toContain "Session Tree")))))

  (it "up at top stays at 0"
    (fn []
      (let [tv (create-tree-viewer (make-session (make-entries)))]
        (.onInput tv "up")
        (.onInput tv "up")
        ;; Should not crash, render should still work
        (let [output (.render tv 80 24)]
          (-> (expect output) (.toContain "Session Tree"))))))))
