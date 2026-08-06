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
