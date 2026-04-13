(ns agent.keybinding-registry
  "Action-ID keybinding registry.

   Layers:
   1. default-actions — built-in action → default combo(s)
   2. user overrides — flat map {combo → action-id} from settings/keybindings.json
   3. create-registry combines both and detects conflicts

   A 'combo' is a canonical lowercase string: 'ctrl+r', 'alt+f', 'escape', 'return',
   'tab', 'up', 'down', 'left', 'right', 'backspace', 'delete', 'space', '?', 'a'...")

(def default-actions
  "Built-in action registry. Each entry: {:description :default-keys :category}.
   category is a keyword used to group actions in the help overlay."
  {"app.help"           {:description  "Show keyboard help"
                         :default-keys ["?"]
                         :category     :navigation}
   "app.interrupt"      {:description  "Interrupt agent / close overlay"
                         :default-keys ["escape"]
                         :category     :agent}
   "app.model.show"     {:description  "Show current model info"
                         :default-keys ["ctrl+l"]
                         :category     :agent}
   "app.history.search" {:description  "Search prompt history"
                         :default-keys ["ctrl+r"]
                         :category     :navigation}
   "app.paste.expand"   {:description  "Expand collapsed paste marker"
                         :default-keys ["ctrl+o"]
                         :category     :editor}
   "app.tools.expand"   {:description  "Expand tool execution view"
                         :default-keys ["ctrl+o"]
                         :category     :tools}})

;;; ─── Combo canonicalization ─────────────────────────────

(defn key-name
  "Extract the canonical base key name from Ink's (input, key) pair.
   Returns nil if nothing maps.

   Mirrors cc-kit's getKeyName (packages/ui/src/keybindings/match.ts:29-47)
   — one place for platform quirks, so pickers and the action registry
   can't drift. Does NOT include modifiers; use combo-from-ink for that.

   Canonical names:
     escape, return, tab, backspace, delete, space,
     up, down, left, right, pageup, pagedown, home, end,
     and any single-character input (lowercased)."
  [input key]
  (cond
    (.-escape key)     "escape"
    (.-return key)     "return"
    (.-tab key)        "tab"
    (.-backspace key)  "backspace"
    (.-delete key)     "delete"
    (.-upArrow key)    "up"
    (.-downArrow key)  "down"
    (.-leftArrow key)  "left"
    (.-rightArrow key) "right"
    (.-pageUp key)     "pageup"
    (.-pageDown key)   "pagedown"
    (.-home key)       "home"
    (.-end key)        "end"
    (= input " ")      "space"
    (and (string? input) (pos? (count input)))
    (.toLowerCase input)
    :else nil))

(defn combo-from-ink
  "Convert Ink's (input, key) pair to a canonical combo string.
   Modifier order: ctrl+ then alt+ then base.
   Shift is NOT emitted for alphabetic bare input — Ink already
   uppercases it via input.

   QUIRK: Ink's parse-keypress sets key.meta=true whenever the escape
   key is pressed — the escape sequence leader leaks through as the
   meta flag (see ink's use-input.js:
     meta: keypress.meta || keypress.name === 'escape' || keypress.option).
   Without the strip below, a plain Escape press would canonicalize to
   'alt+escape' and bindings like 'escape' (the default for
   app.interrupt) would silently never match. cc-kit handles the same
   quirk at packages/ui/src/keybindings/match.ts:93-95."
  [input key]
  (when-let [base (key-name input key)]
    (let [escape? (= base "escape")
          ctrl?   (.-ctrl key)
          ;; Strip the meta flag when the key is Escape — see QUIRK above.
          alt?    (and (.-meta key) (not escape?))]
      (str (when ctrl? "ctrl+")
           (when alt?  "alt+")
           base))))

(defn- parse-combo-internal
  "Split 'ctrl+alt+r' into {:mods #{\"ctrl\" \"alt\"} :base \"r\"}."
  [combo]
  (when (string? combo)
    (let [parts (.split (.toLowerCase combo) "+")
          n     (.-length parts)
          base  (aget parts (dec n))
          mods-arr (let [out #js []]
                     (loop [i 0]
                       (when (< i (dec n))
                         (.push out (aget parts i))
                         (recur (inc i))))
                     out)
          mods  (set mods-arr)]
      {:mods mods :base base})))

(defn normalize-combo
  "Return a canonical form for a combo string: sorted modifiers + base.
   'r+ctrl' → 'ctrl+r', 'ALT+CTRL+X' → 'alt+ctrl+x'? No — ctrl first.
   Canonical modifier order: ctrl, alt, shift."
  [combo]
  (when-let [{:keys [mods base]} (parse-combo-internal combo)]
    (str (when (contains? mods "ctrl")  "ctrl+")
         (when (contains? mods "alt")   "alt+")
         (when (contains? mods "shift") "shift+")
         base)))

;;; ─── Display formatting ─────────────────────────────────

(defn format-key-combo
  "Format a combo for display: 'ctrl+r' → '^R', 'alt+f' → 'M-f',
   'escape' → 'esc', single char → itself."
  [combo]
  (cond
    (nil? combo) ""
    (= combo "escape")     "esc"
    (= combo "return")     "enter"
    (= combo "tab")        "tab"
    (= combo "space")      "space"
    (= combo "up")         "↑"
    (= combo "down")       "↓"
    (= combo "left")       "←"
    (= combo "right")      "→"
    :else
    (let [c (normalize-combo combo)
          {:keys [mods base]} (parse-combo-internal c)]
      (cond
        (and (contains? mods "ctrl") (= (count base) 1))
        (str "^" (.toUpperCase base))

        (and (contains? mods "alt") (= (count base) 1))
        (str "M-" base)

        (= (count base) 1) base

        :else c))))

;;; ─── Conflict detection ─────────────────────────────────

(defn detect-conflicts
  "Return seq of {:key :action-ids} where multiple actions share the same combo.
   Considers defaults AND user overrides. A user override does NOT resolve a
   pre-existing default conflict — both are reported."
  [actions-map user-overrides]
  (let [combo->actions (volatile! {})]
    (doseq [[action-id {:keys [default-keys]}] actions-map]
      (doseq [k default-keys]
        (let [nk (normalize-combo k)]
          (vswap! combo->actions update nk (fnil conj #{}) action-id))))
    (doseq [[combo action-id] user-overrides]
      (let [nk (normalize-combo combo)]
        (vswap! combo->actions update nk (fnil conj #{}) action-id)))
    (->> @combo->actions
         (filter (fn [[_ ids]] (> (count ids) 1)))
         (map (fn [[k ids]] {:key k :action-ids (vec ids)}))
         vec)))

;;; ─── Registry construction ─────────────────────────────

(defn create-registry
  "Build a registry from the default action table plus a user-overrides map.
   user-overrides shape: {combo-string → action-id}

   Returns: {:actions :user-overrides :user-by-action :conflicts}

   :user-by-action is the inverse of :user-overrides — {action-id → #{combos}}.
   Used for get-binding and matches? to compute effective bindings quickly."
  ([]
   (create-registry {}))
  ([user-overrides]
   (let [actions default-actions
         user-by-action (reduce-kv
                         (fn [acc combo action]
                           (update acc action (fnil conj #{}) (normalize-combo combo)))
                         {}
                         (or user-overrides {}))
         conflicts (detect-conflicts actions user-overrides)]
     {:actions        actions
      :user-overrides (or user-overrides {})
      :user-by-action user-by-action
      :conflicts      conflicts})))

;;; ─── Lookup helpers ─────────────────────────────────────

(defn get-binding
  "Return the effective primary combo (normalized) for an action, or nil.
   User overrides win; otherwise the first default-key is used."
  [registry action-id]
  (or (first (get-in registry [:user-by-action action-id]))
      (when-let [defaults (get-in registry [:actions action-id :default-keys])]
        (normalize-combo (first defaults)))))

(defn get-bindings
  "Return the set of effective combos (normalized) for an action.
   User overrides REPLACE defaults when present."
  [registry action-id]
  (if-let [user-set (get-in registry [:user-by-action action-id])]
    user-set
    (into #{} (map normalize-combo)
          (get-in registry [:actions action-id :default-keys]))))

(defn matches?
  "True when the given Ink (input, key) pair matches any of the effective
   combos for action-id. Returns false when action-id is unknown."
  [registry input key action-id]
  (let [combo (combo-from-ink input key)]
    (and combo
         (contains? (get-bindings registry action-id)
                    (normalize-combo combo)))))
