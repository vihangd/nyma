(ns print-stream-json.test
  "`nyma -p --output-format stream-json`: one JSON object per line while the
   run is in flight, then the same result object json mode prints — so an
   orchestrator gets progress without the TUI or the raw rpc firehose, and
   still keys success on the last line and the exit code."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]
            [agent.cli :refer [output-format-error]]
            [agent.modes.print :refer [subscribe-stream-json! start-stream-json]]))

(defn- fake-agent [state-map]
  {:state (atom state-map) :session (atom nil) :events (create-event-bus)})

(defn- emit! [agent ev data] ((:emit (:events agent)) ev data))

(defn- capture!
  "Run `f` with console.log captured; returns the raw stdout lines."
  [f]
  (let [real (.-log js/console)
        out  (atom [])]
    (set! (.-log js/console) (fn [line] (swap! out conj (str line))))
    (try (f) (finally (set! (.-log js/console) real)))
    @out))

(defn- ^:async capture-async! [f]
  (let [real (.-log js/console)
        out  (atom [])]
    (set! (.-log js/console) (fn [line] (swap! out conj (str line))))
    (try (js-await (f)) (finally (set! (.-log js/console) real)))
    @out))

(defn- parsed [lines] (mapv #(js/JSON.parse %) lines))

(defn- step [& {:keys [in out]}]
  #js {:text "" :finishReason "stop"
       :usage (when (or in out) #js {:inputTokens (or in 0) :outputTokens (or out 0)})
       :toolResults #js []})

;;; ─── the in-flight lines ────────────────────────────────────

(describe "stream-json events"
          (fn []
            (it "writes one valid JSON object per line, in the order the run emits them"
                (fn []
                  (let [agent (fake-agent {:messages []})
                        off   (subscribe-stream-json! agent)
                        lines (capture!
                               (fn []
                                 (emit! agent "message_start" #js {})
                                 (emit! agent "message_update" #js {:type "text-delta" :text "hel"})
                                 (emit! agent "message_update" #js {:type "text-delta" :text "lo"})
                                 (emit! agent "message_end" #js {})
                                 (emit! agent "turn_end" (step :in 12 :out 3))
                                 (emit! agent "agent_end" #js {})))
                        evs   (parsed lines)]
                    (off)
                    ;; every line parses — `parsed` would have thrown otherwise
                    (-> (expect (count evs)) (.toBe 6))
                    (-> (expect (mapv #(.-type %) evs))
                        (.toEqual #js ["message_start" "message_update" "message_update"
                                       "message_end" "usage" "agent_end"]))
                    (-> (expect (.-text (nth evs 1))) (.toBe "hel"))
                    (-> (expect (.-text (nth evs 2))) (.toBe "lo"))
                    (-> (expect (.-inputTokens (nth evs 4))) (.toBe 12))
                    (-> (expect (.-outputTokens (nth evs 4))) (.toBe 3)))))

            (it "reads the object stream's textDelta when the v7 text field is absent"
                (fn []
                  (let [agent (fake-agent {:messages []})
                        off   (subscribe-stream-json! agent)
                        evs   (parsed (capture!
                                       (fn [] (emit! agent "message_update" #js {:textDelta "x"}))))]
                    (off)
                    (-> (expect (.-text (first evs))) (.toBe "x")))))

            (it "carries the tool's own payload names: toolName, execId, args, result, isError"
                (fn []
                  (let [agent (fake-agent {:messages []})
                        off   (subscribe-stream-json! agent)
                        evs   (parsed (capture!
                                       (fn []
                                         (emit! agent "tool_execution_start"
                                                {:toolName "read" :execId "e1" :args {:path "a.txt"}})
                                         (emit! agent "tool_execution_end"
                                                {:toolName "read" :execId "e1" :result "contents"
                                                 :isError false}))))]
                    (off)
                    (let [[s e] evs]
                      (-> (expect (.-type s)) (.toBe "tool_execution_start"))
                      (-> (expect (.-toolName s)) (.toBe "read"))
                      (-> (expect (.-execId s)) (.toBe "e1"))
                      (-> (expect (.. s -args -path)) (.toBe "a.txt"))
                      (-> (expect (.-type e)) (.toBe "tool_execution_end"))
                      (-> (expect (.-execId e)) (.toBe "e1"))
                      (-> (expect (.-result e)) (.toBe "contents"))
                      (-> (expect (.-isError e)) (.toBe false))))))

            (it "skips the usage line when the step reported none"
                (fn []
                  ;; Absent, not zeroed — "no usage reported" must stay
                  ;; distinguishable from a free turn.
                  (let [agent (fake-agent {:messages []})
                        off   (subscribe-stream-json! agent)
                        lines (capture! (fn [] (emit! agent "turn_end" (step))))]
                    (off)
                    (-> (expect (count lines)) (.toBe 0)))))

            (it "unsubscribes: nothing is written after the returned thunk runs"
                (fn []
                  (let [agent (fake-agent {:messages []})
                        total (:handler-total (:events agent))
                        off   (subscribe-stream-json! agent)]
                    (-> (expect (pos? (total))) (.toBe true))
                    (off)
                    (-> (expect (total)) (.toBe 0))
                    (-> (expect (count (capture! (fn [] (emit! agent "message_start" #js {})))))
                        (.toBe 0)))))))

;;; ─── the last line + exit code ──────────────────────────────

(defn ^:async test-error-run-ends-with-result []
  (let [orig-code (.-exitCode js/process)
        agent     (fake-agent {:messages []})]
    (set! (.-exitCode js/process) 0)
    ;; A bare map is not a runnable agent: `run` throws, which is the same
    ;; path a provider error takes.
    (let [lines (js-await (capture-async! (fn [] (start-stream-json agent "hi"))))
          code  (.-exitCode js/process)
          last* (js/JSON.parse (last lines))]
      ;; Restore before asserting — a failed expect must not leak exitCode 1
      ;; into bun's own status and redden the whole suite.
      (set! (.-exitCode js/process) (or orig-code 0))
      (-> (expect (.-type last*)) (.toBe "result"))
      (-> (expect (.-is_error last*)) (.toBe true))
      (-> (expect code) (.toBe 1))
      ;; The subscriber does not outlive the run it reported on.
      (-> (expect ((:handler-total (:events agent)))) (.toBe 0)))))

(describe "stream-json result line"
          (fn []
            (it "ends with a result object, exits 1, and unsubscribes when the run throws"
                test-error-run-ends-with-result)))

;;; ─── --output-format validation ─────────────────────────────

(describe "--output-format validation"
          (fn []
            (it "accepts text, json, stream-json and absent"
                (fn []
                  (doseq [f ["text" "json" "stream-json" nil]]
                    (-> (expect (output-format-error f)) (.toBeNil)))))

            (it "names the accepted values for anything else"
                (fn []
                  (-> (expect (output-format-error "josn"))
                      (.toBe "nyma: --output-format must be text, json or stream-json"))))))
