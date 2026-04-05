(ns context-hooks.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.events :refer [create-event-bus]]))

(defn- make-test-agent []
  (create-agent {:model "mock-model" :system-prompt "You are a test agent."}))

;; ── before_provider_request mutable config tests ─────────────

(defn ^:async test-config-has-expected-properties []
  (let [agent (make-test-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen {:has-model    (some? (.-model config))
                      :has-system   (some? (.-system config))
                      :has-messages (some? (.-messages config))
                      :has-tools    (some? (.-tools config))
                      :has-provider-opts (some? (.-providerOptions config))})
        #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect (:has-model @seen)) (.toBe true))
    (-> (expect (:has-system @seen)) (.toBe true))
    (-> (expect (:has-messages @seen)) (.toBe true))
    (-> (expect (:has-tools @seen)) (.toBe true))
    (-> (expect (:has-provider-opts @seen)) (.toBe true))))

(defn ^:async test-mutate-system-to-string []
  (let [agent   (make-test-agent)
        final-sys (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config] (set! (.-system config) "MUTATED SYSTEM PROMPT") nil)
      10)
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! final-sys (.-system config))
        #js {:block true :reason "ok"})
      5)
    (js-await (run agent "test"))
    (-> (expect @final-sys) (.toBe "MUTATED SYSTEM PROMPT"))))

(defn ^:async test-set-provider-options []
  (let [agent (make-test-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (set! (.-providerOptions config)
          #js {:anthropic #js {:cacheControl #js {:enabled true}}})
        nil)
      10)
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen (.-providerOptions config))
        #js {:block true :reason "ok"})
      5)
    (js-await (run agent "test"))
    (-> (expect (.. @seen -anthropic -cacheControl -enabled)) (.toBe true))))

(defn ^:async test-mutate-system-to-array []
  (let [agent   (make-test-agent)
        final-sys (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (set! (.-system config)
          #js [#js {:type "text" :text "Section 1"}
               #js {:type "text" :text "Section 2"}])
        nil)
      10)
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! final-sys (.-system config))
        #js {:block true :reason "ok"})
      5)
    (js-await (run agent "test"))
    (-> (expect (array? @final-sys)) (.toBe true))
    (-> (expect (.-length @final-sys)) (.toBe 2))))

(defn ^:async test-priority-order []
  (let [agent (make-test-agent)
        order (atom [])]
    ((:on (:events agent)) "before_provider_request"
      (fn [_] (swap! order conj "low") nil) 1)
    ((:on (:events agent)) "before_provider_request"
      (fn [_] (swap! order conj "high") nil) 10)
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "ok"}) 0)
    (js-await (run agent "test"))
    (-> (expect (first @order)) (.toBe "high"))
    (-> (expect (second @order)) (.toBe "low"))))

(defn ^:async test-block-backward-compat []
  (let [agent (make-test-agent)]
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "Blocked!"}))
    (js-await (run agent "test"))
    (let [last-msg (last (:messages @(:state agent)))]
      (-> (expect (:content last-msg)) (.toBe "Blocked!")))))

;; ── context_assembly event tests ─────────────────────────────

(defn ^:async test-assembly-fires-with-shape []
  (let [agent (make-test-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "context_assembly"
      (fn [data]
        (reset! seen {:has-messages     (some? (.-messages data))
                      :has-systemPrompt (some? (.-systemPrompt data))
                      :has-tokenBudget  (some? (.-tokenBudget data))
                      :has-providers    (some? (.-providers data))})
        nil))
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect (:has-messages @seen)) (.toBe true))
    (-> (expect (:has-systemPrompt @seen)) (.toBe true))
    (-> (expect (:has-tokenBudget @seen)) (.toBe true))
    (-> (expect (:has-providers @seen)) (.toBe true))))

(defn ^:async test-assembly-token-budget-fields []
  (let [agent  (make-test-agent)
        budget (atom nil)]
    ((:on (:events agent)) "context_assembly"
      (fn [data] (reset! budget (.-tokenBudget data)) nil))
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect (.-contextWindow @budget)) (.toBeGreaterThan 0))
    (-> (expect (.-inputBudget @budget)) (.toBeGreaterThan 0))
    (-> (expect (some? (.-tokensUsed @budget))) (.toBe true))
    (-> (expect (some? (.-model @budget))) (.toBe true))))

(defn ^:async test-assembly-replaces-messages []
  (let [agent     (make-test-agent)
        seen-msgs (atom nil)]
    ((:on (:events agent)) "context_assembly"
      (fn [_]
        #js {:messages #js [#js {:role "user" :content "replaced message"}]}))
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen-msgs (.-messages config))
        #js {:block true :reason "ok"}))
    (js-await (run agent "original message"))
    (let [first-msg (aget @seen-msgs 0)]
      (-> (expect (.-content first-msg)) (.toBe "replaced message")))))

(defn ^:async test-assembly-no-handler-unchanged []
  (let [agent     (make-test-agent)
        seen-msgs (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen-msgs (.-messages config))
        #js {:block true :reason "ok"}))
    (js-await (run agent "original message"))
    (let [first-msg (aget @seen-msgs 0)]
      (-> (expect (.-content first-msg)) (.toBe "original message")))))

;; ── prompt-sections tests ────────────────────────────────────

(defn ^:async test-sections-sorted-by-priority []
  (let [agent      (make-test-agent)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_agent_start"
      (fn [_]
        #js {:prompt-sections
             #js [#js {:content "LOW PRIORITY" :priority 1}
                  #js {:content "HIGH PRIORITY" :priority 100}]}))
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen-system (.-system config))
        #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (let [hi-idx (.indexOf @seen-system "HIGH PRIORITY")
          lo-idx (.indexOf @seen-system "LOW PRIORITY")]
      (-> (expect hi-idx) (.toBeLessThan lo-idx)))))

(defn ^:async test-sections-default-priority []
  (let [agent      (make-test-agent)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_agent_start"
      (fn [_]
        #js {:prompt-sections
             #js [#js {:content "NO PRIORITY SECTION"}
                  #js {:content "HAS PRIORITY" :priority 10}]}))
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen-system (.-system config))
        #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (let [has-idx (.indexOf @seen-system "HAS PRIORITY")
          no-idx  (.indexOf @seen-system "NO PRIORITY SECTION")]
      (-> (expect has-idx) (.toBeLessThan no-idx)))))

;; ── after_provider_request tests ─────────────────────────────

(defn ^:async test-after-provider-not-fired-when-blocked []
  (let [agent (make-test-agent)
        fired (atom false)]
    ((:on (:events agent)) "after_provider_request"
      (fn [_] (reset! fired true)))
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "blocked"}))
    (js-await (run agent "test"))
    (-> (expect @fired) (.toBe false))))

;; ── describe blocks ──────────────────────────────────────────

(describe "before_provider_request - mutable config" (fn []
  (it "handler receives JS object with expected properties" test-config-has-expected-properties)
  (it "handler can mutate config.system to a string" test-mutate-system-to-string)
  (it "handler can set config.providerOptions" test-set-provider-options)
  (it "handler can mutate config.system to array of sections" test-mutate-system-to-array)
  (it "multiple handlers run in priority order" test-priority-order)
  (it "block:true return still prevents LLM call" test-block-backward-compat)))

(describe "context_assembly event" (fn []
  (it "fires with expected shape" test-assembly-fires-with-shape)
  (it "tokenBudget has expected fields" test-assembly-token-budget-fields)
  (it "extension returning messages replaces message list" test-assembly-replaces-messages)
  (it "no handler returns leaves messages unchanged" test-assembly-no-handler-unchanged)))

(describe "prompt-sections via before_agent_start" (fn []
  (it "sections sorted by priority and appended" test-sections-sorted-by-priority)
  (it "section with no priority defaults to 0" test-sections-default-priority)))

(describe "after_provider_request event" (fn []
  (it "does not fire when request is blocked" test-after-provider-not-fired-when-blocked)))
