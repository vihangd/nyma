(ns agent.extensions.small-model.finalize-warn
  "Premature-completion guard — nudge a model that stops with the task
   apparently unfinished.

   Borrowed from little-coder's `finalize-warn`. SOTA motivation
   (NL2Repo-Bench): premature completion is a documented failure mode, and the
   'thinking-model paradox' makes reasoning/weak models stop *more* early via a
   'hallucination of verification'. The same research warns that LLM-based
   self-check verifiers themselves trigger premature exits — so this guard is a
   cheap HEURISTIC (no extra model call), not an LLM judge.

   Signal: a turn that ends with plain text and no tool call (finishReason
   \"stop\") whose text still reads as unfinished (open-task markers like
   'next step', 'I'll', a TODO, or asking the user whether to proceed). Aborted
   turns (stream-filter-aborted etc.) are skipped — finishReason is not \"stop\".

   On a hit, inject one capped follow-up nudge; reset on a clean tool-call turn.
   Off by default; opt-in via :finalize-warn {:enabled true}."
  (:require [clojure.string :as str]))

;; Strong signals only (per the SOTA failure modes): the model asks the user
;; whether to proceed, or states an explicit pending step. Deliberately NOT
;; matching soft closers ("let me know", "remaining") OR bare "I'll <word>"
;; ("Done — I'll keep the formatting." is a finished answer) — every clean
;; answer ends with finishReason "stop", so a loose regex nudges finished agents.
(def ^:private open-task-re
  (js/RegExp.
   (str "(should i\\b|shall i\\b|do you want me|would you like me|"
        "next step|\\btodo\\b|"
        "i'?ll (?:now|go ahead|continue|proceed|start))")
   "i"))

(defn premature?
  "True when the turn ended with text + no tool call (finishReason \"stop\")
   and the text still reads as unfinished. Pure for testability. finishReason
   is normalized to a string at the loop's agent_end emit point."
  [finish-reason text]
  (and (= (str finish-reason) "stop")
       (string? text)
       (not (str/blank? text))
       (boolean (.test open-task-re text))))

(defn- nudge-msg [n]
  (if (<= n 1)
    (str "Your last response stopped with the task apparently unfinished. "
         "Continue working — call the next tool, or call respond() if you are "
         "truly done.")
    (str "The task still looks incomplete. Do NOT stop with a status update. "
         "Either take the next concrete action with a tool, or call respond() "
         "to deliver the finished result.")))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire the agent_end premature-completion guard. Returns a cleanup fn."
  [api config _state]
  (let [fw         (:finalize-warn config)
        max-nudges (or (:max-nudges fw) 2)
        nudges     (atom 0)
        handler
        (fn [data _ctx]
          (let [fr   (.-finishReason data)
                text (str (.-text data))]
            (if (premature? fr text)
              ;; Premature stop → nudge, capped. (At the cap we neither nudge
              ;; nor reset — backoff until a clean turn re-arms below.)
              (when (< @nudges max-nudges)
                (swap! nudges inc)
                (.sendUserMessage api (nudge-msg @nudges)
                                  #js {:deliverAs "followUp"}))
              ;; Any non-premature turn (acted, or finished cleanly) → re-arm.
              (reset! nudges 0))))]
    (.on api "agent_end" handler)
    (fn [] (.off api "agent_end" handler))))
