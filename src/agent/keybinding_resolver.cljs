(ns agent.keybinding-resolver
  "Typed, pure keybinding resolver. Borrowed from cc-kit's
   packages/ui/src/keybindings/resolver.ts — the two functions
   `resolveKey` and `resolveKeyWithChordState`.

   Why this module exists next to keybinding-registry:
     The existing `matches?` helper answers 'does this key match
     action-id X?' one action at a time, and has to be called in
     an explicit cond in every global input handler. That's fine
     for a flat single-key registry, but it means:
       1. The caller can't distinguish 'no binding' from 'explicitly
          unbound' — matches? returns false in both cases.
       2. There's no way to express multi-keystroke chords like
          'ctrl+k ctrl+s'.
       3. Priority between overlapping bindings is implicit (first
          cond branch wins).

     This resolver returns a typed result — {:type :match :action-id},
     {:type :none}, {:type :unbound}, {:type :chord-started :pending},
     {:type :chord-cancelled} — so the caller's branching is
     exhaustive and a chord is modelled explicitly.

   Scope of this port:
     - `resolve-key` (single keystroke) is fully wired to nyma's
       existing registry shape and will drop into any current
       matches?-based caller without data migration.
     - `resolve-key-with-chord` accepts chord bindings at the resolver
       layer. nyma's built-in `default-actions` doesn't declare any
       yet, so this is infrastructure for when we want it.

   Not yet ported:
     - 'Active contexts' (cc-kit's KeybindingContextName set). nyma has
       a single global scope, so the parameter is omitted. Extend when
       we actually grow contexts.
     - Rewiring app.cljs's global handler to use resolve-key instead
       of successive matches? calls. That's a follow-up — the existing
       code still works and we just fixed its escape-meta bug in
       phase 8."
  (:require [agent.keybinding-registry :as kbr]
            [clojure.string :as str]))

;;; ─── Chord parsing ──────────────────────────────────────

(defn parse-chord
  "Split a chord string like 'ctrl+k ctrl+s' into a vector of
   canonical combo strings ['ctrl+k' 'ctrl+s']. A single-key binding
   like 'escape' returns ['escape']. Each step is normalised through
   kbr/normalize-combo so modifier order is stable.

   Mirrors cc-kit's parser splitting chord strings on whitespace."
  [chord]
  (when (string? chord)
    (->> (str/split (str/trim chord) #"\s+")
         (remove str/blank?)
         (map kbr/normalize-combo)
         (remove nil?)
         vec)))

;;; ─── Active-binding enumeration ─────────────────────────

(defn- all-action-chords
  "Walk the registry and produce a seq of
     {:action-id action-id :chord ['ctrl+k' 'ctrl+s'] :source :default|:user}
   for every effective keybinding. Single-key bindings become chords
   of length 1.

   We read from `:user-overrides` (the original combo→action map) and
   `:actions` (the default-keys seq) rather than `:user-by-action`.
   Reason: create-registry's `normalize-combo` destroys chord strings
   like 'ctrl+k ctrl+s' by splitting on '+' without first splitting on
   whitespace — so by the time a chord reaches `:user-by-action` it's
   been mangled into a single-key combo. The original strings are
   still intact in `:user-overrides`, which is where parse-chord can
   split them correctly."
  [registry]
  (let [defaults           (:actions registry)
        user-overrides     (:user-overrides registry)
        overridden-actions (set (vals (or user-overrides {})))]
    (vec
     (concat
      ;; User overrides first — the 'last match wins' scan in
      ;; resolve-key reverses this, so user bindings defined later
      ;; in the file take precedence over defaults. Emit each entry
      ;; from the original combo→action map so chord strings survive.
      (for [[combo action-id] (or user-overrides {})
            :let [chord (parse-chord combo)]
            :when (seq chord)]
        {:action-id action-id :chord chord :source :user})
      ;; Defaults — skip actions that already have a user override
      ;; so the user's binding isn't shadowed by the default.
      (for [[action-id {:keys [default-keys]}] (or defaults {})
            :when (not (contains? overridden-actions action-id))
            combo default-keys
            :let [chord (parse-chord combo)]
            :when (seq chord)]
        {:action-id action-id :chord chord :source :default})))))

;;; ─── Pure resolution (single keystroke) ────────────────

(defn resolve-key
  "Single-keystroke resolution. Returns one of:
     {:type :match :action-id string}
     {:type :unbound}  ; a binding exists but its action is nil
     {:type :none}     ; no binding matches this keystroke

   Ignores chord bindings — use resolve-key-with-chord for those.
   Mirrors cc-kit's resolveKey at resolver.ts:28-57."
  [registry input key]
  (if-let [combo (kbr/combo-from-ink input key)]
    (let [normalized (kbr/normalize-combo combo)
          bindings   (all-action-chords registry)
          ;; Only consider length-1 chords; the last match wins so
          ;; user overrides (emitted first) yield to any explicit
          ;; later default. We reverse and take the first — same
          ;; semantics, cheaper on a long list.
          matching   (filter (fn [b]
                               (and (= 1 (count (:chord b)))
                                    (= normalized (first (:chord b)))))
                             bindings)
          winner     (last matching)]
      (cond
        (nil? winner)          {:type :none}
        (nil? (:action-id winner)) {:type :unbound}
        :else                  {:type :match :action-id (:action-id winner)}))
    {:type :none}))

;;; ─── Chord-aware resolution ─────────────────────────────

(defn- chord-prefix-matches?
  "True when `prefix` is a strict prefix of `full`. `prefix` must be
   shorter than `full` — equal-length chords are handled by exact
   matching, not prefix matching."
  [prefix full]
  (and (< (count prefix) (count full))
       (every? true?
               (map-indexed (fn [i k] (= k (nth full i))) prefix))))

(defn- chord-exactly-matches?
  [chord binding-chord]
  (= chord binding-chord))

(defn resolve-key-with-chord
  "Chord-aware resolution. Returns the same types as resolve-key
   plus:
     {:type :chord-started :pending [combo...]}
     {:type :chord-cancelled}

   `pending` is the currently-accumulated chord prefix from the
   caller (nil or [] when no chord is in progress). The caller owns
   the pending state between calls.

   Escape cancels an in-progress chord — same as cc-kit at
   resolver.ts:163-166. A non-matching keystroke while pending also
   cancels.

   Longer chords win over single-key matches: if a keystroke could
   begin a multi-keystroke sequence, we return :chord-started even
   when a single-key binding would also match."
  [registry input key pending]
  ;; Normalise pending to a vector so callers can pass nil or [].
  (let [pending  (vec (or pending []))
        in-chord? (pos? (count pending))]
    (cond
      ;; Escape cancels any in-progress chord (and yields to any
      ;; later matches? check on escape itself if the caller wants).
      (and in-chord? (.-escape key))
      {:type :chord-cancelled}

      :else
      (let [combo (kbr/combo-from-ink input key)]
        (if-not combo
          (if in-chord? {:type :chord-cancelled} {:type :none})
          (let [normalized (kbr/normalize-combo combo)
                test-chord (conj pending normalized)
                bindings   (all-action-chords registry)
                ;; Does any longer binding start with this test-chord?
                prefix-winners
                (for [b bindings
                      :when (and (chord-prefix-matches? test-chord (:chord b))
                                 (some? (:action-id b)))]
                  b)
                exact
                (last (filter (fn [b]
                                (chord-exactly-matches? test-chord (:chord b)))
                              bindings))]
            (cond
              ;; A longer chord could still form — block the single-key match.
              (seq prefix-winners)
              {:type :chord-started :pending test-chord}

              ;; Exact hit.
              (some? exact)
              (cond
                (nil? (:action-id exact)) {:type :unbound}
                :else                     {:type :match :action-id (:action-id exact)})

              ;; No match and we were mid-chord — cancel.
              in-chord? {:type :chord-cancelled}
              :else     {:type :none})))))))
