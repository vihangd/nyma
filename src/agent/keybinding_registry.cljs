(ns agent.keybinding-registry
  "Action-ID keybinding registry.

   Layers:
   1. default-actions — built-in action → default combo(s)
   2. user overrides — flat map {combo → action-id} from settings/keybindings.json
   3. create-registry combines both and detects conflicts

   A 'combo' is a canonical lowercase string: 'ctrl+r', 'alt+f', 'escape', 'return',
   'tab', 'up', 'down', 'left', 'right', 'backspace', 'delete', 'space', '?', 'a'..."
  (:require [clojure.string :as str]))

(def default-actions
  "Built-in action registry. Each entry:
     {:description :default-keys :category :bound-by :fixed?}

   `:bound-by` names the code that ACTUALLY dispatches the action, and is
   absent when nothing does. `/hotkeys` prints only the bound ones — the
   whole point of generating that list from here is that it stops being a
   list of keys somebody once intended to wire up.

   `:fixed?` means the combo is not rebindable: interactive.cljs adds its
   input listener before the keybindings dispatcher and the editor owns its
   own keys, so a keybindings.json entry for one of these never fires (see
   keybindings.cljs/shortcut-handler)."
  {;; ── Navigation ──
   "app.help"           {:description  "Show keyboard help"
                         :default-keys ["?"]
                         :category     :navigation}
   "app.history.search" {:description  "Search prompt history"
                         :default-keys ["ctrl+r"]
                         :category     :navigation}
   ;; ── Agent ──
   "app.interrupt"      {:description  "Abort the turn in flight — only while one is running, and only with no overlay open"
                         :default-keys ["escape"]
                         :category     :agent
                         :bound-by     "interactive mode"
                         :fixed?       true}
   "app.model.show"     {:description  "Show current model info"
                         :default-keys ["ctrl+l"]
                         :category     :agent}
   "app.steer"          {:description  "Queue a follow-up while a turn is streaming"
                         :default-keys ["return"]
                         :category     :agent
                         :bound-by     "interactive mode"
                         :fixed?       true}
   ;; ── Editor ──
   "app.submit"         {:description  "Submit the message"
                         :default-keys ["return"]
                         :category     :editor
                         :bound-by     "the editor"
                         :fixed?       true}
   "app.paste.expand"   {:description  "Expand collapsed paste marker"
                         :default-keys ["ctrl+e"]
                         :category     :editor}
   "app.tab.complete"   {:description  "Autocomplete: slash command, @file, path"
                         :default-keys ["tab"]
                         :category     :editor
                         :bound-by     "the editor"
                         :fixed?       true}
   "app.bash.mode"      {:description  "Prefix ! runs bash, !! runs it without showing the output"
                         :default-keys ["!"]
                         :category     :editor
                         :bound-by     "the editor"
                         :fixed?       true}
   "app.eval.mode"      {:description  "Prefix $ evaluates a babashka expression"
                         :default-keys ["$"]
                         :category     :editor
                         :bound-by     "the editor"
                         :fixed?       true}
   ;; ── Tools ──
   "app.tools.expand"   {:description  "Expand or collapse the last tool's output"
                         :default-keys ["ctrl+o"]
                         :category     :tools
                         :bound-by     "interactive mode"}
   ;; ── Pager (in-app chat history scroll, active only in pager mode) ──
   "app.scroll.up"      {:description  "Scroll chat up one message"
                         :default-keys ["ctrl+up"]
                         :category     :navigation}
   "app.scroll.down"    {:description  "Scroll chat down one message"
                         :default-keys ["ctrl+down"]
                         :category     :navigation}
   "app.scroll.pageup"  {:description  "Scroll chat up one page"
                         :default-keys ["pageup"]
                         :category     :navigation}
   "app.scroll.pagedown" {:description "Scroll chat down one page"
                          :default-keys ["pagedown"]
                          :category     :navigation}
   "app.scroll.top"     {:description  "Jump to oldest message"
                         :default-keys ["ctrl+home"]
                         :category     :navigation}
   "app.scroll.bottom"  {:description  "Jump to newest message (exit scroll)"
                         :default-keys ["ctrl+end"]
                         :category     :navigation}})

;;; ─── Combo canonicalization ─────────────────────────────

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
   Used for get-binding to compute effective bindings quickly."
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

;;; ─── /hotkeys ───────────────────────────────────────────

(def fixed-input-keys
  "Keys nyma or pi-tui own outright, with no action id behind them.

   Ctrl-C is an `addInputListener` in interactive.cljs, added before the
   keybindings dispatcher, so it is not in the action table and cannot be
   rebound. The editor rows come from pi-tui's own keybinding table
   (dist/keybindings.js: tui.input.newLine, tui.input.tab)."
  [{:combo "ctrl+c"      :category :agent
    :description "Interrupt: stop the turn, shut the session down and exit"}
   {:combo "shift+enter" :category :editor
    :description "Insert a newline instead of submitting (ctrl+j does the same)"}])

(defn shortcut-description
  "What a `(:shortcuts agent)` entry says about itself.

   Three shapes live in that atom: a bare fn (2-arg `registerShortcut`),
   `{:handler :description}` (3-arg `registerShortcut` with opts) and
   `{:action :source :handler}` (keybindings.json). A bare fn can say
   nothing, so it is named by what it is rather than described as
   something it isn't."
  [entry]
  (cond
    (fn? entry) "(extension shortcut)"
    (map? entry)
    (or (:description entry)
        (get entry "description")
        (when-let [a (or (:action entry) (get entry "action"))]
          (if (.startsWith (str a) "command:")
            (str "Run /" (.slice (str a) 8))
            (str a)))
        "(extension shortcut)")
    :else "(extension shortcut)"))

(defn- category-rows
  [registry category]
  (->> (:actions registry)
       (filter (fn [[_ a]] (and (= category (:category a)) (some? (:bound-by a)))))
       (map (fn [[id a]]
              {:combo (if (:fixed? a)
                        (normalize-combo (first (:default-keys a)))
                        (or (get-binding registry id)
                            (normalize-combo (first (:default-keys a)))))
               :description (:description a)}))
       (concat (filter (fn [r] (= category (:category r))) fixed-input-keys))
       (sort-by :description)
       vec))

(defn- all-fixed?
  "A conflict nobody can act on: every action on the key is `:fixed?`, so the
   sharing is by design (submit and steer both live on Enter) and no
   keybindings.json entry could separate them."
  [{:keys [action-ids]}]
  (every? (fn [id] (:fixed? (get default-actions id))) action-ids))

(defn hotkeys-text
  "The `/hotkeys` list, generated from the action registry (so a user
   override in keybindings.json shows the combo that is really in effect)
   plus every registered shortcut with its description.

   This replaced a hardcoded three-line list that claimed Ctrl+L showed the
   model and Ctrl+P was 'Reserved' — neither was bound to anything — while
   saying nothing about Ctrl-C, Enter, Tab, or the extension shortcuts that
   are the only keys most sessions actually use.

   Ends with the registry's conflicts — a keybindings.json line that put a
   second action on a key that already had one — so the user learns why a
   key does something other than what they bound, instead of the warning
   only ever reaching NYMA_DEBUG output.

   Pure: takes the registry value and the shortcuts map, returns a string."
  [registry shortcuts]
  (let [section (fn [title rows]
                  (when (seq rows)
                    (str title "\n"
                         (str/join "\n"
                                   (map (fn [r]
                                          (str "  " (format-key-combo (:combo r))
                                               (.repeat " " (max 1 (- 8 (count (format-key-combo (:combo r))))))
                                               (:description r)))
                                        rows)))))
        ;; An `app.*` action in the shortcuts atom (interactive mode's own
        ;; handler, or a keybindings.json override of one) is already printed
        ;; from the registry above, under its effective combo.
        app-action? (fn [entry]
                      (when (map? entry)
                        (let [a (str (or (:action entry) (get entry "action") ""))]
                          (.startsWith a "app."))))
        ext-rows (->> (or shortcuts {})
                      (remove (fn [[_ entry]] (app-action? entry)))
                      (map (fn [[combo entry]]
                             {:combo combo :description (shortcut-description entry)}))
                      (sort-by :combo)
                      vec)
        conflict-rows (->> (:conflicts registry)
                           (remove all-fixed?)
                           (map (fn [{:keys [key action-ids]}]
                                  {:combo key :description (str/join ", " (sort action-ids))}))
                           (sort-by :combo)
                           vec)
        blocks (keep identity
                     [(section "Agent"      (category-rows registry :agent))
                      (section "Editor"     (category-rows registry :editor))
                      (section "Tools"      (category-rows registry :tools))
                      (section "Navigation" (category-rows registry :navigation))
                      (section "Extensions and custom bindings" ext-rows)
                      (section "Conflicts (one key, several actions)" conflict-rows)])]
    (if (seq blocks)
      (str/join "\n\n" blocks)
      "No keyboard shortcuts are bound.")))

(def first-launch-hint
  "One line printed under the editor on the first launch of a session. The
   three things a new user needs before anything else: how to stop a turn,
   how to leave, and where the rest is written down."
  "Esc aborts · Ctrl-C interrupts · /help")
