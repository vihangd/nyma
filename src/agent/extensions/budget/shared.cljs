(ns agent.extensions.budget.shared
  "Pure helpers for token budgets (Codex-style rollout budgets): hard caps
   that abort a runaway run instead of letting it burn tokens unattended.")

(def default-config
  {:turn-tokens nil :session-tokens nil})

(defn config
  "Read settings#budget. Off unless a cap is set:
   {\"budget\": {\"turn-tokens\": 150000, \"session-tokens\": 2000000}}"
  [settings]
  (let [b (when settings (aget settings "budget"))]
    (merge default-config
           (when b
             (cond-> {}
               (aget b "turn-tokens")    (assoc :turn-tokens (aget b "turn-tokens"))
               (aget b "session-tokens") (assoc :session-tokens (aget b "session-tokens")))))))

(defn enabled? [cfg]
  (boolean (or (:turn-tokens cfg) (:session-tokens cfg))))

(defn over-budget
  "Return :turn, :session, or nil for the first exceeded cap."
  [cfg {:keys [turn session]}]
  (cond
    (and (:turn-tokens cfg) (> turn (:turn-tokens cfg)))       :turn
    (and (:session-tokens cfg) (> session (:session-tokens cfg))) :session
    :else nil))
