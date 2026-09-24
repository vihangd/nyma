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
            [agent.debug :as d]
            [clojure.string :as str]))

;; ── Destructive tool patterns ────────────────────────────────────

(def ^:private commit-commands
  ["git commit" "git push" "git merge"])

(defn commit-like?
  "Is this tool call about to publish work?

   Only `bash` can be: the check is on the command string. `\"write\"` used to
   sit in a `commit-tools` set alongside `\"bash\"`, but the body then guarded
   `(when (= tool-name \"bash\") …)`, so for a write the `and` fell through to
   nil and the gate could never fire — the set implied a capability the code
   did not have. Nothing defines a commit-like *write*, and treating every
   file write as one would summon the advisor on virtually every turn, so the
   predicate now says what it does.

   Exported for tests: this gate is one of three that decide when a second
   model is consulted, and it was silently dead."
  [tool-name args]
  (boolean
   (and (= tool-name "bash")
        args
        (let [cmd (str (.-command args))]
          (some (fn [pat] (str/includes? cmd pat)) commit-commands)))))

;; ── Consult-advisor bridge ────────────────────────────────────────
;;
;; Uses api.getTool("advisor") — returns the full tool def including
;; :execute. dependsOn ["advisor"] guarantees it's loaded before us.

(defn ^:async call-advisor-tool
  "Invoke the `advisor` tool via getTool. Returns the advice, or **nil** when
   there is none to give.

   nil rather than a message, because whatever comes back here is handed
   straight to the worker model as guidance. When the advisor was unreachable
   this returned \"Supervisor: advisor call failed — AI_APICallError: The model
   service is temporarily unavailable\", and that sentence was injected as an
   instruction. Observed on a benchmark task: the worker read it twice, then
   went and read nyma's own settings.json looking for the problem, and timed
   out. A supervisor with nothing to say must say nothing."
  [api focus-question]
  (try
    (let [;; Tools are registered NAMESPACED — extension_scope prefixes with
          ;; "<ns>__" — while getTool passes the name straight through. So a
          ;; bare "advisor" could never match, and every intervention in a real
          ;; session degraded to the canned fallback below without ever saying
          ;; the lookup was the problem. `dependsOn ["advisor"]` guarantees
          ;; which namespace it is; the bare name is kept as a fallback in case
          ;; something registers it unprefixed via overrideTool.
          get-tool (fn [n] (when (.-getTool api)
                             (try (.getTool api n) (catch :default _ nil))))
          adv-tool (or (get-tool "advisor__advisor") (get-tool "advisor"))]
      (if (and adv-tool (.-execute adv-tool))
        (let [result (js-await ((.-execute adv-tool)
                                #js {:focus focus-question}))]
          (str result))
        (do (d/warn "small-model" "supervisor: advisor tool unavailable — skipping intervention")
            nil)))
    (catch :default e
      (d/warn "small-model"
              (str "supervisor: advisor call failed, skipping intervention — "
                   (or (.-message e) (str e))))
      nil)))

(defn ^:async do-intervention
  "Run one supervisor intervention: consult the advisor and steer the
   worker with the returned advice."
  [api state focus]
  (let [advice (js-await (call-advisor-tool api focus))]
    ;; Spent either way. A permanently unreachable advisor would otherwise
    ;; retry on every every-N tick for the whole run; charging the budget caps
    ;; the wasted calls at max-interventions.
    (swap! state update :interventions inc)
    (when-not (str/blank? (str (or advice "")))
      (.sendUserMessage api
                        (str "🧭 Supervisor guidance:\n\n" advice)
                        #js {:deliverAs "steer"}))
    advice))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire supervisor hooks. Returns a cleanup fn."
  [api config state]
  (let [sv-cfg          (:supervisor config)
        every-n         (or (:every-n-turns sv-cfg) 8)
        max-iv          (or (:max-interventions sv-cfg) 3)
        pre-commit?     (not= false (:pre-commit sv-cfg))

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

    ;; Inter-extension event (quality_monitor escalation)
    (.on api "small-model/quality-signal" on-quality-signal)

    (.on api "permission_request" on-permission)

    ;; Cleanup
    (fn [] nil)))
