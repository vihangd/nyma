(ns agent.extensions.small-model.thinking-budget
  "Thinking budget — cap extended thinking tokens per turn; retry without
   thinking on overflow or provider error.

   Hooks used:
     before_provider_request — inject thinkingBudget into providerOptions
     provider_error          — retry once with thinking disabled on budget overflow
     turn_finalize           — a turn cut off at the output cap that called no
                               tool is deliberation that never committed: the
                               loop's continue-nudge runs with thinking off
     agent_end               — restore the thinking level the retry replaced

   The finish-reason path is borrowed from apprentice's little-coder loop,
   which answers an overflowed deliberation by retrying once with thinking
   disabled and the partial trace kept in context. Before it, the nudge
   re-ran with thinking still on and a small model could overflow again."
  (:require [clojure.string :as str]))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire thinking-budget hooks. Returns a cleanup fn."
  [api config]
  (let [tb-cfg    (:thinking-budget config)
        max-tok   (or (:max-tokens tb-cfg) 8000)
        retry?    (not= false (:retry-without-thinking tb-cfg))
        ;; The level a retry replaced, or nil when no retry is in flight.
        ;; Doubles as the "in retry" flag so the disable fires once per turn.
        saved-level (atom nil)

        disable-thinking!
        (fn []
          (let [cur (str (or (.getThinkingLevel api) "off"))]
            (when-not (= cur "off")
              (reset! saved-level cur)
              (.setThinkingLevel api "off")
              true)))

        on-before-request
        (fn [data _ctx]
          (when-not @saved-level
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
                       (not @saved-level)
                       (or (str/includes? msg "thinking budget")
                           (str/includes? msg "budget_exceeded")
                           (str/includes? msg "extended_thinking"))
                       (disable-thinking!))
              ;; Signal the loop to retry
              #js {:retry true})))

        ;; Cut off at the output cap with no tool call: the model spent the
        ;; whole turn deliberating. The loop already queues a continue-nudge;
        ;; this makes that nudge run without thinking so it commits.
        restore-thinking!
        (fn []
          (when-let [lvl @saved-level]
            (reset! saved-level nil)
            (.setThinkingLevel api lvl)))

        on-turn-finalize
        (fn [data _ctx]
          (cond
            ;; A retry turn that threw or was aborted never reached agent_end,
            ;; so this is the only place the level gets put back before the
            ;; user's next turn would otherwise run without thinking.
            (.-error data) (restore-thinking!)

            ;; Only when the loop queued its continue-nudge: that turn is the
            ;; one this disables thinking for, and its agent_end restores it.
            ;; Without a nudge (a step-cap report turn that ran long) nothing
            ;; would follow to restore the level before the user's next prompt.
            (and retry?
                 (not @saved-level)
                 (.-nudged data)
                 (= (str (.-finishReason data)) "length")
                 (zero? (or (.-toolCalls data) 0)))
            (disable-thinking!))
          nil)

        ;; agent_end fires per turn, BEFORE turn_finalize and the follow-up
        ;; drain — so the level saved at turn_finalize is restored at the end
        ;; of the retry turn, not the turn that overflowed.
        on-agent-end
        (fn [_data _ctx] (restore-thinking!))]

    (.on api "before_provider_request" on-before-request)
    (.on api "provider_error" on-provider-error)
    (.on api "turn_finalize" on-turn-finalize)
    (.on api "agent_end" on-agent-end)

    ;; Cleanup
    (fn [] nil)))
