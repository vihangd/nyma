(ns agent.extensions.small-model.supervisor
  "Supervisor — autonomous babysitter orchestration layer.

   The built-in `advisor` extension is reactive: the *worker* model must
   choose to call it, and small models don't reliably self-monitor.  This
   module makes oversight *proactive*:

     quality_monitor (tier 1, rule-based) fires → supervisor escalates
     → calls consult-advisor (reused from the advisor extension)
     → injects the returned plan/correction via sendUserMessage (steer queue)

   Triggers (all configurable):
     • Every N turns (periodic check-in)
     • On a quality signal from quality_monitor (via custom event)
     • Before a destructive/commit tool call (pre-commit)
     • Max-interventions budget (avoids the context-ceiling of over-babysitting)

   Lead/worker split: uses settings.roles.advisor for the supervisor model
   (typically a stronger or cloud model); the small worker runs locally.

   Hooks used:
     after_provider_request — periodic every-N-turns trigger
     agent.extensions.small-model/quality-signal — internal bus event
     before_tool_call (permission_request) — pre-commit gate
   "
  (:require [agent.extensions.small-model.shared :as shared]
            [clojure.string :as str]))

;; ── Destructive tool patterns ────────────────────────────────────

(def ^:private commit-tools
  #{"bash" "write"})

(defn- commit-like? [tool-name args]
  (and (contains? commit-tools tool-name)
       (when (= tool-name "bash")
         (let [cmd (str (.-command args))]
           (or (str/includes? cmd "git commit")
               (str/includes? cmd "git push")
               (str/includes? cmd "git merge"))))))

;; ── Consult-advisor bridge ────────────────────────────────────────
;;
;; Uses api.getTool("advisor") — returns the full tool def including
;; :execute. dependsOn ["advisor"] guarantees it's loaded before us.

(defn ^:async call-advisor-tool
  "Invoke the `advisor` tool via getTool, or fall back if unavailable."
  [api focus-question]
  (try
    (let [adv-tool (when (.-getTool api) (.getTool api "advisor"))]
      (if (and adv-tool (.-execute adv-tool))
        (let [result (js-await ((.-execute adv-tool)
                                #js {:focus focus-question}))]
          (str result))
        (str "Supervisor: advisor tool not available. Focus: " focus-question)))
    (catch :default e
      (str "Supervisor: advisor call failed — " (or (.-message e) (str e))))))

(defn ^:async do-intervention
  "Run one supervisor intervention: consult the advisor and steer the
   worker with the returned advice."
  [api state focus]
  (let [advice (js-await (call-advisor-tool api focus))]
    (swap! state update :interventions inc)
    (.sendUserMessage api
                      (str "🧭 Supervisor guidance:\n\n" advice)
                      #js {:deliverAs "steer"})
    advice))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire supervisor hooks. Returns a cleanup fn."
  [api config state]
  (let [sv-cfg          (:supervisor config)
        every-n         (or (:every-n-turns sv-cfg) 8)
        max-iv          (or (:max-interventions sv-cfg) 3)
        pre-commit?     (not= false (:pre-commit sv-cfg))
        handlers        (atom [])

        ;; Budget guard
        can-intervene?  (fn [] (< (:interventions @state) max-iv))

        ;; ── after_provider_request: periodic every-N check ───────
        on-turn
        (fn [_data _ctx]
          (let [tc (:turn-count @state)
                n  (:interventions @state)]
            (when (and (can-intervene?)
                       (pos? every-n)
                       (zero? (mod tc every-n))
                       (pos? tc))
              (do-intervention api state
                               (str "Periodic check-in at turn " tc
                                    ". Is the agent on track? "
                                    "Any stuck loops, wrong direction, "
                                    "or missing context?")))))

        ;; ── quality-signal: escalate from quality_monitor ────────
        on-quality-signal
        (fn [data _ctx]
          (when (can-intervene?)
            (let [reason (str (or (.-reason data) "quality issue detected"))]
              (do-intervention api state
                               (str "Quality monitor flagged: " reason
                                    ". Please review the agent's last few turns "
                                    "and provide corrective guidance.")))))

        ;; ── permission_request: pre-commit gate ──────────────────
        on-permission
        (fn [data _ctx]
          (when pre-commit?
            (let [tool (str (.-tool data))
                  args (.-args data)]
              (when (and (can-intervene?) (commit-like? tool args))
                ;; Fire async — don't block the permission decision
                (do-intervention api state
                                 (str "The agent is about to run: " tool
                                      ". Is this the right action? "
                                      "Review the planned changes and "
                                      "flag anything that looks wrong.")))))
          ;; Return nil — don't block the tool call, just advise
          nil)]

    (.on api "after_provider_request" on-turn)
    (swap! handlers conj ["after_provider_request" on-turn])

    ;; Inter-extension event (quality_monitor escalation)
    (.on api "small-model/quality-signal" on-quality-signal)
    (swap! handlers conj ["small-model/quality-signal" on-quality-signal])

    (.on api "permission_request" on-permission)
    (swap! handlers conj ["permission_request" on-permission])

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler)))))
