(ns agent.utils.shell-grant
  "What one shell command is, and what an \"Allow always\" for it covers.

   Shared by the bash permission gate (bash_suite) and the prompt's
   \"Allow always\" (middleware), so the two cannot disagree about what counts
   as one simple command."
  (:require [clojure.string :as str]))

(def ^:private compound-re
  "Anything that makes a command more than one simple command: a separator,
   pipe, redirect, background `&`, newline, or command substitution."
  #"[;&|<>`\n]|\$\(")

(defn simple-command?
  "True when `cmd` is ONE simple command — the only shape a remembered grant
   can vouch for. A glob like `echo *` matched against a whole string also
   swallowed `echo x >> ~/.zshrc` and `echo hi; curl … | sh`."
  [cmd]
  (not (re-find compound-re (str cmd))))

(def subcommand-tools
  "Tools whose second word is the real verb: `git push` and `git status` are
   different permissions, `pytest -x` and `pytest tests/` are not."
  #{"git" "npm" "pnpm" "yarn" "bun" "bunx" "npx" "deno" "cargo" "go" "docker"
    "kubectl" "gh" "pip" "pip3" "uv" "poetry" "brew" "apt" "apt-get" "systemctl"
    "make" "dotnet" "mvn" "gradle" "terraform" "helm" "aws" "gcloud" "az"})

(def code-runners
  "Commands whose arguments are themselves arbitrary code or another command:
   interpreters and wrappers. `bash *` after `bash -c 'ls'` also covered
   `bash -c 'rm -rf ~/work'`; `sudo *` after `sudo ls` covered any root
   command. No grant can vouch for what they run, so they never get one.
   ponytail: a deny-list can only cover what it names, and a one-word grant
   covers the tool's every flag (`tar --to-command`); verb-only grants if that
   ever matters."
  #{"bash" "sh" "zsh" "fish" "dash" "ksh" "csh" "tcsh"
    "python" "python2" "python3" "pypy" "pypy3" "ipython" "node" "perl" "ruby" "php"
    "lua" "tclsh" "Rscript" "pwsh" "osascript" "awk" "gawk" "mawk" "nawk" "uvx"
    "sudo" "doas" "su" "env" "xargs" "find" "eval" "exec" "nohup" "timeout" "nice"
    "time" "watch" "ssh" "command" "builtin"
    "source" "." "stdbuf" "setsid" "flock" "script" "ionice" "taskset" "chrt"
    "numactl" "caffeinate" "busybox" "toybox" "unbuffer" "rlwrap" "parallel"
    "chroot" "unshare" "nsenter" "runuser" "pkexec" "fakeroot" "firejail"
    "sandbox-exec" "strace" "ltrace" "gdb" "lldb" "expect" "tmux" "screen" "entr"
    "pipx" "vi" "vim" "nvim" "emacs" "ed" "ex"})

(def ^:private code-verbs
  "Subcommands that run code or a command passed as arguments, on tools otherwise
   granted by verb: `uv run pytest` must not cover `uv run bash -c …`. `npm run`
   and its kin are not here: they take only a script the project already defines."
  #{"deno eval" "deno repl" "deno run" "deno task"
    "bun run" "bun x" "bun exec" "npm exec" "npm x" "pnpm exec" "pnpm dlx"
    "yarn exec" "yarn dlx" "uv run" "uv tool" "poetry run" "cargo run" "go run"
    "dotnet run" "docker run" "docker exec" "docker compose"
    "kubectl exec" "kubectl run" "kubectl debug"})

(def exec-flags
  "Flags that make an otherwise plain tool write, or run a command it is
   handed. A grant for the tool never covers them, and the classifier counts
   them as writes: `tar *` must not cover `tar --to-command='rm -rf ~'`.
   ponytail: matched per word (exact, `--flag=`, or a short flag with its value
   attached); a cluster like `tar -xI…` hides `-I`. Parse clusters if that bites."
  {"find" #{"-exec" "-execdir" "-ok" "-okdir" "-delete" "-fls" "-fprint" "-fprint0"
            "-fprintf" "-files0-from"}
   "rg"   #{"--pre" "--hostname-bin" "-z" "--search-zip"}
   "sort" #{"-o" "--output" "--compress-program"}
   "tree" #{"-o"}
   "tar"  #{"--to-command" "--use-compress-program" "-I" "--checkpoint-action"
            "--info-script" "--new-volume-script" "-F"}
   "git"  #{"-c" "--config-env" "--upload-pack" "--receive-pack" "--exec"}
   "ssh"  #{"-o" "-F"}
   "rsync" #{"-e" "--rsh" "--rsync-path"}})

(defn exec-flag?
  "Does `c` (a string or its words) use one of its tool's `exec-flags`?"
  [c]
  (let [[w & args] (if (string? c) (str/split (str/trim c) #"\s+") (vec c))
        flags      (get exec-flags (last (.split (str w) "/")))]
    (boolean
     (and flags
          (some (fn [a]
                  (let [a (str a)]
                    (some #(or (= a %)
                               (str/starts-with? a (str % "="))
                               (and (= 2 (count %)) (str/starts-with? a %)))
                          flags)))
                args)))))

(defn code-runner?
  "First word of `c` — by name or path (`/bin/sh`), and with any version suffix
   (`python3.12`) — is a code runner. So is an env-assignment prefix: it is not
   the command, and `FOO=1 rm -rf ~` would ride on a grant for `FOO=1 make`."
  [c]
  (let [[w sub] (str/split (str/trim (str c)) #"\s+")
        name    (last (.split (str w) "/"))]
    (or (contains? code-runners name)
        (contains? code-runners (str/replace name #"[\d.]+$" ""))
        (contains? code-verbs (str name " " sub))
        (some? (re-find #"^[A-Za-z_][A-Za-z0-9_]*=" (str w))))))

(defn grant-base
  "What \"Allow always\" for `cmd` remembers: `git push` for `git push origin
   main`, `pytest` for `pytest -x tests/`. A verb tool with a flag where the verb
   goes keeps the whole command: `bun --version` must not become `bun`, and
   `git -c x=y log` must not become `git`. nil for a compound command, an empty
   one, or a code runner — those never get an always-allow."
  [cmd]
  (let [c (str/trim (str cmd))]
    (when (and (seq c) (simple-command? c) (not (code-runner? c)) (not (exec-flag? c)))
      (let [[w sub] (str/split c #"\s+")]
        (cond
          (not (contains? subcommand-tools w)) w
          (and sub (not (str/starts-with? sub "-"))) (str w " " sub)
          :else c)))))

(defn grant-matches?
  "Does the remembered `base` cover `cmd`? Only a simple command, and only on a
   word boundary: `git push` covers `git push --force` but not `git pushx`, and
   never `git push; rm -rf ~`. A bare verb tool covers only itself, so a `git` or
   `bun` grant saved before verbs were split out does not cover every verb."
  [base cmd]
  (let [c (str/trim (str cmd))
        b (str base)]
    ;; A code runner is refused even against a grant saved before they were
    ;; excluded: the grant cannot know what it would be approving.
    (boolean (and (seq b) (simple-command? c) (not (code-runner? c)) (not (exec-flag? c))
                  (or (= c b)
                      (and (not (contains? subcommand-tools b))
                           (str/starts-with? c (str b " "))))))))
