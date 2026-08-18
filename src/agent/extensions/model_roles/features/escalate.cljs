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

   Squint notes: no inline (fn ^:async ...) — async work lives in top-level
   defns called by sync wrappers. Keywords ARE strings, so (:mode cfg) reads
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
   [:network   ["econnreset" "etimedout" "enotfound" "socket hang up"
                "network error" "fetch failed" "timeout"]]
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

(defn task-in-flight?
  "Pure: has any tool run since the last user message? Two no-op turns is a
   normal conversation (question, answer, question, answer) — it is only a
   stall if the model was actually working on something."
  [messages]
  (let [msgs (vec (or messages []))
        idx  (loop [i (dec (count msgs))]
               (cond (neg? i) -1
                     (= (str (:role (nth msgs i))) "user") i
                     :else (recur (dec i))))]
    (boolean (some (fn [m] (= (str (:role m)) "tool_call"))
                   (subvec msgs (inc idx))))))

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

      (and (pos? thresh) (>= n thresh) (task-in-flight? (:messages signals)))
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
(defn- settings [api] (when-let [g (.-getSettings api)] (g)))
(defn- ui [api] (.-ui api))

(defn- notify [api msg level]
  (when (and (ui api) (.-notify (ui api)))
    (.notify (ui api) msg (or level "info"))))

(defn- current-spec
  "The 'provider/model' the session is actually running right now."
  [api]
  (let [s (cur-state api)]
    (or (:runtime-model s) (:model s))))

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
    (try (.setModel api spec) (catch :default _e nil))
    true))

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
           :messages       messages
           :escalated-to   spec
           :escalated-from (current-spec api)
           :escalations    (inc (or (:escalations @st) 0)))
    (try (.setModel api spec) (catch :default _e nil))
    (notify api (str "⚡ escalated to " spec " — " reason
                     ". /escalate off to go back.") "warning")
    (d/info "escalate" (str "escalated to " spec) #js {:reason reason})
    (when (and request (.-sendUserMessage api))
      (.sendUserMessage api request #js {:deliverAs "followUp"}))
    true))

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
      (swap! st dissoc :escalated-to :escalated-from)
      (when back (try (.setModel api back) (catch :default _e nil)))
      true)))

;; ---------------------------------------------------------------------------
;; handlers
;; ---------------------------------------------------------------------------

(defn on-resolve
  "model_resolve: while escalated, the escalation target is the model. Mirrors
   plan-mode's opusplan handler — setModel, return nil, let the loop fall back
   to config.model."
  [api _data]
  (when-let [spec (:escalated-to (cur-state api))]
    (try (.setModel api spec) (catch :default _e nil)))
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
      (swap! st assoc
             :escalate-tried    (vec (conj tried (str nxt)))
             :escalate-tried-at (js/Date.now))
      (swap-model! api nxt (.-config data))
      (notify api (str "⚡ " (name kind) " on " (current-spec api)
                       " — falling back to " nxt) "warning")
      (d/info "escalate" (str "failover → " nxt) #js {:kind (name kind)})
      #js {:retry true})))

(defn on-turn-finalize
  "turn_finalize: the stall check. Runs before auto-compaction and before the
   follow-queue drain, so a prune lands before anything reads the messages."
  [api _data]
  (let [s   (cur-state api)
        cfg (config (settings api))]
    (when (and (not= (mode cfg) "off")
               (not (:escalate-disarmed s))
               (not (:plan-mode s))        ; opusplan already owns the model there
               (not (:escalated-to s)))
      (when-let [reason (stall-reason {:no-op-turns (:no-op-turns s)
                                       :messages    (:messages s)
                                       :verify-exhausted (:escalate-verify-exhausted s)}
                                      cfg)]
        (escalate! api reason false))))
  nil)

(defn on-user-message
  "Episode boundary. The routing unit for an agent is the task, not the turn:
   reverting mid-task drops straight back into the loop that triggered it, so
   the escalation lasts until the next request."
  [api _data]
  (let [st (state-atom api)]
    (swap! st dissoc :escalate-verify-exhausted)
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
      (notify api (str "Escalation: " (mode cfg)
                       "\nTarget: " (or (target-spec api (:to cfg)) "(unresolved)")
                       "\nActive: " (or (:escalated-to @st) "no")
                       "\nUsed: " (or (:escalations @st) 0) "/" (:max-per-session cfg)
                       (when (:escalate-disarmed @st) "\nDisarmed for this session."))
              "info")

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
