(ns keybinding-registry.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :refer [combo-from-ink key-name normalize-combo
                                               format-key-combo detect-conflicts
                                               create-registry get-binding
                                               get-bindings matches? default-actions]]))

;;; ─── Helpers ────────────────────────────────────────────
;;; mk-key / EMPTY_KEY style borrowed from cc-kit:
;;;   tests/ui-keybindings.test.ts:14-39
;;; Every field the real Ink Key object carries is explicitly set
;;; to false so a test that only flips one flag doesn't inherit
;;; garbage from the object literal. Add every field the code
;;; inspects (via .-…) — if a reader checks a field we forgot, the
;;; test will see `undefined` instead of a typed `false`.

(defn- mk-key [opts]
  #js {:ctrl       (or (:ctrl opts) false)
       :meta       (or (:meta opts) false)
       :shift      (or (:shift opts) false)
       :escape     (or (:escape opts) false)
       :return     (or (:return opts) false)
       :tab        (or (:tab opts) false)
       :upArrow    (or (:up opts) false)
       :downArrow  (or (:down opts) false)
       :leftArrow  (or (:left opts) false)
       :rightArrow (or (:right opts) false)
       :backspace  (or (:backspace opts) false)
       :delete     (or (:delete opts) false)
       :pageUp     (or (:pageUp opts) false)
       :pageDown   (or (:pageDown opts) false)
       :home       (or (:home opts) false)
       :end        (or (:end opts) false)})

;;; ─── key-name ──────────────────────────────────────────
;;; Pure base-name extraction; no modifier handling. Mirrors
;;; cc-kit's getKeyName at packages/ui/src/keybindings/match.ts:29-47.

(describe "key-name" (fn []
                       (it "escape"
                           (fn []
                             (-> (expect (key-name "" (mk-key {:escape true}))) (.toBe "escape"))))

                       (it "return"
                           (fn []
                             (-> (expect (key-name "" (mk-key {:return true}))) (.toBe "return"))))

                       (it "tab"
                           (fn []
                             (-> (expect (key-name "" (mk-key {:tab true}))) (.toBe "tab"))))

                       (it "backspace and delete are reported separately"
                           (fn []
                             (-> (expect (key-name "" (mk-key {:backspace true}))) (.toBe "backspace"))
                             (-> (expect (key-name "" (mk-key {:delete true})))    (.toBe "delete"))))

                       (it "four arrows"
                           (fn []
                             (-> (expect (key-name "" (mk-key {:up true})))    (.toBe "up"))
                             (-> (expect (key-name "" (mk-key {:down true})))  (.toBe "down"))
                             (-> (expect (key-name "" (mk-key {:left true})))  (.toBe "left"))
                             (-> (expect (key-name "" (mk-key {:right true}))) (.toBe "right"))))

                       (it "pageup, pagedown, home, end (previously unmapped)"
                           (fn []
                             (-> (expect (key-name "" (mk-key {:pageUp true})))   (.toBe "pageup"))
                             (-> (expect (key-name "" (mk-key {:pageDown true}))) (.toBe "pagedown"))
                             (-> (expect (key-name "" (mk-key {:home true})))     (.toBe "home"))
                             (-> (expect (key-name "" (mk-key {:end true})))      (.toBe "end"))))

                       (it "single printable character is lowercased"
                           (fn []
                             (-> (expect (key-name "a" (mk-key {}))) (.toBe "a"))
                             (-> (expect (key-name "A" (mk-key {}))) (.toBe "a"))
                             (-> (expect (key-name "?" (mk-key {}))) (.toBe "?"))))

                       (it "space is mapped to \"space\" from literal \" \""
                           (fn []
                             (-> (expect (key-name " " (mk-key {}))) (.toBe "space"))))

                       (it "returns nil when nothing matches"
                           (fn []
                             (-> (expect (nil? (key-name "" (mk-key {})))) (.toBe true))))))

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

  ;; REGRESSION: Ink's parse-keypress sets key.meta=true whenever the
  ;; escape key is pressed — the escape leader leaks through as the
  ;; meta flag. Without the quirk-strip in combo-from-ink, a plain
  ;; Escape press canonicalized to "alt+escape" and the default
  ;; binding for app.interrupt ("escape") silently never matched,
  ;; so Esc-to-abort-streaming was dead. Fixed by mirroring cc-kit's
  ;; handling at packages/ui/src/keybindings/match.ts:93-95.
                             (it "escape with the meta quirk still canonicalizes to \"escape\""
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {:escape true :meta true})))
                                       (.toBe "escape"))))

                             (it "converts return"
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {:return true}))) (.toBe "return"))))

                             (it "converts arrows"
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {:up true}))) (.toBe "up"))
                                   (-> (expect (combo-from-ink "" (mk-key {:down true}))) (.toBe "down"))))

                             (it "converts pageup, pagedown, home, end"
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {:pageUp true})))   (.toBe "pageup"))
                                   (-> (expect (combo-from-ink "" (mk-key {:pageDown true}))) (.toBe "pagedown"))
                                   (-> (expect (combo-from-ink "" (mk-key {:home true})))     (.toBe "home"))
                                   (-> (expect (combo-from-ink "" (mk-key {:end true})))      (.toBe "end"))))

                             (it "converts backspace and delete separately"
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {:backspace true}))) (.toBe "backspace"))
                                   (-> (expect (combo-from-ink "" (mk-key {:delete true})))    (.toBe "delete"))))

                             (it "converts alt+f"
                                 (fn []
                                   (-> (expect (combo-from-ink "f" (mk-key {:meta true}))) (.toBe "alt+f"))))

                             (it "converts ctrl+alt+x"
                                 (fn []
                                   (-> (expect (combo-from-ink "x" (mk-key {:ctrl true :meta true})))
                                       (.toBe "ctrl+alt+x"))))

                             (it "ctrl+backspace (rubout word) is ctrl+backspace"
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {:ctrl true :backspace true})))
                                       (.toBe "ctrl+backspace"))))

                             (it "converts space"
                                 (fn []
                                   (-> (expect (combo-from-ink " " (mk-key {}))) (.toBe "space"))))

                             (it "returns nil for empty input and no modifier keys"
                                 (fn []
                                   (-> (expect (combo-from-ink "" (mk-key {}))) (.toBe js/undefined))))))

;;; ─── matches? integration: escape-meta doesn't break app.interrupt ──
;;; This is the integration test that actually exercises the bug the
;;; user hit. matches? goes through combo-from-ink → normalize-combo →
;;; get-bindings, so the whole chain must cooperate.

(describe "matches?: escape regression" (fn []
                                          (it "app.interrupt matches Escape even with the meta quirk"
                                              (fn []
                                                (let [reg (create-registry)]
        ;; The real ink shape: key.escape=true AND key.meta=true.
                                                  (-> (expect (matches? reg "" (mk-key {:escape true :meta true}) "app.interrupt"))
                                                      (.toBe true))
        ;; Without the quirk — still works.
                                                  (-> (expect (matches? reg "" (mk-key {:escape true}) "app.interrupt"))
                                                      (.toBe true)))))

                                          (it "app.interrupt does NOT match ctrl+[ (a real alt+escape-looking combo)"
                                              (fn []
      ;; Sanity check: the quirk-strip only applies to the escape key
      ;; itself. Random ctrl+letter combos must not accidentally match
      ;; escape bindings.
                                                (let [reg (create-registry)]
                                                  (-> (expect (matches? reg "[" (mk-key {:ctrl true}) "app.interrupt"))
                                                      (.toBe false)))))))

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
