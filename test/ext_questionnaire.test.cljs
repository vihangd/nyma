(ns ext-questionnaire.test
  "Tests for the questionnaire extension tool.
   Covers schema validation, happy-path flows, cancellation/abort,
   isSecret handling, and extension lifecycle."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.questionnaire.index :as q-ext]))

;;; ─── helpers ─────────────────────────────────────────────────

(defn- make-mock-api
  "Build a mock API with a controllable ui.select and ui.input.
   :select-answers is a vector of values to return in sequence.
   :input-answers is a vector of values to return in sequence.
   Passing nil as a value simulates cancellation."
  [{:keys [select-answers input-answers ui-available?]
    :or   {select-answers [] input-answers [] ui-available? true}}]
  (let [select-idx (atom 0)
        input-idx  (atom 0)
        tools      (atom {})
        exec-end-payloads (atom [])]
    #js {:registerTool
         (fn [name spec]
           (swap! tools assoc name spec))
         :unregisterTool
         (fn [name]
           (swap! tools dissoc name))
         :getAll
         (fn [] (clj->js @tools))
         :on   (fn [_ _ _] nil)
         :off  (fn [_ _] nil)
         :emit (fn [evt data]
                 (when (= evt "tool_execution_end")
                   (swap! exec-end-payloads conj data)))
         :ui
         #js {:available  ui-available?
              :select     (fn [_title _options _opts]
                            (let [idx @select-idx
                                  ans (nth select-answers idx nil)]
                              (swap! select-idx inc)
                              (js/Promise.resolve ans)))
              :input      (fn [_title _placeholder _opts]
                            (let [idx @input-idx
                                  ans (nth input-answers idx nil)]
                              (swap! input-idx inc)
                              (js/Promise.resolve ans)))}
         :_tools           tools
         :_exec-end        exec-end-payloads}))

(defn- activate [api]
  ((.-default q-ext) api))

(defn- get-tool [api]
  (get @(.-_tools api) "questionnaire"))

(defn- execute [api args & [ctx]]
  (let [tool (get-tool api)]
    ((.-execute tool) args (or ctx nil))))

;;; ─── activation / lifecycle ──────────────────────────────────

(describe "questionnaire:lifecycle"
          (fn []
            (it "activation registers the questionnaire tool"
                (fn []
                  (let [api (make-mock-api {})]
                    (activate api)
                    (-> (expect (some? (get-tool api))) (.toBe true)))))

            (it "activation returns a deactivator function"
                (fn []
                  (let [api   (make-mock-api {})
                        deact (activate api)]
                    (-> (expect (fn? deact)) (.toBe true)))))

            (it "deactivation removes the tool"
                (fn []
                  (let [api   (make-mock-api {})
                        deact (activate api)]
                    (deact)
                    (-> (expect (nil? (get-tool api))) (.toBe true)))))

            (it "re-activation after deactivation works"
                (fn []
                  (let [api   (make-mock-api {})
                        deact (activate api)]
                    (deact)
                    (activate api)
                    (-> (expect (some? (get-tool api))) (.toBe true)))))))

;;; ─── schema validation ───────────────────────────────────────

(describe "questionnaire:schema-validation"
          (fn []
            (it "empty questions array → throws"
                (fn []
                  (let [api (make-mock-api {})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js []}))
                        (.rejects.toThrow)))))

            (it "missing id → throws"
                (fn []
                  (let [api (make-mock-api {})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js [#js {:prompt "What?"}]}))
                        (.rejects.toThrow)))))

            (it "missing prompt → throws"
                (fn []
                  (let [api (make-mock-api {})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js [#js {:id "q1"}]}))
                        (.rejects.toThrow)))))

            (it "duplicate question ids → throws"
                (fn []
                  (let [api (make-mock-api {:input-answers ["a" "b"]})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js [#js {:id "q1" :prompt "Q1"}
                                                                  #js {:id "q1" :prompt "Q2"}]}))
                        (.rejects.toThrow)))))

            (it "duplicate option values in same question → throws"
                (fn []
                  (let [api (make-mock-api {})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js [#js {:id "q1"
                                                                       :prompt "Pick"
                                                                       :options #js [#js {:value "a" :label "A"}
                                                                                     #js {:value "a" :label "A2"}]}]}))
                        (.rejects.toThrow)))))

            (it "single valid question passes validation"
                (fn []
                  (let [api (make-mock-api {:input-answers ["hello"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1" :prompt "Q?"}]})
                        (.then (fn [r] (-> (expect (some? r)) (.toBe true))))))))))

;;; ─── happy path: text input ──────────────────────────────────

(describe "questionnaire:text-input"
          (fn []
            (it "single text question returns typed answer"
                (fn []
                  (let [api (make-mock-api {:input-answers ["hello world"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1" :prompt "What's your name?"}]})
                        (.then (fn [r]
                                 (let [answers (.-answers r)]
                                   (-> (expect (.-length answers)) (.toBe 1))
                                   (-> (expect (.-value (aget answers 0))) (.toBe "hello world"))
                                   (-> (expect (.-id (aget answers 0))) (.toBe "q1"))
                                   (-> (expect (.-wasCustom (aget answers 0))) (.toBe false)))))))))

            (it "multi-question answers are returned in order"
                (fn []
                  (let [api (make-mock-api {:input-answers ["Alice" "42" "blue"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "name"  :prompt "Name?"}
                                                          #js {:id "age"   :prompt "Age?"}
                                                          #js {:id "color" :prompt "Color?"}]})
                        (.then (fn [r]
                                 (let [answers (.-answers r)]
                                   (-> (expect (.-length answers)) (.toBe 3))
                                   (-> (expect (.-value (aget answers 0))) (.toBe "Alice"))
                                   (-> (expect (.-id (aget answers 0))) (.toBe "name"))
                                   (-> (expect (.-value (aget answers 1))) (.toBe "42"))
                                   (-> (expect (.-value (aget answers 2))) (.toBe "blue")))))))))))

;;; ─── happy path: option picker ───────────────────────────────

(describe "questionnaire:option-picker"
          (fn []
            (it "user picks an option → wasCustom false, value matches"
                (fn []
        ;; select returns the option object: {:value "yes" :label "Yes"}
                  (let [api (make-mock-api {:select-answers [#js {:value "yes" :label "Yes" :wasCustom false}]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1"
                                                               :prompt "Proceed?"
                                                               :options #js [#js {:value "yes" :label "Yes"}
                                                                             #js {:value "no"  :label "No"}]}]})
                        (.then (fn [r]
                                 (let [a (aget (.-answers r) 0)]
                                   (-> (expect (.-value a)) (.toBe "yes"))
                                   (-> (expect (.-wasCustom a)) (.toBe false)))))))))

            (it "model-text summary is included in result"
                (fn []
                  (let [api (make-mock-api {:input-answers ["custom text"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1" :prompt "Q?"}]})
                        (.then (fn [r]
                                 (-> (expect (.includes (.-text r) "User answered:")) (.toBe true))
                                 (-> (expect (.includes (.-text r) "q1=")) (.toBe true))))))))

            (it "type-own sentinel → falls back to text input with wasCustom true"
                (fn []
        ;; select returns the sentinel option; input returns the typed value
                  (let [sentinel "__questionnaire_type_own__"
                        api      (make-mock-api
                                  {:select-answers [#js {:value sentinel :label "Type your own answer…"}]
                                   :input-answers  ["my custom answer"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1"
                                                               :prompt "Pick or type"
                                                               :options #js [#js {:value "a" :label "A"}]}]})
                        (.then (fn [r]
                                 (let [a (aget (.-answers r) 0)]
                                   (-> (expect (.-value a)) (.toBe "my custom answer"))
                                   (-> (expect (.-wasCustom a)) (.toBe true)))))))))))

;;; ─── cancellation ────────────────────────────────────────────

(describe "questionnaire:cancellation"
          (fn []
            (it "nil from text input → returns cancelled result"
                (fn []
                  (let [api (make-mock-api {:input-answers [nil]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1" :prompt "Q?"}]})
                        (.then (fn [r]
                                 (-> (expect (.-cancelled r)) (.toBe true))
                                 (-> (expect (.-length (.-answers r))) (.toBe 0))))))))

            (it "nil from select → returns cancelled result"
                (fn []
                  (let [api (make-mock-api {:select-answers [nil]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1"
                                                               :prompt "Pick"
                                                               :options #js [#js {:value "x" :label "X"}]}]})
                        (.then (fn [r]
                                 (-> (expect (.-cancelled r)) (.toBe true))))))))

            (it "cancel mid-questionnaire preserves already-answered questions"
                (fn []
                  (let [api (make-mock-api {:input-answers ["first answer" nil]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "q1" :prompt "Q1?"}
                                                          #js {:id "q2" :prompt "Q2?"}]})
                        (.then (fn [r]
                                 (-> (expect (.-cancelled r)) (.toBe true))
                                 (let [answers (.-answers r)]
                                   (-> (expect (.-length answers)) (.toBe 1))
                                   (-> (expect (.-value (aget answers 0))) (.toBe "first answer")))))))))

            (it "pre-aborted signal → throws before first question"
                (fn []
                  (let [ctrl  (js/AbortController.)
                        _     (.abort ctrl)
                        api   (make-mock-api {:input-answers ["should not be called"]})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js [#js {:id "q1" :prompt "Q?"}]}
                                         #js {:abortSignal (.-signal ctrl)}))
                        (.rejects.toThrow)))))))

;;; ─── isSecret handling ───────────────────────────────────────

(describe "questionnaire:isSecret"
          (fn []
            (it "secret answer is NOT in model-text summary"
                (fn []
                  (let [api (make-mock-api {:input-answers ["hunter2"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "pass" :prompt "Password?" :isSecret true}]})
                        (.then (fn [r]
                                 (-> (expect (.includes (.-text r) "hunter2")) (.toBe false))
                                 (-> (expect (.includes (.-text r) "[secret]")) (.toBe true))))))))

            (it "secret answer IS in the answers array (model can read it)"
                (fn []
                  (let [api (make-mock-api {:input-answers ["hunter2"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "pass" :prompt "Password?" :isSecret true}]})
                        (.then (fn [r]
                                 (let [a (aget (.-answers r) 0)]
                                   (-> (expect (.-value a)) (.toBe "hunter2")))))))))

            (it "isSecret is not present in answer objects"
                (fn []
                  (let [api (make-mock-api {:input-answers ["secret"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "p" :prompt "P?" :isSecret true}]})
                        (.then (fn [r]
                                 (let [a (aget (.-answers r) 0)]
                         ;; :isSecret should be stripped from the returned answer
                                   (-> (expect (undefined? (.-isSecret a))) (.toBe true)))))))))

            (it "non-secret answer appears in model-text"
                (fn []
                  (let [api (make-mock-api {:input-answers ["blue"]})]
                    (activate api)
                    (-> (execute api #js {:questions #js [#js {:id "color" :prompt "Color?"}]})
                        (.then (fn [r]
                                 (-> (expect (.includes (.-text r) "blue")) (.toBe true))))))))))

;;; ─── UI unavailable ──────────────────────────────────────────

(describe "questionnaire:ui-unavailable"
          (fn []
            (it "ui.available false → throws with message"
                (fn []
                  (let [api (make-mock-api {:ui-available? false})]
                    (activate api)
                    (-> (expect (execute api #js {:questions #js [#js {:id "q1" :prompt "Q?"}]}))
                        (.rejects.toThrow "UI not available")))))))
