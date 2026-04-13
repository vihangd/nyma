(ns agent.middleware.self-reminder
  "Self-reminder helper — injects a text block into the system prompt
   every N turns when a configurable action-predicate has not fired.

   Borrowed from helixent's createTodoSystem() pattern: track steps
   since last key action; when the count reaches a threshold, nudge
   the model with a reminder. The pattern is reusable beyond todos —
   plan mode, file-read nudges, context compaction warnings, etc.

   Usage:

     ;; Create a reminder for any extension or middleware consumer:
     (def deactivate
       ((make-reminder {:every-n-steps  10
                        :action-predicate (fn [] (was-todo-written-last-turn?))
                        :reminder-text-fn (fn [] \"<reminder>Write todos</reminder>\")})
        (:events agent)))

     ;; Shut down cleanly on extension deactivation:
     (deactivate)

   Semantics:
     - Counter is incremented on each `turn_start` unless the action
       predicate fired, in which case the counter is RESET and NOT
       incremented for that turn.
     - Reminder fires on `before_agent_start` whenever the counter
       has reached `every-n-steps`.
     - `reminder-text-fn` may return nil to suppress on a specific turn
       (e.g. when the reminder would be redundant with context already
       in the system prompt).
     - Two independent reminders get their own isolated counters; they
       cannot interfere with each other.")

;;; ─── Public API ─────────────────────────────────────────────

(defn make-reminder
  "Build a self-reminder subscription factory.

   Returns an activation function of the form `(fn [events] cleanup-fn)`.
   Calling the activation function registers the reminder on the given
   events bus and returns a zero-arg cleanup function that unregisters it.

   Options map:
     :every-n-steps     int  — number of turns without the action before
                               the reminder fires (required)
     :action-predicate  fn?  — zero-arg fn; when it returns truthy the
                               counter resets (optional; omit to fire
                               unconditionally every N turns)
     :reminder-text-fn  fn?  — zero-arg fn returning the reminder string
                               or nil to suppress for this turn (optional;
                               when omitted the reminder is a no-op)"
  [{:keys [every-n-steps action-predicate reminder-text-fn]}]
  (fn [events]
    (let [counter (atom 0)

          on-turn-start
          (fn [_data]
            ;; If the action fired since the last turn_start, reset.
            ;; Resetting skips the increment for that turn, so the next
            ;; reminder requires exactly `every-n-steps` fresh turns.
            (if (and action-predicate (action-predicate))
              (reset! counter 0)
              (swap! counter inc)))

          on-before-agent-start
          (fn [_data]
            ;; Contribute reminder text when the threshold is reached.
            ;; Returns nil (no contribution) otherwise — compatible with
            ;; emit-collect's last-writer-wins/concat semantics.
            (when (and reminder-text-fn
                       (>= @counter every-n-steps))
              (when-let [text (reminder-text-fn)]
                #js {"system-prompt-additions" #js [text]})))]

      ((:on events) "turn_start" on-turn-start)
      ((:on events) "before_agent_start" on-before-agent-start)

      ;; Return cleanup function for extension deactivation
      (fn []
        ((:off events) "turn_start" on-turn-start)
        ((:off events) "before_agent_start" on-before-agent-start)))))
