(ns loop-empty-turn.test
  "A turn that ends with no text and no tool call used to end in silence: the
   TUI builds the assistant row from text deltas, so nothing appeared, and the
   loop only ever read finishReason \"length\". One session had 5 of 9 turns end
   this way and the user typed `continue` 8 times. Now the loop sends one nudge
   per silence, names a refusal instead of re-asking it, and can be told not to."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.loop :refer [run empty-turn-nudge]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn- finish [reason]
  #js {:type "finish" :finishReason #js {:unified reason :raw reason}
       :usage #js {:inputTokens 1 :outputTokens 1 :totalTokens 2}})

(defn- empty-parts [& [reason]]
  #js [#js {:type "text-start" :id "t1"}
       #js {:type "text-end" :id "t1"}
       (finish (or reason "stop"))])

(defn- text-parts [text]
  #js [#js {:type "text-start" :id "t1"}
       #js {:type "text-delta" :id "t1" :delta text}
       #js {:type "text-end" :id "t1"}
       (finish "stop")])

(defn- scripted-model
  "Answers each call with the next entry of `script` (a vector of part arrays),
   repeating the last one. Counts calls."
  [script]
  (let [calls (atom 0)
        model (new MockLanguageModelV4
                   #js {:doStream (fn [_opts]
                                    (let [i (dec (swap! calls inc))
                                          ps (nth script (min i (dec (count script))))]
                                      (js/Promise.resolve
                                       #js {:stream (convertArrayToReadableStream ps)})))})]
    {:model model :calls calls}))

(defn- notifies!
  "Give the agent a ui with a recording notify; returns the atom."
  [agent]
  (let [seen (atom [])]
    (aset agent "extension_api" #js {:ui #js {:notify (fn [msg level] (swap! seen conj [level msg]))}})
    seen))

(defn- user-texts [agent]
  (keep (fn [m] (when (= (:role m) "user") (str (:content m)))) (:messages @(:state agent))))

(defn- assistant-texts [agent]
  (keep (fn [m] (when (= (:role m) "assistant") (str (:content m)))) (:messages @(:state agent))))

(defn- failing-model []
  (new MockLanguageModelV4
       #js {:doStream (fn [_] (js/Promise.reject (js/Error. "provider down")))}))

(describe "a failed turn"
  (fn []
    (it "drops nyma's queued nudges but keeps what the user queued"
        (fn []
          (let [agent (make-test-agent {:model (failing-model)})]
            ;; queued by the user before the run (alt+enter, /spec import --run)
            (swap! (:follow-queue agent) conj {:role "user" :content "next task"})
            ((:on (:events agent)) "turn_finalize"
                                   (fn [d] (when (.-error d)
                                             ;; what an extension's sendUserMessage followUp queues
                                             (swap! (:follow-queue agent) conj {:role "user" :content "stale nudge"
                                                                                :turn {:synthetic true}}))
                                     nil))
            (-> (run agent "go")
                (.then (fn [] (throw (js/Error. "expected a throw")))
                       (fn [e]
                         (-> (expect (.-message e)) (.toContain "provider down"))
                         (-> (expect (mapv :content @(:follow-queue agent))) (.toEqual #js ["next task"]))))))))))

(describe "a stream filter that keeps aborting"
  (fn []
    (it "ends the turn with a stored, visible reason"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(text-parts "eval() here")])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            ((:on (:events agent)) "stream_filter" (fn [_] #js {:abort true :reason "matched eval()"}))
            (-> (run agent "go")
                (.then (fn []
                         ;; two retries, then the cap
                         (-> (expect @calls) (.toBe 3))
                         (-> (expect (last (assistant-texts agent))) (.toContain "matched eval()"))
                         (-> (expect (some (fn [[lvl msg]] (and (= lvl "warning") (.includes msg "stream filter"))) @seen))
                             (.toBeTruthy))
                         ;; not also treated as an empty turn
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 0))))))))))

(describe "an empty turn"
  (fn []
    (it "gets one nudge, a warning, and the answer that follows is stored"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts) (text-parts "here you go")])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            (-> (run agent "do the thing")
                (.then (fn []
                         (-> (expect @calls) (.toBe 2))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 1))
                         (-> (expect (last (assistant-texts agent))) (.toBe "here you go"))
                         (-> (expect (some (fn [[lvl msg]] (and (= lvl "warning") (.includes msg "empty response"))) @seen))
                             (.toBeTruthy))))))))

    (it "a model that stays empty is nudged once, not forever"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts)])
                agent (make-test-agent {:model model})
                _     (notifies! agent)]
            (-> (run agent "do the thing")
                (.then (fn []
                         (-> (expect @calls) (.toBe 2))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 1))))))))

    (it "a refusal is named, not re-asked"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts "content-filter")])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            (-> (run agent "do the thing")
                (.then (fn []
                         (-> (expect @calls) (.toBe 1))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 0))
                         (-> (expect (some (fn [[_ msg]] (.includes msg "declined")) @seen)) (.toBeTruthy))))))))

    (it "empty-turn-nudge false keeps the old behaviour, with a warning"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts)])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            (aset (:config agent) "empty-turn-nudge" false)
            (-> (run agent "do the thing")
                (.then (fn []
                         (-> (expect @calls) (.toBe 1))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 0))
                         (-> (expect (some (fn [[lvl _]] (= lvl "warning")) @seen)) (.toBeTruthy))))))))

    (it "the next run gets its own nudge, even in a chat with no tools"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts) (text-parts "a1") (empty-parts) (text-parts "a2")])
                agent (make-test-agent {:model model})
                _     (notifies! agent)]
            (-> (run agent "q1")
                (.then (fn [] (run agent "q2")))
                (.then (fn []
                         (-> (expect @calls) (.toBe 4))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 2))
                         (-> (expect (last (assistant-texts agent))) (.toBe "a2"))))))))

    (it "a silent step-capped turn is reported, not nudged on top of the report"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts) (text-parts "report")])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            (aset (:config agent) "max-steps" 1)
            (-> (run agent "go")
                (.then (fn []
                         ;; the empty step, then the step-cap report turn — no third call
                         (-> (expect @calls) (.toBe 2))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 0))
                         (-> (expect (some (fn [[_ msg]] (.includes msg "empty response")) @seen)) (.toBeTruthy))))))))

    (it "a report turn that comes back silent is named, not re-asked"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(empty-parts) (empty-parts)])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            (aset (:config agent) "max-steps" 1)
            (-> (run agent "go")
                (.then (fn []
                         (-> (expect @calls) (.toBe 2))
                         (-> (expect (vec (filter #(= % empty-turn-nudge) (user-texts agent)))) (.toHaveLength 0))
                         (-> (expect (count (filter (fn [[_ msg]] (.includes msg "empty response")) @seen))) (.toBe 2))))))))

    (it "the stored assistant message carries the finish reason, the model never sees it"
        (fn []
          (let [{:keys [model]} (scripted-model [(empty-parts "content-filter")])
                agent (make-test-agent {:model model})
                _     (notifies! agent)]
            (-> (run agent "go")
                (.then (fn []
                         (let [m (last (filter #(= "assistant" (:role %)) (:messages @(:state agent))))]
                           (-> (expect (:finish-reason (:metadata m))) (.toBe "content-filter")))))))))

    (it "a turn with text is left alone"
        (fn []
          (let [{:keys [model calls]} (scripted-model [(text-parts "fine")])
                agent (make-test-agent {:model model})
                seen  (notifies! agent)]
            (-> (run agent "do the thing")
                (.then (fn []
                         (-> (expect @calls) (.toBe 1))
                         (-> (expect (count @seen)) (.toBe 0))))))))))
