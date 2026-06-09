(ns subagent-run.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions.subagent.index :refer [final-text run-parallel run-chain]]))

;; ── B2 regression: final-text returns the last ASSISTANT message ──
(describe "subagent:final-text" (fn [])
          (it "returns the last assistant text, not a trailing tool result"
              (fn []
                (let [msgs [{:role "user" :content "go"}
                            {:role "assistant" :content "the summary"}
                            {:role "tool" :content "raw tool output"}]]
                  (-> (expect (final-text msgs)) (.toBe "the summary")))))

          (it "handles array content (text parts)"
              (fn []
                (let [msgs [{:role "assistant"
                             :content [#js {:type "text" :text "part A"}
                                       #js {:type "text" :text "part B"}]}]]
                  (-> (expect (final-text msgs)) (.toContain "part A"))
                  (-> (expect (final-text msgs)) (.toContain "part B")))))

          (it "returns empty string when there is no assistant message"
              (fn []
                (-> (expect (final-text [{:role "user" :content "x"}])) (.toBe "")))))

;; ── orchestration shape (no network; stub api lacks resolveModel) ──
(def ^:private stub-api #js {})
(def ^:private roles {"scout"   {:provider "anthropic" :model "m1"}
                      "planner" {:provider "anthropic" :model "m2"}})
(defn- base [] {:api stub-api :settings-mgr nil :agents roles})

(describe "subagent:run-parallel" (fn [])
          (it "preserves order and returns one result per task"
              (fn []
                (-> (run-parallel (base) [#js {:agent "scout" :task "a"}
                                          #js {:agent "planner" :task "b"}])
                    (.then (fn [rs]
                             (-> (expect (count rs)) (.toBe 2))
                             (-> (expect (:agent (nth rs 0))) (.toBe "scout"))
                             (-> (expect (:agent (nth rs 1))) (.toBe "planner"))
                   ;; no resolveModel on the stub → structured failure, no throw
                             (-> (expect (:ok (nth rs 0))) (.toBe false)))))))

          (it "rejects more than the max parallel tasks"
              (fn []
                (let [many (vec (repeat 9 #js {:agent "scout" :task "x"}))]
                  (-> (run-parallel (base) many)
                      (.then (fn [rs]
                               (-> (expect (:ok (first rs))) (.toBe false))
                               (-> (expect (:text (first rs))) (.toContain "Too many")))))))))

(describe "subagent:run-chain" (fn [])
          (it "short-circuits on the first failed step"
              (fn []
      ;; step 1 fails (no model) → chain stops; step 2 never runs
                (-> (run-chain (base) [#js {:agent "scout" :task "first"}
                                       #js {:agent "scout" :task "use {previous}"}])
                    (.then (fn [rs]
                             (-> (expect (count rs)) (.toBe 1))
                             (-> (expect (:ok (first rs))) (.toBe false))))))))

;; ── recursion guard: a child agent never gets the `subagent` tool ──
(describe "subagent:recursion-guard" (fn [])
          (it "a freshly created child has no subagent tool (depth capped at 1)"
              (fn []
                (let [child (create-agent {:model "test" :system-prompt "x"})
                      tools ((:all (:tool-registry child)))
                      names (set (keys tools))]
                  (-> (expect (contains? names "subagent")) (.toBe false))
                  (-> (expect (contains? names "subagent__subagent")) (.toBe false))))))
