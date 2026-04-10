(ns agent.keybindings
  (:require ["node:path" :as path]
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
        (js/console.warn "[nyma] Failed to parse keybindings.json:" (.-message e))
        {}))
    {}))

(defn apply-keybindings
  "Merge loaded keybindings into the agent's :shortcuts atom.
   Action format: 'command:name' dispatches the /name command.
   Each binding is stored as {:action string :source \"keybindings.json\"}."
  [shortcuts-atom commands-atom bindings]
  (doseq [[key-combo action] bindings]
    (swap! shortcuts-atom assoc key-combo
      {:action action
       :source "keybindings.json"
       :handler (fn []
                  (when (.startsWith (str action) "command:")
                    (let [cmd-name (.slice (str action) 8)
                          commands @commands-atom]
                      (when-let [cmd (get commands cmd-name)]
                        ((:handler cmd) [] nil)))))})))

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
    (when (seq (:conflicts registry))
      (doseq [{:keys [key action-ids]} (:conflicts registry)]
        (js/console.warn
          (str "[nyma] keybinding conflict: " key
               " → " (.join (clj->js action-ids) ", ")))))
    registry))
