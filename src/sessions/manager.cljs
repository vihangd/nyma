(ns agent.sessions.manager
  (:require ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]))

(defn- nanoid []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn- entry->core-message [entry]
  (select-keys entry [:role :content]))

(defn create-session-manager
  "Manages conversation tree stored as JSONL. Each entry has
   :id and :parent-id enabling in-place branching."
  [file-path]
  (let [entries (atom [])
        leaf-id (atom nil)]

    {:load
     (fn ^:async []
       (when (and file-path (fs/existsSync file-path))
         (let [content (js-await (.readFile fsp file-path "utf8"))
               lines   (->> (.split content "\n")
                             (filter seq)
                             (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)))]
           (reset! entries lines)
           (reset! leaf-id (:id (last lines))))))

     :build-context
     (fn []
       "Walk from leaf to root to build the active conversation path."
       (let [by-id (into {} (map (fn [e] [(:id e) e]) @entries))]
         (loop [current @leaf-id
                path    []]
           (if-let [entry (by-id current)]
             (recur (:parent-id entry) (cons entry path))
             (->> path
                  (filter #(contains? #{"user" "assistant" "tool_call" "tool_result"}
                                      (:role %)))
                  (mapv entry->core-message))))))

     :append
     (fn [entry-data]
       (let [id    (nanoid)
             entry (assoc entry-data :id id :parent-id @leaf-id
                                     :timestamp (js/Date.now))]
         (swap! entries conj entry)
         (reset! leaf-id id)
         (when file-path
           (fs/appendFileSync file-path (str (js/JSON.stringify (clj->js entry)) "\n")))
         id))

     :branch
     (fn [entry-id]
       (reset! leaf-id entry-id))

     :get-tree
     (fn []
       @entries)

     :leaf-id (fn [] @leaf-id)}))
