(ns agent.extensions.lsp-suite.lsp-config
  "Load and merge LSP server configuration from .nyma/settings.json."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.lsp-suite.lsp-servers-catalog :as catalog]))

(defn- expand-env [s]
  (when (string? s)
    (.replace s (js/RegExp. "\\$\\{([^}]+)\\}" "g")
              (fn [_ k] (or (aget js/process.env k) (str "${" k "}"))))))

(defn- resolve-command [cmd-arr]
  (when (and cmd-arr (pos? (.-length cmd-arr)))
    (into [] (map (fn [s] (or (expand-env s) s)) cmd-arr))))

(defn load-config
  "Returns a map of server-id → config map, merging catalog defaults
   with user overrides from .nyma/settings.json#lsp."
  []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")
        user-lsp      (when (fs/existsSync settings-path)
                        (try
                          (let [p (js/JSON.parse (fs/readFileSync settings-path "utf8"))]
                            (.-lsp p))
                          (catch :default _ nil)))]
    ;; Merge catalog entries with user overrides
    (reduce
     (fn [acc [id recipe]]
       (let [ue      (and user-lsp (aget user-lsp id))
             cmd-raw (or (and ue (.-command ue)) (clj->js (:command recipe)))
             ext-raw (or (and ue (.-extensions ue)) (clj->js (:extensions recipe)))]
         (assoc acc id
                {:id          id
                 :name        (:name recipe)
                 :command     (resolve-command cmd-raw)
                 :extensions  (into [] ext-raw)
                 :disabled?   (boolean (and ue (.-disabled ue)))
                 :env         (and ue (.-env ue))
                 :initOptions (and ue (.-initializationOptions ue))
                 :startupTimeout (or (and ue (.-startupTimeout ue)) 15000)
                 :maxRestarts    (or (and ue (.-maxRestarts ue)) 3)})))
     {}
     catalog/catalog)))
