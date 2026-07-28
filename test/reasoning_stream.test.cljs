(ns reasoning-stream.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/utils/reasoning_stream.mjs"
             :refer [extract-think-blocks rewrite-assistant-msg wrap-response]]))

(describe "reasoning-stream/extract-think-blocks"
          (fn []
            (it "closed block: lifts inner to reasoning"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "a<think>b</think>c")]
                    (-> (expect reasoning) (.toBe "b"))
                    (-> (expect clean) (.toBe "ac")))))

            ;; poolside/Laguna template pre-fills <think>; the closer arrives orphaned.
            (it "orphan closer after thinking text (no opener)"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "thinking about it</think>the answer")]
                    (-> (expect reasoning) (.toBe "thinking about it"))
                    (-> (expect clean) (.toBe "the answer"))
                    (-> (expect clean) (.not.toContain "</think>")))))

            (it "orphan closer at start (no thinking this step)"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "</think>just the answer")]
                    (-> (expect reasoning) (.toBe ""))
                    (-> (expect clean) (.toBe "just the answer")))))

            (it "no tags: passthrough"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "plain text")]
                    (-> (expect reasoning) (.toBe ""))
                    (-> (expect clean) (.toBe "plain text")))))))

(describe "reasoning-stream/rewrite-assistant-msg"
          (fn []
            (it "lifts orphan </think> content into reasoning_content"
                (fn []
                  (let [out (rewrite-assistant-msg
                             #js {:role "assistant" :content "reasoned</think>replied"})]
                    (-> (expect (.-content out)) (.toBe "replied"))
                    (-> (expect (.-reasoning_content out)) (.toBe "reasoned")))))

            (it "leaves plain assistant messages untouched"
                (fn []
                  (let [msg #js {:role "assistant" :content "no tags here"}
                        out (rewrite-assistant-msg msg)]
                    (-> (expect (.-content out)) (.toBe "no tags here"))
                    (-> (expect (.-reasoning_content out)) (.toBeUndefined)))))))

(defn- sse-response [chunks]
  (let [encoder (js/TextEncoder.)
        body (js/ReadableStream.
              #js {:start (fn [controller]
                            (doseq [c chunks]
                              (.enqueue controller (.encode encoder c)))
                            (.close controller))})]
    (js/Response. body #js {:headers #js {"content-type" "text/event-stream"}})))

(def ^:private prefill-chunks
  #js ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"weighing options</think>the answer\"},\"index\":0}]}\n\n"
       "data: [DONE]\n\n"])

(describe "reasoning-stream/wrap-response think-prefill"
          (fn []
            (it "synthesizes an opening <think> on the first content delta"
                (fn []
                  (.then (.text (wrap-response (sse-response prefill-chunks) true))
                         (fn [out]
                           ;; first content delta had "" → becomes "<think>"
                           (-> (expect out) (.toContain "\"content\":\"<think>\""))))))

            (it "does nothing when think-prefill is off"
                (fn []
                  (.then (.text (wrap-response (sse-response prefill-chunks) false))
                         (fn [out]
                           (-> (expect out) (.not.toContain "\"content\":\"<think>\""))))))))
