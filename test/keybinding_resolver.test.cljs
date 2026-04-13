(ns keybinding-resolver.test
  "Unit tests for the typed keybinding resolver. Borrowed from
   cc-kit's tests/ui-keybindings.test.ts — adapted to nyma's
   registry shape (map of action-id → {:default-keys …}) and to
   the escape-meta quirk handled by combo-from-ink."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :refer [create-registry]]
            [agent.keybinding-resolver :refer [resolve-key resolve-key-with-chord parse-chord]]))

;;; ─── mk-key fixture (borrowed shape from cc-kit) ──────

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

;;; ─── parse-chord ──────────────────────────────────────

(describe "parse-chord" (fn []
                          (it "splits a single combo into a one-element vector"
                              (fn []
                                (-> (expect (= (parse-chord "escape") ["escape"])) (.toBe true))))

                          (it "splits a multi-keystroke chord on whitespace"
                              (fn []
                                (-> (expect (= (parse-chord "ctrl+k ctrl+s") ["ctrl+k" "ctrl+s"]))
                                    (.toBe true))))

                          (it "normalises each keystroke (modifier order)"
                              (fn []
                                (-> (expect (= (parse-chord "alt+ctrl+x") ["ctrl+alt+x"]))
                                    (.toBe true))))

                          (it "tolerates extra whitespace between keystrokes"
                              (fn []
                                (-> (expect (= (parse-chord "  ctrl+k   ctrl+s  ") ["ctrl+k" "ctrl+s"]))
                                    (.toBe true))))

                          (it "returns nil for nil input"
                              (fn []
                                (-> (expect (nil? (parse-chord nil))) (.toBe true))))))

;;; ─── resolve-key (single keystroke) ───────────────────

(describe "resolve-key" (fn []
                          (it "matches a default single-key binding"
                              (fn []
                                (let [reg    (create-registry)
                                      result (resolve-key reg "" (mk-key {:escape true}))]
                                  (-> (expect (:type result)) (.toBe "match"))
                                  (-> (expect (:action-id result)) (.toBe "app.interrupt")))))

                          ;; Direct regression for the escape-meta bug fixed in Phase 8.
                          ;; Ink sets key.meta=true on escape; the resolver must still
                          ;; produce :match because combo-from-ink strips the quirk.
                          (it "matches escape even with the ink meta-on-escape quirk"
                              (fn []
                                (let [reg    (create-registry)
                                      result (resolve-key reg "" (mk-key {:escape true :meta true}))]
                                  (-> (expect (:type result)) (.toBe "match"))
                                  (-> (expect (:action-id result)) (.toBe "app.interrupt")))))

                          (it "matches a ctrl+letter default (ctrl+r → history search)"
                              (fn []
                                (let [reg    (create-registry)
                                      result (resolve-key reg "r" (mk-key {:ctrl true}))]
                                  (-> (expect (:type result)) (.toBe "match"))
                                  (-> (expect (:action-id result)) (.toBe "app.history.search")))))

                          (it "returns :none when no binding matches"
                              (fn []
                                (let [reg    (create-registry)
                                      result (resolve-key reg "z" (mk-key {:ctrl true :alt true}))]
                                  (-> (expect (:type result)) (.toBe "none")))))

                          (it "returns :none when combo-from-ink can't build a combo"
                              (fn []
                                (let [reg    (create-registry)
                                      result (resolve-key reg "" (mk-key {}))]
                                  (-> (expect (:type result)) (.toBe "none")))))

                          (it "user override wins over default"
                              (fn []
      ;; Override 'app.help' from '?' to 'ctrl+h'
                                (let [reg    (create-registry {"ctrl+h" "app.help"})
                                      result (resolve-key reg "h" (mk-key {:ctrl true}))]
                                  (-> (expect (:type result)) (.toBe "match"))
                                  (-> (expect (:action-id result)) (.toBe "app.help")))))

                          (it "user override of one action doesn't disable other defaults"
                              (fn []
                                (let [reg    (create-registry {"ctrl+h" "app.help"})
                                      result (resolve-key reg "" (mk-key {:escape true}))]
                                  (-> (expect (:type result)) (.toBe "match"))
                                  (-> (expect (:action-id result)) (.toBe "app.interrupt")))))

                          ;; ?-as-bare-char is a tricky case: the default binding is '?'
                          ;; and combo-from-ink returns the char as-is.
                          (it "matches '?' as a bare character for app.help"
                              (fn []
                                (let [reg    (create-registry)
                                      result (resolve-key reg "?" (mk-key {}))]
                                  (-> (expect (:type result)) (.toBe "match"))
                                  (-> (expect (:action-id result)) (.toBe "app.help")))))))

;;; ─── resolve-key-with-chord ───────────────────────────
;;; nyma's built-in registry has no chord bindings, so we can't test
;;; chord_started against the real defaults. We build a custom registry
;;; that mimics the shape a future chord binding would take.

(defn- registry-with-chords
  "Build a test registry whose defaults include multi-keystroke
   chords. The keys go in as user-overrides so create-registry
   doesn't have to care about the :default-keys shape yet."
  [overrides]
  (create-registry overrides))

(describe "resolve-key-with-chord" (fn []
                                     (it "passes through to :match for a bound single-key"
                                         (fn []
                                           (let [reg (create-registry)
                                                 r   (resolve-key-with-chord reg "" (mk-key {:escape true}) nil)]
                                             (-> (expect (:type r)) (.toBe "match"))
                                             (-> (expect (:action-id r)) (.toBe "app.interrupt")))))

                                     (it "returns :none for an unbound single-key"
                                         (fn []
                                           (let [reg (create-registry)
                                                 r   (resolve-key-with-chord reg "z" (mk-key {:ctrl true :alt true}) nil)]
                                             (-> (expect (:type r)) (.toBe "none")))))

                                     (it "returns :chord-started when the keystroke begins a multi-key chord"
                                         (fn []
      ;; Register "ctrl+k ctrl+s" → some action. Pressing ctrl+k alone
      ;; must enter chord-wait and hold off any single-key match on
      ;; ctrl+k.
                                           (let [reg (registry-with-chords {"ctrl+k ctrl+s" "app.save"})
                                                 r   (resolve-key-with-chord reg "k" (mk-key {:ctrl true}) nil)]
                                             (-> (expect (:type r)) (.toBe "chord-started"))
                                             (-> (expect (= (:pending r) ["ctrl+k"])) (.toBe true)))))

                                     (it "chord_started + second keystroke → :match"
                                         (fn []
                                           (let [reg   (registry-with-chords {"ctrl+k ctrl+s" "app.save"})
                                                 step1 (resolve-key-with-chord reg "k" (mk-key {:ctrl true}) nil)
                                                 step2 (resolve-key-with-chord reg "s" (mk-key {:ctrl true}) (:pending step1))]
                                             (-> (expect (:type step2)) (.toBe "match"))
                                             (-> (expect (:action-id step2)) (.toBe "app.save")))))

                                     (it "escape cancels an in-progress chord"
                                         (fn []
                                           (let [reg   (registry-with-chords {"ctrl+k ctrl+s" "app.save"})
                                                 step1 (resolve-key-with-chord reg "k" (mk-key {:ctrl true}) nil)
                                                 step2 (resolve-key-with-chord reg "" (mk-key {:escape true}) (:pending step1))]
                                             (-> (expect (:type step2)) (.toBe "chord-cancelled")))))

                                     (it "non-matching second keystroke cancels the chord"
                                         (fn []
                                           (let [reg   (registry-with-chords {"ctrl+k ctrl+s" "app.save"})
                                                 step1 (resolve-key-with-chord reg "k" (mk-key {:ctrl true}) nil)
                                                 step2 (resolve-key-with-chord reg "z" (mk-key {:ctrl true}) (:pending step1))]
                                             (-> (expect (:type step2)) (.toBe "chord-cancelled")))))

                                     (it "longer chord prevents the single-key binding from firing"
                                         (fn []
      ;; Bind BOTH 'ctrl+k' (single) and 'ctrl+k ctrl+s' (chord) to
      ;; different actions. Pressing ctrl+k must return chord-started,
      ;; NOT a match for the single-key binding.
                                           (let [reg   (create-registry {"ctrl+k"         "app.single"
                                                                         "ctrl+k ctrl+s"  "app.chord"})
                                                 step1 (resolve-key-with-chord reg "k" (mk-key {:ctrl true}) nil)]
                                             (-> (expect (:type step1)) (.toBe "chord-started")))))

                                     (it "pending vector can be empty — treated as nil"
                                         (fn []
                                           (let [reg (create-registry)
                                                 r   (resolve-key-with-chord reg "" (mk-key {:escape true}) [])]
                                             (-> (expect (:type r)) (.toBe "match")))))))
