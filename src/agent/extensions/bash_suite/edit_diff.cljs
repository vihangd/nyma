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
  (:require ["node:fs" :as fs]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.git-files :as git]
            [clojure.string :as str]))

(defn repo-root
  "The working tree's top level for `cwd`, or nil outside a repo. Porcelain
   paths are ROOT-relative, so every stat and pathspec below is resolved
   against this, not against wherever nyma was started."
  [cwd]
  (when (git/in-git-repo? cwd)
    (let [top (git/run-git "rev-parse --show-toplevel" cwd)]
      (when (seq top) top))))

(defn- status-lines
  "`{path status}` from `git status --porcelain`, or nil outside a repo."
  [cwd]
  (let [out (git/run-git "status --porcelain --untracked-files=all" cwd)]
    (when (git/in-git-repo? cwd)
      ;; Not by column: run-git trims the output, so the first line loses
      ;; the leading space of a ` M` status. Status = first 1-2 char token,
      ;; path = the rest.
      (->> (str/split-lines out)
           (keep (fn [l]
                   (when-let [[_ st p] (re-find #"^\s*(\S{1,2})\s+(.+)$" l)]
                     [p st])))
           (into {})))))

(defn- mtime [cwd p]
  (try (.-mtimeMs (fs/statSync (if cwd (str cwd "/" p) p)))
       (catch :default _ nil)))

(defn snapshot
  "`{:root :status {path st} :mtimes {path ms}}` for the dirty set, or nil."
  [cwd]
  (when-let [root (repo-root cwd)]
    (when-let [st (status-lines root)]
      {:root   root
       :status st
       :mtimes (into {} (map (fn [[p _]] [p (mtime root p)]) st))})))

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
                         :enter (fn [ctx]
                                  (when (and enabled (shared/is-bash-tool? (aget ctx "tool-name")))
                                    (when-let [snap (snapshot nil)]
                                      (swap! befores assoc (str (aget ctx "exec-id")) snap)))
                                  ctx)
                         :leave (fn [ctx]
                                  (let [id     (str (aget ctx "exec-id"))
                                        before (get @befores id)]
                                    (when before
                                      (swap! befores dissoc id)
                                      (when-let [s (summary nil before (snapshot nil))]
                                        (aset ctx "result" (with-summary (.-result ctx) s))))
                                    ctx))})
    (fn []
      (.removeMiddleware api "bash-suite/edit-diff")
      (reset! befores {}))))
