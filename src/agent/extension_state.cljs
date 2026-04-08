(ns agent.extension-state
  "Persistent per-extension state stored in .nyma/ext-state/{namespace}.json."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn create-state-api
  "Create a state API for an extension namespace.
   State is persisted as JSON in .nyma/ext-state/{ns-str}.json.
   Returns a JS object with get/set/delete/keys/clear methods."
  [ns-str]
  (let [dir   (path/join (js/process.cwd) ".nyma" "ext-state")
        fpath (path/join dir (str ns-str ".json"))
        load  (fn []
                (try
                  (js/JSON.parse (fs/readFileSync fpath "utf8"))
                  (catch :default _ #js {})))
        save  (fn [data]
                (fs/mkdirSync dir #js {:recursive true})
                (fs/writeFileSync fpath (js/JSON.stringify data nil 2)))]
    #js {:get    (fn [k] (aget (load) k))
         :set    (fn [k v]
                   (let [d (load)]
                     (aset d k v)
                     (save d)))
         :delete (fn [k]
                   (let [d (load)]
                     (js-delete d k)
                     (save d)))
         :keys   (fn [] (js/Object.keys (load)))
         :clear  (fn [] (save #js {}))}))
