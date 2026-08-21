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
  (:require ["bun:test" :refer [describe it expect]]
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
