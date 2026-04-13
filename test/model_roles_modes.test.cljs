(ns model-roles-modes.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.settings.manager :refer [defaults]]))

;;; ─── Modes: tool_access_check ───────────────────────────

(describe "model-roles-modes:tool-access-check" (fn []
  (it "plan role has allowed-tools in defaults"
    (fn []
      (let [plan-role (:plan (:roles defaults))]
        (-> (expect (:allowed-tools plan-role)) (.toBeDefined))
        (-> (expect (count (:allowed-tools plan-role))) (.toBeGreaterThan 0)))))

  (it "plan role does not include write/edit/bash in allowed-tools"
    (fn []
      (let [allowed (set (:allowed-tools (:plan (:roles defaults))))]
        (-> (expect (contains? allowed "write")) (.toBe false))
        (-> (expect (contains? allowed "edit")) (.toBe false))
        (-> (expect (contains? allowed "bash")) (.toBe false)))))

  (it "plan role includes read-only tools"
    (fn []
      (let [allowed (set (:allowed-tools (:plan (:roles defaults))))]
        (-> (expect (contains? allowed "read")) (.toBe true))
        (-> (expect (contains? allowed "grep")) (.toBe true))
        (-> (expect (contains? allowed "glob")) (.toBe true)))))

  (it "default role has no allowed-tools restriction"
    (fn []
      (let [default-role (:default (:roles defaults))]
        (-> (expect (:allowed-tools default-role)) (.toBeUndefined)))))

  (it "commit role has specific allowed-tools"
    (fn []
      (let [commit-role (:commit (:roles defaults))]
        (-> (expect (:allowed-tools commit-role)) (.toBeDefined))
        (-> (expect (some #{"bash"} (:allowed-tools commit-role))) (.toBeTruthy)))))))

;;; ─── Modes: permission_request ──────────────────────────

(describe "model-roles-modes:permissions" (fn []
  (it "plan role has permissions that deny write/edit/bash"
    (fn []
      (let [perms (:permissions (:plan (:roles defaults)))]
        (-> (expect (get perms "write")) (.toBe "deny"))
        (-> (expect (get perms "edit")) (.toBe "deny"))
        (-> (expect (get perms "bash")) (.toBe "deny")))))

  (it "default role has no permissions map"
    (fn []
      (let [default-role (:default (:roles defaults))]
        (-> (expect (:permissions default-role)) (.toBeUndefined)))))

  (it "tool_access_check event returns allowed set for restricted roles"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ;; Simulate a handler that returns restricted tools
        ((:on events) "tool_access_check"
          (fn [_] #js {:allowed #js ["read" "grep"]}))
        (let [p ((:emit-collect events) "tool_access_check"
                  #js {:tools #js ["read" "write" "bash" "grep"]})]
          (.then p (fn [result]
            (let [allowed (get result "allowed")]
              (-> (expect (count allowed)) (.toBe 2)))))))))))
