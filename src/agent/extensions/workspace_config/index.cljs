(ns agent.extensions.workspace-config.index
  "Workspace config — loads .nyma/settings.json for per-project command aliases."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.workspace-config.aliases :as aliases]))

;;; ─── Module-level state ─────────────────────────────────────

(def aliases-atom
  "Map of alias-name → target-command string."
  (atom {}))

;;; ─── Config loading ─────────────────────────────────────────

(defn- config-path []
  (path/join (js/process.cwd) ".nyma" "settings.json"))

(defn load-config!
  "Read .nyma/settings.json; return parsed map or {} on missing/error."
  []
  (let [p (config-path)]
    (if (fs/existsSync p)
      (try
        (let [raw  (.readFileSync fs p "utf8")
              obj  (js/JSON.parse raw)
              ;; Walk JS object entries to build ClojureScript map
              alias-entries (when (.-aliases obj)
                              (js/Object.entries (.-aliases obj)))]
          {:aliases (if alias-entries
                      (reduce (fn [m e] (assoc m (aget e 0) (aget e 1))) {} alias-entries)
                      {})})
        (catch :default _e
          {:aliases {}}))
      {:aliases {}})))

;;; ─── Activation ─────────────────────────────────────────────

(defn ^:export default
  "Extension activation function."
  [api]
  ;; Load config and populate atoms
  (reset! aliases-atom (:aliases (load-config!)))

  ;; Register /workspace-config__reload command
  (.registerCommand api "reload"
    #js {:description "Reload .nyma/settings.json workspace config"
         :handler (fn [_args ctx]
                    (let [config (load-config!)]
                      (reset! aliases-atom (:aliases config))
                      (when (and ctx (.-ui ctx) (.-available (.-ui ctx)))
                        (.notify (.-ui ctx)
                          (str "Workspace config reloaded ("
                               (count @aliases-atom) " aliases)")
                          "info"))))})

  ;; Activate aliases sub-module
  (let [aliases-deactivate (aliases/activate api aliases-atom)]

    ;; Return deactivator
    (fn []
      (when (fn? aliases-deactivate)
        (aliases-deactivate))
      (.unregisterCommand api "reload")
      (reset! aliases-atom {}))))
