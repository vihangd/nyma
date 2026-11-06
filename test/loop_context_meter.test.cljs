(ns loop-context-meter.test
  "`:last-input-tokens` is how full the context is, and what auto-compaction
   triggers on. It used to be `totalUsage`, the sum over a run's steps: each
   step re-sends the conversation, so a long run read ctx:385% on a window
   about 7% full."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.loop :refer [run]]
            [agent.pricing :as pricing]
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

(defn- cached-step
  "A tool step of 1,000,000 input tokens, `read` of them served from cache."
  [read]
  #js [#js {:type "tool-call" :toolCallId "c1" :toolName "think"
            :input "{\"thought\":\"working\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"}
            :usage #js {:inputTokens  #js {:total 1000000 :cacheRead read :cacheWrite 0}
                        :outputTokens #js {:total 0}}}])

(defn- with-rates!
  "Price the agent's model at $1/M fresh, $0.10/M cached; returns the undo."
  [agent]
  (let [k (pricing/model-cost-key (:config agent))]
    (swap! pricing/token-costs assoc k [1 1 0.1 1])
    (fn [] (swap! pricing/token-costs dissoc k))))

(defn ^:async test-live-figures []
  (let [seen  (atom [])
        agent-ref (atom nil)
        left  (atom [(cached-step 900000) (cached-step 900000) (text-step 1000)])
        model (new MockLanguageModelV4
                   #js {:doStream (fn [_]
                                    (let [st @(:state @agent-ref)]
                                      (swap! seen conj [(:run-steps st) (:run-cost st)]))
                                    (let [s (first @left)]
                                      (swap! left rest)
                                      (js/Promise.resolve
                                       #js {:stream (convertArrayToReadableStream s)})))})
        agent (make-test-agent {:model model})
        undo  (with-rates! agent)]
    (reset! agent-ref agent)
    (js-await (run agent "go"))
    (undo)
    (let [[_ one two] @seen
          st @(:state agent)]
      ;; Each step lands while the run is going: 100k fresh + 900k cached = $0.19.
      (-> (expect (first one)) (.toBe 1))
      (-> (expect (second one)) (.toBeCloseTo 0.19 6))
      (-> (expect (first two)) (.toBe 2))
      (-> (expect (second two)) (.toBeCloseTo 0.38 6))
      ;; Folded into the total once, not on top of it.
      (-> (expect (:total-cost st)) (.toBeCloseTo 0.381001 6))
      (-> (expect (:run-cost st)) (.toBeUndefined))
      (-> (expect (:run-steps st)) (.toBeUndefined)))))

(defn ^:async test-cache-read-tracks-last-request []
  (let [agent (make-test-agent {:model (scripted-model [(cached-step 900000) (cached-step 500000)
                                                        (text-step 2000)])})]
    (js-await (run agent "go"))
    ;; The last step reported none: a request with nothing cached.
    (-> (expect (:last-cache-read @(:state agent))) (.toBe 0))
    (-> (expect (:last-input-tokens @(:state agent))) (.toBe 2000))))

(defn ^:async test-stale-live-figures-cleared []
  (let [agent (make-test-agent {:model (scripted-model [(text-step 1000)])})]
    (swap! (:state agent) assoc :run-cost 5 :run-steps 9)
    (js-await (run agent "go"))
    (-> (expect (:total-cost @(:state agent))) (.toBeLessThan 5))
    (-> (expect (:run-steps @(:state agent))) (.toBeUndefined))))

(defn ^:async test-abort-keeps-cache-split []
  (let [agent (make-test-agent {:model (scripted-model [(cached-step 900000) (cached-step 900000)])})
        undo  (with-rates! agent)]
    ((:on (:events agent)) "turn_end"
                           (fn [_ _] (.abort @(:abort-controller agent))))
    (-> (run agent "go") (.catch (fn [_] nil)) js-await)
    (undo)
    (-> (expect (:total-cache-read-tokens @(:state agent))) (.toBe 900000))
    (-> (expect (:total-cost @(:state agent))) (.toBeCloseTo 0.19 6))))

(describe "loop:live-usage" (fn []
                              (it "adds each step while the run is going, and counts it once"
                                  test-live-figures)
                              (it "keeps cache reads with the request they came from"
                                  test-cache-read-tracks-last-request)
                              (it "starts a run without a previous run's figures"
                                  test-stale-live-figures-cleared)
                              (it "an aborted run still prices cached input as cached"
                                  test-abort-keeps-cache-split)))

(describe "loop:context-meter" (fn []
                                 (it "reads the last request, not the sum of the run" test-last-step-not-sum)
                                 (it "keeps the last figure when a step reports no usage" test-step-without-usage)))
