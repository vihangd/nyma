(ns agent.extensions.bash-suite.security-analysis
  (:require ["shell-quote" :as shell-quote]
            [agent.extensions.bash-suite.shared :as shared]
            [clojure.string :as str]))

;; ── Command classification tables ────────────────────────────

(def read-only-commands
  #{"ls" "cat" "head" "tail" "wc" "find" "grep" "rg" "ag" "ack"
    "file" "stat" "which" "type" "echo" "printf" "pwd" "date"
    "env" "printenv" "tree" "df" "du" "ps" "uptime" "whoami"
    "hostname" "uname" "id" "groups" "locale" "lsof" "free"
    "top" "htop" "less" "more" "diff" "comm" "sort" "uniq"
    "cut" "tr" "awk" "column" "basename" "dirname" "realpath"
    "readlink" "sha256sum" "md5sum" "xxd" "hexdump" "strings"
    "nm" "objdump" "test" "[" "true" "false"})

(def write-commands
  #{"cp" "mv" "mkdir" "touch" "chmod" "chown" "sed" "tee"
    "npm" "bun" "yarn" "pnpm" "pip" "pip3" "cargo" "go"
    "git" "make" "cmake" "rake" "gradle" "mvn" "ant"
    "python" "python3" "node" "ruby" "perl" "java" "javac"
    "rustc" "gcc" "g++" "clang" "patch" "install"})

(def network-commands
  #{"curl" "wget" "fetch" "nc" "ncat" "nmap" "ssh" "scp"
    "rsync" "ftp" "sftp" "telnet" "ping" "traceroute" "dig"
    "nslookup" "host" "whois" "netstat" "ss" "socat"})

(def interpreter-commands
  #{"bash" "sh" "zsh" "fish" "dash" "ksh" "csh" "tcsh"
    "eval" "exec" "source" "." "python" "python3" "perl"
    "ruby" "node"})

;; ── Destructive pattern detection ────────────────────────────

(def destructive-patterns
  [;; rm -rf targeting root, home, or wildcard-all
   {:regex #"rm\s+(-[a-zA-Z]*r[a-zA-Z]*f|(-[a-zA-Z]*f[a-zA-Z]*r))\s+(/|/\*|~|~/)(\s|$)"
    :reason "rm with recursive+force targeting root or home"}
   ;; dd writing to block devices
   {:regex #"dd\s+.*if=.*of=/dev/(sd|hd|nvme|vd|xvd|mmcblk)"
    :reason "dd writing to block device"}
   ;; mkfs on any device
   {:regex #"mkfs(\.\w+)?\s+/dev/"
    :reason "filesystem format on block device"}
   ;; Fork bomb patterns
   {:regex #":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*:"
    :reason "fork bomb detected"}
   {:regex #"\.\(\)\s*\{\s*\.\s*\|\s*\.\s*&\s*\}\s*;?\s*\."
    :reason "fork bomb variant detected"}
   ;; chmod 777 on root
   {:regex #"chmod\s+(-R\s+)?777\s+(/|/\*)(\s|$)"
    :reason "chmod 777 on root filesystem"}
   ;; Overwrite system files via redirect
   {:regex #">\s*/dev/(sd|hd|nvme)"
    :reason "redirect to block device"}
   {:regex #">\s*/etc/(passwd|shadow|sudoers)"
    :reason "overwriting critical system file"}
   ;; Kill init
   {:regex #"kill\s+(-9\s+)?1(\s|$)"
    :reason "killing init process"}
   ;; Truncate critical files
   {:regex #">\s*/etc/(passwd|shadow|hosts|resolv\.conf|fstab)"
    :reason "truncating critical system file"}])

;; ── Line-separator / subshell / redirect detection ──────────

(def ^:private line-separator-regex
  ;; U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR, U+0085 NEXT LINE
  (js/RegExp. "[\u2028\u2029\u0085]"))

(defn- has-line-separator-injection? [cmd]
  (boolean (.test line-separator-regex cmd)))

(def ^:private redirect-regex
  ;; heredoc (<<<), append (>>), input (<), output (>), with optional fd digit
  (js/RegExp. "(^|\\s)[0-9]?(>>?|<<?<?)"))

(defn- has-redirect? [cmd]
  (boolean (re-find redirect-regex cmd)))

(defn- extract-subshells
  "Return a vector of inner command strings from $(...) and (...) subshells
   (non-nested only — the recursive classifier handles depth)."
  [cmd]
  (let [dollar-pat #"\$\(([^()]*)\)"
        paren-pat  #"(?<!\$)\(([^()]*)\)"
        dollar-ms  (re-seq dollar-pat (str cmd))
        paren-ms   (re-seq paren-pat  (str cmd))]
    (vec (concat (keep second dollar-ms)
                 (keep second paren-ms)))))

;; ── Obfuscation detection ────────────────────────────────────

(def obfuscation-patterns
  [;; base64 decode piped to shell
   {:regex #"base64\s+(-d|--decode).*\|\s*(bash|sh|zsh|eval)"
    :reason "base64 encoded command piped to interpreter"}
   ;; eval with string or variable
   {:regex #"eval\s+[\"'$]"
    :reason "eval with dynamic content"}
   ;; hex-encoded characters in command
   {:regex #"\\x[0-9a-fA-F]{2}.*\\x[0-9a-fA-F]{2}"
    :reason "hex-encoded characters in command"}
   ;; Excessive backslash escaping
   {:regex #"\\{4,}"
    :reason "excessive backslash escaping (potential obfuscation)"}
   ;; Python/perl -c with suspicious content
   {:regex #"(python3?|perl)\s+-[ce]\s+['\"].*__(import|eval|exec).*['\"]"
    :reason "scripting language executing suspicious code"}])

;; ── Classification logic ─────────────────────────────────────

(defn- risk-level-value [level]
  (case level
    :safe 0
    :read-only 1
    :write 2
    :network 3
    :destructive 4
    0))

(defn- higher-risk [a b]
  (if (> (risk-level-value a) (risk-level-value b)) a b))

(defn- check-destructive-patterns [cmd]
  (reduce
   (fn [reasons pattern]
     (if (re-find (:regex pattern) cmd)
       (conj reasons (:reason pattern))
       reasons))
   []
   destructive-patterns))

(defn- check-obfuscation [cmd]
  (reduce
   (fn [reasons pattern]
     (if (re-find (:regex pattern) cmd)
       (conj reasons (:reason pattern))
       reasons))
   []
   obfuscation-patterns))

(def privilege-commands
  "Commands that run what follows them as another user. They are wrappers, not
   work: the risk belongs to the command BEHIND them."
  #{"sudo" "doas" "pkexec" "su" "runuser"})

(defn strip-privilege-wrapper
  "Tokens with any leading privilege wrapper removed, so the real command is the
   one classified.

   Every classifier here keys off `(first tokens)`, and none of these wrappers
   appears in the command tables — so `sudo cat /etc/shadow` classified as
   :safe (cmd-name \"sudo\" → :else), and `curl x | sudo bash` was NOT flagged
   as a pipe-to-interpreter while `curl x | bash` was. A privilege wrapper
   downgraded everything behind it.

   `su`/`runuser` run their command inside a `-c` STRING, so there is no bare
   command token to recover; those are left in place and caught by
   `check-privilege-escalation` instead. Pure; exposed for tests."
  [tokens]
  (loop [ts (vec tokens)]
    (let [head (first ts)]
      (cond
        (nil? head) ts
        ;; `env FOO=1 cmd` — drop env and its assignments.
        (= head "env") (recur (vec (drop-while (fn [t] (.includes (str t) "="))
                                               (rest ts))))
        ;; Only wrappers that take the command as ARGV can be unwrapped.
        (contains? #{"sudo" "doas" "pkexec"} head)
        (recur (loop [r (vec (rest ts))]
                 (let [o (first r)]
                   (cond
                     (nil? o) r
                     ;; flags taking a value
                     (contains? #{"-u" "-g" "-p" "-U" "--user" "--group"} o) (recur (vec (drop 2 r)))
                     (= o "--") (vec (rest r))
                     (.startsWith (str o) "-") (recur (vec (rest r)))
                     :else r))))
        :else ts))))

(def privilege-patterns
  "Privilege escalation, gated on config `:block-privilege-escalation`.

   `su -c` and `runuser -c` carry their command in a quoted string, so stripping
   the wrapper cannot recover it — these are flagged on the wrapper itself.
   Bare `sudo cmd` is NOT here: the wrapper is stripped and `cmd` is judged on
   its own merits, which is the point. What IS here is sudo used to obtain a
   shell or to run an interpreter, where the intent is the privilege itself."
  [{:regex #"\b(su|runuser)\b[^|;&]*\s-c\b"
    :reason "runs a command as another user via su -c"}
   {:regex #"\b(sudo|doas|pkexec)\s+(-[a-zA-Z]+\s+)*(bash|sh|zsh|dash|ksh)\b"
    :reason "opens a privileged shell"}
   {:regex #"\bsudo\s+(-[a-zA-Z]+\s+)*(python3?|perl|ruby|node)\b"
    :reason "runs an interpreter with elevated privileges"}
   {:regex #"\bsudo\s+-[a-zA-Z]*s\b"
    :reason "sudo -s opens a privileged shell"}
   {:regex #"\bchmod\s+(u\+s|[0-7]*[24][0-7]{3})\b"
    :reason "sets a setuid/setgid bit"}])

(defn check-privilege-escalation
  "Reasons `cmd` escalates privilege, or an empty vector. Pure; exposed for
   tests. Mirrors check-obfuscation."
  [cmd]
  (reduce (fn [acc {:keys [regex reason]}]
            (if (.test regex cmd) (conj acc reason) acc))
          []
          privilege-patterns))

(defn- classify-token-group
  "Classify a group of tokens (a single subcommand) by its command name and arguments."
  [tokens]
  (let [tokens   (strip-privilege-wrapper tokens)
        cmd-name (first tokens)
        args-str (str/join " " tokens)]
    (cond
      (nil? cmd-name) :safe
      (contains? read-only-commands cmd-name) :read-only
      (contains? network-commands cmd-name)   :network
      (contains? write-commands cmd-name)     :write
      :else                                   :safe)))

(defn- check-pipe-to-interpreter
  "Check if any subcommand pipes into an interpreter (curl|bash, etc.)."
  [parsed-tokens]
  (let [groups (atom [])
        current (atom [])
        last-op (atom nil)]
    ;; Split tokens into subcommand groups tracking pipe operators
    (doseq [token parsed-tokens]
      (if (and (not (string? token)) (.-op token))
        (do
          (when (seq @current)
            (swap! groups conj {:tokens @current :piped-from @last-op}))
          (reset! current [])
          (reset! last-op (.-op token)))
        (swap! current conj (str token))))
    (when (seq @current)
      (swap! groups conj {:tokens @current :piped-from @last-op}))
    ;; Check if any piped-to group starts with an interpreter
    (reduce
     (fn [reasons group]
       (if (and (= (:piped-from group) "|")
                (seq (:tokens group))
                (contains? interpreter-commands
                    (first (strip-privilege-wrapper (:tokens group)))))
         (conj reasons (str "piped output to interpreter: "
                            (first (strip-privilege-wrapper (:tokens group)))))
         reasons))
     []
     @groups)))

(defn classify-command
  "Parse and classify a shell command. Returns {:level :reasons :command}.
   Checks (in order): line-separator injection, redirect policy, destructive patterns,
   obfuscation, subshell recursion, then shell-quote structural analysis."
  [cmd config]
  (let [cmd-str   (str cmd)
        max-depth (or (:max-subshell-depth config) 4)]
    ;; (a) Line-separator injection — U+2028/U+2029/U+0085 bypass scanners
    (cond
      (has-line-separator-injection? cmd-str)
      {:level   :destructive
       :reasons ["line-separator injection (U+2028/U+2029/U+0085)"]
       :command cmd-str}

      ;; (b) Redirect policy (opt-in via :block-redirects true)
      (and (:block-redirects config) (has-redirect? cmd-str))
      {:level   :destructive
       :reasons ["redirect blocked by policy"]
       :command cmd-str}

      :else
      ;; Check destructive patterns first (regex-based, no parsing needed)
      (let [destructive-reasons (check-destructive-patterns cmd-str)
            obfuscation-reasons (when (:block-obfuscated config)
                                  (check-obfuscation cmd-str))
            privilege-reasons   (when (:block-privilege-escalation config)
                                  (check-privilege-escalation cmd-str))
            ;; (c) Recursively classify subshell bodies; take highest risk
            subshell-results    (when (pos? max-depth)
                                  (mapv #(classify-command
                                          % (update config :max-subshell-depth dec))
                                        (extract-subshells cmd-str)))
            subshell-max        (reduce higher-risk :safe (map :level subshell-results))
            subshell-reasons    (into [] (mapcat :reasons
                                                 (filter #(= (:level %) :destructive)
                                                         subshell-results)))]
        (if (seq destructive-reasons)
          {:level :destructive :reasons destructive-reasons :command cmd-str}
          (if (seq obfuscation-reasons)
            {:level :destructive :reasons obfuscation-reasons :command cmd-str}
            (if (seq privilege-reasons)
              {:level :destructive :reasons privilege-reasons :command cmd-str}
            (if (= subshell-max :destructive)
              {:level   :destructive
               :reasons (or (seq subshell-reasons) ["destructive command in subshell"])
               :command cmd-str}
              ;; Parse with shell-quote for structural analysis
              (try
                (let [parsed (.parse shell-quote cmd-str)
                      ;; Check pipe-to-interpreter (uses raw JS array)
                      pipe-reasons (when (:block-network-exec config)
                                     (check-pipe-to-interpreter parsed))
                      ;; Split into subcommand groups at operators
                      groups  (atom [])
                      current (atom [])]
                  ;; Build subcommand groups — iterate JS array directly
                  (doseq [token parsed]
                    (if (and (not (string? token)) (.-op token))
                      (do
                        (when (seq @current) (swap! groups conj @current))
                        (reset! current []))
                      (swap! current conj (str token))))
                  (when (seq @current) (swap! groups conj @current))
                  ;; Classify each group and take highest risk
                  (let [group-levels (map classify-token-group @groups)
                        max-level    (reduce higher-risk subshell-max group-levels)
                        final-level  (if (seq pipe-reasons) :destructive max-level)
                        all-reasons  (into (or pipe-reasons [])
                                           (when (= final-level :destructive)
                                             destructive-reasons))]
                    {:level   final-level
                     :reasons (if (seq all-reasons) all-reasons
                                  [(str "classified as " (str final-level))])
                     :command cmd-str}))
                (catch :default _e
                  ;; Fail-closed: unparseable commands treated as destructive
                  {:level   :destructive
                   :reasons ["unparseable command (fail-closed)"]
                   :command cmd-str}))))))))))

(defn should-block?
  "Determine if a classified command should be blocked based on config."
  [classification config]
  (let [level (:level classification)]
    (cond
      (= level :destructive) (:block-destructive config)
      (and (= level :network)
           (some #(str/includes? (str %) "piped output to interpreter")
                 (:reasons classification)))
      (:block-network-exec config)
      :else false)))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config     (shared/load-config)
        sec-config (:security-analysis config)]

    (.on api "before_tool_call"
         (fn [data]
           (when (and (:enabled sec-config)
                      (shared/is-bash-tool? (.-name data)))
             (let [args   (.-args data)
                   cmd    (or (.-command args) (aget args "command"))
                   result (classify-command cmd sec-config)]
            ;; Emit classification on inter-extension bus
               (when-let [events (.-events api)]
                 (let [emit-fn (.-emit events)]
                   (when (fn? emit-fn)
                     (emit-fn "bash:classification" (clj->js result)))))
            ;; Update stats
               (swap! shared/suite-stats update :security-analysis
                      (fn [s] (-> s
                                  (update :commands-analyzed inc)
                                  (update-in [:classified (:level result)] inc))))
            ;; Skip via clean result string — model gets actionable guidance, not an error marker
               (when (should-block? result sec-config)
                 (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
                 #js {:skip true
                      :result (str "Command blocked by security analysis ["
                                   (str (:level result)) "]: "
                                   (str/join "; " (:reasons result))
                                   "\n\nPlease use an alternative approach or ask the user for clarification.")}))))
         100)

    ;; Return deactivator
    (fn [] nil)))
