(ns compaction-cached-prefix.test
  "Compaction summarises on the prefix the last request sent, so it reads the
   prompt cache that request warmed instead of paying full price for the span."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.sessions.compaction :as c]
            [agent.sessions.manager :refer [create-session-manager]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.loop :refer [run]]
            [test-util.agent-harness :refer [make-test-agent]]))

(def six
  (str "## 1. Previous Conversation\nx\n## 2. Current Work\nx\n## 3. Key Technical Concepts\nx\n"
       "## 4. Relevant Files and Code\nx\n## 5. Problem Solving\nx\n## 6. Pending Tasks and Next Steps\nnone\n"))

(defn- marked [text]
  #js {:role "assistant" :content text
       :providerOptions #js {:anthropic #js {:cacheControl #js {:type "ephemeral" :ttl "1h"}}}})

(defn- base-with [msgs]
  #js {:model #js {:modelId "claude-x" :provider "anthropic.messages"}
       :system "SYS" :temperature 0 :providerOptions #js {:anthropic #js {:thinking "t"}}
       :tools #js {:read #js {:description "r" :inputSchema "S"}}
       :messages msgs})

(defn- capture [seen reply]
  (fn [cfg] (swap! seen conj cfg) (js/Promise.resolve #js {:text reply})))

(describe "request-snapshot" (fn []
                               (it "keeps what reaches the wire, drops what only runs locally"
                                   (fn []
                                     (let [cfg  #js {:model "m" :system "S" :temperature 0
                                                     :messages #js [#js {:role "user" :content "q"}]
                                                     :abortSignal "sig" :stopWhen #js [] :onError (fn [] nil)
                                                     :prepareStep (fn [] nil)
                                                     :tools #js {:bash #js {:description "d" :inputSchema "S" :execute (fn [] nil)}}}
                                           live [{:role "user" :content "q"} {:role "assistant" :content "a"}]
                                           snap (c/request-snapshot cfg #js [#js {:role "assistant" :content "a"}] live)
                                           b    (:base snap)]
                                       (-> (expect (.-system b)) (.toBe "S"))
                                       (-> (expect (.-temperature b)) (.toBe 0))
                                       (-> (expect (.-abortSignal b)) (.toBeUndefined))
                                       (-> (expect (.-stopWhen b)) (.toBeUndefined))
                                       (-> (expect (.-onError b)) (.toBeUndefined))
                                       (-> (expect (.-prepareStep b)) (.toBeUndefined))
                                       (-> (expect (.. b -tools -bash -execute)) (.toBeUndefined))
                                       (-> (expect (.. b -tools -bash -description)) (.toBe "d"))
          ;; The live tool keeps its execute.
                                       (-> (expect (fn? (.. cfg -tools -bash -execute))) (.toBe true))
                                       (-> (expect (.. b -messages -length)) (.toBe 2))
                                       (-> (expect (:msg-count snap)) (.toBe 2)))))

                               (it "is usable only while the live conversation is the one it recorded"
                                   (fn []
                                     (let [live  [{:role "user" :content "q"}]
                                           snap  (c/request-snapshot #js {:messages #js []} #js [] live)
                                           state (atom {:messages live :last-request snap})]
                                       (-> (expect (some? (c/usable-snapshot state))) (.toBe true))
          ;; Same length, different conversation (a /resume): not usable.
                                       (swap! state assoc :messages [{:role "user" :content "q"}])
                                       (-> (expect (c/usable-snapshot state)) (.toBeUndefined))
                                       (swap! state assoc :messages (conj live {:role "assistant" :content "a"}))
                                       (-> (expect (c/usable-snapshot state)) (.toBeUndefined)))))))

(describe "compact-with-retry on a request prefix" (fn []
                                                     (it "sends the snapshot's request plus one user turn carrying the breakpoint"
                                                         ^:async (fn []
                                                                   (let [seen (atom [])
                                                                         base (base-with #js [#js {:role "user" :content "q"} (marked "a1") #js {:role "assistant" :content "a2"}])
                                                                         text (js-await (c/compact-with-retry nil "SUMMARISE" [] [] (capture seen six) nil {:base base}))
                                                                         cfg  (first @seen)
                                                                         msgs (.-messages cfg)
                                                                         lst  (aget msgs (dec (.-length msgs)))]
                                                                     (-> (expect text) (.toBe six))
                                                                     (-> (expect (count @seen)) (.toBe 1))
                                                                     (-> (expect (.-system cfg)) (.toBe "SYS"))
                                                                     (-> (expect (.. cfg -providerOptions -anthropic -thinking)) (.toBe "t"))
                                                                     (-> (expect (.. cfg -tools -read -description)) (.toBe "r"))
                                                                     (-> (expect (.-length msgs)) (.toBe 4))
                                                                     (-> (expect (.-content lst)) (.toBe "SUMMARISE"))
                                                                     (-> (expect (.. lst -providerOptions -anthropic -cacheControl -ttl)) (.toBe "1h"))
          ;; The snapshot itself is not mutated.
                                                                     (-> (expect (.. base -messages -length)) (.toBe 3)))))

                                                     (it "adds no breakpoint when three are already placed (the API allows four)"
                                                         ^:async (fn []
                                                                   (let [seen (atom [])
                                                                         base (base-with #js [(marked "a") (marked "b") (marked "c")])
                                                                         _    (js-await (c/compact-with-retry nil "S" [] [] (capture seen six) nil {:base base}))
                                                                         msgs (.-messages (first @seen))]
                                                                     (-> (expect (.-providerOptions (aget msgs 3))) (.toBeUndefined)))))

                                                     (it "the fix-retry continues the same exchange"
                                                         ^:async (fn []
                                                                   (let [seen  (atom [])
                                                                         n     (atom 0)
                                                                         gen   (fn [cfg] (swap! seen conj cfg)
                                                                                 (js/Promise.resolve #js {:text (if (= 1 (swap! n inc)) "bad" six)}))
                                                                         base  (base-with #js [#js {:role "user" :content "q"}])
                                                                         text  (js-await (c/compact-with-retry nil "S" [] [] gen nil {:base base}))
                                                                         msgs  (.-messages (second @seen))]
                                                                     (-> (expect text) (.toBe six))
                                                                     (-> (expect (.-length msgs)) (.toBe 4))
                                                                     (-> (expect (.-content (aget msgs 1))) (.toBe "S"))
                                                                     (-> (expect (.-role (aget msgs 2))) (.toBe "assistant"))
                                                                     (-> (expect (.-content (aget msgs 2))) (.toBe "bad")))))))

(defn- session-with [n]
  (let [sm (create-session-manager nil)]
    (dotimes [i n]
      ((:append sm) {:role "user" :content (str "question number " i)})
      ((:append sm) {:role "assistant" :content (apply str (repeat 300 "padding "))}))
    sm))

(def quiet-events
  {:on (fn [_ _] nil) :emit (fn [_ _] nil)
   :emit-async (fn [_ _] (js/Promise.resolve nil))})

(defn- state-with-snapshot [sm]
  (let [live (vec ((:build-context sm)))]
    (atom {:messages live
           :last-request (c/request-snapshot (base-with #js [#js {:role "user" :content "q"}]) #js [] live)})))

(describe "compact uses the snapshot" (fn []
                                        (it "summarises on the prefix when the snapshot is current"
                                            ^:async (fn []
                                                      (let [sm    (session-with 40)
                                                            state (state-with-snapshot sm)
                                                            seen  (atom [])]
                                                        (js-await (c/compact sm "standalone-model" quiet-events
                                                                             {:gen-fn (capture seen six) :state-atom state :force? true}))
                                                        (-> (expect (.-system (first @seen))) (.toBe "SYS"))
                                                        (-> (expect (.includes (.-content (last (vec (.-messages (first @seen)))))
                                                                               "Do not call any tools"))
                                                            (.toBe true)))))

                                        (it "falls back to the standalone prompt when the prefix call fails"
                                            ^:async (fn []
                                                      (let [sm    (session-with 40)
                                                            state (state-with-snapshot sm)
                                                            seen  (atom [])
                                                            gen   (fn [cfg] (swap! seen conj cfg)
                                                                    (if (= "SYS" (.-system cfg))
                                                                      (js/Promise.reject (js/Error. "boom"))
                                                                      (js/Promise.resolve #js {:text six})))
                                                            res   (js-await (c/compact sm "standalone-model" quiet-events
                                                                                       {:gen-fn gen :state-atom state :force? true}))]
                                                        (-> (expect (:summariser res)) (.toBe "built-in"))
                                                        (-> (expect (.-system (last @seen))) (.toBe c/compact-system-prompt)))))

                                        (it "summarize-only returns the summary and changes nothing"
                                            ^:async (fn []
                                                      (let [sm     (session-with 40)
                                                            state  (state-with-snapshot sm)
                                                            before (count ((:get-tree sm)))
                                                            msgs   (:messages @state)
                                                            res    (js-await (c/compact sm "m" quiet-events
                                                                                        {:gen-fn (capture (atom []) six) :state-atom state
                                                                                         :force? true :summarize-only? true}))]
                                                        (-> (expect (:summary res)) (.toBe six))
                                                        (-> (expect (count ((:get-tree sm)))) (.toBe before))
                                                        (-> (expect (identical? msgs (:messages @state))) (.toBe true)))))

                                        (it "a precomputed summary is applied without a model call"
                                            ^:async (fn []
                                                      (let [sm    (session-with 40)
                                                            state (state-with-snapshot sm)
                                                            seen  (atom [])
                                                            res   (js-await (c/compact sm "m" quiet-events
                                                                                       {:gen-fn (capture seen six) :state-atom state
                                                                                        :force? true :summary six :trigger "idle"}))]
                                                        (-> (expect (count @seen)) (.toBe 0))
                                                        (-> (expect (:summariser res)) (.toBe "extension"))
                                                        (-> (expect (.includes (str (:content (first (:messages @state))))
                                                                               "## 6. Pending Tasks"))
                                                            (.toBe true)))))))

;;; ─── the loop leaves a snapshot compaction can use ───────────────────────

(defn- step [parts]
  (js/Array.prototype.concat.call
   #js [] parts
   #js [#js {:type "finish"
             :finishReason #js {:unified (if (= "tool-call" (.-type (aget parts 0))) "tool-calls" "stop") :raw "x"}
             :usage #js {:inputTokens #js {:total 10} :outputTokens #js {:total 1}}}]))

(defn- scripted [steps]
  (let [left (atom steps)]
    (new MockLanguageModelV4
         #js {:doStream (fn [_]
                          (let [s (first @left)]
                            (swap! left rest)
                            (js/Promise.resolve #js {:stream (convertArrayToReadableStream s)})))})))

(describe "after a real turn" (fn []
  (it "the snapshot matches the live conversation and holds the run's responses"
      ^:async (fn []
        (let [agent (make-test-agent
                     {:model (scripted
                              [(step #js [#js {:type "tool-call" :toolCallId "c1" :toolName "think"
                                               :input "{\"thought\":\"w\"}"}])
                               (step #js [#js {:type "text-start" :id "t"}
                                          #js {:type "text-delta" :id "t" :delta "done"}
                                          #js {:type "text-end" :id "t"}])])})]
          (js-await (run agent "go"))
          (let [snap (c/usable-snapshot (:state agent))
                b    (:base snap)
                roles (mapv #(.-role %) (.-messages b))]
            (-> (expect (some? snap)) (.toBe true))
            ;; user prompt, the tool call, its result, the final answer
            (-> (expect (first roles)) (.toBe "user"))
            (-> (expect (last roles)) (.toBe "assistant"))
            (-> (expect (> (count roles) 2)) (.toBe true))
            (-> (expect (some (fn [k] (fn? (.-execute (aget (.-tools b) k))))
                              (js/Object.keys (.-tools b))))
                (.toBeFalsy))))))))

(describe "summarize-only without a usable snapshot" (fn []
  (it "makes no model call: the standalone prompt would pay full price"
      ^:async (fn []
        (let [sm   (session-with 40)
              seen (atom [])
              res  (js-await (c/compact sm "m" quiet-events
                                        {:gen-fn (capture seen six)
                                         :state-atom (atom {:messages []})
                                         :force? true :summarize-only? true}))]
          (-> (expect (count @seen)) (.toBe 0))
          (-> (expect (:summary res)) (.toBeFalsy)))))))

(describe "compaction.enabled false" (fn []
  (it "turns off the idle path, which forces past the threshold"
      ^:async (fn []
        (let [sm    (session-with 40)
              state (state-with-snapshot sm)
              seen  (atom [])
              res   (js-await (c/compact sm "m" quiet-events
                                         {:gen-fn (capture seen six) :state-atom state
                                          :force? true :summarize-only? true
                                          :trigger "idle" :enabled? false}))]
          (-> (expect res) (.toBeFalsy))
          (-> (expect (count @seen)) (.toBe 0)))))))

(describe "breakpoint from the system block" (fn []
  (it "after a one-turn run no message is marked, so the system block's dialect is used"
      ^:async (fn []
        (let [seen (atom [])
              base (doto (base-with #js [#js {:role "user" :content "q"} #js {:role "assistant" :content "a"}])
                     (aset "system" #js [#js {:role "system" :content "stable"
                                              :providerOptions #js {:anthropic #js {:cacheControl #js {:type "ephemeral"}}}}
                                         #js {:role "system" :content "dynamic"}]))
              _    (js-await (c/compact-with-retry nil "S" [] [] (capture seen six) nil {:base base}))
              msgs (.-messages (first @seen))]
          (-> (expect (.. (aget msgs 2) -providerOptions -anthropic -cacheControl -type)) (.toBe "ephemeral")))))))
