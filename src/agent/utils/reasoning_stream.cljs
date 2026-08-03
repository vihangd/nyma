(ns agent.utils.reasoning-stream
  "Shared SSE stream rewriter for OpenAI-compatible providers that emit
   chain-of-thought through provider-specific delta fields the AI SDK
   doesn't recognize.

   Splices reasoning deltas into delta.content wrapped in <think>…</think>
   so they ride the standard text channel and surface via nyma's
   `agent.ui.think-tag-parser/split-think-blocks` parser.

   Recognized delta shapes:
   - `delta.reasoning_content` (Moonshot K2-thinking, GLM, DeepSeek V4)
   - `delta.reasoning` as string (Moonshot K2.6, Groq GPT-OSS)
   - `delta.reasoning_details[].text` / `.summary` (OpenRouter)"
  (:require [clojure.string :as str]))

(defn- extract-reasoning [delta]
  (or (.-reasoning_content delta)
      (let [r (.-reasoning delta)]
        (when (string? r) r))
      (when-let [details (.-reasoning_details delta)]
        (when (and (.-length details) (pos? (.-length details)))
          (let [parts (atom [])]
            (dotimes [i (.-length details)]
              (let [d (aget details i)
                    t (or (.-text d) (.-summary d) (.-data d))]
                (when (string? t) (swap! parts conj t))))
            (when (seq @parts) (.join (clj->js @parts) "")))))))

(defn- strip-reasoning-fields! [delta]
  ;; Avoid multiple js-delete calls in one fn — squint emits
  ;; `return return delete ...` for the final statement, which is a
  ;; syntax error. Use a single js* form with a sequence of deletes
  ;; followed by an explicit nil so squint's tail-return wraps the nil.
  (js* "delete ~{}.reasoning_content; delete ~{}.reasoning; delete ~{}.reasoning_details;"
       delta delta delta)
  nil)

(defn- transform-sse-line
  "Rewrite one SSE event line. `state` atom tracks {:in-think? bool}.

   Closes the <think> block when the stream pivots to anything that
   isn't more reasoning: content, tool_calls, or a finish_reason. Without
   this, a model that goes reasoning → tool_calls (no text) leaves the
   tag unclosed, breaking the request-side regex on replay."
  [line state]
  (if-not (.startsWith line "data: ")
    line
    (let [data (.slice line 6)]
      (if (.startsWith data "[DONE]")
        ;; Belt-and-suspenders: emit a final closing tag chunk if we
        ;; never saw anything to close it. Covers a synthesized prefill
        ;; opener too — a stream cut short mid-reasoning (max_tokens,
        ;; disconnect) would otherwise leave <think> unterminated and the
        ;; whole turn would render as reasoning with an empty answer.
        (if (or (:in-think? @state) (:prefill-open? @state))
          (do (swap! state assoc :in-think? false :prefill-open? false)
              (str "data: "
                   (js/JSON.stringify
                    #js {:choices #js [#js {:delta #js {:content "</think>\n\n"}
                                            :index 0}]})
                   "\n\n"
                   line))
          line)
        (try
          (let [obj    (js/JSON.parse data)
                choice (when (.-choices obj) (aget (.-choices obj) 0))
                delta  (when choice (.-delta choice))
                finish (when choice (.-finish_reason choice))]
            (if-not delta
              line
              (let [reasoning  (extract-reasoning delta)
                    content    (.-content delta)
                    tool-calls (.-tool_calls delta)
                    in-think?  (:in-think? @state)
                    needs-close? (and in-think?
                                      (or (some? content)
                                          (some? tool-calls)
                                          (some? finish))
                                      (not reasoning))
                    ;; A synthesized prefill opener is still open: don't emit a
                    ;; second <think> if reasoning deltas start arriving after it.
                    open       (when (and reasoning (not in-think?)
                                          (not (:prefill-open? @state)))
                                 (swap! state assoc :in-think? true :prefilled? true) "<think>")
                    close      (when needs-close?
                                 (swap! state assoc :in-think? false) "</think>\n\n")
                    ;; Template-prefilled <think> models (poolside/Laguna) emit no
                    ;; reasoning_content and no opener — their first content token is
                    ;; the closer. Synthesize the opening <think> on the first content
                    ;; delta so the stream is balanced and reasoning renders in place
                    ;; from the start instead of reflowing when </think> arrives.
                    prefill    (when (and (:think-prefill? @state)
                                          (not (:prefilled? @state))
                                          (some? content)
                                          (not reasoning)
                                          (not in-think?))
                                 (swap! state assoc :prefilled? true :prefill-open? true)
                                 "<think>")]
                (when reasoning
                  (aset delta "content" (str (or open "") reasoning))
                  (strip-reasoning-fields! delta))
                (when prefill
                  (aset delta "content" (str prefill content)))
                ;; The model supplies its own closer under prefill; once it
                ;; arrives the synthesized block is balanced.
                (when (and (:prefill-open? @state)
                           (some? content)
                           (.includes (str content) "</think"))
                  (swap! state assoc :prefill-open? false))
                (when (and (seq close) (some? content))
                  (aset delta "content" (str close content)))
                (when (and (seq close) (nil? content))
                  ;; Close-tag has nowhere to ride; attach to delta.content.
                  ;; Tool-call-only chunks then carry both the close and
                  ;; the tool_calls; finish chunks just carry the close.
                  (aset delta "content" close))
                (str "data: " (js/JSON.stringify obj) "\n\n"))))
          (catch :default _ line))))))

(defn wrap-response
  "Wrap a Response so SSE chunks with reasoning deltas are rewritten into
   <think>-tagged content deltas. No-op for non-event-stream responses.
   `think-prefill?` synthesizes an opening <think> at content start for
   models whose chat template pre-fills the opener (poolside/Laguna)."
  [^js response think-prefill?]
  (let [ct (or (.. response -headers (get "content-type")) "")]
    (if-not (and (.includes ct "text/event-stream") (.-body response))
      response
      (let [decoder (js/TextDecoder.)
            encoder (js/TextEncoder.)
            state   (atom {:in-think? false :think-prefill? think-prefill?})
            buffer  (atom "")
            ts (js/TransformStream.
                #js {:transform
                     (fn [chunk controller]
                       (swap! buffer str (.decode decoder chunk #js {:stream true}))
                       ;; SSE events are delimited by \n\n. Process complete
                       ;; events; keep partial in buffer.
                       (let [parts (.split @buffer "\n\n")
                             done  (.slice parts 0 -1)
                             rest- (aget parts (dec (.-length parts)))]
                         (reset! buffer rest-)
                         (doseq [evt done]
                           (let [out (transform-sse-line (str evt "\n\n") state)]
                             (.enqueue controller (.encode encoder out))))))
                     :flush
                     (fn [controller]
                       (when (seq @buffer)
                         (let [out (transform-sse-line @buffer state)]
                           (.enqueue controller (.encode encoder out)))))})]
        (js/Response. (.pipeThrough (.-body response) ts)
                      #js {:status     (.-status response)
                           :statusText (.-statusText response)
                           :headers    (.-headers response)})))))

;;; ─── Request-side: lift <think> tags back to reasoning_content ──────
;;;
;;; DeepSeek V4 and Moonshot K2 thinking models require that any prior
;;; assistant turn's chain-of-thought be replayed as a `reasoning_content`
;;; field on the assistant message. We previously rewrote the streamed
;;; reasoning into `<think>…</think>` blocks inside content, so here we
;;; reverse that on the next request.

(def ^:private think-block-re
  (js/RegExp. "<think(?:ing)?>([\\s\\S]*?)<\\/think(?:ing)?>\\s*" "gi"))

(def ^:private open-think-re
  (js/RegExp. "<think(?:ing)?>" "i"))

;; Orphan closer (no opener) — template-prefilled <think> models (poolside/Laguna).
(def ^:private close-think-re
  (js/RegExp. "<\\/think(?:ing)?>" "i"))

(def ^:private close-think-re-g
  (js/RegExp. "<\\/think(?:ing)?>" "gi"))

(defn extract-think-blocks
  "Pulls reasoning out of content. Handles closed <think>…</think> blocks and
   a trailing unterminated <think>… (everything after the opener becomes
   reasoning — model went reasoning → tool_calls without intervening text).

   `orphan?` additionally enables the orphan-</think>-with-no-opener branch
   (template-prefilled <think> models like poolside/Laguna: everything before
   the closer becomes reasoning). It is OPT-IN because this rewriter runs over
   every replayed assistant message: a turn that merely mentions `</think>` in
   prose or code would otherwise have all preceding text moved out of `content`,
   silently truncating history for providers that ignore `reasoning_content`.
   It also requires that no closed block was found — a model that pairs its tags
   never emits an orphan, so a stray closer beside a pair is literal text.

   Returns [reasoning-text content-cleaned]."
  [s orphan?]
  (let [parts (atom [])
        clean (.replace (str s) think-block-re
                        (fn [_match inner]
                          (swap! parts conj inner)
                          ""))
        m     (.exec open-think-re clean)]
    (if m
      (let [idx (.-index m)
            tag (aget m 0)
            before (subs clean 0 idx)
            after  (subs clean (+ idx (count tag)))]
        (swap! parts conj after)
        [(str/join "\n\n" @parts) before])
      (let [cm (when (and orphan? (empty? @parts))
                 (.exec close-think-re clean))]
        (if cm
          (let [idx    (.-index cm)
                tag    (aget cm 0)
                before (subs clean 0 idx)
                after  (.trimStart (.replace (subs clean (+ idx (count tag))) close-think-re-g ""))]
            (swap! parts conj before)
            [(str/join "\n\n" @parts) after])
          [(str/join "\n\n" @parts) clean])))))

(defn rewrite-assistant-msg
  "Lift <think> tags out of an assistant message's content into a
   `reasoning_content` field — required by DeepSeek-V4 / Moonshot K2
   thinking models on replay. No-op for non-assistant messages or
   assistant messages without <think> markers.
   `orphan?` — see `extract-think-blocks`."
  [msg orphan?]
  (if (and (= (.-role msg) "assistant")
           (string? (.-content msg))
           (or (.includes (.-content msg) "<think")
               (and orphan? (.includes (.-content msg) "</think"))))
    (let [[reasoning clean] (extract-think-blocks (.-content msg) orphan?)]
      (if (seq reasoning)
        (doto (js/Object.assign #js {} msg)
          (aset "content" clean)
          (aset "reasoning_content" reasoning))
        msg))
    msg))

(defn make-lift-think-request-rewriter
  "Build a request-rewriter for `make-fetch`: walks `messages[]` and lifts
   <think> blocks from assistant turns back into reasoning_content.
   `orphan?` enables orphan-closer lifting — pass true ONLY for providers whose
   model template pre-fills the opener (see `extract-think-blocks`)."
  [orphan?]
  (fn [body-str _init]
    (try
      (let [body (js/JSON.parse body-str)]
        (when (and (.-messages body) (.-length (.-messages body)))
          (let [msgs (.-messages body)]
            (dotimes [i (.-length msgs)]
              (aset msgs i (rewrite-assistant-msg (aget msgs i) orphan?)))))
        (js/JSON.stringify body))
      (catch :default _ body-str))))

(def lift-think-request-rewriter
  "Conservative default rewriter (no orphan-closer handling) — safe for any
   provider. DeepSeek / Kimi / opencode-zen use this."
  (make-lift-think-request-rewriter false))

(defn make-fetch
  "Build a custom `fetch` that wraps responses through `wrap-response`.
   `request-rewriter` (optional) is `(fn [body-str init] -> body-str)` —
   used by Kimi to inject `chat_template_kwargs.thinking` and lift
   <think> tags into `reasoning_content` for replay.
   `opts` (optional) may include `:think-prefill?` — synthesize an opening
   <think> at content start for template-prefilled models (poolside/Laguna)."
  ([] (make-fetch nil nil))
  ([request-rewriter] (make-fetch request-rewriter nil))
  ([request-rewriter opts]
   (let [think-prefill? (boolean (:think-prefill? opts))]
     (fn [url init]
       (let [body  (and init (.-body init))
             body2 (if (and request-rewriter (string? body))
                     (request-rewriter body init)
                     body)
             init2 (if (not= body body2)
                     (doto (js/Object.assign #js {} init) (aset "body" body2))
                     init)]
         (-> (js/fetch url init2)
             (.then (fn [resp] (wrap-response resp think-prefill?)))))))))
