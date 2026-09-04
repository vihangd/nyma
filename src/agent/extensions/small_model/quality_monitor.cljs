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
     stream_filter        — observe whether any text arrived this turn
     turn_finalize        — nudge when a turn produced neither text nor a tool call
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

(defn tool-names
  "The active tool NAMES as a set, from whatever shape the host hands over.

   `api.getAllTools` returns `(clj->js (keys …))` — a JS **array of names**
   (extensions.cljs:45). Calling `Object.keys` on that yields \"0\", \"1\", \"2\" …,
   so every real tool name failed the membership check below and every tool
   result was replaced with \"that tool doesn't exist\". The extension scored
   10% against an 80% baseline on the benchmark until this was found.

   Accepts an array or an object, because getActiveTools/getAllTools have
   differed before and a silent mismatch here is catastrophic. Exposed for tests."
  [tools]
  (cond
    (nil? tools)   nil
    (array? tools) (set (map str (vec tools)))
    (object? tools) (set (js/Object.keys tools))
    :else          nil))

(defn- get-active-tool-names [api]
  (try
    (tool-names (.getAllTools api))
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

        ;; ── empty turns: observed on the stream, judged at turn_finalize ──
        ;; This check used to ABORT mid-stream whenever a text delta arrived
        ;; empty with nothing accumulated. `stream_filter` fires only on text
        ;; deltas, so it cannot see tool calls — and a turn that goes straight
        ;; to a tool call with little or no prose is indistinguishable, at that
        ;; point, from a dead turn. Two aborts exhausted the loop's retry budget
        ;; and ended the run with finishReason "stream-filter-aborted": measured
        ;; on python/bottle-song as 4 turns and 66k tokens with the stub never
        ;; touched, a task that passes with this module off. It was the second
        ;; false positive from the same heuristic; tightening the threshold
        ;; again would only move the line.
        ;;
        ;; So: observe here, never abort. `turn_finalize` carries toolCalls, so
        ;; a turn is only empty if it produced neither text nor a tool call.
        text-seen? (atom false)

        on-turn-start
        (fn [_data _ctx] (reset! text-seen? false) nil)

        on-stream-filter
        (fn [data _ctx]
          (when-not (blank? (.-chunk data)) (reset! text-seen? true))
          nil)

        on-empty-turn-finalize
        (fn [data _ctx]
          (let [tool-calls (or (.-toolCalls data) 0)
                acted?     (or (pos? tool-calls) @text-seen?)]
            (if acted?
              (swap! counters assoc :empty 0)
              (let [n (:empty (swap! counters update :empty inc))]
                (.sendUserMessage api (empty-turn-msg n)
                                  #js {:deliverAs "followUp"})))
            nil))

        ;; ── middleware :leave: hallucination + repeat checks ──────
        tool-checker
        #js {:name  "small-model/quality-monitor"
             :leave (fn [ctx]
                      (let [tool-name (str (aget ctx "tool-name"))
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

                          ;; Exact repeat call whose result ALSO came back
                          ;; unchanged. Both halves matter:
                          ;;
                          ;; A coding agent's fix→verify cycle re-runs the same
                          ;; test command byte-for-byte every time; that is the
                          ;; work, not a loop. Flagging on arguments alone made
                          ;; the run that would reveal whether an edit worked
                          ;; return a scolding instead of the test output —
                          ;; qwen3.6-35b-a3b went 80% → 10% on the benchmark
                          ;; with this extension on, and reported that "my
                          ;; environment only has write and edit tools working".
                          ;;
                          ;; And the nudge always claimed "the result hasn't
                          ;; changed" without ever looking at it.
                          (and (contains? prev-sigs sig)
                               (= (get (:tool-result-fps @state) sig)
                                  (shared/result-fingerprint (.-result ctx))))
                          (let [n (swap! counters update :repeat inc)]
                            ;; APPEND — never destroy a real result. The model
                            ;; needs to see what the tool said AND that it has
                            ;; seen it before.
                            (aset ctx "result"
                                  (str (.-result ctx)
                                       "\n\n[nyma] " (repeat-tool-msg tool-name (:repeat n))))
                            (.emitGlobal api "small-model/quality-signal"
                                         #js {:reason (str "repeat tool call: " tool-name)
                                              :tier   (:repeat n)}))

                          ;; Clean call — reset relevant counters
                          :else
                          (do (swap! counters assoc :hallucinated 0 :repeat 0)
                              ;; Coarse expiry: an unbounded sig set grows for
                              ;; the whole session AND flags a legitimately
                              ;; repeated call hours later as a loop.
                              (when (> (count (:all-tool-sigs @state)) 200)
                                (swap! state assoc :all-tool-sigs #{} :tool-result-fps {}))
                              (swap! state update :all-tool-sigs conj sig)
                              (swap! state assoc-in [:tool-result-fps sig]
                                     (shared/result-fingerprint (.-result ctx)))))
                        ctx))}

        ;; ── after_provider_request: advance turn counter ──────────
        on-turn
        (fn [_data _ctx]
          ;; Reset empty-turn counter on any successful turn
          (swap! counters assoc :empty 0)
          ;; No stream-state reset here. This used to `(reset! deltas-seen 0)`,
          ;; a binding that no longer exists — the mid-stream abort it belonged
          ;; to was replaced by the observe-only `text-seen?` above, and the
          ;; rename missed this line. It threw "deltas_seen is not defined" on
          ;; every after_provider_request, killing the turn-count increment
          ;; below with it, so max-turns never fired. `on-turn-start` is what
          ;; clears `text-seen?`.
          (let [tc (swap! state update :turn-count inc)]
            (when (>= (:turn-count tc) max-turns)
              (.sendUserMessage api
                                (turn-budget-msg max-turns)
                                #js {:deliverAs "followUp"}))))]

    (.on api "stream_filter" on-stream-filter)
    (swap! handlers conj ["stream_filter" on-stream-filter])
    (.on api "turn_start" on-turn-start)
    (swap! handlers conj ["turn_start" on-turn-start])
    (.on api "turn_finalize" on-empty-turn-finalize)
    (swap! handlers conj ["turn_finalize" on-empty-turn-finalize])

    (.addMiddleware api tool-checker)

    (.on api "after_provider_request" on-turn)
    (swap! handlers conj ["after_provider_request" on-turn])

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.removeMiddleware api "small-model/quality-monitor"))))
