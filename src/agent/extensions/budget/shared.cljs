(ns agent.extensions.budget.shared
  "Pure helpers for token budgets (Codex-style rollout budgets): hard caps
   that abort a runaway run instead of letting it burn tokens unattended.")

(def default-config
  {:turn-tokens nil :session-tokens nil :wall-seconds nil})

(defn config
  "Read settings#budget. Off unless a cap is set:
   {\"budget\": {\"turn-tokens\": 150000, \"session-tokens\": 2000000,
                 \"wall-seconds\": 900}}"
  [settings]
  (let [b (when settings (aget settings "budget"))]
    (merge default-config
           (when b
             (cond-> {}
               (aget b "turn-tokens")    (assoc :turn-tokens (aget b "turn-tokens"))
               (aget b "session-tokens") (assoc :session-tokens (aget b "session-tokens"))
               (aget b "wall-seconds")   (assoc :wall-seconds (aget b "wall-seconds")))))))

(defn enabled? [cfg]
  (boolean (or (:turn-tokens cfg) (:session-tokens cfg) (:wall-seconds cfg))))

(defn over-budget
  "Return :turn, :session, or nil for the first exceeded TOKEN cap.

   Wall-clock is deliberately not here. This is called from `turn_end`, which
   fires once a step COMPLETES — so a run wedged inside a single provider call,
   the exact case a wall-clock cap exists for, would never reach the check. That
   cap is a timer armed at turn start instead (see index.cljs)."
  [cfg {:keys [turn session]}]
  (cond
    (and (:turn-tokens cfg) (> turn (:turn-tokens cfg)))       :turn
    (and (:session-tokens cfg) (> session (:session-tokens cfg))) :session
    :else nil))
