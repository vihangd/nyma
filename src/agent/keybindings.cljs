(ns agent.keybindings
  (:require [agent.debug :as d]
            ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]
            [agent.keybinding-registry :as kbr]))

(def keybindings-path
  "Path to user keybindings file."
  (path/join (os/homedir) ".nyma" "keybindings.json"))

(defn load-keybindings
  "Read ~/.nyma/keybindings.json. Returns map of {key-combo → action-string} or {}."
  []
  (if (fs/existsSync keybindings-path)
    (try
      (let [raw (js/JSON.parse (fs/readFileSync keybindings-path "utf8"))
            entries (js/Object.entries raw)]
        (into {} (map (fn [e] [(aget e 0) (aget e 1)]) entries)))
      (catch :default e
        (d/warn "[nyma] Failed to parse keybindings.json:" (.-message e))
        {}))
    {}))

(defn apply-keybindings
  "Merge loaded keybindings into the agent's :shortcuts atom.
   Action format: 'command:name' dispatches the /name command.
   Each binding is stored as {:action string :source \"keybindings.json\"}."
  ([shortcuts-atom commands-atom bindings]
   (apply-keybindings shortcuts-atom commands-atom bindings nil))
  ([shortcuts-atom commands-atom bindings agent]
   (doseq [[key-combo action] bindings]
     (swap! shortcuts-atom assoc key-combo
            {:action action
             :source "keybindings.json"
             :handler (fn []
                        (when (.startsWith (str action) "command:")
                          (let [cmd-name (.slice (str action) 8)
                                commands @commands-atom]
                            (when-let [cmd (get commands cmd-name)]
                              ;; Real ctx, not nil — handlers that notify or
                              ;; show overlays otherwise silently no-op when
                              ;; triggered from a keybinding.
                              ((:handler cmd) []
                               #js {:ui    (when agent
                                             (when-let [ext (.-extension-api agent)] (.-ui ext)))
                                    :agent agent})))))}))))

(defn shortcut-handler
  "The handler registered for the first combo in `shortcuts` that `matches?`
   the raw input `data`, or nil.

   Two shapes live in this one atom and both have to work: `registerShortcut`
   stores a BARE FN (extensions.cljs), while `apply-keybindings` above stores
   `{:action :source :handler f}`. Pure — `matches?` is pi-tui's `matchesKey`
   in production and a stub in tests."
  [shortcuts data matches?]
  (some (fn [[combo entry]]
          (when (try (matches? data combo) (catch :default _ false))
            (cond
              (fn? entry)           entry
              (:handler entry)      (:handler entry)
              (get entry "handler") (get entry "handler"))))
        (seq shortcuts)))

(defn dispatch-shortcut!
  "Run the shortcut bound to `data`, if any. Returns true when one ran.

   Nothing called this: `registerShortcut` and every keybindings.json entry
   went into an atom that only `/keys`-style listing read, so prompt_history's
   ctrl+r and model_roles' role-cycle key did nothing, and a user's custom
   binding did nothing. The unit test asserted the atom, never a keypress.

   A throwing handler must not take the input pipeline down with it — the key
   is still reported as handled, because it matched."
  [shortcuts data matches?]
  (if-let [h (shortcut-handler shortcuts data matches?)]
    (do (try (h)
             (catch :default e
               (d/warn "keybindings"
                       (str "shortcut handler failed: " (or (.-message e) (str e))))))
        true)
    false))

(defn rebuild-registry!
  "Rebuild the keybinding-registry atom from user overrides.
   Any binding whose action starts with 'app.' is treated as an
   action-id override; bindings like 'ctrl+k → command:clear' are
   left to :shortcuts only and ignored by the registry.
   Warns on conflicts."
  [registry-atom bindings]
  (let [overrides (into {}
                        (filter (fn [[_ action]]
                                  (and (string? action)
                                       (.startsWith action "app."))))
                        bindings)
        registry  (kbr/create-registry overrides)]
    (reset! registry-atom registry)
    (when (and (seq (:conflicts registry)) (.-NYMA_DEBUG js/process.env))
      (doseq [{:keys [key action-ids]} (:conflicts registry)]
        (d/warn
         (str "[nyma] keybinding conflict: " key
              " → " (.join (clj->js action-ids) ", ")))))
    registry))
