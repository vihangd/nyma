(ns cli.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.cli :refer [resolve-ext-flags resolve-model-via-registry]]))

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
            (-> (expect (:provider r)) (.toBe "vllm")))))))
