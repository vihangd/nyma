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

;;; ─── Extended thinking ──────────────────────────────────────────
;;; agent.thinking routes the `claude-native` provider tag to the anthropic
;;; dialect, but this provider is hand-rolled and reads providerOptions itself.
;;; Before this it ignored them, so /thinking reported a level and changed
;;; nothing on the wire — the silent no-op the routing exists to prevent.

(defn- body-with-thinking [budget max-out]
  (msgs/build-request-body
   "claude-opus-5"
   #js {:prompt #js [#js {:role "user" :content #js [#js {:type "text" :text "hi"}]}]
        :maxOutputTokens max-out
        :providerOptions #js {:anthropic #js {:thinking #js {:type "enabled"
                                                             :budgetTokens budget}}}}))

(describe "claude-native — extended thinking"
  (fn []
    (it "emits an enabled thinking block from providerOptions"
        (fn []
          (let [body (body-with-thinking 24000 2048)]
            (-> (expect (get (get body "thinking") "type")) (.toBe "enabled"))
            (-> (expect (get (get body "thinking") "budget_tokens")) (.toBe 24000)))))

    (it "raises max_tokens above the budget"
        (fn []
          ;; Anthropic counts reasoning against max_tokens and rejects a request
          ;; whose budget is not strictly smaller — a flat 2048 cap would 400.
          (let [body (body-with-thinking 24000 2048)]
            (-> (expect (get body "max_tokens")) (.toBeGreaterThan 24000)))))

    (it "leaves a sufficient max_tokens alone"
        (fn []
          (let [body (body-with-thinking 4000 64000)]
            (-> (expect (get body "max_tokens")) (.toBe 64000)))))

    (it "emits nothing when providerOptions carry no thinking"
        (fn []
          (let [body (msgs/build-request-body
                      "claude-opus-5"
                      #js {:prompt #js [#js {:role "user"
                                             :content #js [#js {:type "text" :text "hi"}]}]
                           :maxOutputTokens 2048
                           :providerOptions #js {}})]
            (-> (expect (get body "thinking")) (.toBeUndefined))
            (-> (expect (get body "max_tokens")) (.toBe 2048)))))

    ;; Forwarded, not ignored: Opus 5 / Sonnet 5 THINK when the field is
    ;; omitted, so agent.thinking sends an explicit disable for `off`, and
    ;; dropping it here turned `off` into thinking at full effort.
    (it "forwards a disabled thinking block"
        (fn []
          (let [body (msgs/build-request-body
                      "claude-opus-5"
                      #js {:prompt #js [#js {:role "user"
                                             :content #js [#js {:type "text" :text "hi"}]}]
                           :providerOptions #js {:anthropic #js {:thinking #js {:type "disabled"}}}})]
            (-> (expect (get-in body ["thinking" "type"])) (.toBe "disabled")))))))

;; ── Prompt-cache breakpoints ─────────────────────────────────

(defn- cc [] #js {:anthropic #js {:cacheControl #js {:type "ephemeral" :ttl "1h"}}})

(describe "claude-native/messages — cache breakpoints reach the wire" (fn []
  (it "a marked message carries cache_control on its last block"
      (fn []
        (let [m    (doto (user-msg "a" "b") (aset "providerOptions" (cc)))
              body (msgs/build-request-body "claude-sonnet-4-6" #js {:prompt #js [m]})
              blocks (.-content (aget (get body "messages") 0))]
          (-> (expect (.-cache_control (aget blocks 0))) (.toBeUndefined))
          (-> (expect (.. (aget blocks 1) -cache_control -ttl)) (.toBe "1h")))))

  (it "a marked system message turns the system prompt into blocks"
      (fn []
        (let [s1   (doto (sys-msg "stable") (aset "providerOptions" (cc)))
              body (msgs/build-request-body "claude-sonnet-4-6"
                                            #js {:prompt #js [s1 (sys-msg "dynamic") (user-msg "hi")]})
              sys  (get body "system")]
          (-> (expect (.-length sys)) (.toBe 2))
          (-> (expect (.. (aget sys 0) -cache_control -type)) (.toBe "ephemeral"))
          (-> (expect (.-text (aget sys 1))) (.toBe "dynamic")))))

  (it "unmarked, the system prompt stays one string"
      (fn []
        (let [body (msgs/build-request-body "claude-sonnet-4-6"
                                            #js {:prompt #js [(sys-msg "a") (sys-msg "b") (user-msg "hi")]})]
          (-> (expect (get body "system")) (.toBe "a\n\nb")))))))

(describe "claude-native/messages — breakpoint budget" (fn []
  (it "drops the oldest message marks past four"
      (fn []
        (let [mk   (fn [t] (doto (user-msg t) (aset "providerOptions" (cc))))
              s1   (doto (sys-msg "stable") (aset "providerOptions" (cc)))
              body (msgs/build-request-body "claude-sonnet-4-6"
                                            #js {:prompt #js [s1 (mk "a") (asst-msg "x") (mk "b")
                                                              (asst-msg "y") (mk "c") (asst-msg "z") (mk "d")]})
              ms   (get body "messages")
              marked (fn [i] (some? (.-cache_control (aget (.-content (aget ms i)) 0))))]
          (-> (expect (marked 0)) (.toBe false))
          (-> (expect (marked 2)) (.toBe true))
          (-> (expect (marked 6)) (.toBe true)))))))

(describe "claude-native/messages — thinking" (fn []
  (it "drops temperature when thinking is on (Anthropic 400s on it)"
    (fn []
      (let [o    (make-opts [(user-msg "hi")] 4096 0.2)
            _    (aset o "providerOptions" #js {:anthropic #js {:thinking #js {:type "enabled" :budgetTokens 1024}}})
            body (msgs/build-request-body "claude-haiku-4-5-20251001" o)]
        (-> (expect (contains? body "temperature")) (.toBe false))
        (-> (expect (get-in body ["thinking" "type"])) (.toBe "enabled")))))

  (it "keeps temperature when thinking is off"
    (fn []
      (let [body (msgs/build-request-body "claude-haiku-4-5-20251001" (make-opts [(user-msg "hi")] 4096 0.2))]
        (-> (expect (get body "temperature")) (.toBe 0.2)))))

  (it "replays a signed reasoning part as a thinking block, drops an unsigned one"
    (fn []
      (let [asst #js {:role "assistant"
                      :content #js [#js {:type "reasoning" :text "plan"
                                         :providerOptions #js {:anthropic #js {:signature "s"}}}
                                    #js {:type "reasoning" :text "nosig"}
                                    (text-part "ok")]}
            body (msgs/build-request-body "claude-haiku-4-5-20251001" (make-opts [(user-msg "hi") asst (user-msg "more")]))
            c    (get-in body ["messages" 1 "content"])]
        (-> (expect (count c)) (.toBe 2))
        (-> (expect (.-type (first c))) (.toBe "thinking"))
        (-> (expect (.-signature (first c))) (.toBe "s")))))

  (it "replays redacted reasoning as a redacted_thinking block"
    (fn []
      (let [asst #js {:role "assistant"
                      :content #js [#js {:type "reasoning" :text ""
                                         :providerOptions #js {:anthropic #js {:redactedData "ENC"}}}
                                    (text-part "ok")]}
            body (msgs/build-request-body "claude-haiku-4-5-20251001" (make-opts [(user-msg "hi") asst (user-msg "more")]))
            b    (first (get-in body ["messages" 1 "content"]))]
        (-> (expect (.-type b)) (.toBe "redacted_thinking"))
        (-> (expect (.-data b)) (.toBe "ENC")))))))

(describe "claude-native/messages — breakpoints and thinking" (fn []
  (it "a message breakpoint skips a trailing thinking block"
    (fn []
      (let [asst #js {:role "assistant"
                      :providerOptions #js {:anthropic #js {:cacheControl #js {:type "ephemeral"}}}
                      :content #js [(text-part "ok")
                                    #js {:type "reasoning" :text "plan"
                                         :providerOptions #js {:anthropic #js {:signature "s"}}}]}
            body (msgs/build-request-body "claude-haiku-4-5-20251001" (make-opts [(user-msg "hi") asst (user-msg "more")]))
            c    (get-in body ["messages" 1 "content"])]
        (-> (expect (.-cache_control (first c))) (.toBeDefined))
        (-> (expect (.-cache_control (second c))) (.toBeUndefined)))))))

(describe "claude-native/messages — server compaction" (fn []
  (it "sends context_management in wire shape"
    (fn []
      (let [o (make-opts [(user-msg "hi")] 4096)
            _ (aset o "providerOptions" #js {:anthropic #js {:contextManagement
                                                             #js {:edits #js [#js {:type "compact_20260112"
                                                                                  :trigger #js {:type "input_tokens" :value 50000}
                                                                                  :pauseAfterCompaction false}]}}})
            b (msgs/build-request-body "claude-sonnet-4-6" o)
            e (first (get-in b ["context_management" "edits"]))]
        (-> (expect (get e "type")) (.toBe "compact_20260112"))
        (-> (expect (get e "pause_after_compaction")) (.toBe false))
        (-> (expect (.-value (get e "trigger"))) (.toBe 50000)))))
  (it "replays a compaction-tagged text part as a compaction block"
    (fn []
      (let [asst #js {:role "assistant"
                      :content #js [#js {:type "text" :text "## Summary"
                                         :providerOptions #js {:anthropic #js {:type "compaction"}}}
                                    (text-part "answer")]}
            b    (msgs/build-request-body "claude-sonnet-4-6" (make-opts [(user-msg "hi") asst (user-msg "more")]))
            c    (get-in b ["messages" 1 "content"])]
        (-> (expect (.-type (first c))) (.toBe "compaction"))
        (-> (expect (.-content (first c))) (.toBe "## Summary")))))))
