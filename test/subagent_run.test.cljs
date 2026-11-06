(ns subagent-run.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.extensions.subagent.index :refer [final-text run-isolated-agent run-parallel run-chain]]
            [agent.pricing :as pricing]))

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

;; ── what a child spends reaches the parent's totals ──
(def ^:private tool-chunks
  #js [#js {:type "tool-call" :toolCallId "c1" :toolName "think"
            :input "{\"thought\":\"working\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"}
            :usage #js {:inputTokens  #js {:total 1000000 :cacheRead 400 :cacheWrite 0}
                        :outputTokens #js {:total 0}}}])

(def ^:private text-chunks
  #js [#js {:type "text-start" :id "t1"}
       #js {:type "text-delta" :id "t1" :delta "done"}
       #js {:type "text-end" :id "t1"}
       #js {:type "finish" :finishReason #js {:unified "stop" :raw "stop"}
            :usage #js {:inputTokens  #js {:total 1000000 :cacheRead 400 :cacheWrite 0}
                        :outputTokens #js {:total 0}}}])

(defn- scripted [& steps]
  (let [left (atom steps)]
    (new MockLanguageModelV4
         #js {:doStream (fn [_]
                          (let [s (first @left)]
                            (swap! left rest)
                            (js/Promise.resolve #js {:stream (convertArrayToReadableStream s)})))})))

(defn ^:async bill-of
  "Run a child on `model`, priced at $1/M, and return [result dispatches]."
  [model]
  (let [bills (atom [])
        k     (pricing/model-cost-key #js {:model model})
        api   #js {:resolveModel (fn [_ _] model)
                   :dispatchState (fn [t d] (swap! bills conj [t d]))}]
    (swap! pricing/token-costs assoc k [1 1])
    (let [r (js-await (run-isolated-agent (assoc (base) :api api :agent-name "scout" :task "go")))]
      (swap! pricing/token-costs dissoc k)
      [r @bills])))

(defn ^:async test-child-billed []
  (let [[r bills] (js-await (bill-of (scripted text-chunks)))
        [t d]     (first bills)]
    (-> (expect (:ok r)) (.toBe true))
    (-> (expect (count bills)) (.toBe 1))
    (-> (expect t) (.toBe "extension-usage"))
    (-> (expect (:model d)) (.toBe "anthropic/m1"))
    (-> (expect (:input-tokens d)) (.toBe 1000000))
    (-> (expect (:cache-read-tokens d)) (.toBe 400))
    (-> (expect (:cost d)) (.toBeCloseTo 1 6))))

(defn ^:async test-failed-child-billed []
  (let [[r bills] (js-await (bill-of (scripted tool-chunks
                                               #js [#js {:type "error" :error "boom"}])))
        [_ d]     (first bills)]
    (-> (expect (:ok r)) (.toBe false))
    (-> (expect (:cost d)) (.toBeCloseTo 1 6))))

(describe "subagent:billing" (fn []
                               (it "adds a child's spend to the parent, once" test-child-billed)
                               (it "bills steps a failed child already paid for" test-failed-child-billed)))
