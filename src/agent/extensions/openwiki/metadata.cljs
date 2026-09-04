(ns agent.extensions.openwiki.metadata
  "Incremental-update bookkeeping for OpenWiki.

   snapshotHash is a SHA-256 over the CONTENTS of the wiki dir (excluding
   .last-update.json) — upstream langchain-ai/openwiki's design, stronger than
   a filename-list hash. It answers 'did this run change anything?', and that
   is ALL it decides.

   It deliberately does not gate the metadata write. An earlier version skipped
   the write when the hash matched, which pinned `gitHead` forever: any commit
   that changed code without warranting a doc edit left the diff window stuck,
   so the next /openwiki update replayed the same range and primed another full
   agent run, indefinitely. Upstream's contract is the opposite — a clean update
   'skips model work and leaves wiki content untouched while refreshing
   .last-update.json'. gitHead/updatedAt now always advance."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.git :as git]))

(defn- wiki-files
  "Bundle-relative paths of every real file under `dir`, sorted, excluding the
   metadata file. [] when the dir doesn't exist."
  [dir]
  (if-not (fs/existsSync dir)
    []
    (->> (fs/readdirSync dir #js {:recursive true})
         (map str)
         (remove #(.endsWith % ".last-update.json"))
         (filter #(try (.isFile (fs/statSync (path/join dir %))) (catch :default _ false)))
         sort
         vec)))

(defn snapshot-hash
  "SHA-256 hex of every file's contents under `dir` (sorted, excluding the
   metadata file). \"\" when the dir doesn't exist."
  [dir]
  (if-not (fs/existsSync dir)
    ""
    (let [h (.createHash crypto "sha256")]
      (doseq [rel (wiki-files dir)]
        (.update h rel)                   ; path — so renames register
        (.update h (fs/readFileSync (path/join dir rel))))
      (.digest h "hex"))))

(defn okf-report
  "OKF conformance report for the bundle at `dir`, or nil when conformant.
   Only .md files participate; everything else is out of OKF's scope."
  [dir]
  (->> (wiki-files dir)
       (filter #(.endsWith % ".md"))
       (map (fn [rel] [rel (fs/readFileSync (path/join dir rel) "utf8")]))
       shared/conformance-report))

(defn load-metadata
  "Parsed .last-update.json (JS object) or nil."
  [dir]
  (let [f (shared/metadata-file dir)]
    (when (fs/existsSync f)
      (try (js/JSON.parse (fs/readFileSync f "utf8"))
           (catch :default _ nil)))))

(defn save-metadata
  "Write .last-update.json unconditionally — gitHead and updatedAt must advance
   on every run or the update diff window sticks (see the ns docstring).
   `:unchanged` reports whether the wiki content itself moved, for the message
   only. Returns {:unchanged bool :metadata js-obj}."
  [dir command model sections]
  (let [new-hash  (snapshot-hash dir)
        prev      (load-metadata dir)
        prev-hash (when prev (aget prev "snapshotHash"))
        meta      #js {:command      command
                       :model        model
                       :updatedAt    (.toISOString (js/Date.))
                       :gitHead      (git/head)
                       :snapshotHash new-hash
                       :okfVersion   shared/okf-version
                       :sections     (clj->js (vec sections))}]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (shared/metadata-file dir) (js/JSON.stringify meta nil 2))
    {:unchanged (boolean (and prev (= new-hash prev-hash) (not= new-hash "")))
     :metadata  meta}))
