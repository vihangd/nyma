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
                                    :on  (fn [evt h] (swap! handlers assoc evt h))
                                    :off (fn [evt _] (swap! handlers dissoc evt))}]
                  (b/activate api)
                  (-> (expect (count @handlers)) (.toBe 0))))))
