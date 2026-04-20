(ns agent.extensions.custom-provider-claude-native.stream
  "Anthropic SSE → LanguageModelV3StreamPart transformer.")

;; ── Stop-reason mapping ─────────────────────────────────────

(defn- map-stop-reason [r]
  (case (str r)
    "end_turn"      "stop"
    "max_tokens"    "length"
    "tool_use"      "tool-calls"
    "stop_sequence" "stop"
    "refusal"       "content-filter"
    "pause_turn"    "stop"
    "other"))

;; ── V3 usage builder ────────────────────────────────────────

(defn- build-v3-usage
  "Construct a LanguageModelV3Usage object from Anthropic token counts.
   msg-usg = message_start.message.usage  (has cache breakdown)
   delta-usg = message_delta.usage         (has final output_tokens)"
  [msg-usg delta-usg]
  (let [in-total  (or (when msg-usg   (.-input_tokens msg-usg)) 0)
        cache-rd  (or (when msg-usg   (.-cache_read_input_tokens msg-usg)) 0)
        cache-wr  (or (when msg-usg   (.-cache_creation_input_tokens msg-usg)) 0)
        out-total (or (when delta-usg (.-output_tokens delta-usg)) 0)]
    #js {:inputTokens  #js {:total    in-total
                            :noCache  (- in-total cache-rd)
                            :cacheRead  cache-rd
                            :cacheWrite cache-wr}
         :outputTokens #js {:total     out-total
                            :text      out-total
                            :reasoning js/undefined}}))

;; ── Per-stream mutable state ─────────────────────────────────

(defn- make-state []
  {:blocks      (atom {})   ; index → {:type "text"|"tool_use" :id "..." :name "..." :json-buf ""}
   :msg-id      (atom "")
   :msg-model   (atom "")
   :msg-usage   (atom nil)  ; message_start.message.usage for cache breakdown
   :stop-reason (atom nil)
   :delta-usage (atom nil)  ; message_delta.usage for final output tokens
   :started?    (atom false)
   :closed?     (atom false)})

(defn- enqueue-safe!
  [ctrl part closed?]
  (when-not @closed?
    (.enqueue ctrl part)))

(defn- close-safe!
  [ctrl closed?]
  (when-not @closed?
    (reset! closed? true)
    (.close ctrl)))

;; ── Event dispatch ───────────────────────────────────────────

(defn- handle-event! [evt ctrl state]
  (let [{:keys [blocks msg-id msg-model msg-usage stop-reason delta-usage started? closed?]} state
        t (.-type evt)]
    (cond
      (= t "message_start")
      (let [msg (.-message evt)]
        (reset! msg-id    (.-id msg))
        (reset! msg-model (.-model msg))
        (reset! msg-usage (.-usage msg))
        (when-not @started?
          (reset! started? true)
          (enqueue-safe! ctrl #js {:type "stream-start" :warnings #js []} closed?)
          ;; V3 response-metadata: carries message id, model, and timestamp
          (enqueue-safe! ctrl #js {:type      "response-metadata"
                                   :id        (.-id msg)
                                   :modelId   (.-model msg)
                                   :timestamp (js/Date.)}
                         closed?)))

      (= t "content_block_start")
      (let [idx   (.-index evt)
            block (.-content_block evt)
            bt    (.-type block)]
        (case bt
          "text"
          (let [block-id (str "text-" idx)]
            (swap! blocks assoc idx {:type "text" :id block-id})
            (enqueue-safe! ctrl #js {:type "text-start" :id block-id} closed?))

          "tool_use"
          (let [block-id  (.-id block)
                tool-name (.-name block)]
            (swap! blocks assoc idx {:type "tool_use" :id block-id :name tool-name :json-buf ""})
            (enqueue-safe! ctrl #js {:type     "tool-input-start"
                                     :id       block-id
                                     :toolName tool-name}
                           closed?))

          nil))  ; unknown block types (thinking, server_tool_use, etc.) skipped for now

      (= t "content_block_delta")
      (let [idx   (.-index evt)
            delta (.-delta evt)
            block (get @blocks idx)]
        (when block
          (cond
            (and (= (:type block) "text") (= (.-type delta) "text_delta"))
            (enqueue-safe! ctrl #js {:type  "text-delta"
                                     :id    (:id block)
                                     :delta (.-text delta)}
                           closed?)

            (and (= (:type block) "tool_use") (= (.-type delta) "input_json_delta"))
            (let [chunk (.-partial_json delta)]
              (swap! blocks update idx update :json-buf str chunk)
              (enqueue-safe! ctrl #js {:type  "tool-input-delta"
                                       :id    (:id block)
                                       :delta chunk}
                             closed?)))))

      (= t "content_block_stop")
      (let [idx   (.-index evt)
            block (get @blocks idx)]
        (when block
          (case (:type block)
            "text"
            (enqueue-safe! ctrl #js {:type "text-end" :id (:id block)} closed?)

            "tool_use"
            (do
              (enqueue-safe! ctrl #js {:type "tool-input-end" :id (:id block)} closed?)
              (enqueue-safe! ctrl #js {:type       "tool-call"
                                       :toolCallId (:id block)
                                       :toolName   (:name block)
                                       :input      (:json-buf block)}
                             closed?)))))

      (= t "message_delta")
      (do
        (reset! stop-reason (.. evt -delta -stop_reason))
        (reset! delta-usage (.-usage evt)))

      (= t "message_stop")
      (let [fr  (or @stop-reason "end_turn")]
        (enqueue-safe! ctrl
                       #js {:type         "finish"
                            :finishReason #js {:unified (map-stop-reason fr)
                                               :raw     (str fr)}
                            :usage        (build-v3-usage @msg-usage @delta-usage)}
                       closed?)
        (close-safe! ctrl closed?))

      (= t "error")
      (do
        (enqueue-safe! ctrl
                       #js {:type  "error"
                            :error (js/Error. (or (when (.-error evt)
                                                    (.-message (.-error evt)))
                                                  "Anthropic API error"))}
                       closed?)
        (close-safe! ctrl closed?))

      ;; "ping" and unknown types — ignore
      )))

;; ── SSE line processor ───────────────────────────────────────

(defn- process-line! [line ctrl state]
  ;; Standard SSE: skip "event: <type>" header lines and empty lines
  (when (.startsWith line "data: ")
    (let [json-str (.trimEnd (.substring line 6))]  ; trim trailing \r if CRLF
      (when (not= json-str "[DONE]")
        (try
          (handle-event! (js/JSON.parse json-str) ctrl state)
          (catch :default e
            (js/console.warn "[claude-native/stream] SSE parse error:" (.-message e))))))))

;; ── Public: pipe SSE body → ReadableStream controller ────────

(defn ^:async pipe-sse
  "Read an Anthropic SSE response body and enqueue LanguageModelV3StreamPart
   values into ctrl. Closes the controller when message_stop is received or
   the body ends."
  [body ctrl]
  (let [reader  (.getReader body)
        decoder (js/TextDecoder. "utf-8")
        buf     (atom "")
        state   (make-state)]
    (loop []
      (let [result (js-await (.read reader))]
        (when-not (.-done result)
          (swap! buf str (.decode decoder (.-value result) #js {:stream true}))
          (loop []
            (let [idx (.indexOf @buf "\n")]
              (when (>= idx 0)
                (let [line (.substring @buf 0 idx)]
                  (swap! buf #(.substring % (inc idx)))
                  (process-line! line ctrl state))
                (recur))))
          (recur))))
    ;; Body exhausted without message_stop — synthesise finish and close
    (let [{:keys [closed? stop-reason msg-usage delta-usage started?]} state]
      (when-not @closed?
        (let [fr (or @stop-reason "end_turn")]
          (when-not @started?
            (enqueue-safe! ctrl #js {:type "stream-start" :warnings #js []} closed?))
          (enqueue-safe! ctrl #js {:type         "finish"
                                   :finishReason #js {:unified (map-stop-reason fr)
                                                      :raw     (str fr)}
                                   :usage        (build-v3-usage @msg-usage @delta-usage)}
                         closed?)
          (close-safe! ctrl closed?))))))
