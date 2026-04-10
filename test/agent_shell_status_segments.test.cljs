(ns agent-shell-status-segments.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.features.status-segments
             :refer [segments register-all!]]))

(def test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :warning   "#e0af68"}})

(describe "acp status segments: registry shape" (fn []
  (it "exposes every expected id"
    (fn []
      (-> (expect (contains? segments "acp.agent")) (.toBe true))
      (-> (expect (contains? segments "acp.model")) (.toBe true))
      (-> (expect (contains? segments "acp.mode")) (.toBe true))
      (-> (expect (contains? segments "acp.context")) (.toBe true))
      (-> (expect (contains? segments "acp.cost")) (.toBe true))
      (-> (expect (contains? segments "acp.turn-usage")) (.toBe true))))

  (it "segment ids are namespaced under acp."
    (fn []
      (doseq [[id _] segments]
        (-> (expect (.startsWith id "acp.")) (.toBe true)))))))

;;; ─── Visibility behavior: no ACP agent connected ─────

(describe "acp segments when no agent connected" (fn []
  (beforeEach
    (fn []
      (reset! shared/active-agent nil)
      (reset! shared/agent-state {})))

  (it "acp.agent is hidden"
    (fn []
      (let [r ((get segments "acp.agent") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe false)))))

  (it "acp.model is hidden"
    (fn []
      (let [r ((get segments "acp.model") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe false)))))

  (it "acp.mode is hidden"
    (fn []
      (let [r ((get segments "acp.mode") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe false)))))

  (it "acp.context is hidden"
    (fn []
      (let [r ((get segments "acp.context") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe false)))))

  (it "acp.cost is hidden"
    (fn []
      (let [r ((get segments "acp.cost") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe false)))))

  (it "acp.turn-usage is hidden"
    (fn []
      (let [r ((get segments "acp.turn-usage") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe false)))))))

;;; ─── Visibility behavior: agent connected ────────────

(describe "acp segments when agent connected" (fn []
  (beforeEach
    (fn []
      (reset! shared/active-agent :claude)
      (reset! shared/agent-state
              {:claude {:model "claude-sonnet-4"
                        :mode  "plan"
                        :usage {:used 42000 :size 200000
                                :cost {:amount 0.123}}
                        :turn-usage {:input-tokens 1500
                                     :output-tokens 800}}})))

  (it "acp.agent renders [claude]"
    (fn []
      (let [r ((get segments "acp.agent") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe true))
        (-> (expect (:content r)) (.toBe "[claude]")))))

  (it "acp.model renders the model name"
    (fn []
      (let [r ((get segments "acp.model") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe true))
        (-> (expect (:content r)) (.toBe "claude-sonnet-4")))))

  (it "acp.mode renders '| plan'"
    (fn []
      (let [r ((get segments "acp.mode") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe true))
        (-> (expect (.includes (:content r) "plan")) (.toBe true)))))

  (it "acp.context renders a percentage and progress bar"
    (fn []
      (let [r ((get segments "acp.context") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe true))
        (-> (expect (.includes (:content r) "%")) (.toBe true))
        (-> (expect (.includes (:content r) "ctx")) (.toBe true)))))

  (it "acp.cost renders a dollar amount"
    (fn []
      (let [r ((get segments "acp.cost") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe true))
        (-> (expect (.startsWith (:content r) "$")) (.toBe true)))))

  (it "acp.turn-usage renders ↑in ↓out"
    (fn []
      (let [r ((get segments "acp.turn-usage") {:theme test-theme})]
        (-> (expect (:visible? r)) (.toBe true))
        (-> (expect (.includes (:content r) "\u2191")) (.toBe true))
        (-> (expect (.includes (:content r) "\u2193")) (.toBe true)))))))

;;; ─── register-all! contract ─────────────────────────

(describe "register-all!" (fn []
  (it "calls registerStatusSegment once per segment"
    (fn []
      (let [calls (atom [])
            mock-api #js {:registerStatusSegment
                          (fn [id config]
                            (swap! calls conj {:id id :config config}))}]
        (register-all! mock-api)
        (-> (expect (count @calls)) (.toBe 6))
        (-> (expect (every? (fn [c] (.startsWith (:id c) "acp.")) @calls))
            (.toBe true)))))

  (it "skips when api has no registerStatusSegment"
    (fn []
      ;; Older hosts without the API should not throw.
      (let [mock-api #js {}]
        (register-all! mock-api)
        (-> (expect true) (.toBe true)))))))
