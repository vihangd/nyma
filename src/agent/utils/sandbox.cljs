(ns agent.utils.sandbox
  "Bash that can only write where it is meant to, read no secrets, and reach
   only the hosts it is allowed.

   Backed by Anthropic's sandbox-runtime (srt): Seatbelt on macOS, bubblewrap
   plus seccomp on Linux, and a host-side proxy that is the only way out to the
   network. Hand-rolling that is where other agents got their CVEs; srt carries
   the fixes, the deny-by-default macOS profile (no `open`, Apple Events or
   launchd), and the protected-file list (.git/hooks, .git/config, shell rc,
   .vscode, .mcp.json) relative to the command's cwd.

   On top of srt's list: nyma's own config, which the parent would run, the
   repo's shared .git when it is not under the root (a worktree's), and the
   credential files a prompt-injected command would read."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["@anthropic-ai/sandbox-runtime" :refer [SandboxManager]]
            [agent.debug :as d]
            [agent.utils.home :as home]))

;; The directory bash may write under, or nil for no sandbox.
(defonce root (atom nil))
;; Promise of true once srt is up for `root`, false when it cannot be.
(defonce ^:private ready (atom nil))
;; What the permission gate puts in bash's `unsandboxed` once the user said
;; yes. An object: no model- or script-supplied argument can be identical to it.
(defonce escape-approved #js {:approved true})

;; Whether bash is confined right now, for the status line.
(defonce on? (atom false))

(def registry-hosts
  "Where builds fetch from. A worktree child gets none of them unless
   `sandbox.child-network` is \"registries\"."
  ["registry.npmjs.org" "registry.yarnpkg.com" "pypi.org" "files.pythonhosted.org"
   "crates.io" "static.crates.io" "index.crates.io" "proxy.golang.org" "sum.golang.org"
   "rubygems.org" "repo.maven.apache.org" "github.com" "codeload.github.com"
   "objects.githubusercontent.com" "raw.githubusercontent.com" "gitlab.com"])

(def cache-dirs
  "Package caches under HOME the main session's installs write."
  [".npm" ".bun/install/cache" ".cache" "Library/Caches" ".cargo/registry" ".cargo/git"
   "go/pkg/mod" ".gradle/caches" ".m2/repository" ".pnpm-store" "Library/pnpm"])

(def secret-paths
  "Read-denied: what an injected `curl -d @…` would send."
  ["~/.ssh" "~/.aws" "~/.config/gcloud" "~/.config/gh" "~/.azure" "~/.kube"
   "~/.docker/config.json" "~/.netrc" "~/.npmrc" "~/.pypirc" "~/.git-credentials"
   "~/.nyma/credentials.json" "~/.nyma/settings.json" "~/Library/Keychains"])

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

(defn- git-protected
  "hooks, config and info of the repo and each submodule: the parent runs them."
  [git]
  (let [mods (path/join git "modules")]
    (->> (cons git (when (fs/existsSync mods)
                     (map #(path/join mods %) (vec (fs/readdirSync mods)))))
         (mapcat #(map (fn [f] (path/join % f)) ["hooks" "config" "info"])))))

(defn policy
  "srt config for bash confined to `dir`. `cfg` is the user's `sandbox`
   settings; `child?` a worktree child, which gets no network by default."
  [dir git child? cfg]
  {:network    {:allowedDomains (vec (concat (when (or (not child?) (= "registries" (:child-network cfg)))
                                               registry-hosts)
                                             (when-not child? (:allow-hosts cfg))))
                :deniedDomains  []}
   :filesystem {:allowWrite (vec (keep identity (concat [dir git (real (os/tmpdir))
                                                        ;; scripts write /tmp, not $TMPDIR; srt wants it resolved
                                                        (real "/tmp")]
                                                       ;; installs need them; a child's would be run
                                                       ;; later by its unconfined parent, so not there
                                                       (when-not child? (keep real (map #(path/join (home/dir) %) cache-dirs))))))
                :denyWrite  (vec (concat [(path/join dir ".nyma") (path/join dir ".envrc")]
                                         (when git (git-protected git))))
                :denyRead   (vec (concat secret-paths (:deny-read cfg)))}})

(def host-choices ["Allow once" "Allow for this session" "Allow always (every project)" "Deny"])

(defn host-asker
  "srt's callback for a host no list decides. `ask` shows `host-choices` and
   resolves to one (nil: no one to ask); `save!` records an always. A child,
   and a session with no one to ask, is refused with the host named."
  [child? ask save!]
  (let [session (atom #{})
        deny    (fn [reason] (if (.-askCallbackDenyReason SandboxManager) #js {:allow false :reason reason} false))]
    ^:async
    (fn [params]
      (let [host (str (.-host params))]
        (cond
          (contains? @session host) true
          (or child? (nil? ask))
          (deny (str host " is not on the sandbox's allow list; ask the user to add it to sandbox.allow-hosts"))
          :else
          (let [c (str (js-await (ask host)))]
            (cond
              (= c (nth host-choices 0)) true
              (= c (nth host-choices 1)) (do (swap! session conj host) true)
              (= c (nth host-choices 2)) (do (swap! session conj host) (when save! (save! host)) true)
              :else (deny (str "the user did not allow " host)))))))))

(defn- ^:async start!
  [dir child? cfg opts]
  (try
    (when-not (.isSupportedPlatform SandboxManager)
      (throw (js/Error. "this platform is not supported")))
    (when-let [errs (seq (.-errors (.checkDependencies SandboxManager)))]
      (throw (js/Error. (first errs))))
    (js-await (.reset SandboxManager))
    (js-await (.initialize SandboxManager (clj->js (policy dir (git-common-dir dir) child? cfg))
                           (host-asker child? (:ask opts) (:save-host! opts))))
    true
    (catch :default e
      (d/warn "sandbox" (str "bash is not confined to " dir ": " (.-message e)))
      false)))

(defn configure!
  "Pick the root from the user's `sandbox` settings: `bash` \"always\"
   (default) confines any session to its cwd and a worktree child to its
   checkout, \"worktree\" only the child, \"off\" nothing. `opts`: `:ask` and `:save-host!`, see
   `host-asker`. Returns the readiness promise."
  [cfg & [opts]]
  (let [child (.. js/process -env -NYMA_WRITE_ROOT)
        dir   (some-> (case (str (or (:bash cfg) "always"))
                        "off"    nil
                        "always" (or child (js/process.cwd))
                        child)
                      real)]
    (reset! root dir)
    (reset! on? false)
    (reset! ready (-> (if dir (start! dir (some? child) cfg opts) (js/Promise.resolve false))
                      (.then (fn [ok] (reset! on? ok) ok))))))

(def ^:private secret-env
  #"(?i)(_API_KEY|_TOKEN|_SECRET|_PASSWORD|_CREDENTIALS?)$|^(AWS_|ANTHROPIC_|OPENAI_|SSH_AUTH_SOCK$|GH_TOKEN$|GITHUB_TOKEN$|TYPESAFE_)")

(defn scrub-env
  "`env` without what a sandboxed command has no business holding: nyma's own
   provider keys among them. srt's proxy variables stay."
  [env]
  (let [out #js {}]
    (doseq [k (js/Object.keys env)]
      (when-not (re-find secret-env k) (aset out k (aget env k))))
    out))

(defn ^:async wrap
  "`{:argv :env}` to run `command` under the sandbox, or plain `sh -c` when
   there is none. `:env` is nil when unchanged."
  [command]
  (if (and @ready (js-await @ready))
    (let [r (js-await (.wrapWithSandboxArgv SandboxManager command))]
      {:argv (vec (.-argv r)) :env (scrub-env (.-env r)) :sandboxed? true})
    {:argv ["sh" "-c" command] :env nil}))

(defn ^:async wrap-call
  "`wrap` for a bash call's args: plain `sh -c` only when the permission gate
   put its approval token in `unsandboxed`, and never in a worktree child."
  [command unsandboxed]
  (if (and (identical? unsandboxed escape-approved) (not (.. js/process -env -NYMA_WRITE_ROOT)))
    {:argv ["sh" "-c" command] :env nil}
    (js-await (wrap command))))

(defn annotate
  "`stderr` with the sandbox's denials for `command` appended, so the model
   learns which path or host was refused instead of retrying blind."
  [command stderr]
  (let [s (try (.annotateStderrWithSandboxFailures SandboxManager command stderr)
               (catch :default _ stderr))]
    ;; srt names network denials; file ones only reach its log monitor late
    (cond-> s
      (.includes (str stderr) "Operation not permitted")
      (str "\n<sandbox>bash writes only under " @root " and temp, cannot read credential files,"
           " and cannot write .nyma/, .envrc, shell rc files or git hooks/config. Do not retry"
           " the same path; ask the user if it is needed.</sandbox>"))))
