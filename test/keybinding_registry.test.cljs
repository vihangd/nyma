(ns keybinding-registry.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :refer [normalize-combo
                                               format-key-combo detect-conflicts
                                               create-registry get-binding
                                               default-actions]]))

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

                               (it "detects the built-in return conflict (submit vs steer)"
                                   (fn []
      ;; submit and steer intentionally share return — they're
      ;; context-sensitive (submit when idle, steer when streaming).
      ;; Conflict detection should still report it so users are aware.
                                     (let [conflicts (detect-conflicts default-actions {})]
                                       (-> (expect (some #(= "return" (:key %)) conflicts)) (.toBeTruthy)))))))

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
