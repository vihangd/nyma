(ns key-hints.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :refer [create-registry]]
            [agent.ui.key-hints :refer [format-hint hint-row all-actions-grouped]]))

(describe "format-hint" (fn []
  (it "formats a known binding"
    (fn []
      (let [r (create-registry)]
        (-> (expect (format-hint r "app.history.search" "history"))
            (.toBe "^R history")))))

  (it "formats ? for the help action"
    (fn []
      (let [r (create-registry)]
        (-> (expect (format-hint r "app.help" "help"))
            (.toBe "? help")))))

  (it "uses [?] placeholder for an unknown action"
    (fn []
      (let [r (create-registry)]
        (-> (expect (format-hint r "nonexistent" "foo"))
            (.toBe "[?] foo")))))

  (it "reflects user overrides"
    (fn []
      (let [r (create-registry {"ctrl+k" "app.history.search"})]
        (-> (expect (format-hint r "app.history.search" "history"))
            (.toBe "^K history")))))))

(describe "hint-row" (fn []
  (it "joins hints with double-space separator"
    (fn []
      (let [r (create-registry)
            row (hint-row r [["app.help" "help"] ["app.interrupt" "quit"]])]
        (-> (expect (.includes row "? help")) (.toBe true))
        (-> (expect (.includes row "esc quit")) (.toBe true))
        (-> (expect (.includes row "  ")) (.toBe true)))))

  (it "returns empty string for empty pairs"
    (fn []
      (let [r (create-registry)]
        (-> (expect (hint-row r [])) (.toBe "")))))))

(describe "all-actions-grouped" (fn []
  (it "returns grouped non-empty categories"
    (fn []
      (let [r (create-registry)
            groups (all-actions-grouped r)]
        (-> (expect (pos? (count groups))) (.toBe true))
        (-> (expect (every? (fn [g] (pos? (count (:actions g)))) groups))
            (.toBe true)))))

  (it "emits categories in stable order"
    (fn []
      (let [r (create-registry)
            groups (all-actions-grouped r)
            cats   (mapv :category groups)
            order  [:navigation :agent :editor :tools :session]
            idx-in-order #(.indexOf (clj->js order) %)]
        ;; Categories appear in the same relative order as declared.
        (-> (expect (apply < (map idx-in-order cats)))
            (.toBe true)))))

  (it "each action has a description"
    (fn []
      (let [r (create-registry)
            groups (all-actions-grouped r)]
        (doseq [g groups
                a (:actions g)]
          (-> (expect (string? (:description a))) (.toBe true))))))))
