(ns cli.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.version :refer [version]]
            [clojure.string :as str]
            [agent.cli :refer [resolve-ext-flags resolve-model-via-registry help-text]]))

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
                  (-> (expect (str/includes? help-text "exits\n                         1"))
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
