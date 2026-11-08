(ns agent.utils.sandbox
  "Bash that can only write where it is meant to.

   A worktree child's edit tools are confined to its checkout, but its bash
   could still write ~/.zshrc. The OS does the confining here, as Codex and
   Claude Code do: Seatbelt (`sandbox-exec`) on macOS, bubblewrap on Linux.
   Writes are allowed under the root, the temp dirs and package caches; git's
   hooks and config stay read-only so a child cannot plant code for the parent.
   Reads and network are left alone: builds need registries.
   ponytail: hand-rolled profile; @anthropic-ai/sandbox-runtime if network
   proxying or Windows is ever wanted."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.debug :as d]
            [agent.utils.home :as home]))

;; The directory bash may write under, or nil for no sandbox.
(defonce root (atom nil))

(defn- real [p]
  (try (fs/realpathSync p) (catch :default _ nil)))

(defn- git-common-dir
  "The repo's shared .git: a worktree's commits land there, not in its checkout."
  [dir]
  (try
    (let [r (js/Bun.spawnSync #js ["git" "-C" dir "rev-parse" "--path-format=absolute" "--git-common-dir"]
                              #js {:stdout "pipe" :stderr "ignore"})]
      (when (zero? (.-exitCode r))
        (real (.trim (.toString (.-stdout r))))))
    (catch :default _ nil)))

(defn writable
  "Directories `dir`'s sandbox may write under, all resolved."
  [dir]
  (let [h (home/dir)]
    (->> (concat [dir (git-common-dir dir) (os/tmpdir) "/tmp" "/private/var/folders"]
                 (map #(path/join h %) [".cache" ".npm" ".bun/install/cache"
                                        "Library/Caches" ".cargo/registry"]))
         (keep #(when % (real %)))
         distinct
         vec)))

(defn seatbelt-profile [dirs git]
  (str "(version 1)\n(allow default)\n(deny file-write*)\n"
       "(allow file-write* (literal \"/dev/null\") (regex #\"^/dev/(fd|tty|ptmx|ttys)\")"
       (apply str (map-indexed (fn [i _] (str " (subpath (param \"W" i "\"))")) dirs))
       ")\n"
       (when git
         "(deny file-write* (subpath (string-append (param \"GIT\") \"/hooks\")) (literal (string-append (param \"GIT\") \"/config\")))\n")))

(defn- on-path? [bin]
  (some? (js/Bun.which bin)))

(defonce ^:private warned? (atom false))

(defn wrap
  "`argv` to run under the sandbox, or unchanged when there is none. A missing
   sandbox tool warns once rather than refusing bash outright."
  [argv]
  (if-let [dir (some-> @root real)]
    (let [dirs (writable dir)
          git  (git-common-dir dir)]
      (cond
        (and (= "darwin" js/process.platform) (on-path? "sandbox-exec"))
        (-> ["sandbox-exec" "-p" (seatbelt-profile dirs (some? git))]
            (into (mapcat (fn [i p] ["-D" (str "W" i "=" p)]) (range) dirs))
            (into (when git ["-D" (str "GIT=" git)]))
            (into argv))

        (on-path? "bwrap")
        (-> ["bwrap" "--ro-bind" "/" "/" "--dev" "/dev" "--proc" "/proc" "--die-with-parent"]
            (into (mapcat (fn [p] ["--bind" p p]) dirs))
            (into (when git (mapcat (fn [p] (when (real p) ["--ro-bind" p p]))
                                    [(path/join git "hooks") (path/join git "config")])))
            (into ["--"])
            (into argv))

        :else
        (do (when-not @warned?
              (reset! warned? true)
              (d/warn "sandbox" (str "no sandbox-exec or bwrap: bash is not confined to " dir)))
            argv)))
    argv))

(defn configure!
  "Pick the root from setting `sandbox.bash`: \"worktree\" (default) confines a
   worktree child to its checkout, \"always\" any session to its cwd, \"off\"
   nothing."
  [mode]
  (reset! root (case (str (or mode "worktree"))
                 "off"    nil
                 "always" (or (.. js/process -env -NYMA_WRITE_ROOT) (js/process.cwd))
                 (.. js/process -env -NYMA_WRITE_ROOT))))

(defn active? [] (some? @root))
