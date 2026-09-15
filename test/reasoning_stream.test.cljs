(ns reasoning-stream.test
  (:require [test-util.sse :refer [sse-response]]
             ["bun:test" :refer [describe it expect]]
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
                           ;; The opener rides the first delta carrying REAL text —
                           ;; the role-only chunk's "" is not a content token.
                           (-> (expect out)
                               (.toContain "<think>weighing options</think>"))))))

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

;;; ─── a blank reasoning field is not reasoning ────────────────────────────
;;; In squint "" is TRUTHY — only nil and false are falsy. yunwu's OpenAI shim
;;; sends `reasoning_content: ""` on EVERY delta, so the rewriter treated each
;;; frame as reasoning and overwrote `content` with "<think>" plus an empty
;;; string. The model's answer was destroyed token by token and every assistant
;;; turn rendered as `<think></think>` while tool calls kept working.

(def ^:private blank-reasoning-chunks
  ;; exactly what yunwu emits for deepseek-v4-pro
  #js ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\",\"reasoning_content\":\"\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"!\",\"reasoning_content\":\"\"},\"index\":0}]}\n\n"
       "data: [DONE]\n\n"])

(def ^:private real-reasoning-chunks
  #js ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"let me think\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\" harder\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"Answer: 42\"},\"index\":0}]}\n\n"
       "data: [DONE]\n\n"])

(defn- assembled-content
  "delta.content across the rewritten stream — the text a user actually sees."
  [text]
  (->> (.split (str text) "\n")
       (filter #(.startsWith % "data: "))
       (map #(.slice % 6))
       (remove #(or (empty? (.trim %)) (= "[DONE]" (.trim %))))
       (keep (fn [p] (try (some-> (js/JSON.parse p) .-choices (aget 0) .-delta .-content)
                          (catch :default _ nil))))
       (apply str)))

(describe "reasoning-stream/wrap-response blank reasoning_content"
          (fn []
            (it "keeps the answer when reasoning_content is an empty string"
                (^:async fn []
                 (let [out (js-await (.text (wrap-response (sse-response blank-reasoning-chunks) false)))
                       content (assembled-content out)]
                   ;; The whole bug: this used to be "<think></think>".
                   (-> (expect content) (.toBe "Hello!"))
                   (-> (expect (.includes content "<think>")) (.toBe false)))))

            (it "still wraps genuine reasoning"
                (^:async fn []
                 (let [out (js-await (.text (wrap-response (sse-response real-reasoning-chunks) false)))
                       content (assembled-content out)]
                   (-> (expect (.includes content "<think>let me think harder</think>")) (.toBe true))
                   (-> (expect (.includes content "Answer: 42")) (.toBe true)))))))

;;; ─── prefill must not fire on the role-only chunk ────────────────────────
;;;
;;; vLLM opens every stream with {"role":"assistant","content":""} and then
;;; sends chain-of-thought via `delta.reasoning`. `""` is `some?`, so prefill
;;; used to fire on that opener: :prefill-open? set with :in-think? false meant
;;; `open` stayed suppressed, `needs-close?` could never fire, and [DONE] closed
;;; the block AFTER the answer. The whole turn parsed as one <think>…</think>:
;;; the answer rendered in the reasoning pane and the bubble was empty.

(def ^:private vllm-reasoning-chunks
  #js ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"reasoning\":\"17*23 = 340 + 51\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"reasoning\":\" = 391.\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"\\n\\n391\"},\"index\":0}]}\n\n"
       "data: [DONE]\n\n"])

(describe "reasoning-stream/wrap-response vLLM reasoning deltas under think-prefill"
          (fn []
            (it "closes the think block before the answer, not at [DONE]"
                (^:async fn []
                 (let [out     (js-await (.text (wrap-response
                                                 (sse-response vllm-reasoning-chunks) true)))
                       content (assembled-content out)
                       close   (.indexOf content "</think>")
                       ;; lastIndexOf: "391" also appears inside the reasoning.
                       answer  (.lastIndexOf content "391")]
                   ;; Exactly one block, and the answer lands OUTSIDE it.
                   (-> (expect (.-length (.split content "<think>"))) (.toBe 2))
                   (-> (expect (.-length (.split content "</think>"))) (.toBe 2))
                   (-> (expect (> answer close)) (.toBe true))
                   ;; Reasoning stayed inside.
                   (-> (expect (.includes (.slice content 0 close) "340 + 51")) (.toBe true))
                   ;; What the user sees after the closer is the answer itself.
                   (-> (expect (.trim (.slice content (+ close 8)))) (.toBe "391")))))))

(def ^:private own-opener-chunks
  #js ["data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"<think>\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"weighing options\"},\"index\":0}]}\n\n"
       "data: {\"choices\":[{\"delta\":{\"content\":\"</think>answer\"},\"index\":0}]}\n\n"
       "data: [DONE]\n\n"])

(describe "reasoning-stream/wrap-response — model supplies its own opener"
  (fn []
    (it "adopts it instead of synthesizing a second one"
        (^:async fn []
         ;; Skipping prefill for just this delta postponed the duplicate to the
         ;; next one: the state still said "not yet prefilled", so the very next
         ;; content token got a <think> in front of it.
         (let [out     (js-await (.text (wrap-response (sse-response own-opener-chunks) true)))
               content (assembled-content out)]
           (-> (expect (.-length (.split content "<think>"))) (.toBe 2))
           (-> (expect (.-length (.split content "</think>"))) (.toBe 2))
           (-> (expect content) (.toContain "<think>weighing options</think>")))))))


;; The SSE rewriter buffers on \n\n, so an event split across two network
;; chunks — even inside the JSON — must come out as one rewritten event.
(defn- ^:async collect-content [chunks]
  (let [resp (js-await (wrap-response (sse-response chunks) false))
        text (js-await (.text resp))]
    (->> (.split text "\n\n")
         (filter #(.startsWith % "data: {"))
         (map #(js/JSON.parse (.slice % 6)))
         (keep #(some-> (aget % "choices") (aget 0) (aget "delta") (aget "content")))
         (apply str))))

(describe "reasoning-stream/wrap-response across chunk boundaries"
          (fn []
            (it "an event split mid-JSON is reassembled before rewriting"
                (fn []
                  (-> (collect-content
                       #js ["data: {\"choices\":[{\"delta\":{\"reason"
                            "ing_content\":\"think\"},\"index\":0}]}\n\n"
                            "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"index\":0}]}\n"
                            "\ndata: [DONE]\n\n"])
                      (.then (fn [s] (-> (expect s) (.toBe "<think>think</think>\n\nanswer")))))))

            (it "reasoning that never closes is closed at [DONE]"
                (fn []
                  (-> (collect-content
                       #js ["data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"a\"},\"index\":0}]}\n\n"
                            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"b\"},\"index\":0}]}\n\n"
                            "data: [DONE]\n\n"])
                      (.then (fn [s] (-> (expect s) (.toBe "<think>ab</think>\n\n")))))))

            (it "a stream with no reasoning at all passes through untouched"
                (fn []
                  (-> (collect-content
                       #js ["data: {\"choices\":[{\"delta\":{\"content\":\"plain\"},\"index\":0}]}\n\n"
                            "data: [DONE]\n\n"])
                      (.then (fn [s] (-> (expect s) (.toBe "plain")))))))))
