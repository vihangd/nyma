(ns agent.extensions.budget.shared
  "Pure helpers for token budgets (Codex-style rollout budgets): hard caps
   that abort a runaway run instead of letting it burn tokens unattended."
  (:require [agent.utils.js-interop :as ji]))

(def default-config
  "Mirrors extension.json's `settings.budget`. The manifest is what the settings
   manager merges under the user's values, so this is only the floor for a
   caller that hands `config` a bare map instead of the merged settings."
  {:turn-tokens nil :session-tokens nil :wall-seconds nil})

(defn config
  "Read settings#budget. Off unless a cap is set:
   {\"budget\": {\"turn-tokens\": 150000, \"session-tokens\": 2000000,
                 \"wall-seconds\": 900}}

   No key-by-key ladder: extension.json declares every key with a nil default,
   so the section arrives complete and a plain merge is the whole job."
  [settings]
  (merge default-config
         (ji/js->clj* (when settings (aget settings "budget")))))

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
