(ns agent.extensions.openwiki.metadata
  "Incremental-update metadata for OpenWiki.

   snapshotHash is a SHA-256 of the concatenated CONTENTS of the wiki dir
   (excluding .last-update.json) — the upstream langchain-ai/openwiki design,
   stronger than a filename-list hash. The no-op guard: if the freshly-written
   wiki hashes identical to the last run, we DON'T rewrite metadata — this
   prevents endless doc churn in scheduled/CI update loops."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.git :as git]))

(defn snapshot-hash
  "SHA-256 hex of every file's contents under `dir` (sorted, excluding the
   metadata file). \"\" when the dir doesn't exist."
  [dir]
  (if-not (fs/existsSync dir)
    ""
    (let [rels (->> (fs/readdirSync dir #js {:recursive true})
                    (map str)
                    (remove #(.endsWith % ".last-update.json"))
                    sort)
          h    (.createHash crypto "sha256")]
      (doseq [rel rels]
        (let [full (path/join dir rel)]
          (when (try (.isFile (fs/statSync full)) (catch :default _ false))
            (.update h rel)                 ; path — so renames register
            (.update h (fs/readFileSync full)))))
      (.digest h "hex"))))

(defn load-metadata
  "Parsed .last-update.json (JS object) or nil."
  [dir]
  (let [f (shared/metadata-file dir)]
    (when (fs/existsSync f)
      (try (js/JSON.parse (fs/readFileSync f "utf8"))
           (catch :default _ nil)))))

(defn save-metadata
  "Write .last-update.json unless the wiki is byte-identical to last run
   (no-op guard). Returns {:skipped bool ...}."
  [dir command model]
  (let [new-hash  (snapshot-hash dir)
        prev      (load-metadata dir)
        prev-hash (when prev (aget prev "snapshotHash"))]
    (if (and prev (= new-hash prev-hash) (not= new-hash ""))
      {:skipped true :snapshotHash new-hash}
      (let [meta #js {:command   command
                      :model     model
                      :updatedAt (.toISOString (js/Date.))
                      :gitHead   (git/head)
                      :snapshotHash new-hash}]
        (fs/mkdirSync dir #js {:recursive true})
        (fs/writeFileSync (shared/metadata-file dir) (js/JSON.stringify meta nil 2))
        {:skipped false :metadata meta}))))
