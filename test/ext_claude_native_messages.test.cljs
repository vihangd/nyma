(ns ext-claude-native-messages.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-claude-native.messages :as msgs]))

;; ── Helpers ──────────────────────────────────────────────────

(defn- make-opts
  "Build a minimal LanguageModelV3CallOptions-shaped JS object."
  ([prompt]
   #js {:prompt (clj->js prompt) :maxOutputTokens nil :temperature nil})
  ([prompt max-tokens]
   #js {:prompt (clj->js prompt) :maxOutputTokens max-tokens :temperature nil})
  ([prompt max-tokens temperature]
   #js {:prompt (clj->js prompt) :maxOutputTokens max-tokens :temperature temperature}))

(defn- text-part [t]
  #js {:type "text" :text t})

(defn- sys-msg [content]
  #js {:role "system" :content content})

(defn- user-msg [& texts]
  #js {:role "user" :content (clj->js (mapv text-part texts))})

(defn- asst-msg [& texts]
  #js {:role "assistant" :content (clj->js (mapv text-part texts))})

;; ── Basic request body shape ─────────────────────────────────

(describe "claude-native/messages — request body shape" (fn []
                                                          (it "sets model and stream:true"
                                                              (fn []
                                                                (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(user-msg "hi")]))]
                                                                  (-> (expect (get body "model"))  (.toBe "claude-sonnet-4-6"))
                                                                  (-> (expect (get body "stream")) (.toBe true)))))

                                                          (it "uses default max_tokens when not specified"
                                                              (fn []
                                                                (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(user-msg "hi")]))]
                                                                  (-> (expect (get body "max_tokens")) (.toBeGreaterThan 0)))))

                                                          (it "uses caller-specified max_tokens"
                                                              (fn []
                                                                (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(user-msg "hi")] 1024))]
                                                                  (-> (expect (get body "max_tokens")) (.toBe 1024)))))

                                                          (it "omits temperature when not provided"
                                                              (fn []
                                                                (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(user-msg "hi")]))]
                                                                  (-> (expect (contains? body "temperature")) (.toBe false)))))

                                                          (it "includes temperature when provided"
                                                              (fn []
                                                                (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(user-msg "hi")] nil 0.7))]
                                                                  (-> (expect (get body "temperature")) (.toBe 0.7)))))))

;; ── System message extraction ─────────────────────────────────

(describe "claude-native/messages — system extraction" (fn []
                                                         (it "extracts system string from system-role message"
                                                             (fn []
                                                               (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                   (make-opts [(sys-msg "You are helpful.")
                                                                                                               (user-msg "hi")]))]
                                                                 (-> (expect (get body "system")) (.toBe "You are helpful.")))))

                                                         (it "omits system key when no system messages present"
                                                             (fn []
                                                               (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                   (make-opts [(user-msg "hi")]))]
                                                                 (-> (expect (contains? body "system")) (.toBe false)))))

                                                         (it "joins multiple system messages with double newline"
                                                             (fn []
                                                               (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                   (make-opts [(sys-msg "Part 1.")
                                                                                                               (sys-msg "Part 2.")
                                                                                                               (user-msg "hi")]))]
                                                                 (-> (expect (.includes (get body "system") "Part 1.")) (.toBe true))
                                                                 (-> (expect (.includes (get body "system") "Part 2.")) (.toBe true)))))

                                                         (it "does not include system messages in messages array"
                                                             (fn []
                                                               (let [body  (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(sys-msg "System.")
                                                                                                                (user-msg "User msg")]))
                                                                     msgs  (get body "messages")
                                                                     roles (mapv #(.-role %) msgs)]
                                                                 (-> (expect (contains? (set roles) "system")) (.toBe false)))))))

;; ── Message conversion ────────────────────────────────────────

(describe "claude-native/messages — message conversion" (fn []
                                                          (it "converts user text message"
                                                              (fn []
                                                                (let [body      (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                         (make-opts [(user-msg "Hello")]))
                                                                      first-msg (aget (get body "messages") 0)]
                                                                  (-> (expect (.-role first-msg)) (.toBe "user"))
                                                                  (-> (expect (.-type (aget (.-content first-msg) 0))) (.toBe "text"))
                                                                  (-> (expect (.-text (aget (.-content first-msg) 0))) (.toBe "Hello")))))

                                                          (it "converts assistant text message"
                                                              (fn []
                                                                (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                    (make-opts [(user-msg "Hi")
                                                                                                                (asst-msg "Hello back")]))
                                                                      msgs (get body "messages")
                                                                      asst (aget msgs 1)]
                                                                  (-> (expect (.-role asst)) (.toBe "assistant"))
                                                                  (-> (expect (.-text (aget (.-content asst) 0))) (.toBe "Hello back")))))

                                                          (it "preserves message order"
                                                              (fn []
                                                                (let [body  (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                     (make-opts [(user-msg "Q1")
                                                                                                                 (asst-msg "A1")
                                                                                                                 (user-msg "Q2")]))
                                                                      msgs  (get body "messages")
                                                                      roles (mapv #(.-role %) msgs)]
                                                                  (-> (expect (first roles))  (.toBe "user"))
                                                                  (-> (expect (second roles)) (.toBe "assistant"))
                                                                  (-> (expect (nth roles 2))  (.toBe "user")))))

                                                          (it "concatenates multiple text parts in one user message"
                                                              (fn []
                                                                (let [body  (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                     (make-opts [(user-msg "A" "B")]))
                                                                      msg   (aget (get body "messages") 0)
                                                                      parts (.-content msg)]
                                                                  (-> (expect (.-length parts)) (.toBe 2))
                                                                  (-> (expect (.-text (aget parts 0))) (.toBe "A"))
                                                                  (-> (expect (.-text (aget parts 1))) (.toBe "B")))))))

;; ── Tool round-trip ───────────────────────────────────────────

(defn- tool-call-part [id name input-json]
  #js {:type "tool-call" :toolCallId id :toolName name :input input-json})

(defn- tool-result-part
  ([id name text-val]
   #js {:type "tool-result" :toolCallId id :toolName name
        :output #js {:type "text" :value text-val}})
  ([id name output-type output-val]
   #js {:type "tool-result" :toolCallId id :toolName name
        :output #js {:type output-type :value output-val}}))

(defn- asst-with-tool-call [id name input-json]
  #js {:role "assistant"
       :content #js [#js {:type "text" :text "using tool"}
                     (tool-call-part id name input-json)]})

(defn- tool-role-msg [& results]
  #js {:role "tool" :content (clj->js results)})

(describe "claude-native/messages — tool round-trip" (fn []
                                                       (it "converts tool-call part in assistant message to tool_use block"
                                                           (fn []
                                                             (let [body  (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                  (make-opts [(user-msg "go")
                                                                                                              (asst-with-tool-call "tc_1" "bash" "{\"cmd\":\"ls\"}")]))
                                                                   msgs  (get body "messages")
                                                                   asst  (aget msgs 1)
                                                                   parts (.-content asst)
                                                                   tu    (aget parts 1)]
                                                               (-> (expect (.-type tu))  (.toBe "tool_use"))
                                                               (-> (expect (.-id tu))    (.toBe "tc_1"))
                                                               (-> (expect (.-name tu))  (.toBe "bash"))
                                                               (-> (expect (.-cmd (.-input tu))) (.toBe "ls")))))

                                                       (it "converts tool role message to user role with tool_result blocks"
                                                           (fn []
                                                             (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                 (make-opts [(user-msg "go")
                                                                                                             (asst-with-tool-call "tc_1" "bash" "{}")
                                                                                                             (tool-role-msg (tool-result-part "tc_1" "bash" "output text"))]))
                                                                   msgs (get body "messages")
                                                                   tr-msg (aget msgs 2)]
                                                               (-> (expect (.-role tr-msg)) (.toBe "user"))
                                                               (let [tr (aget (.-content tr-msg) 0)]
                                                                 (-> (expect (.-type tr))        (.toBe "tool_result"))
                                                                 (-> (expect (.-tool_use_id tr)) (.toBe "tc_1"))
                                                                 (-> (expect (.-content tr))     (.toBe "output text"))))))

                                                       (it "sets is_error on error-text tool results"
                                                           (fn []
                                                             (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                 (make-opts [(user-msg "go")
                                                                                                             (asst-with-tool-call "tc_1" "bash" "{}")
                                                                                                             (tool-role-msg (tool-result-part "tc_1" "bash" "error-text" "command failed"))]))
                                                                   msgs (get body "messages")
                                                                   tr   (aget (.-content (aget msgs 2)) 0)]
                                                               (-> (expect (.-is_error tr)) (.toBe true))
                                                               (-> (expect (.-content tr))  (.toBe "command failed")))))

                                                       (it "serialises json output as JSON string"
                                                           (fn []
                                                             (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                                                                                 (make-opts [(user-msg "go")
                                                                                                             (asst-with-tool-call "tc_1" "bash" "{}")
                                                                                                             (tool-role-msg (tool-result-part "tc_1" "bash" "json" #js {:exit 0}))]))
                                                                   msgs (get body "messages")
                                                                   tr   (aget (.-content (aget msgs 2)) 0)]
                                                               (-> (expect (.-exit (js/JSON.parse (.-content tr)))) (.toBe 0)))))

                                                       (it "full turn round-trip: user → assistant(tool_use) → tool_result → assistant"
                                                           (fn []
                                                             (let [prompt [(user-msg "do it")
                                                                           (asst-with-tool-call "tc_1" "bash" "{\"cmd\":\"pwd\"}")
                                                                           (tool-role-msg (tool-result-part "tc_1" "bash" "/home/user"))
                                                                           #js {:role "assistant"
                                                                                :content #js [#js {:type "text" :text "Done."}]}]
                                                                   body   (msgs/build-request-body "claude-sonnet-4-6" (make-opts prompt))
                                                                   msgs   (get body "messages")
                                                                   roles  (mapv #(.-role %) msgs)]
                                                               (-> (expect (nth roles 0)) (.toBe "user"))
                                                               (-> (expect (nth roles 1)) (.toBe "assistant"))
                                                               (-> (expect (nth roles 2)) (.toBe "user"))
                                                               (-> (expect (nth roles 3)) (.toBe "assistant")))))))
