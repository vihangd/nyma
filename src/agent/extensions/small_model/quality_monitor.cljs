(ns agent.extensions.small-model.quality-monitor
  "Quality monitor — detect empty turns, hallucinated tool names, repeat
   tool-call loops, and enforce a turn budget.

   Two-tier design:
     Tier 1 (this file): cheap rule-based checks per turn.
     Tier 2: supervisor.cljs escalates to an LLM critic when rules fire.

   Nudge escalation (from Forge — antoinezambelli/forge):
     Tier 1 (first offence):   polite correction
     Tier 2 (second offence):  direct instruction
     Tier 3 (third+ offence):  aggressive — ALL CAPS, hard constraint

   Hooks used:
     stream_filter        — abort mid-stream on empty/blank turns
     addMiddleware :leave — track tool-call signatures for repeat detection
                           and override hallucinated/repeat results with nudges
     after_provider_request — advance turn counter, emit quality-signal
  "
  (:require [agent.extensions.small-model.shared :as shared]
            [clojure.string :as str]))

;; ── Escalating nudge helpers ─────────────────────────────────────

(defn- tier [n] (max 1 (min 3 n)))

(defn- hallucinated-tool-msg [tool-name active-tools n]
  (let [tools-list (str/join ", " (sort active-tools))]
    (case (tier n)
      1 (str "Tool \"" tool-name "\" does not exist. "
             "Available tools: " tools-list ". "
             "Pick one of those or call respond() to reply directly.")
      2 (str "\"" tool-name "\" is not a valid tool. "
             "You must call one of: " tools-list ". Pick one.")
      (str "STOP. \"" tool-name "\" does not exist. "
           "You MUST call one of: " tools-list ". "
           "Your next response MUST be a valid tool call."))))

(defn- repeat-tool-msg [tool-name n]
  (case (tier n)
    1 (str "You already ran \"" tool-name "\" with these exact arguments. "
           "The result hasn't changed. Use a different tool, "
           "change your approach, or finish and report your findings.")
    2 (str "You ran \"" tool-name "\" with the same args again. "
           "Do not repeat it. Choose a different action or finish.")
    (str "STOP repeating \"" tool-name "\". "
         "You MUST do something different. "
         "Change the tool, change the arguments, or call respond() to finish.")))

(defn- empty-turn-msg [n]
  (case (tier n)
    1 (str "Your response was empty or only whitespace. "
           "Please continue with the task or call respond() to report your findings.")
    2 (str "Empty response again. You must either call a tool or call respond() "
           "to reply to the user. Do not produce blank output.")
    (str "STOP producing empty responses. "
         "You MUST call a tool or call respond() now. "
         "An empty response is not acceptable.")))

(defn- turn-budget-msg [max-turns]
  (str "You've reached the turn limit (" max-turns " turns). "
       "Summarise what you've found so far and stop — do not continue executing."))

;; ── Helpers ──────────────────────────────────────────────────────

(defn- blank? [s]
  (or (nil? s) (str/blank? (str s))))

(defn- get-active-tool-names [api]
  (try
    (let [tools (.getAllTools api)]
      (when tools (set (js/Object.keys tools))))
    (catch :default _ nil)))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire quality-monitor hooks. Returns a cleanup fn."
  [api config state]
  (let [qm-cfg    (:quality-monitor config)
        max-turns (or (:max-turns qm-cfg) 40)
        handlers  (atom [])
        ;; Escalation counters (keyed by violation type)
        counters  (atom {:empty 0 :hallucinated 0 :repeat 0})

        ;; ── stream_filter: empty turns ───────────────────────────
        on-stream-filter
        (fn [data _ctx]
          (let [delta (str (.-delta data))
                chunk (str (.-chunk data))]
            ;; Abort when stream ends with nothing (chunk="" and accumulated blank)
            (when (and (= chunk "") (blank? delta))
              (let [n (swap! counters update :empty inc)]
                #js {:abort  true
                     :reason "empty-turn"
                     :inject [#js {:role    "user"
                                   :content (empty-turn-msg (:empty n))}]}))))

        ;; ── middleware :leave: hallucination + repeat checks ──────
        tool-checker
        #js {:name  "small-model/quality-monitor"
             :leave (fn [ctx]
                      (let [tool-name (str (.-tool-name ctx))
                            args      (.-args ctx)
                            sig       (shared/tool-call-sig tool-name args)
                            prev-sigs (:all-tool-sigs @state)
                            active    (get-active-tool-names api)]
                        (cond
                          ;; Hallucinated tool name
                          (and active (not (contains? active tool-name)))
                          (let [n (swap! counters update :hallucinated inc)]
                            (aset ctx "result"
                                  (hallucinated-tool-msg tool-name active (:hallucinated n)))
                            ;; Emit quality-signal for supervisor escalation
                            (.emitGlobal api "small-model/quality-signal"
                                         #js {:reason (str "hallucinated tool: " tool-name)
                                              :tier   (:hallucinated n)}))

                          ;; Exact repeat call
                          (contains? prev-sigs sig)
                          (let [n (swap! counters update :repeat inc)]
                            (aset ctx "result"
                                  (repeat-tool-msg tool-name (:repeat n)))
                            (.emitGlobal api "small-model/quality-signal"
                                         #js {:reason (str "repeat tool call: " tool-name)
                                              :tier   (:repeat n)}))

                          ;; Clean call — reset relevant counters
                          :else
                          (do (swap! counters assoc :hallucinated 0 :repeat 0)
                              (swap! state update :all-tool-sigs conj sig)))
                        ctx))}

        ;; ── after_provider_request: advance turn counter ──────────
        on-turn
        (fn [data _ctx]
          ;; Reset empty-turn counter on any successful turn
          (swap! counters assoc :empty 0)
          (let [tc (swap! state update :turn-count inc)]
            (when (>= (:turn-count tc) max-turns)
              (.sendUserMessage api
                                (turn-budget-msg max-turns)
                                #js {:deliverAs "followUp"}))))]

    (.on api "stream_filter" on-stream-filter)
    (swap! handlers conj ["stream_filter" on-stream-filter])

    (.addMiddleware api tool-checker)

    (.on api "after_provider_request" on-turn)
    (swap! handlers conj ["after_provider_request" on-turn])

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.removeMiddleware api "small-model/quality-monitor"))))
