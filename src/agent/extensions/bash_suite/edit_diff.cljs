(ns agent.extensions.bash-suite.edit-diff
  "What did that command change on disk?

   `edit` and `write` leave a diff the model (and the transcript) can see.
   `bash` does not: a `sed -i`, a codemod, a formatter, an `npm install` that
   rewrote the lockfile — all invisible, to the model deciding what to do next
   and to the user deciding whether to trust it. Borrowed from Claude Code's
   `bashEditDiffEnabled`.

   Mechanism: a git working-tree snapshot around the command, appended to the
   tool result as one line per changed file with +added/-removed. Snapshot =
   `git status --porcelain` plus the mtime of every dirty path, so a file
   edited in place (status unchanged, content changed) is caught too. Outside
   a git repo, or when git is missing, nothing is appended.

   Cost: two `git status` calls per bash command — the cheap end of what the
   command itself usually costs."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.git-files :as git]
            [clojure.string :as str]))

;; cwd → repo root (or nil). A directory's repo does not move during a
;; session, and the two rev-parse calls per snapshot — four per bash call —
;; cost 0.1–0.2 s of frozen UI each on this repo, measured.
;; ponytail: never invalidated; `git init` mid-session shows up after a restart
(defonce ^:private roots (atom {}))

(defn repo-root
  "The working tree's top level for `cwd`, or nil outside a repo. Porcelain
   paths are ROOT-relative, so every stat and pathspec below is resolved
   against this, not against wherever nyma was started."
  [cwd]
  (let [k (or cwd (js/process.cwd))]
    (if (contains? @roots k)
      (get @roots k)
      (let [top (git/run-git "rev-parse --show-toplevel" cwd)
            root (when (seq top) top)]
        (swap! roots assoc k root)
        root))))

(defn- parse-status
  "`{path status}` from `git status --porcelain` output. Not by column: the
   output is trimmed, so the first line loses the leading space of a ` M`
   status. Status = first 1-2 char token, path = the rest."
  [out]
  (->> (str/split-lines (str out))
       (keep (fn [l]
               (when-let [[_ st p] (re-find #"^\s*(\S{1,2})\s+(.+)$" l)]
                 [p st])))
       (into {})))

(defn- mtime [cwd p]
  (try (.-mtimeMs (fs/statSync (if cwd (str cwd "/" p) p)))
       (catch :default _ nil)))

(defn- snapshot-of [root st]
  {:root   root
   :status st
   :mtimes (into {} (map (fn [[p _]] [p (mtime root p)]) st))})

(defn snapshot
  "`{:root :status {path st} :mtimes {path ms}}` for the dirty set, or nil."
  [cwd]
  (when-let [root (repo-root cwd)]
    (snapshot-of root (parse-status (git/run-git "status --porcelain --untracked-files=all" root)))))

(defn ^:async snapshot-async
  "`snapshot` for the middleware: the status call is a child the UI does not
   wait on. It ran twice per bash command, 0.2–0.4 s each here."
  [cwd]
  (when-let [root (repo-root cwd)]
    (snapshot-of root (parse-status (js-await (git/run-git-async ["status" "--porcelain" "--untracked-files=all"] root))))))

(defn changed-paths
  "Paths whose status or mtime differ between two snapshots. Pure."
  [before after]
  (let [bs (:status before) as (:status after)
        bm (:mtimes before) am (:mtimes after)]
    (->> (distinct (concat (keys bs) (keys as)))
         (filter (fn [p] (or (not= (get bs p) (get as p))
                             (not= (get bm p) (get am p)))))
         sort
         vec)))

(defn- numstat
  "`{path [added removed]}` for `paths` from `git diff --numstat`. Untracked
   files are not in the diff; they are reported by line count."
  [cwd paths]
  (let [quoted (str/join " " (map #(str "'" (str/replace % "'" "'\\''") "'") paths))
        out    (git/run-git (str "diff --numstat -- " quoted) cwd)]
    (into {}
          (keep (fn [l]
                  (let [[a r p] (str/split l #"\t" 3)]
                    (when (and p (seq p))
                      [p [(js/parseInt a) (js/parseInt r)]])))
                (str/split-lines out)))))

(defn- line-count [cwd p]
  (try (count (str/split-lines (fs/readFileSync (if cwd (str cwd "/" p) p) "utf8")))
       (catch :default _ nil)))

(defn summary
  "One line per changed path: `path (+a/-r)`, or nil when nothing changed.
   Paths are resolved against the snapshot's repo root. A path that left the
   status because it became CLEAN (committed, stashed, checked out) is not a
   change to the file and is not listed; one that is gone from disk is
   `(deleted)`."
  [_cwd before after]
  (let [root  (or (:root after) (:root before))
        paths (changed-paths before after)
        rows  (when (seq paths)
                (let [ns (numstat root paths)]
                  (keep (fn [p]
                          (let [[a r] (get ns p)
                                st    (get (:status after) p)
                                ;; git reports a removed tracked file as ` D` until it
                                ;; is staged; the disk is the authority either way.
                                gone? (not (fs/existsSync (if root (str root "/" p) p)))]
                            (cond
                              ;; numstat reports a deleted tracked file as +0/-N;
                              ;; "gone from disk" is the fact worth stating.
                              gone?                         (str "  " p " (deleted)")
                              (and (number? a) (number? r)) (str "  " p " (+" a "/-" r ")")
                              (nil? st)                     nil
                              (= "??" st)                   (str "  " p " (new, "
                                                                 (or (line-count root p) "?") " lines)")
                              :else                         (str "  " p " (" (str/trim st) ")"))))
                        paths)))]
    (when (seq rows)
      (str "Files changed by this command:\n" (str/join "\n" rows)))))

(def ^:private max-pre-bytes
  "Checkpoints' own limit: it holds file contents in memory and on disk."
  (* 2 1024 1024))

(def ^:private max-pre-paths
  "Each path costs two synchronous git spawns inside the tool call."
  200)

(defn- head-state
  "{:state content} for `p` at HEAD, {:state nil} when HEAD does not have it, and
   nil when that cannot be said: a state of nil tells /undo to DELETE the file.
   Too big, not valid UTF-8 (a state is a string) and any git failure are all
   unknown, so /undo leaves the file alone."
  [root p]
  (let [opts #js {:cwd root :maxBuffer (inc max-pre-bytes)
                  :stdio #js ["ignore" "pipe" "ignore"]}]
    (try
      (let [line (str/trim (str (cp/execFileSync "git" #js ["ls-tree" "-l" "HEAD" "--" p] opts)))]
        (if (empty? line)
          {:state nil}
          (when (<= (js/parseInt (aget (.split line (js/RegExp. "\\s+")) 3)) max-pre-bytes)
            (let [buf (cp/execFileSync "git" #js ["show" (str "HEAD:" p)] opts)
                  s   (.toString buf "utf8")]
              (when (.equals buf (js/Buffer.from s "utf8"))
                {:state s})))))
      (catch :default _ nil))))

(defn pre-states
  "For each changed path, what it was BEFORE the command, when that is
   knowable without a content snapshot: a path absent from the before-status
   was clean (its content is HEAD's) or did not exist. A path already dirty
   before the command is left out — its pre-command content was never seen.
   Nor can it be said when the command changed a .gitignore: a file that had been
   ignored shows up new, and calling it absent has /undo delete it.
   Returns [{:path abs :state content-or-nil}], nil meaning \"did not exist\"."
  [before after paths]
  (let [root   (or (:root after) (:root before))
        rules? (some #(or (= ".gitignore" %) (str/ends-with? % "/.gitignore")) paths)]
    (->> paths
         (remove #(contains? (:status before) %))
         (take max-pre-paths)
         (keep (fn [p]
                 (when-let [known (head-state root p)]
                   (when-not (and rules? (nil? (:state known)))
                     (assoc known :path (if root (str root "/" p) p))))))
         vec)))

(defn with-summary
  "The bash result with the summary attached. The result is the bash tool's
   JSON envelope; the summary goes in as a `filesChanged` field so the
   envelope stays parseable for output-handling and the renderer. A result
   that is not that envelope gets the text appended."
  [result s]
  (if (string? result)
    (let [parsed (try (js/JSON.parse result) (catch :default _ nil))]
      (if (and parsed (object? parsed) (some? (.-stdout parsed)))
        (do (aset parsed "filesChanged" s) (js/JSON.stringify parsed))
        (str result "\n\n" s)))
    (str (js/JSON.stringify result) "\n\n" s)))

(defn activate [api]
  (let [cfg     (:edit-diff (shared/load-config))
        enabled (not (false? (:enabled cfg)))
        ;; exec-id → snapshot, so parallel bash calls do not share one.
        befores (atom {})]
    (.addMiddleware api
                    #js {:name  "bash-suite/edit-diff"
                         :enter (^:async fn [ctx]
                                  (when (and enabled (shared/is-bash-tool? (aget ctx "tool-name")))
                                    (when-let [snap (js-await (snapshot-async nil))]
                                      (swap! befores assoc (str (aget ctx "exec-id")) snap)))
                                  ctx)
                         :leave (^:async fn [ctx]
                                  (let [id     (str (aget ctx "exec-id"))
                                        before (get @befores id)]
                                    (when before
                                      (swap! befores dissoc id)
                                      (let [after (js-await (snapshot-async nil))]
                                        (when-let [s (summary nil before after)]
                                          (aset ctx "result" (with-summary (.-result ctx) s)))
                                        ;; Tell other extensions which files a shell
                                        ;; command changed (checkpoints' /diff), as
                                        ;; absolute paths. The edit tools are visible
                                        ;; by name; a `sed -i` is visible only here.
                                        (let [paths (changed-paths before after)
                                              root  (or (:root after) (:root before))]
                                          (when (seq paths)
                                            (when-let [emit (some-> (.-events api) .-emit)]
                                              (emit "bash:files-changed"
                                                    #js {:paths (clj->js (map #(if root (str root "/" %) %) paths))
                                                         ;; what checkpoints can restore them to
                                                         :pre   (clj->js (pre-states before after paths))}))))))
                                    ctx))})
    (fn []
      (.removeMiddleware api "bash-suite/edit-diff")
      (reset! befores {}))))
