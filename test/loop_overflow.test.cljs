(ns loop-overflow.test
  "A prompt too big for the model arrives as a stream `error` part, not a throw,
   so recovery has to live on that path."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            ["@ai-sdk/provider" :refer [APICallError]]
            [agent.loop :refer [run]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.sessions.compaction :as cmp]
            [test-util.agent-harness :refer [make-test-agent]]))

(def ^:private summary
  (str "## 1. Previous Conversation\nSUMMARISED\n\n## 2. Current Work\nsrc/a.cljs:1\n\n"
       "## 3. Key Technical Concepts\nx\n\n## 4. Relevant Files and Code\nsrc/a.cljs:1\n\n"
       "## 5. Problem Solving\nx\n\n## 6. Pending Tasks and Next Steps\n- x\n  Quote: \"y\""))

(defn- too-long []
  (new APICallError
       #js {:message "Bad Request" :url "http://x" :requestBodyValues #js {}
            :statusCode 400 :isRetryable false
            :responseBody "{\"error\":{\"message\":\"prompt is too long: 250000 tokens > 200000\"}}"}))

(def ^:private usage-part
  #js {:inputTokens #js {:total 10} :outputTokens #js {:total 5}})

(def ^:private answer
  #js [#js {:type "text-start" :id "t"}
       #js {:type "text-delta" :id "t" :delta "done"}
       #js {:type "text-end" :id "t"}
       #js {:type "finish" :finishReason #js {:unified "stop" :raw "stop"} :usage usage-part}])

(def ^:private tool-with-text
  #js [#js {:type "text-start" :id "t"}
       #js {:type "text-delta" :id "t" :delta "working on it"}
       #js {:type "text-end" :id "t"}
       #js {:type "tool-call" :toolCallId "c1" :toolName "think"
            :input "{\"thought\":\"x\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"} :usage usage-part}])

(defn- scripted
  "Each of `steps` in turn; :overflow rejects as the provider would."
  [prompts steps]
  (let [left (atom steps)]
    (new MockLanguageModelV4
         #js {:doStream (fn [o]
                          (swap! prompts conj (js/JSON.stringify (.-prompt o)))
                          (let [s (first @left)]
                            (swap! left rest)
                            (if (= s :overflow)
                              (js/Promise.reject (too-long))
                              (js/Promise.resolve #js {:stream (convertArrayToReadableStream s)}))))})))

(defn- agent-with-history
  "An agent whose session and state hold a few turns, and a before_compact that
   offers a summary, so no model is asked for one."
  [model]
  (let [agent (make-test-agent {:model model})
        sm    (create-session-manager nil)
        turns [{:role "user" :content "OLD QUESTION"} {:role "assistant" :content "old answer"}
               {:role "user" :content "more"} {:role "assistant" :content "sure"}]]
    (doseq [m turns] ((:append sm) m))
    (reset! (:session agent) sm)
    (swap! (:state agent) assoc :messages (vec turns))
    ((:on (:events agent)) "before_compact" (fn [ctx] (aset ctx "summary" summary)))
    agent))

(defn- count-event [agent evt]
  (let [n (atom 0)]
    ((:on (:events agent)) evt (fn [& _] (swap! n inc) nil))
    n))

(defn ^:async test-first-step-recovers []
  (let [prompts  (atom [])
        agent    (agent-with-history (scripted prompts [:overflow answer]))
        failures (count-event agent "provider_error")
        compacts (count-event agent "compact")]
    (js-await (run agent "go"))
    (-> (expect (count @prompts)) (.toBe 2))
    (-> (expect @compacts) (.toBe 1))
    (-> (expect @failures) (.toBe 0))
    (-> (expect (nth @prompts 1)) (.toContain "SUMMARISED"))
    (-> (expect (:content (last (:messages @(:state agent))))) (.toBe "done"))))

(defn ^:async test-second-overflow-is-an-error []
  (let [prompts (atom [])
        agent   (agent-with-history (scripted prompts [:overflow :overflow answer]))
        err     (js-await (-> (run agent "go") (.then (fn [_] nil)) (.catch (fn [e] e))))]
    (-> (expect (count @prompts)) (.toBe 2))
    (-> (expect (.-message err)) (.toContain "prompt is too long"))))

(defn ^:async test-later-step-continues []
  (let [prompts  (atom [])
        agent    (agent-with-history (scripted prompts [tool-with-text :overflow answer]))
        compacts (count-event agent "compact")
        ran      (count-event agent "tool_execution_start")]
    (js-await (run agent "go"))
    (-> (expect (count @prompts)) (.toBe 3))
    ;; Continued, not restarted: the tool ran once, and the retry carries its
    ;; call and result after the compacted history.
    (-> (expect @ran) (.toBe 1))
    (-> (expect (nth @prompts 2)) (.toContain "SUMMARISED"))
    (-> (expect (nth @prompts 2)) (.toContain "\"c1\""))
    (-> (expect (nth @prompts 2)) (.toContain "tool-result"))
    (-> (expect @compacts) (.toBe 1))
    (-> (expect (:content (last (:messages @(:state agent))))) (.toBe "done"))))

(defn ^:async test-later-step-second-overflow []
  (let [prompts (atom [])
        agent   (agent-with-history (scripted prompts [tool-with-text :overflow :overflow answer]))
        err     (js-await (-> (run agent "go") (.then (fn [_] nil)) (.catch (fn [e] e))))]
    (-> (expect (count @prompts)) (.toBe 3))
    (-> (expect (.-message err)) (.toContain "prompt is too long"))))

(defn ^:async test-stub-large-outputs []
  (let [big   (apply str (repeat 9000 "x"))
        msgs  #js [#js {:role "assistant" :content #js [#js {:type "tool-call" :toolCallId "a"}]}
                   #js {:role "tool" :content #js [#js {:type "tool-result" :toolCallId "a"
                                                       :output #js {:type "text" :value big}}
                                                  #js {:type "tool-result" :toolCallId "b"
                                                       :output #js {:type "text" :value "small"}}]}]
        out   (cmp/stub-large-tool-outputs msgs 8000)
        parts (.-content (aget out 1))]
    (-> (expect (.. (aget parts 0) -output -value)) (.toContain "elided"))
    (-> (expect (.. (aget parts 1) -output -value)) (.toBe "small"))
    ;; not mutated
    (-> (expect (.. (aget (.-content (aget msgs 1)) 0) -output -value)) (.toBe big))))

(defn ^:async test-no-session []
  (let [prompts (atom [])
        agent   (make-test-agent {:model (scripted prompts [:overflow answer])})
        err     (js-await (-> (run agent "go") (.then (fn [_] nil)) (.catch (fn [e] e))))]
    (-> (expect (count @prompts)) (.toBe 1))
    (-> (expect (.-message err)) (.toContain "prompt is too long"))))

(describe "loop: context overflow"
          (fn []
            (it "the first step compacts and retries, without reporting a failure" test-first-step-recovers)
            (it "a second overflow is an error, not a loop" test-second-overflow-is-an-error)
            (it "a later step continues from the completed steps; tools do not re-run" test-later-step-continues)
            (it "a second overflow after a tool is an error, not a loop" test-later-step-second-overflow)
            (it "stubs only the oversized tool outputs" test-stub-large-outputs)
            (it "without a session there is nothing to compact" test-no-session)))
