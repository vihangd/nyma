(ns agent.extensions.claude-hook-bridge.watch
  "Hot-reload via fs.watch. Watches every settings file the loader
   actually consumed; on any change, re-loads and atomically swaps
   the hooks-atom. Event handler closures dereference that atom on
   every fire, so re-subscription is unnecessary.

   Debounced at ~100ms because most editors emit several rapid
   change events on save (truncate + write)."
  (:require ["node:fs" :as fs]
            [agent.extensions.claude-hook-bridge.config :as config]))

(def ^:private debounce-ms 100)

(defn- safe-watch
  "fs/watch wrapped in try/catch — silently no-ops when the file
   doesn't exist (settings.local.json is often absent) or when the
   filesystem doesn't support watching (some CI sandboxes)."
  [p on-change]
  (try
    (when (fs/existsSync p)
      (fs/watch p (fn [_event-type _filename] (on-change))))
    (catch :default _e nil)))

(defn start-watcher
  "Begin watching every plausible settings source. Returns a
   close-fn that unwatches everything when called.

   Args:
     :paths        — list of file paths to watch (typically every
                     source the initial config load consumed, plus the
                     candidate paths so newly-created files are picked up)
     :on-reload    — () -> any. Called debounced after any change.
     :additional-paths — extra paths to watch (e.g. local.json that
                     might be created later)"
  [{:keys [paths on-reload]}]
  (let [pending (atom nil)
        debounced (fn []
                    (when @pending (js/clearTimeout @pending))
                    (reset! pending
                            (js/setTimeout
                             (fn []
                               (reset! pending nil)
                               (try (on-reload)
                                    (catch :default e
                                      (js/console.warn
                                       "[hook-bridge] reload failed:"
                                       (or (.-message e) (str e))))))
                             debounce-ms)))
        watchers (atom [])]
    (doseq [p (distinct paths)]
      (when-let [w (safe-watch p debounced)]
        (swap! watchers conj w)))
    (fn []
      (when @pending (js/clearTimeout @pending))
      (doseq [w @watchers]
        (try (.close w) (catch :default _e nil)))
      (reset! watchers []))))

(defn watched-paths
  "Compute the list of file paths to watch given a cwd, compat flags,
   and home dir. Always includes the candidates regardless of whether
   they currently exist — fs.watch tolerates missing files via the
   try/catch in safe-watch and the file appearing later won't auto-
   register a new watcher, but the user can still :reload manually."
  [cwd compat home]
  (->> (config/default-source-paths cwd compat home)
       (map :path)))
