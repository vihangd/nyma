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
   With the judge configured, a first regex hit is put to Jev before the
   nudge: the regex cannot tell \"Should I also add X?\" after finished work
   from a stop halfway. It sees the user's request and the final text, and
   the nudge goes out at p 0.8 or above, or when Jev cannot answer.

   Off by default; opt-in via :finalize-warn {:enabled true}."
  (:require [clojure.string :as str]
            [agent.utils.decide :as decide]))

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
         "Continue working — call the next tool, or call small-model__respond if you are "
         "truly done.")
    (str "The task still looks incomplete. Do NOT stop with a status update. "
         "Either take the next concrete action with a tool, or call small-model__respond "
         "to deliver the finished result.")))

(defn nudge-messages
  "Both nudge tiers, for the lint that checks they name a real tool."
  []
  [(nudge-msg 1) (nudge-msg 2)])

(defn- tail [s n] (let [s (str s)] (subs s (max 0 (- (count s) n)))))

(defn ^:async unfinished?
  "Does Jev agree the agent stopped with work it could still do? True on
   no answer: the regex already said so."
  [request text]
  (let [r (js-await
           (decide/ask! {:kind "finalize" :cfg (decide/config)
                         :state (str "The user asked a coding agent:\n" (decide/visible (subs (str request) 0 (min 700 (count (str request)))) false)
                                     "\n\nThe agent then stopped with this message:\n" (decide/visible (tail text 800) false))
                         :questions {"stopped" {:instructions "Did the agent stop while work remains that it could do itself, rather than finishing, reporting what it did, or asking something only the user can answer?"}}}))
        p (get r "stopped")]
    (if (number? p) (>= p 0.8) true)))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire the agent_end premature-completion guard. Returns a cleanup fn."
  [api config _state]
  (let [fw         (:finalize-warn config)
        max-nudges (or (:max-nudges fw) 2)
        nudges     (atom 0)
        ;; Jev's verdict on this turn, settled at turn_finalize: a follow-up
        ;; sent after the drain would wait for the user's next message.
        judged     (atom nil)
        nudge!     (fn []
                     (swap! nudges inc)
                     (.sendUserMessage api (nudge-msg @nudges)
                                       #js {:deliverAs "followUp"}))
        handler
        (fn [data _ctx]
          (let [fr   (.-finishReason data)
                text (str (.-text data))]
            (if (premature? fr text)
              ;; Premature stop → nudge, capped. (At the cap we neither nudge
              ;; nor reset — backoff until a clean turn re-arms below.)
              (when (< @nudges max-nudges)
                ;; Only the first: after a nudge the latest user message is
                ;; ours, not the request Jev should weigh the stop against.
                (if (and (decide/config) (zero? @nudges))
                  (reset! judged (unfinished? (decide/user-request (:messages (.getState api))) text))
                  (nudge!)))
              ;; Any non-premature turn (acted, or finished cleanly) → re-arm.
              (reset! nudges 0))))
        on-final
        ^:async (fn [_ _]
                  (when-let [p @judged]
                    (reset! judged nil)
                    (when (js-await p) (nudge!))))]
    (.on api "agent_end" handler)
    (.on api "turn_finalize" on-final)
    (fn []
      (.off api "agent_end" handler)
      (.off api "turn_finalize" on-final))))
