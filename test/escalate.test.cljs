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
                        st  (atom {:model "zen/cheap-1" :escalate-tried ["mm/m2.5" "yun/opus-5"]})
                        r   (esc/on-provider-error (fake-api st c)
                                                   #js {:message "429" :config #js {}})]
                    (-> (expect r) (.toBeFalsy)))))))

;; ── stall detection ───────────────────────────────────────────────────────
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
        api   (fake-api st (assoc c :ui (ui-with-select "No — keep going" (:notes c))))]
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
        api (fake-api st (assoc c :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
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
        api (fake-api st (assoc c :ui (ui-with-select "No, and don't ask again this session"
                                                      (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalate-disarmed @st)) (.toBe true))))

(defn ^:async t-refuses-same-model []
  ;; ManagerWorker: weak-directs-weak scored BELOW the weak model alone.
  (let [c   (collector)
        st  (atom {:model "yun/opus-5" :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalated-to @st)) (.toBeFalsy))
    (-> (expect (count (:messages @st))) (.toBe 1))))

(defn ^:async t-respects-cap []
  (let [c   (collector)
        st  (atom {:model "zen/cheap-1" :escalations 2
                   :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
    (js-await (esc/escalate! api "stalled" false))
    (-> (expect (:escalated-to @st)) (.toBeFalsy))))

(defn ^:async t-permissions-untouched []
  (let [c   (collector)
        st  (atom {:model "zen/cheap-1" :active-role "default"
                   :permission-mode "accept-edits"
                   :messages [{:role "user" :content "fix X"}]})
        api (fake-api st (assoc c :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
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
                api (fake-api st (assoc c :ui (ui-with-select "Yes — escalate and retry" (:notes c))))]
            (esc/on-turn-finalize api nil)
            (-> (expect (:escalated-to @st)) (.toBeFalsy)))))

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
