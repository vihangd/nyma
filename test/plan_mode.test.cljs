(ns plan-mode.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.model-roles.features.plan-mode :as pm]
            [agent.settings.manager :refer [defaults]]
            [agent.core :refer [create-agent]]))

(def ^:private plan-text
  "Here is the plan.\n\nPlan:\n1. Read the loop\n2. Add the hook\n3. Write tests\nDone.")

(describe "plan-mode:extract-todos" (fn []
                                      (it "extracts numbered steps"
                                          (fn []
                                            (let [todos (pm/extract-todos plan-text)]
                                              (-> (expect (count todos)) (.toBe 3))
                                              (-> (expect (:step (first todos))) (.toBe 1))
                                              (-> (expect (:text (first todos))) (.toBe "Read the loop"))
                                              (-> (expect (:completed (first todos))) (.toBe false)))))

                                      (it "returns empty for prose with no numbered list"
                                          (fn []
                                            (-> (expect (count (pm/extract-todos "no steps here"))) (.toBe 0))))

                                      (it "handles n) style numbering"
                                          (fn []
                                            (-> (expect (count (pm/extract-todos "1) one\n2) two"))) (.toBe 2))))))

(describe "plan-mode:mark-done" (fn []
                                  (it "marks steps referenced by [DONE:n]"
                                      (fn []
                                        (let [todos (pm/extract-todos plan-text)
                                              marked (pm/mark-done todos "Finished step one [DONE:1] and [DONE:3].")]
                                          (-> (expect (:completed (nth marked 0))) (.toBe true))
                                          (-> (expect (:completed (nth marked 1))) (.toBe false))
                                          (-> (expect (:completed (nth marked 2))) (.toBe true)))))

                                  (it "no markers leaves todos untouched"
                                      (fn []
                                        (let [todos (pm/extract-todos plan-text)
                                              marked (pm/mark-done todos "nothing done yet")]
                                          (-> (expect (some :completed marked)) (.toBeFalsy)))))))

;; Regression: leaving plan mode must restore the prior role's model.
;; model_roles' model_resolve SKIPS :default, so plan mode must compute
;; the spec itself — otherwise execution strands on the plan (opus) model.
(describe "plan-mode:role-model-spec" (fn []
  (it "produces a spec for :default (the bug — must NOT be nil)"
    (fn []
      (-> (expect (pm/role-model-spec defaults :default))
          (.toBe "anthropic/claude-sonnet-4-20250514"))))

  (it "default restore differs from the plan (opus) model"
    (fn []
      (-> (expect (pm/role-model-spec defaults :default))
          (.not.toBe (pm/role-model-spec defaults :plan)))))

  (it "tolerates string-keyed settings (user JSON)"
    (fn []
      (let [s #js {"roles" #js {"default" #js {"provider" "openai" "model" "gpt-5"}}}]
        (-> (expect (pm/role-model-spec s "default")) (.toBe "openai/gpt-5")))))

  (it "returns nil for an unknown role"
    (fn []
      (-> (expect (pm/role-model-spec defaults :nope)) (.toBeNil))))))

;; Isolation property the subagent relies on: a freshly created agent
;; (the shape used for a child) has no session attached and its tool set
;; can be restricted to a read-only subset.
(describe "subagent:isolation-properties" (fn []
                                            (it "a new agent has nil session (no writes to parent JSONL)"
                                                (fn []
                                                  (let [child (create-agent {:model "test" :system-prompt "x"})]
                                                    (-> (expect @(:session child)) (.toBeNull)))))

                                            (it "set-active restricts the child's active tools to a subset"
                                                (fn []
                                                  (let [child (create-agent {:model "test" :system-prompt "x"})]
                                                    ((:set-active (:tool-registry child)) #{"read" "grep"})
                                                    (let [active (set (keys ((:get-active (:tool-registry child)))))]
                                                      (-> (expect (contains? active "read")) (.toBe true))
                                                      (-> (expect (contains? active "write")) (.toBe false))
                                                      (-> (expect (contains? active "edit")) (.toBe false))))))))

;; ── B1 regression: step->text reads the AI-SDK step (.text/.content) ──
(describe "plan-mode:step->text"
          (fn []
            (it "reads .text from a step payload"
                (fn []
                  (-> (expect (pm/step->text #js {:text "did it [DONE:1]"}))
                      (.toBe "did it [DONE:1]"))))
            (it "reads array .content when .text is absent"
                (fn []
                  (-> (expect (pm/step->text #js {:content #js [#js {:type "text" :text "hi [DONE:2]"}]}))
                      (.toContain "[DONE:2]"))))
            (it "falls back to a {message} wrapper"
                (fn []
                  (-> (expect (pm/step->text #js {:message #js {:content "yo [DONE:3]"}}))
                      (.toContain "[DONE:3]"))))))

;; ── plan-mode state transitions via a stub api ──
(defn- stub-api [st sent]
  (let [settings {:roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
                          :plan    {:provider "anthropic" :model "claude-opus-4-20250514"}}
                  :plan-mode {:auto-approve false}}]
    #js {:__state_atom    st
         :getState        (fn [] @st)
         :getSettings     (fn [] settings)
         :setModel        (fn [_m] nil)
         :sendUserMessage (fn [t _o] (swap! sent conj t))
         :ui              #js {:available false}}))

(describe "plan-mode:transitions"
          (fn []
            (it "enter! engages the :plan role and remembers the prior role"
                (fn []
                  (let [st (atom {:active-role :default :messages []})
                        api (stub-api st (atom []))]
                    (pm/enter! api)
                    (-> (expect (:plan-mode @st)) (.toBe true))
                    (-> (expect (:active-role @st)) (.toBe :plan))
                    (-> (expect (:plan-prev-role @st)) (.toBe :default)))))

            (it "execute! restores the prior role and sends a follow-up"
                (fn []
                  (let [st (atom {:active-role :plan :plan-mode true
                                  :plan-prev-role :default
                                  :plan-todos [{:step 1 :text "do x" :completed false}]})
                        sent (atom [])
                        api (stub-api st sent)]
                    (pm/execute! api)
                    (-> (expect (:plan-mode @st)) (.toBe false))
                    (-> (expect (:active-role @st)) (.toBe :default))
                    (-> (expect (:plan-executing @st)) (.toBe true))
                    (-> (expect (count @sent)) (.toBe 1))
                    (-> (expect (first @sent)) (.toContain "do x")))))

            (it "cancel! leaves plan mode without executing"
                (fn []
                  (let [st (atom {:active-role :plan :plan-mode true :plan-prev-role :default})
                        sent (atom [])
                        api (stub-api st sent)]
                    (pm/cancel! api)
                    (-> (expect (:plan-mode @st)) (.toBe false))
                    (-> (expect (:active-role @st)) (.toBe :default))
                    (-> (expect (count @sent)) (.toBe 0)))))))

;; ── /plan guard: only register /plan when no extension owns a *__plan ──
(defn- guard-api [cmds registered]
  #js {:getCommands       (fn [] @cmds)
       :registerCommand   (fn [n o] (swap! registered assoc n o))
       :unregisterCommand (fn [_n] nil)
       :on                (fn [_e _h] nil)
       :off               (fn [_e _h] nil)})

(describe "plan-mode:plan-command-guard"
          (fn []
            (it "registers /plan when no extension owns a __plan command"
                (fn []
                  (let [registered (atom {})
                        api (guard-api (atom {"model-roles__role" {}}) registered)]
                    (pm/activate api)
                    (-> (expect (contains? @registered "planmode")) (.toBe true))
                    (-> (expect (contains? @registered "plan")) (.toBe true)))))

            (it "skips /plan when agent_shell already owns a __plan command"
                (fn []
                  (let [registered (atom {})
                        api (guard-api (atom {"agent-shell__plan" {}}) registered)]
                    (pm/activate api)
                    (-> (expect (contains? @registered "planmode")) (.toBe true))
                    (-> (expect (contains? @registered "plan")) (.toBe false)))))))

;; ── opusplan: planner-model override ──
(def ^:private oc-settings
  {:roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
           :deep    {:provider "anthropic" :model "claude-opus-deep"}
           :advisor {:provider "anthropic" :model "claude-opus-advisor"}
           :plan    {:provider "anthropic" :model "claude-opus-plan"
                     :allowed-tools ["read"]}}
   :plan-mode {:auto-approve false :planner-role :advisor}})

(defn- oc-api [st settings set-calls]
  #js {:__state_atom st
       :getState     (fn [] @st)
       :getSettings  (fn [] settings)
       :setModel     (fn [m] (swap! set-calls conj m))
       :ui           #js {:available false}})

(describe "plan-mode:planner-spec"
          (fn []
            (it "defaults to the :advisor role's model"
                (fn []
                  (-> (expect (pm/planner-spec oc-settings)) (.toBe "anthropic/claude-opus-advisor"))))
            (it "falls back to :deep when advisor is unset"
                (fn []
                  (let [s (assoc-in oc-settings [:roles :advisor] nil)]
                    (-> (expect (pm/planner-spec s)) (.toBe "anthropic/claude-opus-deep")))))
            (it "honors an explicit planner-role (:plan → :plan role's model)"
                (fn []
                  (let [s (assoc-in oc-settings [:plan-mode :planner-role] :plan)]
                    (-> (expect (pm/planner-spec s)) (.toBe "anthropic/claude-opus-plan")))))
            (it "returns nil when planner-role is false (no switch)"
                (fn []
                  (let [s (assoc-in oc-settings [:plan-mode :planner-role] false)]
                    (-> (expect (pm/planner-spec s)) (.toBeNil)))))
            (it "defaults to :advisor when planner-role key is absent"
                (fn []
                  (let [s (assoc oc-settings :plan-mode {:auto-approve false})]
                    (-> (expect (pm/planner-spec s)) (.toBe "anthropic/claude-opus-advisor")))))))

(describe "plan-mode:on-plan-resolve"
          (fn []
            (it "applies the planner model while in plan mode"
                (fn []
                  (let [calls (atom [])
                        api (oc-api (atom {:plan-mode true}) oc-settings calls)]
                    (pm/on-plan-resolve api nil)
                    (-> (expect (vec @calls)) (.toEqual ["anthropic/claude-opus-advisor"])))))
            (it "does nothing when not in plan mode (execution / switch-back)"
                (fn []
                  (let [calls (atom [])
                        api (oc-api (atom {:plan-mode false}) oc-settings calls)]
                    (pm/on-plan-resolve api nil)
                    (-> (expect (count @calls)) (.toBe 0)))))
            (it "does nothing once the planner is marked unusable (flaw A fallback)"
                (fn []
                  (let [calls (atom [])
                        api (oc-api (atom {:plan-mode true :plan-planner-unusable true}) oc-settings calls)]
                    (pm/on-plan-resolve api nil)
                    (-> (expect (count @calls)) (.toBe 0)))))))

(describe "plan-mode:provider-error-fallback"
          (fn []
            (it "marks planner unusable + retries on an auth error in plan mode"
                (fn []
                  (let [st (atom {:plan-mode true})
                        api (oc-api st oc-settings (atom []))
                        r  (pm/on-plan-provider-error api #js {:message "401 Unauthorized: invalid api key"})]
                    (-> (expect (:plan-planner-unusable @st)) (.toBe true))
                    (-> (expect (and r (.-retry r))) (.toBe true)))))
            (it "ignores a non-auth error (does not disable the planner)"
                (fn []
                  (let [st (atom {:plan-mode true})
                        api (oc-api st oc-settings (atom []))
                        r  (pm/on-plan-provider-error api #js {:message "500 server overloaded"})]
                    (-> (expect (:plan-planner-unusable @st)) (.toBeFalsy))
                    (-> (expect r) (.toBeFalsy)))))
            (it "ignores errors when not in plan mode"
                (fn []
                  (let [st (atom {:plan-mode false})
                        api (oc-api st oc-settings (atom []))
                        r  (pm/on-plan-provider-error api #js {:message "401 unauthorized"})]
                    (-> (expect r) (.toBeFalsy)))))))

;; flaw D — priority ordering: the -10 handler is the last writer
(describe "plan-mode:model-resolve-ordering"
          (fn []
            (it "a lower-priority model_resolve handler runs last (planner wins)"
                (fn []
                  (let [agent (create-agent {:model "test" :system-prompt "x"})
                        order (atom [])]
                    ((:on (:events agent)) "model_resolve" (fn [_] (swap! order conj "roles") nil) 0)
                    ((:on (:events agent)) "model_resolve" (fn [_] (swap! order conj "plan") nil) -10)
                    (-> ((:emit-collect (:events agent)) "model_resolve" #js {})
                        (.then (fn [_] (-> (expect (last @order)) (.toBe "plan"))))))))))
