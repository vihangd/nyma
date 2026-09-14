(ns agent.extensions.model-roles.features.escalate
  "Escalation: hand the wheel to a stronger model when the cheap one is
   demonstrably stuck, and fall over to the next model when a provider says no.

   nyma already had the ADVICE tier — the advisor reviews a transcript and
   returns text, the supervisor does that automatically — but nothing ever
   handed over CONTROL, and there was no provider failover at all. This adds
   both, as a model override (never a role switch: switching :active-role would
   drag the target role's allowed-tools/permissions along and could strip write
   and bash mid-task).

   Two rules the research is blunt about:
     - Never retry in place. A failed attempt left in context raises the error
       rate ~7x, so escalation prunes the stalled span before re-running.
     - A cascade needs a real capability gap. Weak-directs-weak scored BELOW the
       weak model alone, so if the target resolves to the model already running,
       refuse rather than churn.

   Squint notes: async work lives in top-level defns called by sync wrappers
   (named, so testable; `^:async (fn …)` with the meta on the FORM works too). Keywords ARE strings, so (:mode cfg) reads
   both CLJS defaults and user JSON."
  (:require [clojure.string :as str]
            [agent.utils.ui :refer [ui-prompt-ready?]]
            [agent.debug :as d]
            [agent.extensions.model-roles.features.plan-mode :as plan-mode]))

;; ---------------------------------------------------------------------------
;; config (pure)
;; ---------------------------------------------------------------------------

(def default-config
  {:mode            "ask"          ; ask | auto | off  (off = failover only)
   :to              "advisor"
   :on              {:no-op-turns 3 :repeat-tool-calls 3 :verify-exhausted true}
   :prune           true
   ;; Sequential refinement before escalation. Measured on this repo's
   ;; benchmark: qwen3.5-9b failed `transpose` under three different scaffold
   ;; configurations, then passed it on the third independent attempt — the task
   ;; is within the model's reach about one try in three. Retrying the SAME
   ;; model from a clean context is therefore worth doing before paying for a
   ;; bigger one. This is the PDR half of arXiv 2604.16529 (70.9% -> 77.6% on
   ;; SWE-Bench Verified for Claude-4.5-Opus).
   :retries-before-escalate 1
   :revert          "next-request"  ; next-request | never
   :max-per-session 2
   :fallback        {:default [] :cooldown-ms 300000 :revert "cooldown"}})

(defn config
  "Pure: settings → the escalate config, user keys merged over defaults.
   Nested :on and :fallback merge field-wise so setting one key keeps the rest."
  [settings]
  (let [raw (or (:escalate settings) (get settings "escalate") {})]
    (-> (merge default-config raw)
        (assoc :on       (merge (:on default-config)       (:on raw)))
        (assoc :fallback (merge (:fallback default-config) (:fallback raw))))))

(defn mode [cfg] (str (or (:mode cfg) "ask")))

;; ---------------------------------------------------------------------------
;; provider errors (pure)
;; ---------------------------------------------------------------------------

;; Relay and local providers phrase these differently and often carry no status
;; field at all, so match the message the way compaction/context-overflow-error?
;; and plan-mode/auth-error? already do.
(def ^:private error-patterns
  [[:quota     ["429" "rate limit" "rate_limit" "ratelimit" "quota"
                "too many requests" "usage limit" "capacity"]]
   [:server    ["500" "502" "503" "504" "overloaded" "bad gateway"
                "service unavailable" "server error" "internal error"]]
   ;; "unable to connect" is Bun's wording — its fetch reports BOTH a refused
   ;; port and a dead host as `TypeError: Unable to connect. Is the computer
   ;; able to access the url?`, with an empty cause. None of the other patterns
   ;; match it, so a self-hosted box being switched off produced error-kind nil,
   ;; retryable? false, and no failover at all: the configured fallback chain was
   ;; decorative. "econnrefused" covers the same case under Node.
   [:network   ["econnreset" "etimedout" "enotfound" "socket hang up"
                "network error" "fetch failed" "timeout"
                "unable to connect" "econnrefused"]]
   [:not-found ["model not found" "model_not_found" "no such model"
                "unknown model" "does not exist"]]])

(defn error-kind
  "Pure: an error message → :quota | :server | :network | :not-found | nil."
  [msg]
  (let [m (str/lower-case (str msg))]
    (when (seq m)
      (some (fn [[kind pats]]
              (when (some (fn [p] (.includes m p)) pats) kind))
            error-patterns))))

(defn retryable?
  "Pure: is this an error another model might survive?"
  [msg]
  (some? (error-kind msg)))

(defn next-fallback
  "Pure: the first chain entry not already tried, or nil when exhausted."
  [chain tried]
  (let [tried (set (or tried []))]
    (first (remove (fn [s] (contains? tried (str s))) (or chain [])))))

(defn chain-for
  "Pure: the fallback chain for `role`, falling back to the `default` chain."
  [cfg role]
  (let [fb (:fallback cfg)]
    (vec (or (get fb (str role)) (:default fb) []))))

;; ---------------------------------------------------------------------------
;; stall detection (pure)
;; ---------------------------------------------------------------------------

;; Whether a task is in flight used to be derived by scanning `state :messages`
;; for a `tool_call` role. Nothing writes that role — `state :messages` only
;; ever holds user/assistant (sessions/manager.cljs:27) — so the scan always
;; returned false and the no-op-turns branch below could never fire. The signal
;; lives in the turn_finalize payload instead (`:toolCalls`, loop.cljs:576),
;; latched across turns in `:escalate-task-in-flight` and cleared at the
;; episode boundary by on-user-message.

(defn stall-reason
  "Pure: {:no-op-turns n :messages [...] :verify-exhausted bool} + cfg →
   a human reason string, or nil when nothing is wrong. Deliberately narrower
   than loop.cljs's two-turn UI warning: a warning can afford a false positive,
   a model swap plus a destructive prune cannot."
  [signals cfg]
  (let [on      (:on cfg)
        n       (or (:no-op-turns signals) 0)
        thresh  (or (:no-op-turns on) 3)]
    (cond
      (and (:verify-exhausted signals) (:verify-exhausted on))
      "the verify command is still failing after the fix attempts ran out"

      (and (pos? thresh) (>= n thresh) (boolean (:task-in-flight signals)))
      (str n " turns ran no tools mid-task")

      :else nil)))

;; ---------------------------------------------------------------------------
;; pruning (pure)
;; ---------------------------------------------------------------------------

(defn- content->text [c]
  (cond
    (string? c) c
    (nil? c)    ""
    :else       (str c)))

(defn prune-tail
  "Pure: drop the last user message AND everything after it, appending an
   assistant-role note in its place. Returns {:messages :request}.

   Pruning only what comes AFTER the request would leave the request in context
   and then re-deliver it — the model would see it twice. The note is assistant
   role so user/assistant alternation survives; two consecutive user messages
   are rejected by some providers. Dropping a whole suffix can never orphan a
   tool_result from its tool_call, so no cut-position logic is needed."
  [messages note]
  (let [msgs (vec (or messages []))
        idx  (loop [i (dec (count msgs))]
               (cond (neg? i) -1
                     (= (str (:role (nth msgs i))) "user") i
                     :else (recur (dec i))))]
    (if (neg? idx)
      {:messages msgs :request nil}
      {:messages (conj (subvec msgs 0 idx) {:role "assistant" :content note})
       :request  (content->text (:content (nth msgs idx)))})))

(defn stall-note
  "Pure: the note that replaces the pruned span."
  [model reason]
  (str "[escalation] The previous attempt on " model " stalled: " reason
       ". Do not repeat those steps — take a different approach."))

;; ---------------------------------------------------------------------------
;; state helpers
;; ---------------------------------------------------------------------------

(defn- state-atom [api] (.-__state-atom api))
(defn- cur-state [api] (.getState api))
(defn- settings [api] (.settings api))
(defn- ui [api] (.-ui api))

(defn- notify [api msg level]
  (when (and (ui api) (.-notify (ui api)))
    (.notify (ui api) msg (or level "info"))))

(defn- current-spec
  "The 'provider/model' the session is actually running right now."
  [api]
  ;; `:runtime-model` led this `or` and has no writer anywhere.
  (:model (cur-state api)))

(defn- split-spec [spec]
  (let [s (str spec)
        i (.indexOf s "/")]
    (when (pos? i) [(.substring s 0 i) (.substring s (inc i))])))

(defn target-spec
  "The 'provider/model' to escalate to: a role name resolves through the role
   table, a literal provider/model spec is taken as-is."
  [api to]
  (let [t (str to)]
    (if (.includes t "/")
      t
      (plan-mode/role-model-spec (settings api) t))))

(defn try-set-model!
  "Apply `spec`; true on success, false on failure. `setModel` returns nil
   always, so throwing is its only signal.

   Five call sites wrapped it in `(try … (catch :default _e nil))` and then
   asserted success anyway — notifying \"⚡ escalated to X\", writing
   :escalated-to, incrementing :escalations, or (in revert!) clearing the state
   while the config stayed on the expensive model for the rest of the session.
   The realistic failure is a missing credential for the target provider, which
   is exactly the case an escalation chain walks into.

   `warn-quiet` rather than `warn`: this runs on `model_resolve`, which fires
   mid-render, and d/warn mirrors to stderr — a stderr write during a render
   desynchronises pi-tui's differential renderer.

   Note what this CANNOT catch: an unknown provider does not throw. setModel
   guards on the registry and silently assigns the raw spec string to
   config.model. That is a separate defect.

   Pure-ish and exposed for tests."
  [api spec]
  (try
    (.setModel api spec)
    true
    (catch :default e
      (d/warn-quiet "escalate" (str "setModel failed for " spec ": "
                                    (or (.-message e) (str e))))
      false)))

(defn- swap-model!
  "Apply `spec` to the live request. All three steps matter: st-config carries
   the model object the retry actually sends, setModel keeps the status bar and
   cost attribution honest, and only then is the swap real."
  [api spec st-config]
  (when-let [[provider model-id] (split-spec spec)]
    (when (and st-config (.-resolveModel api))
      (try
        (let [obj (.resolveModel api provider model-id)]
          (when obj
            (aset st-config "model" obj)
            ;; providerOptions were built from the OLD model's thinking config;
            ;; shipping one provider's dialect to another's model is a 400.
            (aset st-config "providerOptions" #js {})))
        (catch :default e
          (d/warn "escalate" (str "resolveModel failed for " spec ": " (.-message e))))))
    ;; Report the real outcome. This returned `true` unconditionally, and the
    ;; caller notified "falling back to X" on the strength of it.
    (try-set-model! api spec)))

;; ---------------------------------------------------------------------------
;; capability escalation
;; ---------------------------------------------------------------------------

(defn ^:async ask-user
  "The consent prompt. Returns :yes | :yes-always | :no | :no-never."
  [api reason spec]
  (let [choice (js-await
                (.select (ui api)
                         (str "⚡ " reason ". Hand this task to " spec
                              "? The stalled span will be pruned from context.")
                         #js ["Yes — escalate and retry"
                              "Yes, and stop asking this session"
                              "No — keep going"
                              "No, and don't ask again this session"]))]
    (cond
      (= choice "Yes — escalate and retry")           :yes
      (= choice "Yes, and stop asking this session")  :yes-always
      (= choice "No, and don't ask again this session") :no-never
      :else                                            :no)))

(defn retry-note
  "Pure: the note that replaces a failed attempt on a same-model retry."
  [reason]
  (str "[retry] The previous attempt failed: " reason
       ". Start over from the current state of the files. Do not repeat the"
       " approach that failed — read the tests first, then write the solution."))

(defn apply-retry!
  "Sequential refinement: prune the failed attempt and re-deliver the request to
   the SAME model with a note about what went wrong. No model swap, no cost
   beyond the retry itself."
  [api reason]
  (let [st   (state-atom api)
        msgs (vec (:messages @st))
        {:keys [messages request]} (prune-tail msgs (retry-note reason))]
    (swap! st assoc
           :messages       messages
           :escalate-retries (inc (or (:escalate-retries @st) 0)))
    (notify api (str "↻ retrying from a clean context — " reason) "info")
    (d/info "escalate" (str "retry " (:escalate-retries @st)) #js {:reason reason})
    (when (and request (.-sendUserMessage api))
      (.sendUserMessage api request #js {:deliverAs "followUp"}))
    true))

(defn apply-escalation!
  "Prune, swap the model, and re-deliver the captured request as a follow-up.
   The follow-queue drain rebuilds context from the pruned state, so nothing
   has to touch st-config here."
  [api spec reason]
  (let [st   (state-atom api)
        cfg  (config (settings api))
        msgs (vec (:messages @st))
        {:keys [messages request]}
        (if (:prune cfg)
          (prune-tail msgs (stall-note (current-spec api) reason))
          {:messages msgs :request nil})]
    (swap! st assoc
           :messages       messages)
    ;; Switch FIRST, and only record the escalation if it took. This wrote
    ;; :escalated-to and incremented :escalations before calling setModel, so a
    ;; failure burned a slot against max-per-session, left the state claiming a
    ;; model that was never applied, and re-delivered the request to the model
    ;; that had just stalled — with the context already pruned.
    (if-not (try-set-model! api spec)
      (do (notify api (str "escalation to " spec " failed — staying on "
                           (current-spec api)
                           ". Check credentials for that provider.") "error")
          nil)
      (do
        (swap! st assoc
               :escalated-to   spec
               :escalated-from (current-spec api)
               :escalations    (inc (or (:escalations @st) 0)))
        (notify api (str "⚡ escalated to " spec " — " reason
                         ". /escalate off to go back.") "warning")
        (d/info "escalate" (str "escalated to " spec) #js {:reason reason})
        (when (and request (.-sendUserMessage api))
          (.sendUserMessage api request #js {:deliverAs "followUp"}))
        true))))

(defn ^:async escalate!
  "One capability escalation, consent included. Every refusal path is silent
   except the one the user needs to know about."
  [api reason forced?]
  (let [st   (state-atom api)
        s    @st
        cfg  (config (settings api))
        spec (target-spec api (:to cfg))]
    (cond
      (not spec)
      (when forced?
        (notify api (str "No escalation target: role '" (:to cfg)
                         "' has no provider/model.") "warning"))

      ;; ManagerWorker's 42%-vs-44% as a runtime check: structure without
      ;; substance is pure overhead, so refuse a swap that isn't one. A user
      ;; roles map REPLACES the shipped one, so this fires more than it looks.
      (= (str spec) (str (current-spec api)))
      (when forced?
        (notify api (str "Already running " spec " — nothing to escalate to.") "info"))

      (and (not forced?)
           (>= (or (:escalations s) 0) (or (:max-per-session cfg) 2)))
      (notify api (str "⚡ stalled again, but the escalation cap ("
                       (:max-per-session cfg) ") is used up. /escalate to override.")
              "warning")

      ;; Retry the same model from a clean context before paying for a bigger
      ;; one. A forced /escalate skips straight to the model swap.
      (and (not forced?)
           (< (or (:escalate-retries s) 0) (or (:retries-before-escalate cfg) 0)))
      (apply-retry! api reason)

      :else
      (let [ask? (and (not forced?)
                      (= (mode cfg) "ask")
                      (not (:escalate-always s)))]
        (if (and ask? (not (ui-prompt-ready? (ui api))))
          ;; ask means ask — headless can't, so it doesn't.
          (d/info "escalate" "stall detected but mode=ask and no prompt available")
          (let [answer (if ask? (js-await (ask-user api reason spec)) :yes)]
            (when (= answer :yes-always) (swap! st assoc :escalate-always true))
            (when (= answer :no-never)   (swap! st assoc :escalate-disarmed true))
            (when (contains? #{:yes :yes-always} answer)
              (apply-escalation! api spec reason))))))))

(defn revert!
  "Drop back to the configured model. Also the /role hook: a manual choice must
   never be silently re-overridden on the next turn."
  [api]
  (let [st   (state-atom api)
        ;; What we escalated FROM is the only spec guaranteed to exist. Deriving
        ;; it from the role table can come back nil (no :base-model-spec, no
        ;; roles.default entry), which would strand config.model on the
        ;; expensive model for the rest of the session — the exact cost leak
        ;; this feature promises not to have.
        back (or (:escalated-from @st)
                 (plan-mode/effective-model-spec api (or (:active-role @st) "default")))]
    (when (:escalated-to @st)
      ;; Restore BEFORE clearing the state. Clearing first and swallowing the
      ;; failure meant the state said "not escalated" while config.model stayed
      ;; on the expensive model for the rest of the session — and on-resolve no
      ;; longer re-applied anything, so nothing ever corrected it. That is the
      ;; exact cost leak this function exists to prevent, so it must be loud.
      (if (and back (not (try-set-model! api back)))
        (do (notify api (str "could not switch back to " back
                             " — still running " (current-spec api)
                             ". /model " back " to fix.") "error")
            false)
        (do (swap! st dissoc :escalated-to :escalated-from)
            true)))))

;; ---------------------------------------------------------------------------
;; handlers
;; ---------------------------------------------------------------------------

(defn on-resolve
  "model_resolve: while escalated, the escalation target is the model. Mirrors
   plan-mode's opusplan handler — setModel, return nil, let the loop fall back
   to config.model."
  [api _data]
  (when-let [spec (:escalated-to (cur-state api))]
    ;; Fires every turn. A silent failure here ran the whole session on the
    ;; wrong model while :escalated-to insisted otherwise. try-set-model! warns
    ;; quietly — this is mid-render, and stderr during a render desyncs pi-tui.
    (try-set-model! api spec))
  nil)

(defn on-provider-error
  "provider_error: availability failover. Not asked about — a 429 is not a
   choice, and the fallback is a model the user listed."
  [api data]
  (let [st    (state-atom api)
        s     @st
        cfg   (config (settings api))
        msg   (str (or (.-message data)
                       (when-let [e (.-error data)] (.-message e))
                       (.-error data)))
        kind  (error-kind msg)
        chain (filter (fn [spec] (and spec (.includes (str spec) "/")))
                      (map (fn [e] (target-spec api e))
                           (chain-for cfg (or (:active-role s) "default"))))
        ;; cooldown: a provider that 429'd an hour ago has probably recovered,
        ;; so stop excluding it once the window lapses.
        cool  (or (:cooldown-ms (:fallback cfg)) 300000)
        fresh (and (:escalate-tried-at s)
                   (< (- (js/Date.now) (:escalate-tried-at s)) cool))
        tried (conj (set (when fresh (:escalate-tried s))) (str (current-spec api)))
        nxt   (next-fallback chain tried)]
    (when (and kind nxt (.-config data))
      ;; Capture BEFORE the swap: setModel updates state, so reading it after
      ;; reports the fallback as the model that failed.
      (let [failed (current-spec api)]
        (swap! st assoc
               :escalate-tried    (vec (conj tried (str nxt)))
               :escalate-tried-at (js/Date.now))
        (swap-model! api nxt (.-config data))
        (notify api (str "⚡ " (name kind) " on " failed
                         " — falling back to " nxt) "warning"))
      (d/info "escalate" (str "failover → " nxt) #js {:kind (name kind)})
      #js {:retry true})))

(defn on-turn-finalize
  "turn_finalize: the stall check. Runs before auto-compaction and before the
   follow-queue drain, so a prune lands before anything reads the messages.
   Prefers the count carried in the event payload — it is authoritative for the
   turn that just ended."
  [api data]
  ;; Latch first: a turn that ran tools marks the episode as real work, and the
  ;; stall that follows is then several tool-less turns LATER — by which time
  ;; this turn's own count is long gone.
  (when (pos? (or (and data (.-toolCalls data)) 0))
    (swap! (state-atom api) assoc :escalate-task-in-flight true))
  ;; A response cut off at the output-token cap is not a stall — the model was
  ;; still working when the cap hit, and the loop already refuses to count it as
  ;; a no-op turn. Standing down here too means a stale count from earlier turns
  ;; can't escalate on the back of a truncation.
  (when (= (str (and data (.-finishReason data))) "length")
    (d/info "escalate" "turn was cut off at the output-token cap — not a stall"))
  (let [s   (cur-state api)
        cfg (config (settings api))]
    (when (and (not= (mode cfg) "off")
               (not (:escalate-disarmed s))
               (not (:plan-mode s))        ; opusplan already owns the model there
               (not (:escalated-to s)))
      ;; Returned, not fired and forgotten: turn_finalize is awaited
      ;; (emit-async awaits handlers that return promises), so returning it is
      ;; what guarantees the prune lands BEFORE the follow-queue drain re-enters
      ;; the loop. Dropping the promise races the prune against the next turn.
      (when-let [reason (when-not (= (str (and data (.-finishReason data))) "length")
                          (stall-reason {:no-op-turns (or (and data (.-noOpTurns data))
                                                          (:no-op-turns s))
                                         :task-in-flight (:escalate-task-in-flight s)
                                         :verify-exhausted (:escalate-verify-exhausted s)}
                                        cfg))]
        (escalate! api reason false)))))

(defn on-user-message
  "Episode boundary. The routing unit for an agent is the task, not the turn:
   reverting mid-task drops straight back into the loop that triggered it, so
   the escalation lasts until the next request."
  [api _data]
  (let [st (state-atom api)]
    (swap! st dissoc :escalate-verify-exhausted :escalate-retries
           :escalate-task-in-flight)
    (when (and (:escalated-to @st)
               (= (str (:revert (config (settings api)))) "next-request"))
      (revert! api)))
  nil)

;; ---------------------------------------------------------------------------
;; activation
;; ---------------------------------------------------------------------------

(defn command-handler [api args ctx]
  (let [sub (str/lower-case (str (first args)))
        st  (state-atom api)
        cfg (config (settings api))]
    (cond
      (or (= sub "off") (= sub "stop"))
      (do (swap! st assoc :escalate-disarmed true)
          (revert! api)
          (notify api "Escalation off for this session." "info"))

      (= sub "status")
      ;; The failover chain is reported too. It was invisible here, so the only
      ;; way to tell a configured chain from the empty default was to trigger a
      ;; provider error and watch — and an empty chain fails exactly like a
      ;; missing one (rethrow, turn dies). Resolve each entry so a chain naming
      ;; a role that no longer exists is obvious rather than silently inert.
      (let [role  (or (:active-role @st) "default")
            chain (chain-for cfg role)]
        (notify api (str "Escalation: " (mode cfg)
                         "\nTarget: " (or (target-spec api (:to cfg)) "(unresolved)")
                         "\nActive: " (or (:escalated-to @st) "no")
                         "\nUsed: " (or (:escalations @st) 0) "/" (:max-per-session cfg)
                         "\nFallback (" role "): "
                         (if (seq chain)
                           (str/join " -> "
                                     (map (fn [e]
                                            (str e " [" (or (target-spec api e) "unresolved") "]"))
                                          chain))
                           "(none — a provider error will end the turn)")
                         (when (:escalate-disarmed @st) "\nDisarmed for this session."))
                "info"))

      :else
      (do (swap! st dissoc :escalate-disarmed)
          (escalate! api "you asked for it" true)))
    (when (and ctx (.-ui ctx)) nil)))

(defn activate
  "Wire the feature. Returns a cleanup thunk."
  [api]
  (let [handlers (atom [])
        on-mres  (fn [data] (on-resolve api data))
        on-perr  (fn [data] (on-provider-error api data))
        on-final (fn [data] (on-turn-finalize api data))
        on-user  (fn [data] (on-user-message api data))
        on-vexh  (fn [_data] (swap! (state-atom api) assoc :escalate-verify-exhausted true) nil)]
    ;; Priority -20: after model_roles (0) and after plan_mode's opusplan (-10),
    ;; so an active escalation is the last writer of config.model.
    (.on api "model_resolve" on-mres -20)
    (.on api "provider_error" on-perr)
    (.on api "turn_finalize" on-final)
    (.on api "input_submit" on-user)
    (.on api "small-model/verify-exhausted" on-vexh)
    (swap! handlers into [["model_resolve" on-mres]
                          ["provider_error" on-perr]
                          ["turn_finalize" on-final]
                          ["input_submit" on-user]
                          ["small-model/verify-exhausted" on-vexh]])

    (.registerCommand api "escalate"
                      #js {:description "Hand this task to the stronger model. Usage: /escalate [off|status]"
                           :handler (fn [args ctx] (command-handler api args ctx))})

    (fn []
      (doseq [[event handler] @handlers] (.off api event handler))
      (.unregisterCommand api "escalate"))))
