(ns subagent-settings.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.settings.manager :refer [defaults]]
            [agent.extensions.subagent.index :refer [run-isolated-agent]]))

(def ^:private read-only-roles [:scout :planner :reviewer :researcher])

(describe "subagent:settings-roles" (fn []
                                      (it "ships scout/planner/reviewer/researcher/worker roles"
                                          (fn []
                                            (let [roles (:roles defaults)]
                                              (doseq [r [:scout :planner :reviewer :researcher :worker]]
                                                (-> (expect (contains? roles r)) (.toBe true))))))

                                      (it "default subagent roles are READ-ONLY (no edit/write/bash)"
                                          (fn []
                                            (let [roles (:roles defaults)]
                                              (doseq [r read-only-roles]
                                                (let [allowed (set (:allowed-tools (get roles r)))]
                                                  (-> (expect (contains? allowed "edit"))  (.toBe false))
                                                  (-> (expect (contains? allowed "write")) (.toBe false))
                                                  (-> (expect (contains? allowed "bash"))  (.toBe false)))))))

                                      (it "scout uses a fast (haiku) model"
                                          (fn []
                                            (-> (expect (get-in (:roles defaults) [:scout :model])) (.toContain "haiku"))))

                                      (it "worker (editing) role is disabled by default"
                                          (fn []
                                            (-> (expect (get-in (:roles defaults) [:worker :enabled])) (.toBe false))))

                                      (it "subagent roles carry :description and :system-prompt"
                                          (fn []
                                            (doseq [r read-only-roles]
                                              (let [cfg (get-in (:roles defaults) [:roles r] (get (:roles defaults) r))]
                                                (-> (expect (:description cfg)) (.toBeDefined))
                                                (-> (expect (:system-prompt cfg)) (.toBeDefined))))))))

(describe "subagent:settings-knobs" (fn []
                                      (it "has :subagent knobs with max-depth 1 (recursion cap)"
                                          (fn []
                                            (-> (expect (get-in defaults [:subagent :max-depth])) (.toBe 1))
                                            (-> (expect (get-in defaults [:subagent :async-by-default])) (.toBe false))))

                                      (it "has :plan-mode :auto-approve false by default"
                                          (fn []
                                            (-> (expect (get-in defaults [:plan-mode :auto-approve])) (.toBe false))))))

(describe "subagent:enabled-gate"
          (fn []
            (it "refuses to run a role with :enabled false (before model resolution)"
                (fn []
                  (-> (run-isolated-agent
                       {:api #js {} :settings-mgr nil
                        :agents {"worker" {:provider "anthropic" :model "m" :enabled false
                                           :allowed-tools ["edit" "write" "bash"]}}
                        :agent-name "worker" :task "do a thing"})
                      (.then (fn [r]
                               (-> (expect (:ok r)) (.toBe false))
                               (-> (expect (:text r)) (.toContain "disabled")))))))
            (it "an enabled role passes the gate (fails later at model resolution with stub api)"
                (fn []
                  (-> (run-isolated-agent
                       {:api #js {} :settings-mgr nil
                        :agents {"scout" {:provider "anthropic" :model "m"
                                          :allowed-tools ["read"]}}
                        :agent-name "scout" :task "recon"})
                      (.then (fn [r]
                               (-> (expect (:ok r)) (.toBe false))
                               (-> (expect (:text r)) (.toContain "Could not resolve")))))))))
