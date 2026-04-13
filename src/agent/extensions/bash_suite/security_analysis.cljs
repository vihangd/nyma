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

(defn- classify-token-group
  "Classify a group of tokens (a single subcommand) by its command name and arguments."
  [tokens]
  (let [cmd-name (first tokens)
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
                (contains? interpreter-commands (first (:tokens group))))
         (conj reasons (str "piped output to interpreter: " (first (:tokens group))))
         reasons))
     []
     @groups)))

(defn classify-command
  "Parse and classify a shell command. Returns {:level :reasons :command}."
  [cmd config]
  (let [cmd-str (str cmd)]
    ;; Check destructive patterns first (regex-based, no parsing needed)
    (let [destructive-reasons (check-destructive-patterns cmd-str)
          obfuscation-reasons (when (:block-obfuscated config)
                                (check-obfuscation cmd-str))]
      (if (seq destructive-reasons)
        {:level :destructive :reasons destructive-reasons :command cmd-str}
        (if (seq obfuscation-reasons)
          {:level :destructive :reasons obfuscation-reasons :command cmd-str}
          ;; Parse with shell-quote for structural analysis
          (try
            (let [parsed (.parse shell-quote cmd-str)
                  ;; Check pipe-to-interpreter (uses raw JS array)
                  pipe-reasons (when (:block-network-exec config)
                                 (check-pipe-to-interpreter parsed))
                  ;; Split into subcommand groups at operators
                  groups (atom [])
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
                    max-level (reduce higher-risk :safe group-levels)
                    final-level (if (seq pipe-reasons) :destructive max-level)
                    all-reasons (into (or pipe-reasons [])
                                      (when (= final-level :destructive)
                                        destructive-reasons))]
                {:level   final-level
                 :reasons (if (seq all-reasons) all-reasons
                              [(str "classified as " (str final-level))])
                 :command cmd-str}))
            (catch :default _e
              ;; Fail-closed: unparseable commands treated as destructive
              {:level :destructive
               :reasons ["unparseable command (fail-closed)"]
               :command cmd-str})))))))

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
