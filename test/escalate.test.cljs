(ns escalate.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.model-roles.features.escalate :as esc]
            [agent.extensions.model-roles.status-segment :as seg]))

(def ^:private settings
  {:model "cheap-1"
   :roles {:default {:provider "zen" :model "cheap-1"}
           :advisor {:provider "yun" :model "opus-5"}
           :build   {:provider "mm"  :model "m2.5"}}
   :escalate {:fallback {:default ["build" "advisor"]}}})

;; The first trigger now RETRIES the same model; escalation is the second step.
;; Tests that target escalation spend the retry budget up front.
(defn- no-retry [s] (assoc s :escalate (merge (:escalate s) {:retries-before-escalate 0})))

(defn- fake-api [st & [opts]]
  (let [opts (or opts {})]
    #js {:__state_atom st
         :getState     (fn [] @st)
         :getSettings  (fn [] (or (:settings opts) settings))
         :setModel     (fn [m] (swap! (:set-calls opts) conj m))
         :resolveModel (fn [p m] #js {:id (str p "/" m)})
         :sendUserMessage (fn [text o] (swap! (:sent opts) conj [text (.-deliverAs o)]))
         :ui           (or (:ui opts) #js {:available false})}))

(defn- collector [] {:set-calls (atom []) :sent (atom []) :notes (atom [])})

(defn- ui-with-select [choice notes]
  #js {:available true
       :notify (fn [m _l] (swap! notes conj m))
       :select (fn [_q _opts] (js/Promise.resolve choice))})

;; ── config ────────────────────────────────────────────────────────────────
(describe "escalate:config"
          (fn []
            (it "defaults to ask, not auto — escalating spends money"
                (fn []
                  (-> (expect (esc/mode (esc/config {}))) (.toBe "ask"))))
            (it "merges nested keys field-wise so setting one keeps the rest"
                (fn []
                  (let [c (esc/config {:escalate {:on {:no-op-turns 5}}})]
                    (-> (expect (:no-op-turns (:on c))) (.toBe 5))
                    (-> (expect (:verify-exhausted (:on c))) (.toBe true))
                    (-> (expect (:max-per-session c)) (.toBe 2)))))))

;; ── provider errors ───────────────────────────────────────────────────────
(describe "escalate:error-kind"
          (fn []
            (it "classifies quota / server / network / missing-model"
                (fn []
                  (-> (expect (esc/error-kind "HTTP 429 Too Many Requests")) (.toBe "quota"))
                  (-> (expect (esc/error-kind "503 service unavailable")) (.toBe "server"))
                  (-> (expect (esc/error-kind "fetch failed: ECONNRESET")) (.toBe "network"))
                  (-> (expect (esc/error-kind "model not found: nope")) (.toBe "not-found"))))
            (it "does not treat a real model error as retryable"
                (fn []
                  (-> (expect (esc/error-kind "invalid tool arguments")) (.toBeFalsy))
                  (-> (expect (esc/retryable? "context length exceeded")) (.toBeFalsy))))
            (it "walks the chain and stops when exhausted"
                (fn []
                  (-> (expect (esc/next-fallback ["a" "b"] #{})) (.toBe "a"))
                  (-> (expect (esc/next-fallback ["a" "b"] #{"a"})) (.toBe "b"))
                  (-> (expect (esc/next-fallback ["a" "b"] #{"a" "b"})) (.toBeFalsy))))))

(describe "escalate:failover"
          (fn []
            (it "puts the new model into st-config, not just config"
        ;; The retry re-uses the st-config object built before the error;
        ;; setModel alone mutates config.model, which the retry never reads.
                (fn []
                  (let [c   (collector)
                        st  (atom {:model "zen/cheap-1"})
                        api (fake-api st c)
                        cfg #js {:model #js {:id "zen/cheap-1"} :providerOptions #js {:thinking "high"}}
                        r   (esc/on-provider-error api #js {:message "429 rate limit" :config cfg})]
                    (-> (expect (and r (.-retry r))) (.toBe true))
                    (-> (expect (.-id (.-model cfg))) (.toBe "mm/m2.5"))
                    (-> (expect (vec @(:set-calls c))) (.toEqual ["mm/m2.5"]))
            ;; stale thinking options from the old provider would 400 the retry
                    (-> (expect (.-thinking (.-providerOptions cfg))) (.toBeUndefined)))))
            (it "names the model that FAILED, not the one it fell back to"
                (fn []
                  (let [c   (collector)
                        st  (atom {:model "zen/cheap-1"})
                        api (fake-api st (assoc c :ui #js {:available true
                                                           :notify (fn [m _l] (swap! (:notes c) conj m))}))]
                    (esc/on-provider-error api #js {:message "429" :config #js {}})
                    (-> (expect (first @(:notes c))) (.toContain "on zen/cheap-1")))))

            (it "leaves a non-retryable error alone"
                (fn []
                  (let [c   (collector)
                        st  (atom {:model "zen/cheap-1"})
                        r   (esc/on-provider-error (fake-api st c)
                                                   #js {:message "400 bad request" :config #js {}})]
                    (-> (expect r) (.toBeFalsy))
                    (-> (expect (count @(:set-calls c))) (.toBe 0)))))
            (it "rethrows (returns nothing) once every chain entry is spent"
                (fn []
                  (let [c   (collector)
                        st  (atom {:model "zen/cheap-1"
                                   :escalate-tried ["mm/m2.5" "yun/opus-5"]
                                   :escalate-tried-at (js/Date.now)})
                        r   (esc/on-provider-error (fake-api st c)
                                                   #js {:message "429" :config #js {}})]
                    (-> (expect r) (.toBeFalsy)))))

            (it "a spent chain becomes usable again once the cooldown lapses"
                ;; a provider that 429'd an hour ago has probably recovered
                (fn []
                  (let [c   (collector)
                        st  (atom {:model "zen/cheap-1"
                                   :escalate-tried ["mm/m2.5" "yun/opus-5"]
                                   :escalate-tried-at (- (js/Date.now) 3600000)})
                        r   (esc/on-provider-error (fake-api st c)
                                                   #js {:message "429" :config #js {}})]
                    (-> (expect (and r (.-retry r))) (.toBe true))
                    (-> (expect (vec @(:set-calls c))) (.toEqual ["mm/m2.5"])))))))

;; ── stall detection ───────────────────────────────────────────────────────
;; A state read lagged the event payload by a turn, so escalation fired on the
;; 4th consecutive no-op turn instead of the 3rd. auto mode so the assertion is
;; about the count, not the prompt. Awaited: turn_finalize handlers that return
;; a promise are awaited by the loop, which is what orders the prune before the
;; follow-queue drain.
(defn ^:async t-payload-count-wins []
  (let [c    (collector)
        ;; targets escalation, so skip the retry step that now precedes it
        auto (assoc settings :escalate {:mode "auto" :retries-before-escalate 0})
        st   (atom {:model "zen/cheap-1" :no-op-turns 0
                    :messages [{:role "user" :content "fix X"}
                               {:role "tool_call" :content "read"}]})
        api  (fake-api st (assoc c :settings auto))]
    (js-await (esc/on-turn-finalize api #js {:noOpTurns 3}))
    (-> (expect (:escalated-to @st)) (.toBe "yun/opus-5"))
    ;; and the request was re-delivered for the strong model to pick up
    (-> (expect (count @(:sent c))) (.toBe 1))))

(describe "escalate:stall-reason"
          (fn []
            (it "does NOT fire on a plain conversation (the false-positive case)"
        ;; loop.cljs resets :no-op-turns only on a turn that ran tools, so
        ;; question/answer/question/answer reaches 2 with nothing wrong.
                (fn []
                  (-> (expect (esc/stall-reason {:no-op-turns 2
                                                 :messages [{:role "user" :content "hi"}
                                                            {:role "assistant" :content "hello"}]}
                                                (esc/config {})))
                      (.toBeFalsy))))
            (it "does not fire at 3 either when no tool has run since the request"
                (fn []
                  (-> (expect (esc/stall-reason {:no-op-turns 3
                                                 :messages [{:role "user" :content "explain this"}
                                                            {:role "assistant" :content "sure"}]}
                                                (esc/config {})))
                      (.toBeFalsy))))
            (it "uses the count the loop reports, not the stale state read"
                t-payload-count-wins)

            (it "a payload below the threshold escalates nothing"
                (fn []
                  (let [c    (collector)
                        auto (assoc settings :escalate {:mode "auto"})
                        st   (atom {:model "zen/cheap-1" :no-op-turns 9
                                    :messages [{:role "user" :content "fix X"}
                                               {:role "tool_call" :content "read"}]})
                        api  (fake-api st (assoc c :settings auto))]
                    (esc/on-turn-finalize api #js {:noOpTurns 1})
                    (-> (expect (:escalated-to @st)) (.toBeFalsy)))))

            (it "fires at 3 no-op turns with a task in flight"
                (fn []
                  (-> (expect (esc/stall-reason {:no-op-turns 3
                                                 :messages [{:role "user" :content "fix X"}
                                                            {:role "tool_call" :content "read"}
                                                            {:role "assistant" :content "hmm"}]}
                                                (esc/config {})))
                      (.toContain "3 turns ran no tools"))))
            (it "fires when the verify gate is out of road"
                (fn []
                  (-> (expect (esc/stall-reason {:no-op-turns 0 :verify-exhausted true :messages []}
                                                (esc/config {})))
                      (.toContain "verify"))))))

;; ── pruning ───────────────────────────────────────────────────────────────
(describe "escalate:prune-tail"
          (fn []
            (it "drops the request AND the failed span, returning the request once"
                (fn []
                  (let [{:keys [messages request]}
                        (esc/prune-tail [{:role "user" :content "earlier"}
                                         {:role "assistant" :content "ok"}
                                         {:role "user" :content "fix X"}
                                         {:role "assistant" :content "trying"}
                                         {:role "tool_call" :content "bash"}
                                         {:role "tool_result" :content "boom"}
                                         {:role "assistant" :content "hmm"}]
                                        "note")]
                    (-> (expect request) (.toBe "fix X"))
                    (-> (expect (count messages)) (.toBe 3))
            ;; the request must NOT survive in context — it is re-delivered
                    (-> (expect (some (fn [m] (= (:content m) "fix X")) messages)) (.toBeFalsy))
            ;; alternation: the note is assistant, never a second user message
                    (-> (expect (:role (last messages))) (.toBe "assistant")))))
            (it "leaves a transcript with no user message alone"
                (fn []
                  (let [r (esc/prune-tail [{:role "assistant" :content "hi"}] "note")]
                    (-> (expect (:request r)) (.toBeFalsy))
                    (-> (expect (count (:messages r))) (.toBe 1)))))))

;; ── consent + application ─────────────────────────────────────────────────
;; Squint has no inline (fn ^:async ...), so async bodies are top-level defns.

(defn ^:async t-no-keeps-context []
  (let [c     (collector)
        st    (atom {:model "zen/cheap-1"
                     :messages [{:role "user" :content "fix X"}
                                {:role "assistant" :content "hmm"}]})
        api   (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "No — keep going" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalated-to @st)) (.toBeFalsy))
    (-> (expect (count (:messages @st))) (.toBe 2))
    (-> (expect (count @(:sent c))) (.toBe 0))))

(defn ^:async t-yes-prunes-swaps-redelivers []
  (let [c   (collector)
        st  (atom {:model "zen/cheap-1"
                   :messages [{:role "user" :content "fix X"}
                              {:role "tool_call" :content "read"}
                              {:role "assistant" :content "hmm"}]})
        api (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "3 turns ran no tools mid-task" false))
    (-> (expect (:escalated-to @st)) (.toBe "yun/opus-5"))
    (-> (expect (vec @(:set-calls c))) (.toEqual ["yun/opus-5"]))
    (-> (expect (count @(:sent c))) (.toBe 1))
    (-> (expect (first (first @(:sent c)))) (.toBe "fix X"))
    (-> (expect (second (first @(:sent c)))) (.toBe "followUp"))
    ;; the stalled span is gone, replaced by one assistant note
    (-> (expect (count (:messages @st))) (.toBe 1))
    (-> (expect (:role (first (:messages @st)))) (.toBe "assistant"))))

(defn ^:async t-never-disarms []
  (let [c   (collector)
        st  (atom {:model "zen/cheap-1" :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "No, and don't ask again this session"
                                                      (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalate-disarmed @st)) (.toBe true))))

(defn ^:async t-refuses-same-model []
  ;; ManagerWorker: weak-directs-weak scored BELOW the weak model alone.
  (let [c   (collector)
        st  (atom {:model "yun/opus-5" :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalated-to @st)) (.toBeFalsy))
    (-> (expect (count (:messages @st))) (.toBe 1))))

(defn ^:async t-respects-cap []
  (let [c   (collector)
        st  (atom {:model "zen/cheap-1" :escalations 2
                   :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalated-to @st)) (.toBeFalsy))))

(defn ^:async t-revert-restores-the-cheap-model []
  ;; Not just clearing the slot: config.model must actually go back, or the
  ;; session keeps spending on the expensive model silently. Settings here have
  ;; NO :base-model-spec and NO roles.default, so a role-table lookup returns
  ;; nil and only :escalated-from can answer.
  (let [c   (collector)
        bare {:roles {:advisor {:provider "yun" :model "opus-5"}}}
        st  (atom {:model "zen/cheap-1" :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :settings (no-retry bare)
                                  :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalated-to @st)) (.toBe "yun/opus-5"))
    (reset! (:set-calls c) [])
    (esc/on-user-message api nil)
    (-> (expect (:escalated-to @st)) (.toBeFalsy))
    (-> (expect (vec @(:set-calls c))) (.toEqual ["zen/cheap-1"]))))

(defn ^:async t-permissions-untouched []
  (let [c   (collector)
        st  (atom {:model "zen/cheap-1" :active-role "default"
                   :permission-mode "accept-edits"
                   :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:active-role @st)) (.toBe "default"))
    (-> (expect (:permission-mode @st)) (.toBe "accept-edits"))))

(describe "escalate:consent"
  (fn []
    (it "ask mode with no prompt available does NOT escalate"
        (fn []
          (let [c   (collector)
                st  (atom {:model "zen/cheap-1" :no-op-turns 3
                           :messages [{:role "user" :content "fix X"}
                                      {:role "tool_call" :content "read"}]})
                api (fake-api st c)]
            (esc/on-turn-finalize api nil)
            (-> (expect (:escalated-to @st)) (.toBeFalsy))
            (-> (expect (count @(:sent c))) (.toBe 0)))))
    (it "answering no leaves the context untouched" t-no-keeps-context)
    (it "yes prunes, swaps and re-delivers the request exactly once" t-yes-prunes-swaps-redelivers)
    (it "'don't ask again' disarms the session" t-never-disarms)))

(describe "escalate:guards"
  (fn []
    (it "refuses when the target is the model already running" t-refuses-same-model)
    (it "respects max-per-session" t-respects-cap)
    (it "never touches :active-role, allowed-tools or the permission mode" t-permissions-untouched)

    (it "stands down in plan mode — opusplan already owns the model"
        (fn []
          (let [c   (collector)
                st  (atom {:model "zen/cheap-1" :plan-mode true :no-op-turns 9
                           :messages [{:role "user" :content "fix X"}
                                      {:role "tool_call" :content "read"}]})
                api (fake-api st (assoc c :settings (no-retry settings) :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
            (esc/on-turn-finalize api nil)
            (-> (expect (:escalated-to @st)) (.toBeFalsy)))))

    (it "revert puts the ORIGINAL model back, not just the flag"
        t-revert-restores-the-cheap-model)

    (it "an unresolvable chain entry is skipped, not counted as a fallback"
        ;; setModel would fire and the retry would re-run on the same model
        ;; while the toast claimed a swap.
        (fn []
          (let [c   (collector)
                bad {:roles {:default {:provider "zen" :model "cheap-1"}}
                     :escalate {:fallback {:default ["typo-role"]}}}
                st  (atom {:model "zen/cheap-1"})
                r   (esc/on-provider-error (fake-api st (assoc c :settings bad))
                                           #js {:message "429" :config #js {}})]
            (-> (expect r) (.toBeFalsy))
            (-> (expect (count @(:set-calls c))) (.toBe 0)))))

    (it "reverts on the next user request (the task is the episode)"
        (fn []
          (let [c   (collector)
                st  (atom {:model "zen/cheap-1" :escalated-to "yun/opus-5" :active-role "default"})
                api (fake-api st c)]
            (esc/on-user-message api nil)
            (-> (expect (:escalated-to @st)) (.toBeFalsy)))))

    (it "a quiet session never resolves or swaps anything"
        (fn []
          (let [c   (collector)
                st  (atom {:model "zen/cheap-1" :no-op-turns 0 :messages []})
                api (fake-api st c)]
            (esc/on-turn-finalize api nil)
            (esc/on-resolve api nil)
            (-> (expect (count @(:set-calls c))) (.toBe 0)))))

    (it "while escalated, model_resolve keeps applying the target"
        (fn []
          (let [c   (collector)
                st  (atom {:escalated-to "yun/opus-5"})
                api (fake-api st c)]
            (esc/on-resolve api nil)
            (-> (expect (vec @(:set-calls c))) (.toEqual ["yun/opus-5"])))))))

;; ── the status marker ─────────────────────────────────────────────────────
(describe "escalate:status-segment"
  (fn []
    (it "shows the model, not a role — escalation never changes the role"
        (fn []
          (let [s (seg/render-escalated "yun/opus-5")]
            (-> (expect (:visible? s)) (.toBe true))
            (-> (expect (:content s)) (.toContain "opus-5")))))
    (it "is hidden when nothing is escalated"
        (fn []
          (-> (expect (:visible? (seg/render-escalated nil))) (.toBeFalsy))
          (-> (expect (:visible? (seg/render-escalated ""))) (.toBeFalsy))))))

;; ── retry before escalation (sequential refinement) ──────────────────────
;; Measured: qwen3.5-9b failed `transpose` under three scaffold configurations
;; and then passed it on the third independent attempt. Retrying the same model
;; from a clean context is worth doing before paying for a bigger one.
(defn ^:async t-retry-before-escalating []
  (let [c   (collector)
        auto (assoc settings :escalate {:mode "auto"})
        st  (atom {:model "zen/cheap-1"
                   :messages [{:role "user" :content "fix X"}
                              {:role "tool_call" :content "read"}
                              {:role "assistant" :content "broken"}]})
        api (fake-api st (assoc c :settings auto))]
    (js-await (esc/escalate! api "tests still failing" false))
    ;; same model, request re-delivered, nothing escalated
    (-> (expect (:escalated-to @st)) (.toBeFalsy))
    (-> (expect (:escalate-retries @st)) (.toBe 1))
    (-> (expect (count @(:set-calls c))) (.toBe 0))
    (-> (expect (first (first @(:sent c)))) (.toBe "fix X"))
    ;; the failed span is gone and the note explains why
    (-> (expect (count (:messages @st))) (.toBe 1))
    (-> (expect (:content (first (:messages @st)))) (.toContain "previous attempt failed"))))

(defn ^:async t-escalates-once-retries-are-spent []
  (let [c   (collector)
        auto (assoc settings :escalate {:mode "auto"})
        st  (atom {:model "zen/cheap-1" :escalate-retries 1
                   :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :settings auto))]
    (js-await (esc/escalate! api "tests still failing" false))
    (-> (expect (:escalated-to @st)) (.toBe "yun/opus-5"))))

(describe "escalate:retry-first"
  (fn []
    (it "retries the same model before escalating" t-retry-before-escalating)
    (it "escalates once the retry budget is spent" t-escalates-once-retries-are-spent)
    (it "a new user request refills the retry budget"
        (fn []
          (let [c  (collector)
                st (atom {:model "zen/cheap-1" :escalate-retries 1})
                api (fake-api st c)]
            (esc/on-user-message api nil)
            (-> (expect (:escalate-retries @st)) (.toBeFalsy)))))))

;; ── does the trigger actually reach the retry? ───────────────────────────
;; The retry has unit tests, but in a real benchmark run arm A scored exactly
;; what the no-retry control scored, on the same tasks. That is what this
;; checks: the verify_gate -> bus -> turn_finalize -> retry wiring, end to end.
(defn ^:async t-verify-exhausted-triggers-retry []
  (let [c    (collector)
        auto (assoc settings :escalate {:mode "auto" :retries-before-escalate 1})
        st   (atom {:model "zen/cheap-1"
                    :messages [{:role "user" :content "fix X"}
                               {:role "tool_call" :content "edit"}
                               {:role "assistant" :content "done?"}]})
        handlers (atom {})
        api  (fake-api st (assoc c :settings auto))]
    ;; capture handlers the way the real activation registers them
    (aset api "on" (fn [ev h & _] (swap! handlers assoc ev h)))
    (aset api "off" (fn [& _] nil))
    (aset api "registerCommand" (fn [& _] nil))
    (aset api "unregisterCommand" (fn [& _] nil))
    (esc/activate api)
    ;; verify_gate emits this once its fix attempts are spent
    ((get @handlers "small-model/verify-exhausted") #js {:reason "cmd"} nil)
    ;; the loop then finalises the turn
    (js-await ((get @handlers "turn_finalize") #js {:error false :noOpTurns 0}))
    (-> (expect (:escalate-retries @st)) (.toBe 1))
    (-> (expect (count @(:sent c))) (.toBe 1))))

(describe "escalate:trigger-wiring"
  (fn []
    (it "a spent verify gate reaches the retry" t-verify-exhausted-triggers-retry)))

;;; ─── chain-for is what /escalate status now reports ────────────────────────

(describe "escalate:chain visibility"
          (fn []
            (it "resolves the configured chain for the active role"
        ;; `/escalate status` printed mode/target/active/used but never the
        ;; FAILOVER CHAIN — so a configured chain and the empty default looked
        ;; identical, and an empty chain fails exactly like a missing one
        ;; (rethrow, turn dies). The status line now renders this.
                (fn []
                  (let [cfg (esc/config settings)]
                    (-> (expect (esc/chain-for cfg "default")) (.toEqual #js ["build" "advisor"])))))

            (it "reports an empty chain when nothing is configured"
                (fn []
                  (let [cfg (esc/config {:roles {}})]
                    (-> (expect (count (esc/chain-for cfg "default"))) (.toBe 0)))))

            (it "falls back to the default chain for an unlisted role"
                (fn []
                  (let [cfg (esc/config settings)]
                    (-> (expect (esc/chain-for cfg "some-other-role"))
                        (.toEqual #js ["build" "advisor"])))))

            (it "prefers a role-specific chain over the default"
                (fn []
                  (let [cfg (esc/config
                             (assoc settings :escalate
                                    {:fallback {:default ["build"] :plan ["advisor"]}}))]
                    (-> (expect (esc/chain-for cfg "plan")) (.toEqual #js ["advisor"]))
                    (-> (expect (esc/chain-for cfg "default")) (.toEqual #js ["build"])))))))

;;; ─── A failed switch must not be reported as a success ─────────────────────
;;
;; `(try (.setModel api spec) (catch :default _e nil))` appeared at five sites,
;; each followed by an assertion that it worked. setModel returns nil always, so
;; throwing is its only signal — and the realistic throw is a missing credential
;; for the target provider, exactly what an escalation chain walks into.
;;
;; The costs differed per site: apply-escalation! burned a slot against
;; max-per-session and re-delivered the request to the model that had just
;; stalled; revert! left config.model on the EXPENSIVE model for the rest of the
;; session while the state said "not escalated", which is the leak the function
;; exists to prevent.

(defn- api-that [f]
  (let [calls (atom [])]
    {:calls calls
     :api #js {:setModel (fn [spec] (swap! calls conj spec) (f spec))}}))

(describe "escalate/try-set-model!" (fn []

  (it "reports success when setModel returns"
      (fn []
        (let [{:keys [api calls]} (api-that (fn [_] nil))]
          (-> (expect (esc/try-set-model! api "p/m")) (.toBe true))
          (-> (expect (vec @calls)) (.toEqual #js ["p/m"])))))

  (it "reports failure instead of swallowing"
      (fn []
        ;; The credential case: provider has no key, create-model throws.
        (let [{:keys [api]} (api-that (fn [_] (throw (js/Error. "No credentials for provider 'x'"))))]
          (-> (expect (esc/try-set-model! api "x/m")) (.toBe false)))))

  (it "does not throw out of the handler it runs in"
      (fn []
        ;; on-resolve calls this mid-turn; a throw there would take the turn
        ;; down. It must convert the failure into a return value.
        (let [{:keys [api]} (api-that (fn [_] (throw (js/Error. "boom"))))]
          (-> (expect (fn? (fn [] (esc/try-set-model! api "x/m")))) (.toBe true))
          (-> (expect (esc/try-set-model! api "x/m")) (.toBe false)))))))

;;; ─── A self-hosted box being switched off must escalate ────────────────────
;;
;; `error-kind` classified :network on econnreset/etimedout/enotfound/etc, none
;; of which Bun produces. Its fetch reports BOTH a refused port and a dead host
;; as `TypeError: Unable to connect. Is the computer able to access the url?`
;; with an empty cause — verified against a closed port and an unroutable host.
;;
;; So a `local` role pointed at a LAN vllm box would, the moment the box was
;; off, yield error-kind nil → retryable? false → no failover. The configured
;; fallback chain was decorative, and the run died with a provider error instead
;; of switching to the paid model.

(def ^:private bun-connect-failure
  "Verbatim from Bun for a refused port AND an unreachable host."
  "TypeError: Unable to connect. Is the computer able to access the url?")

(describe "escalate: unreachable self-hosted provider" (fn []

  (it "classifies Bun's connect failure as a network error"
      (fn []
        ;; squint compiles keywords to bare strings — the same fact behind the
        ;; roles.default guard, so no leading colon here.
        (-> (expect (str (esc/error-kind bun-connect-failure))) (.toBe "network"))
        (-> (expect (esc/retryable? bun-connect-failure)) (.toBe true))))

  (it "classifies the Node wording too"
      (fn []
        (-> (expect (esc/retryable? "connect ECONNREFUSED 192.168.14.15:8000")) (.toBe true))
        (-> (expect (esc/retryable? "TypeError: fetch failed")) (.toBe true))))

  (it "still ignores an error no other model would survive"
      (fn []
        ;; retryable? must stay narrow — escalating on a genuine 400 would just
        ;; burn the chain on a request that is wrong everywhere.
        (-> (expect (esc/retryable? "400 invalid request: bad tool schema")) (.toBe false))
        (-> (expect (esc/retryable? "")) (.toBe false))))

  (it "picks the configured chain for the failing role"
      (fn []
        ;; roles.local -> escalate.fallback.local = ["build"], so a dead box
        ;; moves to minimax rather than retrying the box.
        (let [cfg {:fallback {"local" ["build"] :default ["fast"]}}]
          (-> (expect (vec (esc/chain-for cfg "local"))) (.toEqual #js ["build"]))
          ;; …and an unlisted role still gets the default chain.
          (-> (expect (vec (esc/chain-for cfg "other"))) (.toEqual #js ["fast"])))))

  (it "does not re-try a target already burned"
      (fn []
        (-> (expect (esc/next-fallback ["build" "fast"] ["build"])) (.toBe "fast"))
        (-> (expect (esc/next-fallback ["build"] ["build"])) (.toBeNil))))))
