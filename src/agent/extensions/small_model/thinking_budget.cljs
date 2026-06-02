(ns agent.extensions.small-model.thinking-budget
  "Thinking budget — cap extended thinking tokens per turn; retry without
   thinking on overflow or provider error.

   Hooks used:
     before_provider_request — inject thinkingBudget into providerOptions
     provider_error          — retry once with thinking disabled on budget overflow
  "
  (:require [clojure.string :as str]))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire thinking-budget hooks. Returns a cleanup fn."
  [api config]
  (let [tb-cfg    (:thinking-budget config)
        max-tok   (or (:max-tokens tb-cfg) 8000)
        retry?    (not= false (:retry-without-thinking tb-cfg))
        handlers  (atom [])
        ;; Track whether we're in a no-thinking retry to avoid infinite loop
        in-retry  (atom false)

        on-before-request
        (fn [data _ctx]
          (when-not @in-retry
            ;; Inject thinkingBudget via providerOptions (Anthropic extended thinking)
            (let [po (or (.-providerOptions data) #js {})]
              (aset po "thinkingBudget" max-tok)
              (aset data "providerOptions" po)))
          nil)

        on-provider-error
        (fn [data _ctx]
          (let [msg (str (or (.-message data) ""))]
            ;; Detect thinking budget overflow errors
            (when (and retry?
                       (not @in-retry)
                       (or (str/includes? msg "thinking budget")
                           (str/includes? msg "budget_exceeded")
                           (str/includes? msg "extended_thinking")))
              (reset! in-retry true)
              ;; Disable thinking for the retry
              (.setThinkingLevel api "off")
              ;; Signal the loop to retry
              #js {:retry true})))]

    (.on api "before_provider_request" on-before-request)
    (swap! handlers conj ["before_provider_request" on-before-request])

    (.on api "provider_error" on-provider-error)
    (swap! handlers conj ["provider_error" on-provider-error])

    ;; Reset in-retry flag after each completed turn
    (let [on-agent-end
          (fn [_data _ctx]
            (reset! in-retry false))]
      (.on api "agent_end" on-agent-end)
      (swap! handlers conj ["agent_end" on-agent-end]))

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler)))))
