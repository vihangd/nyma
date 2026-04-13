(ns model-roles.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.core :refer [create-agent]]
            [agent.settings.manager :refer [defaults]]))

;;; ─── Settings defaults ──────────────────────────────────

(describe "model-roles:settings-defaults" (fn []
  (it "includes roles in defaults"
    (fn []
      (-> (expect (:roles defaults)) (.toBeDefined))))

  (it "has :default, :fast, :deep, :plan, :commit roles"
    (fn []
      (let [roles (:roles defaults)]
        (-> (expect (contains? roles :default)) (.toBe true))
        (-> (expect (contains? roles :fast)) (.toBe true))
        (-> (expect (contains? roles :deep)) (.toBe true))
        (-> (expect (contains? roles :plan)) (.toBe true))
        (-> (expect (contains? roles :commit)) (.toBe true)))))

  (it "each role has :provider and :model keys"
    (fn []
      (doseq [[_name role-cfg] (:roles defaults)]
        (-> (expect (:provider role-cfg)) (.toBeDefined))
        (-> (expect (:model role-cfg)) (.toBeDefined)))))))

;;; ─── Agent state ─────────────────────────────────────────

(describe "model-roles:agent-state" (fn []
  (it "agent initial state includes :active-role :default"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})]
        (-> (expect (:active-role @(:state agent))) (.toBe :default)))))

  (it "active-role can be switched via swap!"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})]
        (swap! (:state agent) assoc :active-role :fast)
        (-> (expect (:active-role @(:state agent))) (.toBe :fast)))))

  (it "active-role :default is present alongside existing state keys"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})
            state @(:state agent)]
        (-> (expect (contains? state :active-role)) (.toBe true))
        (-> (expect (contains? state :messages)) (.toBe true))
        (-> (expect (contains? state :active-skills)) (.toBe true)))))))

;;; ─── model_resolve event ────────────────────────────────

(describe "model-roles:model-resolve-event" (fn []
  (it "model_resolve event fires and returns merged result"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)
            handler (fn [data]
                      #js {:model "override-model"})]
        ((:on events) "model_resolve" handler)
        (let [p ((:emit-collect events) "model_resolve"
                  #js {:default "original" :context "generation"})]
          (.then p (fn [result]
            (-> (expect (get result "model")) (.toBe "override-model"))))))))

  (it "model_resolve returns empty map when no handlers override"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        (let [p ((:emit-collect events) "model_resolve"
                  #js {:default "original"})]
          (.then p (fn [result]
            (-> (expect (get result "model")) (.toBeUndefined))))))))

  (it "model_resolve data includes context and turnCount"
    (fn []
      (let [agent    (create-agent {:model "test" :system-prompt "test"})
            events   (:events agent)
            received (atom nil)
            handler  (fn [data] (reset! received data) nil)]
        ((:on events) "model_resolve" handler)
        (let [p ((:emit-collect events) "model_resolve"
                  #js {:default "m" :context "generation" :turnCount 5})]
          (.then p (fn [_]
            (-> (expect (.-context @received)) (.toBe "generation"))
            (-> (expect (.-turnCount @received)) (.toBe 5))))))))))

;;; ─── Role config lookup ─────────────────────────────────

(describe "model-roles:role-config" (fn []
  (it "fast role maps to haiku model"
    (fn []
      (let [roles (:roles defaults)
            fast  (:fast roles)]
        (-> (expect (:model fast)) (.toContain "haiku")))))

  (it "deep role maps to opus model"
    (fn []
      (let [roles (:roles defaults)
            deep  (:deep roles)]
        (-> (expect (:model deep)) (.toContain "opus")))))

  (it "roles can be overridden with custom map"
    (fn []
      (let [custom-roles (merge (:roles defaults)
                                {:fast {:provider "openai" :model "gpt-4o-mini"}})]
        (-> (expect (get-in custom-roles [:fast :provider])) (.toBe "openai"))
        (-> (expect (get-in custom-roles [:fast :model])) (.toBe "gpt-4o-mini")))))))
