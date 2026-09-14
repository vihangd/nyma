(ns budget.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.budget.shared :as shared]
            [agent.extensions.budget.index :as b]))

(describe "budget shared" (fn [])
          (it "off unless a cap is configured"
              (fn []
                (-> (expect (shared/enabled? (shared/config nil))) (.toBe false))
                (-> (expect (shared/enabled? (shared/config #js {:budget #js {:turn-tokens 100}}))) (.toBe true))))

          (it "over-budget picks the first exceeded cap"
              (fn []
                (let [cfg {:turn-tokens 100 :session-tokens 1000}]
                  (-> (expect (shared/over-budget cfg {:turn 50 :session 500})) (.toBeFalsy))
                  (-> (expect (shared/over-budget cfg {:turn 150 :session 500})) (.toBe :turn))
                  (-> (expect (shared/over-budget cfg {:turn 50 :session 1500})) (.toBe :session))))))

(describe "budget extension" (fn [])
          (it "aborts the run when the turn cap is exceeded"
              (fn []
                (let [handlers (atom {})
                      aborted  (atom false)
                      api      #js {:getSettings (fn [] #js {:budget #js {:turn-tokens 100}})
                                    :settings (fn [sec] (let [all #js {:budget #js {:turn-tokens 100}}] (if sec (or (get all sec) {}) (or all {}))))
                                    :on  (fn [evt h] (swap! handlers assoc evt h))
                                    :off (fn [evt _] (swap! handlers dissoc evt))}
                      _        (b/activate api)
                      ctx      #js {:abort (fn [] (reset! aborted true))}]
                  ((get @handlers "before_agent_start") #js {} ctx)
                  ((get @handlers "turn_end")
                   #js {:usage #js {:inputTokens 80 :outputTokens 10}} ctx)
                  (-> (expect @aborted) (.toBe false))
                  ((get @handlers "turn_end")
                   #js {:usage #js {:inputTokens 80 :outputTokens 10}} ctx)
                  (-> (expect @aborted) (.toBe true)))))

          (it "turn counter resets per run; session counter persists"
              (fn []
                (let [handlers (atom {})
                      aborted  (atom 0)
                      api      #js {:getSettings (fn [] #js {:budget #js {:turn-tokens 100 :session-tokens 250}})
                                    :settings (fn [sec] (let [all #js {:budget #js {:turn-tokens 100 :session-tokens 250}}] (if sec (or (get all sec) {}) (or all {}))))
                                    :on  (fn [evt h] (swap! handlers assoc evt h))
                                    :off (fn [evt _] (swap! handlers dissoc evt))}
                      _        (b/activate api)
                      ctx      #js {:abort (fn [] (swap! aborted inc))}
                      use!     (fn [n] ((get @handlers "turn_end")
                                        #js {:usage #js {:inputTokens n :outputTokens 0}} ctx))]
                  ((get @handlers "before_agent_start") #js {} ctx)
                  (use! 90)
                  ((get @handlers "before_agent_start") #js {} ctx)   ;; new turn
                  (use! 90)                                            ;; turn 90 ok, session 180 ok
                  (-> (expect @aborted) (.toBe 0))
                  ((get @handlers "before_agent_start") #js {} ctx)
                  (use! 90)                                            ;; session 270 > 250
                  (-> (expect @aborted) (.toBe 1)))))

          (it "registers nothing when unconfigured"
              (fn []
                (let [handlers (atom {})
                      api      #js {:getSettings (fn [] #js {})
                                    :settings (fn [sec] (let [all #js {}] (if sec (or (get all sec) {}) (or all {}))))
                                    :on  (fn [evt h] (swap! handlers assoc evt h))
                                    :off (fn [evt _] (swap! handlers dissoc evt))}]
                  (b/activate api)
                  (-> (expect (count @handlers)) (.toBe 0))))))

;;; ─── Wall clock ─────────────────────────────────────────────
;;; A run wedged inside one provider call finishes no step, so the token checks
;;; on turn_end never run. The wall-clock cap is a timer for exactly that case.

(defn ^:async t-wall-clock-aborts-a-run-with-no-steps []
  (let [handlers (atom {})
        aborted  (atom false)
        api      #js {:getSettings (fn [] #js {:budget #js {:wall-seconds 0.05}})
                      :settings (fn [sec] (let [all #js {:budget #js {:wall-seconds 0.05}}] (if sec (or (get all sec) {}) (or all {}))))
                      :on  (fn [evt h] (swap! handlers assoc evt h))
                      :off (fn [evt _] (swap! handlers dissoc evt))}
        _        (b/activate api)
        ctx      #js {:abort (fn [] (reset! aborted true))}]
    ((get @handlers "before_agent_start") #js {} ctx)
    ;; No turn_end ever fires — the provider is hung.
    (js-await (js/Promise. (fn [res _] (js/setTimeout res 90))))
    (-> (expect @aborted) (.toBe true))))

(defn ^:async t-wall-clock-disarms-on-agent-end []
  (let [handlers (atom {})
        aborted  (atom false)
        api      #js {:getSettings (fn [] #js {:budget #js {:wall-seconds 0.05}})
                      :settings (fn [sec] (let [all #js {:budget #js {:wall-seconds 0.05}}] (if sec (or (get all sec) {}) (or all {}))))
                      :on  (fn [evt h] (swap! handlers assoc evt h))
                      :off (fn [evt _] (swap! handlers dissoc evt))}
        _        (b/activate api)
        ctx      #js {:abort (fn [] (reset! aborted true))}]
    ((get @handlers "before_agent_start") #js {} ctx)
    ((get @handlers "agent_end") #js {} ctx)
    (js-await (js/Promise. (fn [res _] (js/setTimeout res 90))))
    (-> (expect @aborted) (.toBe false))))

(describe "budget wall clock" (fn [])
          (it "config carries wall-seconds and turns the extension on"
              (fn []
                (let [cfg (shared/config #js {:budget #js {:wall-seconds 900}})]
                  (-> (expect (:wall-seconds cfg)) (.toBe 900))
                  (-> (expect (shared/enabled? cfg)) (.toBe true)))))

          ;; It is NOT part of over-budget: that runs on turn_end, which a hung
          ;; run never reaches.
          (it "stays out of the token check"
              (fn []
                (-> (expect (shared/over-budget {:wall-seconds 1} {:turn 0 :session 0}))
                    (.toBeFalsy))))

          (it "aborts a run that never finishes a step" t-wall-clock-aborts-a-run-with-no-steps)
          (it "disarms when the run ends normally" t-wall-clock-disarms-on-agent-end)

          (it "arms nothing when unset"
              (fn []
                (let [handlers (atom {})
                      api      #js {:getSettings (fn [] #js {:budget #js {:turn-tokens 100}})
                                    :settings (fn [sec] (let [all #js {:budget #js {:turn-tokens 100}}] (if sec (or (get all sec) {}) (or all {}))))
                                    :on  (fn [evt h] (swap! handlers assoc evt h))
                                    :off (fn [evt _] (swap! handlers dissoc evt))}
                      _        (b/activate api)
                      ctx      #js {:abort (fn [] (throw (js/Error. "must not abort")))}]
                  ;; No throw = no timer was armed.
                  ((get @handlers "before_agent_start") #js {} ctx)
                  (-> (expect true) (.toBe true))))))
