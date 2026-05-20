(ns agent.utils.reasoning-stream
  "Shared SSE stream rewriter for OpenAI-compatible providers that emit
   chain-of-thought through provider-specific delta fields the AI SDK
   doesn't recognize.

   Splices reasoning deltas into delta.content wrapped in <think>…</think>
   so they ride the standard text channel and surface via nyma's
   `agent.ui.think-tag-parser/split-think-blocks` parser.

   Recognized delta shapes:
   - `delta.reasoning_content` (Moonshot K2-thinking, GLM)
   - `delta.reasoning` as string (Moonshot K2.6, Groq GPT-OSS)
   - `delta.reasoning_details[].text` / `.summary` (OpenRouter)")

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
        ;; never saw anything to close it.
        (if (:in-think? @state)
          (do (swap! state assoc :in-think? false)
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
                    open       (when (and reasoning (not in-think?))
                                 (swap! state assoc :in-think? true) "<think>")
                    close      (when needs-close?
                                 (swap! state assoc :in-think? false) "</think>\n\n")]
                (when reasoning
                  (aset delta "content" (str (or open "") reasoning))
                  (strip-reasoning-fields! delta))
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
   <think>-tagged content deltas. No-op for non-event-stream responses."
  [^js response]
  (let [ct (or (.. response -headers (get "content-type")) "")]
    (if-not (and (.includes ct "text/event-stream") (.-body response))
      response
      (let [decoder (js/TextDecoder.)
            encoder (js/TextEncoder.)
            state   (atom {:in-think? false})
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

(defn make-fetch
  "Build a custom `fetch` that wraps responses through `wrap-response`.
   `request-rewriter` (optional) is `(fn [body-str init] -> body-str)` —
   used by Kimi to inject `chat_template_kwargs.thinking` and lift
   <think> tags into `reasoning_content` for replay."
  ([] (make-fetch nil))
  ([request-rewriter]
   (fn [url init]
     (let [body  (and init (.-body init))
           body2 (if (and request-rewriter (string? body))
                   (request-rewriter body init)
                   body)
           init2 (if (not= body body2)
                   (doto (js/Object.assign #js {} init) (aset "body" body2))
                   init)]
       (-> (js/fetch url init2)
           (.then wrap-response))))))
