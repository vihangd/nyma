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
            [clojure.string :as str]))

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
  "Directories `dir`'s sandbox may write under, all resolved. No package
   caches: the parent runs what lands there (npx, cargo build.rs) unconfined."
  [dir git]
  (->> [dir git (os/tmpdir) "/tmp" "/private/var/folders"]
       (keep #(when % (real %)))
       distinct
       vec))

(defn- re-quote [s] (str/replace s #"[.*+?^${}()|\[\]\\]" #(str "\\" %)))

(defn seatbelt-profile
  "Writes denied everywhere but `dirs`; in the repo's .git (`git`), hooks and
   config stay read-only, a submodule's too, since the parent runs them."
  [dirs git]
  (str "(version 1)\n(allow default)\n(deny file-write*)\n"
       "(allow file-write* (literal \"/dev/null\") (regex #\"^/dev/(fd|tty|ptmx|ttys)\")"
       (apply str (map-indexed (fn [i _] (str " (subpath (param \"W" i "\"))")) dirs))
       ")\n"
       (when git
         (str "(deny file-write* (regex #\"^" (re-quote git)
              "(/modules/.+)?/(hooks(/|$)|config$)\"))\n"))))

(defn- bwrap-ok?
  "Can bwrap start here? Blocked user namespaces (Ubuntu 24.04, containers)
   make it fail on every call."
  []
  (try (zero? (.-exitCode (js/Bun.spawnSync #js ["bwrap" "--ro-bind" "/" "/" "--dev" "/dev" "true"]
                                            #js {:stdout "ignore" :stderr "ignore"})))
       (catch :default _ false)))

(defn- git-protected
  "hooks and config of the repo and each submodule, as far as they exist."
  [git]
  (let [mods (path/join git "modules")]
    (->> (cons git (when (fs/existsSync mods)
                     (map #(path/join mods %) (vec (fs/readdirSync mods)))))
         (mapcat #(vector (path/join % "hooks") (path/join % "config")))
         (filter fs/existsSync))))

(defn- prefix-for
  "The argv prefix confining bash to `dir`, or nil when no sandbox can run."
  [dir]
  (let [git  (git-common-dir dir)
        dirs (writable dir git)]
    (cond
      (and (= "darwin" js/process.platform) (some? (js/Bun.which "sandbox-exec")))
      (-> ["sandbox-exec" "-p" (seatbelt-profile dirs git)]
          (into (mapcat (fn [i p] ["-D" (str "W" i "=" p)]) (range) dirs)))

      (and (some? (js/Bun.which "bwrap")) (bwrap-ok?))
      ;; ponytail: submodules one level deep; nested ones are not protected
      (-> ["bwrap" "--ro-bind" "/" "/" "--dev" "/dev" "--die-with-parent"]
          (into (mapcat (fn [p] ["--bind" p p]) dirs))
          (into (mapcat (fn [p] ["--ro-bind" p p]) (when git (git-protected git))))
          (into ["--"])))))

;; root → prefix (nil when unavailable): git and the probe run once, not per command
(defonce ^:private prefixes (atom {}))

(defn- prefix []
  (when-let [dir (some-> @root real)]
    (if (contains? @prefixes dir)
      (get @prefixes dir)
      (let [p (prefix-for dir)]
        (when-not p
          (d/warn "sandbox" (str "no usable sandbox-exec or bwrap: bash is not confined to " dir)))
        (swap! prefixes assoc dir p)
        p))))

(defn wrap
  "`argv` to run under the sandbox, or unchanged when there is none. A missing
   sandbox warns once rather than refusing bash outright."
  [argv]
  (if-let [p (prefix)] (into p argv) argv))

(defn configure!
  "Pick the root from setting `sandbox.bash`: \"worktree\" (default) confines a
   worktree child to its checkout, \"always\" any session to its cwd, \"off\"
   nothing."
  [mode]
  (reset! root (case (str (or mode "worktree"))
                 "off"    nil
                 "always" (or (.. js/process -env -NYMA_WRITE_ROOT) (js/process.cwd))
                 (.. js/process -env -NYMA_WRITE_ROOT))))
