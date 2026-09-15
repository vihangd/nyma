(ns cli.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.version :refer [version]]
            [clojure.string :as str]
            ["node:os" :as os]
            ["node:util" :refer [parseArgs]]
            [agent.cli :refer [resolve-ext-flags resolve-model-via-registry help-text
                               parse-args-error error-line resolve-session cli-options
                               interactive-resources output-format-error]]))

;; ── output-format-error ────────────────────────────────────

(describe "output-format-error"
          (fn []
            (it "refuses a typo in print mode, where the flag is read"
                (fn []
                  (-> (expect (output-format-error "josn" "print"))
                      (.toBe "nyma: --output-format must be text, json or stream-json"))
                  (-> (expect (output-format-error "stream-json" "print")) (.toBeNil))
                  (-> (expect (output-format-error nil "print")) (.toBeNil))))

            (it "ignores the flag in rpc and interactive mode, which never read it"
                (fn []
                  (-> (expect (output-format-error "josn" "rpc")) (.toBeNil))
                  (-> (expect (output-format-error "josn" "interactive")) (.toBeNil))))))

;; ── resolve-ext-flags ──────────────────────────────────────

(defn- setup-flagged-agent
  "Create agent + register flags for testing."
  []
  (let [agent (create-agent {:model "test" :system-prompt "test"})
        api   (create-extension-api agent)]
    ;; Register some flags
    (.registerFlag api "verbose" #js {:type "boolean" :default false})
    (.registerFlag api "format" #js {:type "string" :default "json"})
    (.registerFlag api "count" #js {:type "number" :default 10})
    agent))

(describe "resolve-ext-flags" (fn []
                                (it "resolves boolean flag without value"
                                    (fn []
                                      (let [agent (setup-flagged-agent)
                                            orig  js/process.argv]
                                        (set! js/process.argv #js ["node" "nyma" "--ext-verbose"])
                                        (resolve-ext-flags agent)
                                        (set! js/process.argv orig)
                                        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBe true)))))

                                (it "resolves string flag with value"
                                    (fn []
                                      (let [agent (setup-flagged-agent)
                                            orig  js/process.argv]
                                        (set! js/process.argv #js ["node" "nyma" "--ext-format=text"])
                                        (resolve-ext-flags agent)
                                        (set! js/process.argv orig)
                                        (-> (expect (:value (get @(:flags agent) "format"))) (.toBe "text")))))

                                (it "resolves number flag with value"
                                    (fn []
                                      (let [agent (setup-flagged-agent)
                                            orig  js/process.argv]
                                        (set! js/process.argv #js ["node" "nyma" "--ext-count=5"])
                                        (resolve-ext-flags agent)
                                        (set! js/process.argv orig)
                                        (-> (expect (:value (get @(:flags agent) "count"))) (.toBe 5)))))

                                (it "resolves boolean false"
                                    (fn []
                                      (let [agent (setup-flagged-agent)
                                            orig  js/process.argv]
                                        (set! js/process.argv #js ["node" "nyma" "--ext-verbose=false"])
                                        (resolve-ext-flags agent)
                                        (set! js/process.argv orig)
                                        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBe false)))))

                                (it "ignores unknown ext flags"
                                    (fn []
                                      (let [agent (setup-flagged-agent)
                                            orig  js/process.argv]
                                        (set! js/process.argv #js ["node" "nyma" "--ext-unknown=x"])
                                        (resolve-ext-flags agent)
                                        (set! js/process.argv orig)
        ;; Known flags should still have nil value
                                        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBeNull)))))

                                (it "handles no ext flags"
                                    (fn []
                                      (let [agent (setup-flagged-agent)
                                            orig  js/process.argv]
                                        (set! js/process.argv #js ["node" "nyma" "--model" "test"])
                                        (resolve-ext-flags agent)
                                        (set! js/process.argv orig)
        ;; All values should remain nil
                                        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBeNull)))))))

;;; ─── --model takes a role name ──────────────────────────────
;;; Roles are how the rest of nyma talks about models (/role, escalate's
;;; fallback chains, bench's --model). One-shot was the only place the word was
;;; taken literally: `--model local` went out as the model id "local" and came
;;; back "Unknown Model, please check the model code" from whichever provider
;;; happened to be the default — a provider error for a user error nyma could
;;; have resolved itself.

(defn- fake-registry [resolved]
  {:get     (fn [_p] {})
   :list    (fn [] {"vllm" {} "anthropic" {}})
   :resolve (fn [p m] (when resolved #js {:provider p :modelId m}))})

(describe "--model accepts a role name"
          (fn []
            (it "expands a role to its provider/model"
                (fn []
                  (let [r (resolve-model-via-registry
                           (fake-registry true)
                           {:model "local"}
                           {:roles {:local {:provider "vllm" :model "unsloth/Qwen3.8-27B"}}})]
                    (-> (expect (:provider r)) (.toBe "vllm"))
                    (-> (expect (:model-id r)) (.toBe "unsloth/Qwen3.8-27B")))))

            (it "leaves an explicit provider/model spec alone, slashes and all"
                (fn []
          ;; HuggingFace ids carry their own slash; only the first one splits.
                  (let [r (resolve-model-via-registry
                           (fake-registry true)
                           {:model "vllm/unsloth/Qwen3.8-27B"}
                           {:roles {:local {:provider "x" :model "y"}}})]
                    (-> (expect (:provider r)) (.toBe "vllm"))
                    (-> (expect (:model-id r)) (.toBe "unsloth/Qwen3.8-27B")))))

            (it "leaves a bare model id that names no role alone"
                (fn []
                  (let [r (resolve-model-via-registry
                           (fake-registry true)
                           {:model "claude-sonnet-4-20250514"}
                           {:provider "anthropic" :roles {:local {:provider "vllm" :model "m"}}})]
                    (-> (expect (:provider r)) (.toBe "anthropic"))
                    (-> (expect (:model-id r)) (.toBe "claude-sonnet-4-20250514")))))

            (it "reports nil rather than throwing when the provider is unknown"
                (fn []
          ;; The caller turns this into "No model configured for '<spec>'" —
          ;; naming what was asked for, instead of sending the user to /login
          ;; for a provider that was never registered.
                  (let [r (resolve-model-via-registry (fake-registry false) {:model "vllm/m"} {})]
                    (-> (expect (:model r)) (.toBeFalsy))
                    (-> (expect (:provider r)) (.toBe "vllm")))))

    ;; This function runs TWICE: once before extensions load, once after. The
    ;; first pass is EXPECTED to miss every extension-registered provider
    ;; (minimax, zai, qwen-cli, every custom_provider_*), so it must report the
    ;; miss rather than announce it — a warning there told users with a working
    ;; config that their provider was unknown, listing the three builtins as
    ;; proof.
            (it "reports whether the provider was known, for the caller to judge"
                (fn []
                  (let [known   (resolve-model-via-registry
                                 {:get (fn [_] {}) :list (fn [] {}) :resolve (fn [_ _] #js {})}
                                 {:model "anthropic/claude"} {})
                        unknown (resolve-model-via-registry
                                 {:get (fn [_] nil) :list (fn [] {}) :resolve (fn [_ _] nil)}
                                 {:model "zai/glm-5.3-flash"} {})]
                    (-> (expect (:provider-known? known)) (.toBe true))
                    (-> (expect (:provider-known? unknown)) (.toBe false))
                    (-> (expect (:provider unknown)) (.toBe "zai")))))))

;; ── --version ──────────────────────────────────────────────

(describe "version"
          (fn []
            (it "matches package.json"
                (fn []
                  ;; agent.version is generated by `bun run build`, so it cannot
                  ;; be stale — but if the generator ever stops running, this is
                  ;; what says so rather than shipping a binary that lies about
                  ;; which nyma it is.
                  (let [pkg (-> (fs/readFileSync
                                 (path/join (js/process.cwd) "package.json") "utf8")
                                (js/JSON.parse)
                                (.-version))]
                    (-> (expect version) (.toBe pkg)))))

            (it "documents every flag nyma actually parses"
                (fn []
                  ;; --help is the contract. A flag that parses but is not
                  ;; listed is a feature nobody can find; a line under the wrong
                  ;; heading is worse — "(Default: a fresh session file per
                  ;; launch.)" sat under --discover, which creates no session.
                  (doseq [flag ["--output-format" "--mode" "--permission-mode"
                                "--provider" "--model" "--thinking" "--continue"
                                "--resume" "--all" "--fork" "--session"
                                "--no-session" "--discover" "--tools" "--debug"
                                "--help" "--version" "--approve" "--no-approve"
                                "pi-rpc"]]
                    (-> (expect (str/includes? help-text flag)) (.toBe true)))))

            (it "names the credential env vars and the debug switches"
                (fn []
                  (doseq [v ["Environment:" "ANTHROPIC_API_KEY" "OPENAI_API_KEY"
                             "GOOGLE_GENERATIVE_AI_API_KEY" "NYMA_DEBUG"
                             "NYMA_NO_MODEL_DISCOVERY"]]
                    (-> (expect (str/includes? help-text v)) (.toBe true)))))

            (it "says --output-format exits 1 on error"
                (fn []
                  ;; Two substrings rather than one that spans the wrap: the
                  ;; claim is what matters, not where the paragraph breaks.
                  (-> (expect (str/includes? help-text "--output-format")) (.toBe true))
                  (-> (expect (str/includes? help-text "1 when the run fails"))
                      (.toBe true))))

            (it "puts the fresh-session default under --session, not --discover"
                (fn []
                  (let [lines   (str/split-lines help-text)
                        idx-of  (fn [needle]
                                  (first (keep-indexed
                                          (fn [i l] (when (str/includes? l needle) i))
                                          lines)))
                        default (idx-of "(Default: a fresh session file")]
                    (-> (expect (some? default)) (.toBe true))
                    (-> (expect (= default (inc (idx-of "--session <path>")))) (.toBe true)))))

            (it "omits --tools from the default claim (omit = all built-ins)"
                (fn []
                  (-> (expect (str/includes? help-text "Omit to enable all built-ins"))
                      (.toBe true))))

            (it "is a plain version, not a banner"
                (fn []
                  ;; `nyma --version` is meant to be usable in a shell
                  ;; substitution, so nothing but the number goes to stdout.
                  (-> (expect version) (.toMatch #"^\d+\.\d+\.\d+"))))))

;;; ─── the positional prompt reaches interactive mode ─────────
;;; `Usage: nyma [options] [prompt]` is what --help has always said, and
;;; interactive mode dropped the positional: `nyma "fix the build"` opened an
;;; empty session with the prompt gone.

(describe "nyma \"<prompt>\" with no -p"
          (fn []
            (it "hands the prompt to the TUI as seed-prompt"
                (fn []
                  (let [r (interactive-resources {:settings :s} ["fix the build"])]
                    (-> (expect (:seed-prompt r)) (.toBe "fix the build"))
            ;; The TUI reads it with aget, so it has to be a plain string key.
                    (-> (expect (aget r "seed-prompt")) (.toBe "fix the build"))
            ;; And nothing else in the map is disturbed.
                    (-> (expect (:settings r)) (.toBe :s)))))

            (it "joins multiple positionals the way the shell split them"
                (fn []
                  (-> (expect (:seed-prompt (interactive-resources {} ["fix" "the" "build"])))
                      (.toBe "fix the build"))))

            (it "is an empty string with no positionals, which the TUI treats as absent"
                (fn []
                  (-> (expect (:seed-prompt (interactive-resources {} []))) (.toBe ""))
                  (-> (expect (:seed-prompt (interactive-resources {} nil))) (.toBe ""))))))

;;; ─── a mistyped flag ────────────────────────────────────────
;;; An unrecognised flag escaped as an unhandled Error: node printed its class,
;;; its `code` and a stack through squint's compiled output, and the process
;;; died on the default handler's exit code rather than the 2 a shell expects
;;; for a usage error.

(defn- parse-throw
  "Run the REAL parseArgs against argv and hand back what it threw."
  [argv]
  (try
    (parseArgs #js {:args (clj->js argv) :options cli-options :allowPositionals true})
    nil
    (catch :default e e)))

(describe "an unknown flag"
          (fn []
            (it "prints one line naming the flag and pointing at --help"
                (fn []
                  (let [e (parse-throw ["--x"])]
                    (-> (expect (some? e)) (.toBe true))
                    (-> (expect (parse-args-error e))
                        (.toBe "nyma: unknown option '--x' (see --help)")))))

            (it "names the long form of an unknown short flag too"
                (fn []
                  (let [msg (parse-args-error (parse-throw ["-z"]))]
                    (-> (expect msg) (.toContain "see --help"))
                    (-> (expect msg) (.toContain "nyma: ")))))

            (it "says nothing about arguments that parse"
                (fn []
                  (-> (expect (parse-throw ["-p" "hello" "--model" "x"])) (.toBeNull))))

            (it "leaves a non-parseArgs error alone, so a real bug is not swallowed"
                (fn []
                  (-> (expect (parse-args-error (js/Error. "boom"))) (.toBeNull))))))

;;; ─── a thrown error is one line, not a stack ────────────────

(describe "a failure from mode dispatch"
          (fn []
            (it "reads `nyma: <message>`"
                (fn []
                  (-> (expect (error-line (js/Error. "ENOENT: no such file or directory")))
                      (.toBe "nyma: ENOENT: no such file or directory"))))

            (it "still says something for a thrown non-Error"
                (fn []
                  (-> (expect (error-line "plain string")) (.toBe "nyma: plain string"))))))

;;; ─── -c / -r with nothing to resume ─────────────────────────
;;; Both printed NOTHING and silently started fresh, which reads as "my session
;;; is gone". And -p -c / -p -r / -p --fork parsed, did nothing, and said
;;; nothing: `nyma -p -c "and then?"` looked like it was continuing a
;;; conversation and was starting a new one.

(defn- capture-stderr [f]
  (let [lines (atom [])
        orig  (.-write (.-stderr js/process))]
    (set! (.-write (.-stderr js/process)) (fn [d] (swap! lines conj (str d)) true))
    (-> (js/Promise.resolve (f))
        (.finally (fn [] (set! (.-write (.-stderr js/process)) orig)))
        (.then (fn [v] {:value v :stderr (str/join "" @lines)})))))

(defn- empty-sessions-dir []
  (path/join (fs/mkdtempSync (path/join (os/tmpdir) "nyma-nosess-")) "sessions"))

(defn ^:async test-continue-with-no-sessions []
  (let [dir (empty-sessions-dir)
        r   (js-await (capture-stderr
                       (fn [] (resolve-session {:continue true} "interactive" dir))))]
    (-> (expect (:stderr r)) (.toContain "no sessions for this project — starting fresh"))
    ;; And it really does start one, rather than dying.
    (-> (expect (some? ((:get-file-path (:value r))))) (.toBe true))))

(defn ^:async test-resume-with-no-sessions []
  (let [dir (empty-sessions-dir)
        r   (js-await (capture-stderr
                       (fn [] (resolve-session {:resume true} "interactive" dir))))]
    (-> (expect (:stderr r)) (.toContain "no sessions for this project — starting fresh"))))

(defn ^:async test-print-mode-ignores-continue []
  (let [dir (empty-sessions-dir)
        r   (js-await (capture-stderr
                       (fn [] (resolve-session {:continue true} "print" dir))))]
    (-> (expect (:stderr r))
        (.toContain "-c/-r/--fork are ignored in print mode; use --session"))
    ;; One-shot runs stay ephemeral — the warning does not change that.
    (-> (expect ((:get-file-path (:value r)))) (.toBeNull))))

(defn ^:async test-print-mode-with-session-is-quiet []
  (let [dir  (empty-sessions-dir)
        file (path/join dir "explicit.jsonl")
        r    (js-await (capture-stderr
                        (fn [] (resolve-session {:continue true :session file} "print" dir))))]
    ;; --session is the documented way to do this, so it is not a mistake.
    (-> (expect (.includes (:stderr r) "ignored in print mode")) (.toBe false))
    (fs/rmSync dir #js {:recursive true :force true})))

(defn ^:async test-interactive-is-quiet-with-sessions []
  (let [dir (empty-sessions-dir)]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "1.jsonl")
                      (str (js/JSON.stringify
                            #js {:id "a" :role "user" :content "hi"}) "\n"))
    (let [r (js-await (capture-stderr
                       (fn [] (resolve-session {:continue true} "interactive" dir))))]
      (-> (expect (.includes (:stderr r) "no sessions")) (.toBe false))
      (fs/rmSync dir #js {:recursive true :force true}))))

(describe "-c / -r / --fork feedback"
          (fn []
            (it "says so when there is nothing to continue" test-continue-with-no-sessions)
            (it "says so when there is nothing to resume" test-resume-with-no-sessions)
            (it "warns that -c is ignored in print mode" test-print-mode-ignores-continue)
            (it "stays quiet when --session says what to use" test-print-mode-with-session-is-quiet)
            (it "stays quiet when there IS a session to continue"
                test-interactive-is-quiet-with-sessions)))
