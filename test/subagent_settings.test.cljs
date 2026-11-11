(ns subagent-settings.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.settings.manager :refer [defaults]]
            [agent.extensions.subagent.index :as sub :refer [run-isolated-agent live-settings all-agents child-tools worktree-args inherit-model]]))

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
                                      (it "has :subagent knobs"
                                          (fn []
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

(describe "subagent:role-overrides"
          (fn []
            (it "--settings roles reach the tool, through the session's settings"
                (fn []
                  (let [api    #js {:settings (fn [] {:roles {:mine {:model "m"}}})}
                        agents (all-agents (live-settings api))]
                    (-> (expect (:model (get agents "mine"))) (.toBe "m")))))

            (it "a role naming only a model keeps the built-in's tool limits"
                (fn []
                  (let [api    #js {:settings (fn [] {:roles {:scout {:model "cheap"}}})}
                        scout  (get (all-agents (live-settings api)) "scout")]
                    (-> (expect (:model scout)) (.toBe "cheap"))
                    (-> (expect (vec (:allowed-tools scout)))
                        (.toEqual #js ["read" "glob" "grep" "ls"])))))

            (it "the child's manager still answers its permission gate"
                (fn []
                  (let [sm (live-settings #js {:settings (fn [] {})})]
                    (-> (expect (fn? (:tool-allowed? sm))) (.toBe true)))))

            (it "an api without settings falls back to the files"
                (fn []
                  (-> (expect (fn? (:get (live-settings #js {})))) (.toBe true))))))

(describe "subagent:child-tools"
          (fn []
            (it "a role that names no tools is read-only"
                (fn []
                  (doseq [none [nil []]]
                    (let [tools (set (child-tools none))]
                      (-> (expect (contains? tools "read")) (.toBe true))
                      (doseq [t ["bash" "write" "edit"]]
                        (-> (expect (contains? tools t)) (.toBe false)))))))

            (it "a named list is used as it is"
                (fn []
                  (-> (expect (child-tools ["bash"])) (.toEqual #js ["bash"]))))

            (it "every tool has to be asked for"
                (fn []
                  (-> (expect (child-tools ["*"])) (.toBeNil))))

            (it "* is never passed to a worktree child as a tool name"
                (fn []
                  (let [args (fn [t] (vec (worktree-args {:model "m" :sys "s" :allowed t})))]
                    (-> (expect (.includes (args ["*"]) "--tools")) (.toBe false))
                    (-> (expect (.includes (args ["read"]) "--tools")) (.toBe true)))))

            (it "a worktree role naming no tools can edit, not run"
                (fn []
                  (let [tools (fn [mode] (let [a (vec (worktree-args {:model "m" :sys "s" :mode mode}))]
                                           (nth a (inc (.indexOf a "--tools")))))]
                    (-> (expect (tools "default")) (.toContain "write"))
                    (-> (expect (tools "default")) (.not.toContain "bash"))
                    (-> (expect (tools "plan")) (.not.toContain "write")))))))

(describe "subagent:depth"
          (fn []
            (it "a worktree child gets no subagent tool, and still has /agents"
                (fn []
                  (let [tools (atom []) cmds (atom [])
                        api   #js {:registerTool (fn [n _] (swap! tools conj n))
                                   :registerCommand (fn [n _] (swap! cmds conj n))}]
                    (aset js/process.env "NYMA_SUBAGENT_DEPTH" "1")
                    (try
                      ((aget sub "default") api)
                      (finally (js-delete js/process.env "NYMA_SUBAGENT_DEPTH")))
                    (-> (expect (count @tools)) (.toBe 0))
                    (-> (expect (.includes (vec @cmds) "agents")) (.toBe true)))))))

(describe "subagent:inherit-model"
          (fn []
            (let [api #js {:getActiveModelSpec (fn [] "zai/glm-5.3-flash")}
                  on  (fn [cfg] (inherit-model cfg api))]
              (it "a shipped role on another provider takes the parent's model"
                  (fn []
                    (let [r (on {:provider "anthropic" :model "claude-haiku-4-5" :shipped? true})]
                      (-> (expect (:provider r)) (.toBe "zai"))
                      (-> (expect (:model r)) (.toBe "glm-5.3-flash")))))

              (it "a role the user named stays as named"
                  (fn []
                    (-> (expect (:model (on {:provider "minimax" :model "M2.7"}))) (.toBe "M2.7"))))

              (it "inherit and no model both take the parent's"
                  (fn []
                    (-> (expect (:model (on {:provider "anthropic" :model "inherit"}))) (.toBe "glm-5.3-flash"))
                    (-> (expect (:provider (on {:provider "anthropic"}))) (.toBe "zai"))))

              (it "only shipped roles are marked shipped"
                  (fn []
                    (let [agents (all-agents (live-settings #js {:settings (fn [] {:roles {:scout {:provider "x" :model "m"}}})}))]
                      (-> (expect (:shipped? (get agents "scout"))) (.toBe false))
                      (-> (expect (:shipped? (get agents "planner"))) (.toBe true)))))

              (it "the settings defaults' own copy of a role is still shipped"
                  (fn []
                    (let [agents (all-agents (live-settings #js {:settings (fn [] {:roles (:roles defaults)})}))]
                      (-> (expect (:shipped? (get agents "scout"))) (.toBe true)))))

              (it "an unknown role stays unknown"
                  (fn []
                    (-> (expect (on nil)) (.toBeFalsy)))))))
