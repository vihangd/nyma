(ns model-roles-lead.test
  "The :lead role is a delegation-only primary borrowed from apprentice's loop
   of the same shape. It pins no model (inherits the active one) but does shape
   the tool set — so it is a /role, not a /mode, and its :system-prompt has to
   reach the primary, which no role's prompt did before."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.model-roles.index :as mr :refer [model-roles-only policy-roles-only]]
            [agent.settings.manager :refer [defaults]]))

(describe "model-roles:lead" (fn []
                               (it "lead has no read/edit/write/bash but keeps the locate tools and subagent"
                                   (fn []
                                     (let [allowed (set (:allowed-tools (:lead mr/default-roles)))]
                                       (doseq [t ["glob" "grep" "ls" "subagent__subagent" "retrieve_result"]]
                                         (-> (expect (contains? allowed t)) (.toBe true)))
                                       (doseq [t ["read" "edit" "write" "bash"]]
                                         (-> (expect (contains? allowed t)) (.toBe false))))))

                               (it "lead lives in the extension only — a manager copy would shadow it on merge"
      (fn []
        (-> (expect (:lead (:roles defaults))) (.toBeUndefined))))

  (it "lead is model-less (inherits the active model)"
                                   (fn []
                                     (-> (expect (:model (:lead mr/default-roles))) (.toBeUndefined))))

                               (it "/roles lists lead as a role, not a permission mode"
                                   (fn []
                                     (let [roles {"lead"         {:allowed-tools ["grep"]}
                                                  "full-auto"    {:policy {"write" "allow"}}
                                                  "deep"         {:provider "anthropic" :model "m"}}]
                                       (-> (expect (contains? (model-roles-only roles) "lead")) (.toBe true))
                                       (-> (expect (contains? (model-roles-only roles) "deep")) (.toBe true))
                                       (-> (expect (contains? (model-roles-only roles) "full-auto")) (.toBe false))
                                       (-> (expect (contains? (policy-roles-only roles) "full-auto")) (.toBe true))
                                       (-> (expect (contains? (policy-roles-only roles) "lead")) (.toBe false)))))))
