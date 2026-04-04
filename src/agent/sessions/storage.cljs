(ns agent.sessions.storage
  (:require ["bun:sqlite" :refer [Database]]))

(def ^:private schema-sql
  ["CREATE TABLE IF NOT EXISTS entries (
      id TEXT PRIMARY KEY,
      parent_id TEXT,
      role TEXT,
      content TEXT,
      metadata TEXT,
      timestamp INTEGER,
      session_file TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_entries_parent ON entries(parent_id)"
   "CREATE INDEX IF NOT EXISTS idx_entries_session ON entries(session_file)"
   "CREATE INDEX IF NOT EXISTS idx_entries_role ON entries(role)"

   "CREATE TABLE IF NOT EXISTS usage (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_file TEXT,
      turn_id TEXT,
      model TEXT,
      input_tokens INTEGER,
      output_tokens INTEGER,
      cost_usd REAL,
      timestamp INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_usage_session ON usage(session_file)"

   "CREATE TABLE IF NOT EXISTS branch_summaries (
      id TEXT PRIMARY KEY,
      branch_leaf_id TEXT,
      session_file TEXT,
      summary TEXT,
      files_read TEXT,
      files_modified TEXT,
      timestamp INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_branch_session ON branch_summaries(session_file)"])

(def ^:private branch-path-sql
  "WITH RECURSIVE branch(id, parent_id, role, content, metadata, timestamp, depth) AS (
     SELECT id, parent_id, role, content, metadata, timestamp, 0
       FROM entries WHERE id = ?
     UNION ALL
     SELECT e.id, e.parent_id, e.role, e.content, e.metadata, e.timestamp, b.depth + 1
       FROM entries e JOIN branch b ON e.id = b.parent_id
       WHERE b.depth < 10000
   )
   SELECT id, parent_id, role, content, metadata, timestamp
     FROM branch ORDER BY depth DESC")

(defn- row->entry [row]
  (let [base {:id        (.-id row)
              :parent-id (.-parent_id row)
              :role      (.-role row)
              :content   (.-content row)
              :timestamp (.-timestamp row)}]
    (if-let [meta-str (.-metadata row)]
      (assoc base :metadata (js/JSON.parse meta-str))
      base)))

(defn create-sqlite-store
  "Create a SQLite storage mirror for session data.
   Uses bun:sqlite (synchronous API, fast, built-in).
   The db file lives alongside the JSONL session file."
  [db-path]
  (let [db (new Database db-path)]
    ;; Enable WAL mode for better concurrent read performance
    (.run (.prepare db "PRAGMA journal_mode=WAL"))

    {:init-schema
     (fn []
       (doseq [sql schema-sql]
         (.run (.prepare db sql))))

     :upsert-entry
     (fn [entry]
       (let [stmt (.prepare db
                    "INSERT OR REPLACE INTO entries (id, parent_id, role, content, metadata, timestamp, session_file)
                     VALUES (?, ?, ?, ?, ?, ?, ?)")]
         (.run stmt
           (or (:id entry) "")
           (or (:parent-id entry) "")
           (or (:role entry) "")
           (or (:content entry) "")
           (when (:metadata entry) (js/JSON.stringify (clj->js (:metadata entry))))
           (or (:timestamp entry) 0)
           (or (:session-file entry) ""))))

     :query-branch-path
     (fn [leaf-id]
       (let [stmt (.prepare db branch-path-sql)
             rows (.all stmt leaf-id)]
         (mapv row->entry rows)))

     :search-content
     (fn [query]
       (let [;; Escape LIKE metacharacters to prevent wildcard injection
             escaped (-> query
                         (.replace "\\" "\\\\")
                         (.replace "%" "\\%")
                         (.replace "_" "\\_"))
             stmt (.prepare db
                    "SELECT id, parent_id, role, content, metadata, timestamp
                       FROM entries WHERE content LIKE ? ESCAPE '\\' LIMIT 50")
             rows (.all stmt (str "%" escaped "%"))]
         (mapv row->entry rows)))

     :upsert-usage
     (fn [usage-map]
       (let [stmt (.prepare db
                    "INSERT INTO usage (session_file, turn_id, model, input_tokens, output_tokens, cost_usd, timestamp)
                     VALUES (?, ?, ?, ?, ?, ?, ?)")]
         (.run stmt
           (or (:session-file usage-map) "")
           (or (:turn-id usage-map) "")
           (or (:model usage-map) "")
           (or (:input-tokens usage-map) 0)
           (or (:output-tokens usage-map) 0)
           (or (:cost-usd usage-map) 0)
           (or (:timestamp usage-map) (js/Date.now)))))

     :get-session-usage
     (fn [session-file]
       (let [stmt (.prepare db
                    "SELECT SUM(input_tokens) as total_input,
                            SUM(output_tokens) as total_output,
                            SUM(cost_usd) as total_cost,
                            COUNT(*) as turn_count
                       FROM usage WHERE session_file = ?")
             row (first (.all stmt session-file))]
         (when row
           {:total-input-tokens  (or (.-total_input row) 0)
            :total-output-tokens (or (.-total_output row) 0)
            :total-cost          (or (.-total_cost row) 0)
            :turn-count          (or (.-turn_count row) 0)})))

     :upsert-branch-summary
     (fn [summary-map]
       (let [stmt (.prepare db
                    "INSERT OR REPLACE INTO branch_summaries
                     (id, branch_leaf_id, session_file, summary, files_read, files_modified, timestamp)
                     VALUES (?, ?, ?, ?, ?, ?, ?)")]
         (.run stmt
           (or (:id summary-map) "")
           (or (:branch-leaf-id summary-map) "")
           (or (:session-file summary-map) "")
           (or (:summary summary-map) "")
           (when (:files-read summary-map) (js/JSON.stringify (clj->js (:files-read summary-map))))
           (when (:files-modified summary-map) (js/JSON.stringify (clj->js (:files-modified summary-map))))
           (or (:timestamp summary-map) (js/Date.now)))))

     :get-branch-summaries
     (fn [session-file]
       (let [stmt (.prepare db
                    "SELECT * FROM branch_summaries WHERE session_file = ? ORDER BY timestamp DESC")
             rows (.all stmt session-file)]
         (mapv (fn [row]
                 {:id             (.-id row)
                  :branch-leaf-id (.-branch_leaf_id row)
                  :summary        (.-summary row)
                  :files-read     (when (.-files_read row) (js/JSON.parse (.-files_read row)))
                  :files-modified (when (.-files_modified row) (js/JSON.parse (.-files_modified row)))
                  :timestamp      (.-timestamp row)})
           rows)))

     :close
     (fn [] (.close db))

     :db db}))
