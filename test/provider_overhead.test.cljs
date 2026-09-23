(ns provider-overhead.test
  "A gateway can prepend tokens nyma never sees — one relay was measured
   injecting ~6.8k of its own system prompt into every request. Left
   unaccounted, nyma's context estimate is silently low and compaction plans
   against a window it does not have.

   The provider declares the figure, because only the operator can know it:
   Anthropic's count_tokens endpoint is frequently not proxied, so there is
   nothing to discover it from."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.extensions :refer [create-extension-api]]
            [agent.token-estimation :as te]
            [agent.extensions.custom-provider-relay.index :as relay]))

(defn- register! [agent opts]
  (let [api (create-extension-api agent "test-gw")]
    (.registerProvider api "gw"
                       (clj->js (merge {:createModel (fn [id] id)
                                        :models [{:id "m1" :contextWindow 100000}]}
                                       opts)))
    ;; The loop reads the provider name off the config, the way setModel and the
    ;; CLI both set it.
    (aset (:config agent) "active-provider-name" "gw")
    api))

(defn ^:async budget-for
  "Run one turn and return the tokenBudget seen by context_assembly."
  [opts]
  (let [agent  (create-agent {:model "m1" :system-prompt "You are a test agent."})
        budget (atom nil)]
    (register! agent opts)
    ((:on (:events agent)) "context_assembly"
                           (fn [data] (reset! budget (.-tokenBudget data)) nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_config] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    @budget))

(defn ^:async test-overhead-counted-as-used []
  (let [with-oh    (js-await (budget-for {:overheadTokens 6800}))
        without-oh (js-await (budget-for {}))]
    (-> (expect (.-tokensUsed with-oh))
        (.toBe (+ (.-tokensUsed without-oh) 6800)))))

(defn ^:async test-overhead-reported-separately []
  (let [b (js-await (budget-for {:overheadTokens 6800}))]
    ;; Broken out so a consumer can tell how much of tokensUsed is not its own.
    (-> (expect (.-overheadTokens b)) (.toBe 6800))))

(defn ^:async test-no-overhead-by-default []
  (let [b (js-await (budget-for {}))]
    (-> (expect (.-overheadTokens b)) (.toBe 0))))

(defn ^:async test-window-not-reduced []
  ;; Counted as used rather than deducted from the window: every consumer of
  ;; this budget already reasons about used-vs-window, and shrinking the window
  ;; would misreport the model's actual capacity.
  (let [b (js-await (budget-for {:overheadTokens 6800}))]
    (-> (expect (.-contextWindow b)) (.toBe 100000))))

(defn ^:async test-bogus-overhead-ignored []
  (let [zero (js-await (budget-for {:overheadTokens 0}))
        neg  (js-await (budget-for {:overheadTokens -5}))]
    (-> (expect (.-overheadTokens zero)) (.toBe 0))
    (-> (expect (.-overheadTokens neg)) (.toBe 0))))

(describe "provider overhead tokens" (fn []
                                       (it "is added to tokensUsed" test-overhead-counted-as-used)
                                       (it "is reported separately from the caller's own content"
                                           test-overhead-reported-separately)
                                       (it "is zero for a provider that declares none" test-no-overhead-by-default)
                                       (it "does not shrink the reported context window" test-window-not-reduced)
                                       (it "ignores a zero or negative declaration" test-bogus-overhead-ignored)))

;; ── Settings surface ─────────────────────────────────────────

(describe "relay overheadTokens setting" (fn []
                                           (it "reads camelCase from settings"
                                               (fn []
                                                 (-> (expect (:overhead-tokens
                                                              (relay/normalize-entry #js {:name "g" :overheadTokens 6800})))
                                                     (.toBe 6800))))

                                           (it "reads kebab-case from settings"
                                               (fn []
                                                 (-> (expect (:overhead-tokens
                                                              (relay/normalize-entry #js {"name" "g" "overhead-tokens" 6800})))
                                                     (.toBe 6800))))

                                           (it "is nil when unset, so nothing is assumed about a gateway"
                                               (fn []
                                                 (-> (expect (:overhead-tokens (relay/normalize-entry #js {:name "g"})))
                                                     (.toBeNil))))

                                           (it "rejects a non-positive value"
                                               (fn []
                                                 (-> (expect (:overhead-tokens
                                                              (relay/normalize-entry #js {:name "g" :overheadTokens 0})))
                                                     (.toBeNil))))))

;;; ─── The loop resolves the window on the qualified key ──────────
;;; Model ids are not unique across providers. The loop used to look the window
;;; up by bare id, so it read whichever provider registered last — while the
;;; /model picker read the qualified key and showed a different number.

(defn ^:async window-for-two-providers []
  (let [agent  (create-agent {:model "shared-id" :system-prompt "You are a test agent."})
        api    (create-extension-api agent "test-two")
        budget (atom nil)]
    ;; First provider declares a real window for the shared id.
    (.registerProvider api "vendor"
                       #js {:createModel (fn [id] id)
                            :models #js [#js {:id "shared-id" :contextWindow 262144}]})
    ;; Second carries the SAME id with a DIFFERENT window. Two real values, so
    ;; the clobber guard can't mask this: the bare key resolves to whichever
    ;; provider registered last, and only the qualified key is unambiguous.
    (.registerProvider api "gateway"
                       #js {:createModel (fn [id] id)
                            :models #js [#js {:id "shared-id" :contextWindow 40000}]})
    ;; Active model is the FIRST provider's.
    (aset (:config agent) "model" #js {:modelId "shared-id"})
    (aset (:config agent) "active-provider-name" "vendor")
    ((:on (:events agent)) "context_assembly"
     (fn [data] (reset! budget (.-tokenBudget data)) nil))
    ((:on (:events agent)) "before_provider_request"
     (fn [_config] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (.-contextWindow @budget)))

(defn ^:async test-loop-uses-qualified-key []
  ;; 40000 here means the bare id won and the loop read the OTHER provider's
  ;; window for a model it isn't running.
  (-> (expect (js-await (window-for-two-providers))) (.toBe 262144)))

(describe "loop context window" (fn []
  (it "resolves on the provider-qualified key, not the shared bare id"
      test-loop-uses-qualified-key)))

;;; ─── the system prompt is part of the budget ──────────────────
;;;
;;; The estimator was only ever applied to `messages`. The system prompt — in
;;; this repo AGENTS.md alone is ~10k tokens, before the skills listing and the
;;; section injectors — was counted nowhere, so priority_assembly sized its
;;; working set against a budget with that much phantom room and under-pruned,
;;; and headroom compressed late. Compaction was never affected: it prefers the
;;; provider's own count of the last request.

(defn ^:async budget-for-prompt [prompt]
  (let [agent  (create-agent {:model "m1" :system-prompt prompt})
        budget (atom nil)]
    (register! agent {})
    ((:on (:events agent)) "context_assembly"
                           (fn [data] (reset! budget (.-tokenBudget data)) nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_config] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    @budget))

(defn ^:async test-system-prompt-counted []
  (let [short-b (js-await (budget-for-prompt "You are a test agent."))
        long-b  (js-await (budget-for-prompt (str "You are a test agent. "
                                                  (.repeat "Extra guidance. " 500))))]
    (-> (expect (.-tokensUsed long-b)) (.toBeGreaterThan (+ 1000 (.-tokensUsed short-b))))
    ;; and it is broken out, so a consumer can see what the prompt costs
    (-> (expect (.-systemTokens long-b)) (.toBeGreaterThan 1000))
    (-> (expect (.-systemTokens short-b)) (.toBeLessThan 100))))

;;; ─── observed vs declared ─────────────────────────────────────

(defn ^:async budget-with-observed
  "Seed an observed figure for this provider+model, then read the budget."
  [opts observed]
  (te/reset-overhead!)
  (dotimes [_ te/overhead-min-samples]
    (te/record-overhead! "gw/m1" (+ 1000 observed) 1000))
  (let [b (js-await (budget-for opts))]
    (te/reset-overhead!)
    b))

(defn ^:async test-declared-beats-observed []
  ;; An operator who measured properly is not second-guessed.
  (let [b (js-await (budget-with-observed {:unpriced true :overheadTokens 500} 9999))]
    (-> (expect (.-overheadTokens b)) (.toBe 500))))

(defn ^:async test-observed-used-for-a-gateway []
  (let [b (js-await (budget-with-observed {:unpriced true} 4200))]
    (-> (expect (.-overheadTokens b)) (.toBe 4200))))

(defn ^:async test-observed-ignored-for-first-party []
  ;; No `unpriced` marker means this is not a gateway, and nothing it reports
  ;; may change how we budget for it.
  (let [b (js-await (budget-with-observed {} 4200))]
    (-> (expect (.-overheadTokens b)) (.toBe 0))))

(describe "token budget: the system prompt" (fn []
  (it "counts the system prompt, broken out as systemTokens" test-system-prompt-counted)))

(describe "token budget: observed gateway overhead" (fn []
  (it "a declared overheadTokens still wins" test-declared-beats-observed)
  (it "uses the observed figure for a gateway" test-observed-used-for-a-gateway)
  (it "never uses it for a first-party provider" test-observed-ignored-for-first-party)))
