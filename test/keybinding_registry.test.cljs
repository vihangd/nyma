(ns keybinding-registry.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :refer [combo-from-ink normalize-combo
                                                format-key-combo detect-conflicts
                                                create-registry get-binding
                                                get-bindings matches? default-actions]]))

;;; ─── Helpers ────────────────────────────────────────────

(defn- mk-key [opts]
  #js {:ctrl      (or (:ctrl opts) false)
       :meta      (or (:meta opts) false)
       :shift     (or (:shift opts) false)
       :escape    (or (:escape opts) false)
       :return    (or (:return opts) false)
       :tab       (or (:tab opts) false)
       :upArrow   (or (:up opts) false)
       :downArrow (or (:down opts) false)
       :leftArrow (or (:left opts) false)
       :rightArrow (or (:right opts) false)
       :backspace (or (:backspace opts) false)
       :delete    (or (:delete opts) false)})

;;; ─── combo-from-ink ─────────────────────────────────────

(describe "combo-from-ink" (fn []
  (it "converts ctrl+r"
    (fn []
      (-> (expect (combo-from-ink "r" (mk-key {:ctrl true}))) (.toBe "ctrl+r"))))

  (it "converts bare letter to lowercase"
    (fn []
      (-> (expect (combo-from-ink "a" (mk-key {}))) (.toBe "a"))))

  (it "converts bare ? (used for help)"
    (fn []
      (-> (expect (combo-from-ink "?" (mk-key {}))) (.toBe "?"))))

  (it "converts escape"
    (fn []
      (-> (expect (combo-from-ink "" (mk-key {:escape true}))) (.toBe "escape"))))

  (it "converts return"
    (fn []
      (-> (expect (combo-from-ink "" (mk-key {:return true}))) (.toBe "return"))))

  (it "converts arrows"
    (fn []
      (-> (expect (combo-from-ink "" (mk-key {:up true}))) (.toBe "up"))
      (-> (expect (combo-from-ink "" (mk-key {:down true}))) (.toBe "down"))))

  (it "converts alt+f"
    (fn []
      (-> (expect (combo-from-ink "f" (mk-key {:meta true}))) (.toBe "alt+f"))))

  (it "converts ctrl+alt+x"
    (fn []
      (-> (expect (combo-from-ink "x" (mk-key {:ctrl true :meta true})))
          (.toBe "ctrl+alt+x"))))

  (it "converts space"
    (fn []
      (-> (expect (combo-from-ink " " (mk-key {}))) (.toBe "space"))))

  (it "returns nil for empty input and no modifier keys"
    (fn []
      (-> (expect (combo-from-ink "" (mk-key {}))) (.toBe js/undefined))))))

;;; ─── normalize-combo ────────────────────────────────────

(describe "normalize-combo" (fn []
  (it "preserves canonical form"
    (fn []
      (-> (expect (normalize-combo "ctrl+r")) (.toBe "ctrl+r"))))

  (it "lowercases uppercase input"
    (fn []
      (-> (expect (normalize-combo "CTRL+R")) (.toBe "ctrl+r"))))

  (it "reorders modifiers to ctrl→alt→shift"
    (fn []
      (-> (expect (normalize-combo "alt+ctrl+x")) (.toBe "ctrl+alt+x"))))

  (it "passes bare keys through"
    (fn []
      (-> (expect (normalize-combo "escape")) (.toBe "escape"))))))

;;; ─── format-key-combo ───────────────────────────────────

(describe "format-key-combo" (fn []
  (it "formats ctrl+r as ^R"
    (fn []
      (-> (expect (format-key-combo "ctrl+r")) (.toBe "^R"))))

  (it "formats alt+f as M-f"
    (fn []
      (-> (expect (format-key-combo "alt+f")) (.toBe "M-f"))))

  (it "formats escape as esc"
    (fn []
      (-> (expect (format-key-combo "escape")) (.toBe "esc"))))

  (it "formats single char as itself"
    (fn []
      (-> (expect (format-key-combo "?")) (.toBe "?"))))

  (it "returns empty string for nil"
    (fn []
      (-> (expect (format-key-combo nil)) (.toBe ""))))))

;;; ─── detect-conflicts ───────────────────────────────────

(describe "detect-conflicts" (fn []
  (it "finds a conflict between two defaults on the same combo"
    (fn []
      (let [actions {"a.one" {:default-keys ["ctrl+x"]}
                     "a.two" {:default-keys ["ctrl+x"]}}
            conflicts (detect-conflicts actions {})]
        (-> (expect (count conflicts)) (.toBe 1))
        (-> (expect (:key (first conflicts))) (.toBe "ctrl+x"))
        (-> (expect (count (:action-ids (first conflicts)))) (.toBe 2)))))

  (it "returns empty when no conflict"
    (fn []
      (let [actions {"a.one" {:default-keys ["ctrl+x"]}
                     "a.two" {:default-keys ["ctrl+y"]}}]
        (-> (expect (count (detect-conflicts actions {}))) (.toBe 0)))))

  (it "finds conflicts introduced by user overrides"
    (fn []
      (let [actions  {"a.one" {:default-keys ["ctrl+x"]}
                      "a.two" {:default-keys ["ctrl+y"]}}
            override {"ctrl+x" "a.two"}
            conflicts (detect-conflicts actions override)]
        (-> (expect (count conflicts)) (.toBe 1))
        (-> (expect (:key (first conflicts))) (.toBe "ctrl+x")))))

  (it "detects the built-in ctrl+o conflict (paste.expand vs tools.expand)"
    (fn []
      ;; These intentionally share ctrl+o — they're context-sensitive.
      ;; Conflict detection should still report it so users are aware.
      (let [conflicts (detect-conflicts default-actions {})]
        (-> (expect (some #(= "ctrl+o" (:key %)) conflicts)) (.toBeTruthy)))))))

;;; ─── create-registry + lookups ──────────────────────────

(describe "create-registry" (fn []
  (it "returns a registry with defaults and no overrides"
    (fn []
      (let [r (create-registry)]
        (-> (expect (:actions r)) (.toBeDefined))
        (-> (expect (:user-overrides r)) (.toEqual {})))))

  (it "indexes user overrides by action"
    (fn []
      (let [r (create-registry {"ctrl+k" "app.help"})]
        (-> (expect (contains? (get-in r [:user-by-action "app.help"]) "ctrl+k"))
            (.toBe true)))))))

(describe "get-binding" (fn []
  (it "returns the default when no override exists"
    (fn []
      (let [r (create-registry)]
        (-> (expect (get-binding r "app.history.search")) (.toBe "ctrl+r")))))

  (it "returns the user override when set"
    (fn []
      (let [r (create-registry {"ctrl+k" "app.history.search"})]
        (-> (expect (get-binding r "app.history.search")) (.toBe "ctrl+k")))))

  (it "returns nil for an unknown action"
    (fn []
      (let [r (create-registry)]
        (-> (expect (get-binding r "nonexistent.action")) (.toBe js/undefined)))))))

(describe "matches?" (fn []
  (it "returns true for a default binding"
    (fn []
      (let [r (create-registry)]
        (-> (expect (matches? r "r" (mk-key {:ctrl true}) "app.history.search"))
            (.toBe true)))))

  (it "returns true for ? → help"
    (fn []
      (let [r (create-registry)]
        (-> (expect (matches? r "?" (mk-key {}) "app.help")) (.toBe true)))))

  (it "returns true for a user override"
    (fn []
      (let [r (create-registry {"ctrl+k" "app.history.search"})]
        (-> (expect (matches? r "k" (mk-key {:ctrl true}) "app.history.search"))
            (.toBe true)))))

  (it "returns false when an override REPLACES the default"
    (fn []
      (let [r (create-registry {"ctrl+k" "app.history.search"})]
        (-> (expect (matches? r "r" (mk-key {:ctrl true}) "app.history.search"))
            (.toBe false)))))

  (it "returns false for a non-matching combo"
    (fn []
      (let [r (create-registry)]
        (-> (expect (matches? r "x" (mk-key {:ctrl true}) "app.history.search"))
            (.toBe false)))))

  (it "returns false for an unknown action"
    (fn []
      (let [r (create-registry)]
        (-> (expect (matches? r "r" (mk-key {:ctrl true}) "nonexistent"))
            (.toBe false)))))))
