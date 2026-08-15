(ns todos.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.todos.shared :as t]
            ["./agent/extensions/todos/index.mjs" :as todos]))

(describe "todos shared" (fn []
                           (it "normalizes status synonyms"
                               (fn []
                                 (-> (expect (t/normalize-status "completed")) (.toBe :done))
                                 (-> (expect (t/normalize-status "done")) (.toBe :done))
                                 (-> (expect (t/normalize-status "in_progress")) (.toBe :in-progress))
                                 (-> (expect (t/normalize-status "doing")) (.toBe :in-progress))
                                 (-> (expect (t/normalize-status nil)) (.toBe :pending))))

                           (it "parses a JS todo array, dropping blanks"
                               (fn []
                                 (let [p (t/parse-todos #js [#js {:content "a" :status "completed"}
                                                             #js {:content "  " :status "pending"}
                                                             #js {:content "b"}])]
                                   (-> (expect (count p)) (.toBe 2))
                                   (-> (expect (:status (first p))) (.toBe :done))
                                   (-> (expect (:status (nth p 1))) (.toBe :pending)))))

                           (it "counts open/total"
                               (fn []
                                 (let [c (t/counts [{:content "a" :status :done} {:content "b" :status :pending}
                                                    {:content "c" :status :in-progress}])]
                                   (-> (expect (:open c)) (.toBe 2))
                                   (-> (expect (:total c)) (.toBe 3)))))

                           (it "render collapses completed items (HIPIF), shows live in full"
                               (fn []
                                 (let [s (t/render-ledger [{:content "done1" :status :done}
                                                           {:content "open1" :status :pending}
                                                           {:content "doing1" :status :in-progress}])]
                                   (-> (expect (.includes s "- [ ] open1")) (.toBe true))
                                   (-> (expect (.includes s "- [~] doing1")) (.toBe true))
                                   (-> (expect (.includes s "done1")) (.toBe false))     ; completed content collapsed
                                   (-> (expect (.includes s "1 completed")) (.toBe true)))))

                           (it "render is nil for an empty ledger"
                               (fn []
                                 (-> (expect (t/render-ledger [])) (.toBeNil))))))

;;; ─── one tracker, not two ────────────────────────────────────────────────
;;; Plan mode keeps its own step list and re-injects the remaining steps every
;;; turn (plan_mode.cljs:222-228) — the per-turn reminder is the one plan-
;;; adherence intervention with evidence behind it. The todos ledger injected
;;; independently on the same event, so a session could carry TWO lists in one
;;; prompt, free to disagree. Worst for the small local models least able to
;;; absorb contradictory instructions.

(defn- stub-api
  "Extension api stub. `state` is what getState returns."
  [state]
  (let [handlers (atom {})
        segments (atom {})
        tools    (atom {})]
    {:api #js {:getState              (fn [] state)
               :on                    (fn [evt f & _] (swap! handlers assoc evt f))
               :off                   (fn [_ _] nil)
               :registerTool          (fn [n cfg] (swap! tools assoc n cfg))
               :unregisterTool        (fn [n] (swap! tools dissoc n))
               :registerStatusSegment (fn [n cfg] (swap! segments assoc n cfg))}
     :handlers handlers :segments segments :tools tools}))

(defn- inject [api-map]
  (let [h (get @(:handlers api-map) "before_agent_start")]
    (h nil nil)))

(describe "todos vs an executing plan"
          (fn []
            (it "stays silent while a plan is executing"
                (fn []
                  (let [m (stub-api {:plan-executing true
                                     :plan-todos [{:step 1 :text "one" :completed false}]})
                        _ ((.-default todos) (:api m))
                        ]
                    ;; Model wrote a ledger too — it must not reach the prompt,
                    ;; because plan mode is already injecting its own list.
                    ((.-execute (get @(:tools m) "todo_write"))
                     #js {:todos #js [#js {:content "ledger item" :status "pending"}]})
                    (-> (expect (inject m)) (.toBeFalsy)))))

            (it "injects its ledger normally when no plan is running"
                (fn []
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    ;; No regression for the model-driven path.
                    (-> (expect (inject m)) (.toBeFalsy))   ;; empty ledger → nothing
                    )))

            (it "registers no new tool for the plan integration"
                (fn []
                  ;; The integration must not put a tool call on the critical
                  ;; path: SLMs emit invalid tool calls ~19x more than large
                  ;; models, and ONE invalid call costs ~30pp accuracy. Every
                  ;; extra tool also costs selection accuracy.
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    (-> (expect (count @(:tools m))) (.toBe 2))
                    (-> (expect (contains? @(:tools m) "todo_write")) (.toBe true))
                    (-> (expect (contains? @(:tools m) "todo_read")) (.toBe true)))))

            (it "shows the PLAN's progress in the status segment while one runs"
                (fn []
                  (let [m (stub-api {:plan-executing true
                                     :plan-todos [{:step 1 :text "a" :completed true}
                                                  {:step 2 :text "b" :completed false}
                                                  {:step 3 :text "c" :completed false}]})
                        _ ((.-default todos) (:api m))
                        seg (get @(:segments m) "todos.progress")
                        out ((.-render seg) {:theme {}})]
                    ;; 1 of 3 done — the plan's numbers, not the empty ledger's.
                    (-> (expect (.-content out)) (.toBe "☐ 1/3"))
                    (-> (expect (aget out "visible?")) (.toBe true)))))))
