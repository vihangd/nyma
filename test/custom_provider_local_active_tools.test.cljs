(ns custom-provider-local-active-tools.test
  "The tool-call rescue parser validates every rescued call against the active
   tool names — `(contains? available tool-name)` in toolcall_adapter. It was
   fed `Object.keys` of `getAllTools`, which returns an ARRAY of names
   (extensions.cljs:45), so the set was #{\"0\" \"1\" \"2\" …} and every rescued
   call was thrown away. The rescue existed, was configured, and produced
   nothing — on exactly the local models it was written for.

   The identical mistake was live in small_model/quality_monitor at the same
   time, where it destroyed every tool result instead. Two independent
   consumers, one ambiguous return shape; these tests pin both shapes."
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-local.index :as local]
            [agent.extensions.custom-provider-local.toolcall-adapter :as adapter]))

(defn- names-from [tools]
  ((local/active-tools-fn #js {:getAllTools (fn [] tools)})))

(describe "custom-provider-local/active-tools-fn"
          (fn []
            (it "reads the ARRAY of names getAllTools actually returns"
                (fn []
                  (let [s (names-from #js ["read" "write" "bash"])]
                    (-> (expect (contains? s "read")) (.toBe true))
                    (-> (expect (contains? s "bash")) (.toBe true))
            ;; the bug: indices instead of names
                    (-> (expect (contains? s "0")) (.toBe false)))))

            (it "also accepts an object-shaped tool map"
                (fn []
                  (let [s (names-from #js {:read #js {} :write #js {}})]
                    (-> (expect (contains? s "read")) (.toBe true)))))

            (it "returns an empty set rather than throwing when the host gives nothing"
                (fn []
                  (-> (expect (count (names-from nil))) (.toBe 0))
                  (-> (expect (count ((local/active-tools-fn
                                       #js {:getAllTools (fn [] (throw (js/Error. "boom")))}))))
                      (.toBe 0))))))

;;; ─── the rescued chunk must actually reach the consumer ──────────────────
;;;
;;; The transform mutated the PARSED chunk object and then enqueued the
;;; original, unmodified SSE line — so every rescued tool call was silently
;;; dropped and the turn arrived as plain text. The rescue ran, matched, and
;;; changed nothing.

(defn- sse-response [chunks]
  (let [encoder (js/TextEncoder.)
        body (js/ReadableStream.
              #js {:start (fn [controller]
                            (doseq [c chunks]
                              (.enqueue controller (.encode encoder c)))
                            (.close controller))})]
    (js/Response. body #js {:headers #js {"content-type" "text/event-stream"}})))

(def ^:private qwen-xml-chunks
  #js ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"<function=read><parameter=path>main.go</parameter></function>\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{},\"index\":0,\"finish_reason\":\"stop\"}]}\n\n"
       "data: [DONE]\n\n"])

(describe "custom-provider-local/wrap-fetch-with-rescue"
          (fn []
            (it "emits the injected tool_calls, not the original chunk"
                (^:async fn []
                 (let [fetch (adapter/wrap-fetch-with-rescue
                              (fn [_url _init] (js/Promise.resolve (sse-response qwen-xml-chunks)))
                              (fn [] #{"read"}))
                       out   (js-await (.text (js-await (fetch "http://x/v1/chat/completions" nil))))]
                   (-> (expect (.includes out "tool_calls")) (.toBe true))
                   (-> (expect (.includes out "\"name\":\"read\"")) (.toBe true))
                   (-> (expect (.includes out "main.go")) (.toBe true)))))

            (it "passes chunks through untouched when nothing is rescued"
                (^:async fn []
                 (let [fetch (adapter/wrap-fetch-with-rescue
                              (fn [_url _init] (js/Promise.resolve (sse-response qwen-xml-chunks)))
                              (fn [] #{}))
                       out   (js-await (.text (js-await (fetch "http://x/v1/chat/completions" nil))))]
                   (-> (expect (.includes out "tool_calls")) (.toBe false))
                   (-> (expect (.includes out "\"finish_reason\":\"stop\"")) (.toBe true)))))))

;; ── Through the real AI SDK ──────────────────────────────────
;;
;; The rescue's output has to satisfy @ai-sdk/openai's chunk schema, and only
;; the real schema can tell us that. `index` is required on a tool_calls entry
;; while every sibling field is .nullish(), so omitting it fails validation and
;; the SDK converts a bad chunk into an ERRORED STREAM — the rescue would end
;; the turn rather than call a tool. A hand-rolled assertion on the emitted
;; bytes cannot catch that.

(defn- rescue-fetch* [chunks tools]
  (adapter/wrap-fetch-with-rescue
   (fn [_url _init]
     (js/Promise.resolve
      (js/Response.
       (js/ReadableStream.
        #js {:start (fn [ctrl]
                      (let [enc (js/TextEncoder.)]
                        (doseq [c chunks] (.enqueue ctrl (.encode enc c)))
                        (.close ctrl)))})
       #js {:status 200 :headers #js {"content-type" "text/event-stream"}})))
   (fn [] tools)))

(defn- rescue-fetch [chunks] (rescue-fetch* chunks #{"read"}))

(describe "toolcall-adapter through @ai-sdk/openai" (fn []

  (it "produces a real tool call from rescued prose"
      (^:async fn []
       ;; Deliberately the two shapes that used to kill the rescue silently:
       ;; a terminal event with NO `delta` key, and no trailing blank line, so
       ;; it arrives via :flush rather than :transform.
       (let [chunks #js [(str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":"
                              "\"<function=read><parameter=path>main.go</parameter></function>\"},"
                              "\"index\":0}]}\n\n")
                         "data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}"]
             model  (.chat (createOpenAI #js {:apiKey "x"
                                              :baseURL "http://local.test/v1"
                                              :compatibility "compatible"
                                              :fetch (rescue-fetch chunks)})
                           "m")
             res    (js-await (.doStream model
                                         #js {:prompt #js [#js {:role "user"
                                                                :content #js [#js {:type "text" :text "hi"}]}]}))
             parts  (atom [])]
         (js-await (.pipeTo (.-stream res)
                            (js/WritableStream.
                             #js {:write (fn [p] (swap! parts conj p))})))
         (let [types (set (map (fn [p] (.-type p)) @parts))
               call  (first (filter (fn [p] (= "tool-call" (.-type p))) @parts))
               fin   (first (filter (fn [p] (= "finish" (.-type p))) @parts))]
           ;; An `error` part here means the chunk failed schema validation.
           (-> (expect (contains? types "error")) (.toBe false))
           (-> (expect (some? call)) (.toBe true))
           (-> (expect (.-toolName call)) (.toBe "read"))
           (-> (expect (.-input call)) (.toContain "main.go"))
           (-> (expect (.-unified (.-finishReason fin))) (.toBe "tool-calls"))))))))

;; ── Withholding the raw markup ───────────────────────────────
;;
;; A rescued turn used to carry BOTH calls: the `<function=…>` markup had
;; already streamed as assistant text, so it was persisted and replayed on the
;; next turn — teaching the model the format it is being rescued from.

(defn- content-chunks [contents]
  (let [out (atom [])]
    (doseq [[i c] (map-indexed vector contents)]
      (swap! out conj
             (str "data: "
                  (js/JSON.stringify
                   #js {:choices #js [#js {:delta (if (zero? i)
                                                    #js {:role "assistant" :content c}
                                                    #js {:content c})
                                           :index 0}]})
                  "\n\n")))
    (swap! out conj (str "data: "
                         (js/JSON.stringify
                          #js {:choices #js [#js {:index 0 :finish_reason "stop"}]})
                         "\n\n"))
    (clj->js @out)))

(defn ^:async drain [contents tools]
  (let [model (.chat (createOpenAI #js {:apiKey "x"
                                        :baseURL "http://local.test/v1"
                                        :compatibility "compatible"
                                        :fetch (rescue-fetch* (content-chunks contents) tools)})
                     "m")
        res   (js-await (.doStream model
                                   #js {:prompt #js [#js {:role "user"
                                                          :content #js [#js {:type "text" :text "hi"}]}]}))
        parts (atom [])]
    (js-await (.pipeTo (.-stream res)
                       (js/WritableStream. #js {:write (fn [p] (swap! parts conj p))})))
    {:text  (apply str (map (fn [p] (.-delta p))
                            (filter (fn [p] (= "text-delta" (.-type p))) @parts)))
     :calls (mapv (fn [p] (.-toolName p))
                  (filter (fn [p] (= "tool-call" (.-type p))) @parts))}))

(describe "toolcall-adapter — raw markup never reaches the message" (fn []

  (it "keeps the prose and drops the markup when the rescue fires"
      (^:async fn []
       (let [r (js-await (drain ["Let me read it. "
                                 "<function=read><parameter=path>main.go</parameter></function>"]
                                #{"read"}))]
         (-> (expect (:calls r)) (.toEqual #js ["read"]))
         (-> (expect (:text r)) (.toBe "Let me read it. "))
         ;; The whole point: it must not also be sitting in the text.
         (-> (expect (.includes (:text r) "<function=")) (.toBe false)))))

  (it "releases the held text unchanged when nothing rescues"
      (^:async fn []
       ;; `nope` is not an active tool, so this is ordinary text and must
       ;; arrive in full — withholding may never lose content.
       (let [r (js-await (drain ["Prose then "
                                 "<function=nope><parameter=x>1</parameter></function>"]
                                #{"read"}))]
         (-> (expect (:calls r)) (.toEqual #js []))
         (-> (expect (:text r)) (.toBe "Prose then <function=nope><parameter=x>1</parameter></function>")))))

  (it "does not withhold a code block that merely contains a brace"
      (^:async fn []
       ;; The JSON rescue scans for `{` anywhere; holding on those would stall
       ;; every code block until the turn ended.
       (let [r (js-await (drain ["func main() {" "\n  fmt.Println(1)\n}"] #{"read"}))]
         (-> (expect (:text r)) (.toBe "func main() {\n  fmt.Println(1)\n}")))))

  (it "suppresses a message that is nothing but a bare JSON call"
      (^:async fn []
       (let [r (js-await (drain ["{\"name\":\"read\"," "\"arguments\":{\"path\":\"a.go\"}}"]
                                #{"read"}))]
         (-> (expect (:calls r)) (.toEqual #js ["read"]))
         (-> (expect (:text r)) (.toBe "")))))))
