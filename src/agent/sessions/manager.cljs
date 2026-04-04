(ns agent.sessions.manager
  (:require ["node:fs" :as fs]
            [agent.protocols :refer [ISessionStore_session_load
                                     ISessionStore_session_append
                                     ISessionStore_session_build_context
                                     ISessionStore_session_branch
                                     ISessionStore_session_get_tree
                                     ISessionStore_session_leaf_id]]))

(defn- nanoid []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn- entry->core-message [entry]
  (select-keys entry [:role :content]))

(defn- build-index
  "Build an in-memory index from entries: id → {:idx n, :parent-id pid, :role r}"
  [entries]
  (into {}
    (map-indexed
      (fn [idx entry]
        [(:id entry) {:idx idx :parent-id (:parent-id entry) :role (:role entry)}]))
    entries))

(defn- walk-branch
  "Walk from leaf-id to root using the index. Returns vector of array indices
   in root-to-leaf order."
  [index leaf-id]
  (loop [current leaf-id
         indices []]
    (if-let [entry (get index current)]
      (recur (:parent-id entry) (conj indices (:idx entry)))
      (vec (reverse indices)))))

(defn create-session-manager
  "Manages conversation tree stored as JSONL. Each entry has
   :id and :parent-id enabling in-place branching.

   opts (optional map):
     :sqlite-store — SQLite mirror store (from create-sqlite-store)
     :events       — event bus for branch switch events
     :session-file — session file identifier for SQLite"
  [initial-file-path & [opts]]
  (let [file-path     (atom initial-file-path)
        entries       (atom [])
        leaf-id       (atom nil)
        index         (atom {})  ;; id → {:idx, :parent-id, :role}
        session-name  (atom nil)
        entry-labels  (atom {})  ;; entry-id → label string
        sqlite-store  (:sqlite-store opts)
        events        (:events opts)
        session-file  (or (:session-file opts) initial-file-path)

        load-fn
        (fn []
          (let [fp @file-path]
          (when (and fp (fs/existsSync fp))
            (let [content (.readFileSync fs fp "utf8")
                  lines   (->> (.split content "\n")
                               (filter seq)
                               (mapv #(js/JSON.parse %)))]
              (reset! entries lines)
              (reset! leaf-id (:id (last lines)))
              (reset! index (build-index lines))
              ;; Sync to SQLite if available
              (when sqlite-store
                (doseq [entry lines]
                  ((:upsert-entry sqlite-store)
                    (assoc entry :session-file session-file))))))))

        build-context-fn
        (fn []
          ;; Use index for O(branch-depth) traversal instead of O(n) map construction
          (let [branch-indices (walk-branch @index @leaf-id)
                all-entries    @entries]
            (->> branch-indices
                 (map #(nth all-entries %))
                 (filter #(contains? #{"user" "assistant" "tool_call" "tool_result"
                                       "compaction" "branch-summary"}
                                     (:role %)))
                 (mapv entry->core-message))))

        append-fn
        (fn [entry-data]
          (let [id    (nanoid)
                entry (assoc entry-data :id id :parent-id @leaf-id
                                        :timestamp (js/Date.now))]
            (let [new-idx (count @entries)]
              (swap! entries conj entry)
              (reset! leaf-id id)
              (swap! index assoc id {:idx new-idx :parent-id (:parent-id entry) :role (:role entry)}))
            (when-let [fp @file-path]
              (fs/appendFileSync fp (str (js/JSON.stringify (clj->js entry)) "\n")))
            ;; Mirror to SQLite
            (when sqlite-store
              ((:upsert-entry sqlite-store)
                (assoc entry :session-file session-file)))
            id))

        branch-fn
        (fn [entry-id]
          (let [old-leaf @leaf-id]
            ;; Emit branch switch event if events available
            (when events
              ((:emit events) "before_branch_switch"
                {:old-leaf old-leaf :new-leaf entry-id}))
            (reset! leaf-id entry-id)
            old-leaf))

        get-tree-fn (fn [] @entries)
        leaf-id-fn  (fn [] @leaf-id)

        search-fn
        (fn [query]
          (if sqlite-store
            ((:search-content sqlite-store) query)
            ;; Fallback: linear scan in memory
            (->> @entries
                 (filter #(and (:content %) (.includes (str (:content %)) query)))
                 (take 50)
                 vec)))

        mgr {:load             load-fn
             :build-context    build-context-fn
             :append           append-fn
             :branch           branch-fn
             :get-tree         get-tree-fn
             :leaf-id          leaf-id-fn
             :search           search-fn
             ;; Session naming and entry labeling (pi-compat)
             :set-session-name (fn [n] (reset! session-name n))
             :get-session-name (fn [] @session-name)
             :set-label        (fn [entry-id label] (swap! entry-labels assoc entry-id label))
             :get-label        (fn [entry-id] (get @entry-labels entry-id))
             :get-entries      (fn [] @entries)
             :get-branch       (fn [] (build-context-fn))
             :get-leaf-id      (fn [] @leaf-id)
             ;; Runtime session switching (for /resume, /import)
             :get-file-path    (fn [] @file-path)
             :switch-file      (fn [new-path]
                                 (reset! file-path new-path)
                                 (reset! entries [])
                                 (reset! index {})
                                 (reset! leaf-id nil)
                                 (reset! session-name nil)
                                 (reset! entry-labels {})
                                 (load-fn)
                                 new-path)}]

    ;; Protocol conformance — set Symbol keys for protocol dispatch
    (aset mgr ISessionStore_session_load (fn [_] (load-fn)))
    (aset mgr ISessionStore_session_append (fn [_ entry] (append-fn entry)))
    (aset mgr ISessionStore_session_build_context (fn [_] (build-context-fn)))
    (aset mgr ISessionStore_session_branch (fn [_ eid] (branch-fn eid)))
    (aset mgr ISessionStore_session_get_tree (fn [_] (get-tree-fn)))
    (aset mgr ISessionStore_session_leaf_id (fn [_] (leaf-id-fn)))
    mgr))
