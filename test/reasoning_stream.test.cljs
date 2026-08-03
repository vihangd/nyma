(ns reasoning-stream.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/utils/reasoning_stream.mjs"
             :refer [extract-think-blocks rewrite-assistant-msg wrap-response]]))

(describe "reasoning-stream/extract-think-blocks"
          (fn []
            (it "closed block: lifts inner to reasoning"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "a<think>b</think>c" false)]
                    (-> (expect reasoning) (.toBe "b"))
                    (-> (expect clean) (.toBe "ac")))))

            ;; poolside/Laguna template pre-fills <think>; the closer arrives orphaned.
            (it "orphan closer after thinking text (no opener), orphan? on"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "thinking about it</think>the answer" true)]
                    (-> (expect reasoning) (.toBe "thinking about it"))
                    (-> (expect clean) (.toBe "the answer"))
                    (-> (expect clean) (.not.toContain "</think>")))))

            (it "orphan closer at start (no thinking this step), orphan? on"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "</think>just the answer" true)]
                    (-> (expect reasoning) (.toBe ""))
                    (-> (expect clean) (.toBe "just the answer")))))

            ;; Regression: this rewriter runs for EVERY provider using the
            ;; conservative default. Prose mentioning </think> must survive intact.
            (it "orphan? off: leading text is NOT eaten by a literal </think>"
                (fn []
                  (let [src "the parser strips a bare </think> tag"
                        [reasoning clean] (extract-think-blocks src false)]
                    (-> (expect reasoning) (.toBe ""))
                    (-> (expect clean) (.toBe src)))))

            ;; Regression: a stray closer next to a properly closed pair is
            ;; literal text — a tag-pairing model never emits an orphan.
            (it "orphan? on: closed pair present ⇒ stray closer left alone"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "<think>A</think>B</think>C" true)]
                    (-> (expect reasoning) (.toBe "A"))
                    (-> (expect clean) (.toBe "B</think>C")))))

            (it "no tags: passthrough"
                (fn []
                  (let [[reasoning clean] (extract-think-blocks "plain text" false)]
                    (-> (expect reasoning) (.toBe ""))
                    (-> (expect clean) (.toBe "plain text")))))))

(describe "reasoning-stream/rewrite-assistant-msg"
          (fn []
            (it "lifts orphan </think> content into reasoning_content when orphan? on"
                (fn []
                  (let [out (rewrite-assistant-msg
                             #js {:role "assistant" :content "reasoned</think>replied"} true)]
                    (-> (expect (.-content out)) (.toBe "replied"))
                    (-> (expect (.-reasoning_content out)) (.toBe "reasoned")))))

            ;; Regression: no silent history truncation for non-prefill providers.
            (it "orphan? off: message quoting </think> is untouched"
                (fn []
                  (let [msg #js {:role "assistant" :content "use </think> to close"}
                        out (rewrite-assistant-msg msg false)]
                    (-> (expect (.-content out)) (.toBe "use </think> to close"))
                    (-> (expect (.-reasoning_content out)) (.toBeUndefined)))))

            ;; Regression: reasoning is empty here, but the content still has a
            ;; stray tag. Guarding only on reasoning replayed it verbatim.
            (it "orphan? on: strips a leading </think> even with no reasoning"
                (fn []
                  (let [out (rewrite-assistant-msg
                             #js {:role "assistant" :content "</think>just the answer"} true)]
                    (-> (expect (.-content out)) (.toBe "just the answer"))
                    (-> (expect (.-content out)) (.not.toContain "</think>")))))

            (it "leaves plain assistant messages untouched"
                (fn []
                  (let [msg #js {:role "assistant" :content "no tags here"}
                        out (rewrite-assistant-msg msg false)]
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
                           (-> (expect out) (.not.toContain "\"content\":\"<think>\""))))))

            ;; Regression: providers may split the closing tag across deltas
            ;; ("</" then "think>"). Matching a single delta left :prefill-open?
            ;; set, so [DONE] appended a SECOND, literal </think>.
            (it "detects a closer split across two deltas"
                (fn []
                  (let [split #js ["data: {\"choices\":[{\"delta\":{\"content\":\"reasoning\"},\"index\":0}]}\n\n"
                                   "data: {\"choices\":[{\"delta\":{\"content\":\"</\"},\"index\":0}]}\n\n"
                                   "data: {\"choices\":[{\"delta\":{\"content\":\"think>\"},\"index\":0}]}\n\n"
                                   "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"index\":0}]}\n\n"
                                   "data: [DONE]\n\n"]]
                    ;; The tag is split across deltas, so it is never contiguous
                    ;; in the raw SSE text — assemble delta.content first.
                    (.then (.text (wrap-response (sse-response split) true))
                           (fn [out]
                             (let [content (->> (.split out "\n")
                                                (filter #(and (.startsWith % "data: ")
                                                              (not (.includes % "[DONE]"))))
                                                (map (fn [l]
                                                       (try
                                                         (or (.-content (.-delta (aget (.-choices (js/JSON.parse (.slice l 6))) 0))) "")
                                                         (catch :default _ ""))))
                                                (apply str))]
                               ;; Exactly one closer: no duplicate appended at [DONE].
                               (-> (expect (.-length (.split content "</think>"))) (.toBe 2))))))))

            ;; Regression: a prefill stream cut off mid-reasoning (max_tokens,
            ;; disconnect) must still be closed, else the whole turn renders as
            ;; reasoning and the user sees an empty answer.
            (it "closes a synthesized <think> when the stream ends unterminated"
                (fn []
                  (let [truncated #js ["data: {\"choices\":[{\"delta\":{\"content\":\"still reasoning\"},\"index\":0}]}\n\n"
                                       "data: [DONE]\n\n"]]
                    (.then (.text (wrap-response (sse-response truncated) true))
                           (fn [out]
                             (-> (expect out) (.toContain "<think>"))
                             (-> (expect out) (.toContain "</think>")))))))))
