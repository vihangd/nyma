(ns agent.sessions.archive
  "Compressed session logs.

   Session JSONL is extremely compressible — measured on this machine, a 0.82 MB
   session goes to 0.11 MB (13%) in 2 ms, and the whole sessions directory 3.2 MB
   → 0.6 MB. deepseek-harness stores its logs the same way
   (`session.vN.jsonl.zstd`).

   Two rules keep this from being a data-loss feature:

   1. **The live file is never compressed.** Appends are the write path
      (`manager.cljs appendFileSync`), and there is no appending to a zstd frame.
      Only sessions untouched for `archive-after-days` are candidates.
   2. **Compress, verify, then unlink — in that order.** The round-trip is
      checked against the bytes just read before the original is removed, so a
      corrupt frame costs a compression pass, never the session.

   Reading is transparent: `read-text` takes either path shape, and `restore!`
   turns an archive back into a plain JSONL before anything opens it for write."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.debug :as d]))

(def ^:private suffix ".zstd")

(defn archived-path [p] (str p suffix))

(defn archived?
  "Is `p` itself an archive?"
  [p]
  (.endsWith (str p) suffix))

(defn resolve-path
  "The file that actually holds this session: `p` if it exists, else its
   archive, else nil. Callers pass the plain `.jsonl` path they already have."
  [p]
  (cond
    (not p)                            nil
    (fs/existsSync p)                  p
    (fs/existsSync (archived-path p))  (archived-path p)
    :else                              nil))

(defn read-text
  "Session text from either shape. Returns nil when neither exists, so callers
   keep their existing \"no session yet\" branch."
  [p]
  (when-let [actual (resolve-path p)]
    (if (archived? actual)
      (let [buf (fs/readFileSync actual)]
        (.toString (js/Bun.zstdDecompressSync buf) "utf8"))
      (fs/readFileSync actual "utf8"))))

(defn archive-file!
  "Compress `p` to `p.zstd` and remove `p`. Returns bytes saved on success and
   **nil** when it declined or failed — a caller counting successes must not
   have to infer them from a byte count, since a tiny session can compress to
   more than it started with.

   The verify step is the point: the decompressed frame must equal the bytes
   that were read before the original is unlinked."
  [p]
  (try
    (if (or (archived? p) (not (fs/existsSync p)))
      nil
      (let [raw     (fs/readFileSync p)
            st      (fs/statSync p)
            packed  (js/Bun.zstdCompressSync raw)
            target  (archived-path p)
            back    (js/Bun.zstdDecompressSync packed)]
        (cond
          (not= (.toString back "utf8") (.toString raw "utf8"))
          (do (d/warn "sessions" "archive round-trip mismatch — keeping the original"
                      #js {:path p})
              nil)

          ;; Nothing to gain: a very short session can pack larger than it is.
          (>= (.-length packed) (.-length raw))
          nil

          :else
          (do
            ;; Written 0600 like the rest of a session's data, and the original
            ;; goes only after the archive is on disk.
            (fs/writeFileSync target packed #js {:mode 0600})
            ;; Carry the original timestamps over. The session picker sorts by
            ;; mtime, so without this an archive pass silently reorders the list
            ;; into "most recently compressed" — and a sweep would look like
            ;; activity to anything else reading mtimes.
            (try (fs/utimesSync target (/ (.-atimeMs st) 1000) (/ (.-mtimeMs st) 1000))
                 (catch :default _ nil))
            (fs/unlinkSync p)
            (- (.-length raw) (.-length packed))))))
    (catch :default e
      (d/warn "sessions" "archive failed" #js {:path p :error (str e)})
      nil)))

(defn restore!
  "Turn `p.zstd` back into `p` so it can be appended to. Returns true when the
   plain file is present afterwards.

   Called before a session is opened for write: a resumed session must be a live
   JSONL again, not a frame something will try to append to."
  [p]
  (try
    (let [target (archived-path p)]
      (cond
        (fs/existsSync p)            true
        (not (fs/existsSync target)) false
        :else
        (let [text (.toString (js/Bun.zstdDecompressSync (fs/readFileSync target)) "utf8")
              st   (fs/statSync target)]
          (fs/writeFileSync p text #js {:mode 0600})
          (try (fs/utimesSync p (/ (.-atimeMs st) 1000) (/ (.-mtimeMs st) 1000))
               (catch :default _ nil))
          (fs/unlinkSync target)
          true)))
    (catch :default e
      (d/warn "sessions" "restore failed" #js {:path p :error (str e)})
      false)))

(defn candidates
  "Pure: which of `files` (from readdirSync) are plain sessions last touched
   before `cutoff-ms`. `stat-fn` returns mtimeMs for a path.

   `active-path` is excluded outright rather than trusted to be recent — the
   current session's mtime is recent only until someone leaves a window open
   overnight."
  [files dir cutoff-ms stat-fn active-path]
  (->> files
       (filter (fn [f] (.endsWith (str f) ".jsonl")))
       (map (fn [f] (path/join dir f)))
       (remove (fn [p] (= p active-path)))
       (filter (fn [p] (try (< (stat-fn p) cutoff-ms) (catch :default _ false))))
       vec))

(defn sweep!
  "Archive every session in `dir` older than `days`. Returns
   {:archived n :bytes-saved n}. A `days` of 0 or nil is off."
  [dir days & [active-path]]
  (if (or (not dir) (not days) (<= days 0) (not (fs/existsSync dir)))
    {:archived 0 :bytes-saved 0}
    (let [cutoff (- (js/Date.now) (* days 24 60 60 1000))
          stat   (fn [p] (.-mtimeMs (fs/statSync p)))
          olds   (candidates (vec (fs/readdirSync dir)) dir cutoff stat active-path)]
      (reduce (fn [acc p]
                (if-let [saved (archive-file! p)]
                  (-> acc (update :archived inc) (update :bytes-saved + saved))
                  acc))
              {:archived 0 :bytes-saved 0}
              olds))))
