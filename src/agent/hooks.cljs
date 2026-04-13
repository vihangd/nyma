(ns agent.hooks
  "Declarative hook scripts from .nyma/hooks.json and ~/.nyma/hooks.json.
   Scripts receive {event, data} as JSON on stdin, return JSON on stdout.
   Exit code 0 = success, non-zero = error (logged, no crash)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]))

(defn load-hooks
  "Load hooks from .nyma/hooks.json (project) and ~/.nyma/hooks.json (global).
   Returns JS object {eventName: [cmdConfig, ...]} or nil."
  [cwd]
  (let [project-path (path/join cwd ".nyma" "hooks.json")
        global-path  (path/join (os/homedir) ".nyma" "hooks.json")
        load-file    (fn [p]
                       (when (fs/existsSync p)
                         (try (js/JSON.parse (fs/readFileSync p "utf8"))
                              (catch :default _ nil))))
        global  (load-file global-path)
        project (load-file project-path)]
    (when (or global project)
      (js/Object.assign #js {} (or global #js {}) (or project #js {})))))

(defn ^:async run-hook-script
  "Execute a hook script. Sends event data as JSON on stdin, reads JSON from stdout."
  [command input-data timeout-ms cwd]
  (try
    (let [parts (.split (str/trim command) " ")
          proc  (js/Bun.spawn (clj->js parts)
                  #js {:cwd    cwd
                       :stdin  "pipe"
                       :stdout "pipe"
                       :stderr "pipe"})
          _     (.write (.-stdin proc) (js/JSON.stringify (clj->js input-data)))
          _     (.end (.-stdin proc))
          exit-code (js-await
                      (js/Promise.race
                        #js [(.-exited proc)
                             (js/Promise. (fn [resolve]
                               (js/setTimeout
                                 (fn [] (.kill proc) (resolve :timeout))
                                 (or timeout-ms 5000))))]))]
      (when (and (number? exit-code) (zero? exit-code))
        (let [stdout (js-await (.text (.-stdout proc)))]
          (when (> (count (str/trim stdout)) 0)
            (js/JSON.parse stdout)))))
    (catch :default e
      (js/console.warn (str "[hooks] Error running '" command "': " (.-message e)))
      nil)))

(defn register-hooks
  "Register all hooks from a hooks map. Returns cleanup function."
  [events hooks-map cwd]
  (let [registered (atom [])]
    (doseq [event-name (js/Object.keys hooks-map)]
      (let [commands (let [v (aget hooks-map event-name)]
                       (if (array? v) (vec v) [v]))
            handler  (fn [data]
                       (let [last-result (atom nil)]
                         (doseq [cmd-config commands]
                           (let [command (if (string? cmd-config) cmd-config (.-command cmd-config))
                                 timeout (if (string? cmd-config) 5000 (or (.-timeout cmd-config) 5000))
                                 result  (run-hook-script command
                                           {:event event-name :data data}
                                           timeout cwd)]
                             ;; run-hook-script is async but we're in a sync handler context
                             ;; Use .then to chain
                             (.then (js/Promise.resolve result)
                               (fn [r] (when r (reset! last-result r))))))
                         @last-result))]
        ((:on events) event-name handler)
        (swap! registered conj [event-name handler])))
    ;; Return cleanup function
    (fn []
      (doseq [[event handler] @registered]
        ((:off events) event handler)))))
