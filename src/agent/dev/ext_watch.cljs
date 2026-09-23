(ns agent.dev.ext-watch
  "Save-to-live for extensions on disk.

   `bun run dev` restarts the whole process when compiled core changes, and
   loses the session with it. Extensions under `~/.nyma/extensions` and
   `.nyma/extensions` are loaded dynamically, so they need no restart at all:
   watch their directories and `reload-one!` the extension whose files
   changed. Builtins are compiled into the process and are not watched.

   Opt-in: `NYMA_WATCH_EXTENSIONS=1` or settings `{\"dev\": {\"watch-extensions\":
   true}}`. Never on for a one-shot run."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.debug :as d]
            [agent.extension-loader :as loader]))

(defn enabled?
  [settings]
  (and (not= "1" (str (or (aget js/process.env "NYMA_ONE_SHOT") "")))
       (or (= "1" (str (or (aget js/process.env "NYMA_WATCH_EXTENSIONS") "")))
           (boolean (get-in settings [:dev :watch-extensions])))))

(defn entry-for-file
  "The loaded extension (non-builtin) whose directory contains `file`, if
   any. Pure over the entries vector."
  [entries file]
  (let [f (path/resolve (str file))]
    (some (fn [{:keys [path module] :as e}]
            (when (and path (not module))
              (let [dir (path/dirname (path/resolve (str path)))]
                (when (or (= f dir) (.startsWith f (str dir path/sep))) e))))
          entries)))

(defn watch!
  "Start watching `dirs` (those that exist). On a change inside a loaded
   extension's directory, reload that extension after a short quiet period.
   Returns a stop fn."
  [api extensions-atom dirs & [{:keys [debounce-ms notify] :or {debounce-ms 250}}]]
  (let [timers   (atom {})
        watchers (atom [])
        fire!    (fn [ns]
                   (swap! timers dissoc ns)
                   (when-let [old (some #(when (= (:namespace %) ns) %) @extensions-atom)]
                     (-> (loader/reload-one! api old)
                         (.then (fn [new]
                                  ;; keep a failed entry so the next save retries it
                                  (let [kept (or new (assoc old :deactivate nil :failed? true))]
                                    (swap! extensions-atom
                                           (fn [es] (mapv #(if (= (:namespace %) ns) kept %) es))))
                                  (let [msg (if new
                                              (str "Reloaded extension " ns " (file changed)")
                                              (str "Extension " ns " failed to reload — it is OFF until fixed"))]
                                    (d/info msg)
                                    (when notify (notify msg (if new "info" "warning")))))))))
        on-change (fn [dir _event file]
                    (when file
                      (when-let [e (entry-for-file @extensions-atom (path/join dir (str file)))]
                        (let [ns (:namespace e)]
                          (when-let [t (get @timers ns)] (js/clearTimeout t))
                          (swap! timers assoc ns (js/setTimeout #(fire! ns) debounce-ms))))))]
    (doseq [dir dirs]
      (when (and dir (fs/existsSync dir))
        (try
          (swap! watchers conj
                 (fs/watch dir #js {:recursive true}
                           (fn [event file] (on-change dir event file))))
          (d/info (str "Watching " dir " for extension changes"))
          (catch :default e
            (d/warn "ext-watch" (str "cannot watch " dir ": " (.-message e)))))))
    (fn []
      (doseq [w @watchers] (try (.close w) (catch :default _ nil)))
      (doseq [[_ t] @timers] (js/clearTimeout t))
      (reset! watchers []) (reset! timers {}) nil)))
