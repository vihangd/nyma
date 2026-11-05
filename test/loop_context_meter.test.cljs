(ns loop-context-meter.test
  "`:last-input-tokens` is how full the context is, and what auto-compaction
   triggers on. It used to be `totalUsage`, the sum over a run's steps: each
   step re-sends the conversation, so a long run read ctx:385% on a window
   about 7% full."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.loop :refer [run]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn- usage [input]
  #js {:inputTokens  #js {:total input}
       :outputTokens #js {:total 1}})

(defn- tool-step [input]
  #js [#js {:type "tool-call" :toolCallId "c1" :toolName "think"
            :input "{\"thought\":\"working\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"}
            :usage (usage input)}])

(defn- text-step [input]
  #js [#js {:type "text-start" :id "t1"}
       #js {:type "text-delta" :id "t1" :delta "done"}
       #js {:type "text-end" :id "t1"}
       #js {:type "finish" :finishReason #js {:unified "stop" :raw "stop"}
            :usage (usage input)}])

(defn- scripted-model
  "Answers each request with the next of `steps`."
  [steps]
  (let [left (atom steps)]
    (new MockLanguageModelV4
         #js {:doStream (fn [_]
                          (let [s (first @left)]
                            (swap! left rest)
                            (js/Promise.resolve
                             #js {:stream (convertArrayToReadableStream s)})))})))

(defn ^:async test-last-step-not-sum []
  (let [agent (make-test-agent {:model (scripted-model [(tool-step 1000) (tool-step 1500) (text-step 2000)])})]
    (js-await (run agent "go"))
    (-> (expect (:last-input-tokens @(:state agent))) (.toBe 2000))
    ;; Cost still pays for every step.
    (-> (expect (:total-input-tokens @(:state agent))) (.toBe 4500))))

(defn ^:async test-step-without-usage []
  (let [agent (make-test-agent {:model (scripted-model [(tool-step 1000) (text-step js/undefined)])})]
    (js-await (run agent "go"))
    (-> (expect (:last-input-tokens @(:state agent))) (.toBe 1000))))

(describe "loop:context-meter" (fn []
                                 (it "reads the last request, not the sum of the run" test-last-step-not-sum)
                                 (it "keeps the last figure when a step reports no usage" test-step-without-usage)))
