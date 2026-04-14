(ns gateway.streaming
  "Per-channel streaming policies for gateway response contexts.

   Each policy wraps a `send-fn` (async fn that takes a content-map) and returns a
   handler map with two keys:

     :on-chunk  (fn [chunk-str]) — called for each streaming text delta
     :on-end    (fn [final-str]) — called once when the LLM response is complete

   Policies:
     :immediate    — emit every chunk as it arrives; low latency, many small messages
     :debounce     — accumulate chunks; emit after delay-ms silence (default 400 ms)
     :throttle     — accumulate chunks; emit at most once per interval-ms (default 500 ms)
     :batch-on-end — accumulate everything; single emit when streaming is done

   All policies are safe to use with platforms that don't support message editing —
   they always call send-fn with a complete text payload, never raw deltas.
   The gateway.loop layer decides whether to post a new message or edit an existing one.")

(defn wrap-immediate
  "Emit every chunk to the platform immediately. Best for fast-editing platforms
   (Slack, Telegram) where each call UPDATES the same message in-place."
  [send-fn]
  {:on-chunk (fn [chunk]
               (when (seq chunk)
                 (send-fn {:text chunk})))
   :on-end   (fn [_final] nil)})

(defn wrap-debounce
  "Buffer chunks; flush after `delay-ms` ms of silence.
   Balances responsiveness against platform rate limits."
  [send-fn {:keys [delay-ms] :or {delay-ms 400}}]
  (let [buf   (atom "")
        timer (atom nil)]
    (letfn [(flush! []
              (when (seq @buf)
                (let [text @buf]
                  (reset! buf "")
                  (send-fn {:text text}))))]
      {:on-chunk
       (fn [chunk]
         (when (seq chunk)
           (swap! buf str chunk)
           (when @timer (js/clearTimeout @timer))
           (reset! timer (js/setTimeout flush! delay-ms))))
       :on-end
       (fn [_]
         (when @timer
           (js/clearTimeout @timer)
           (reset! timer nil))
         (flush!))})))

(defn wrap-throttle
  "Emit at most once per `interval-ms` ms. Guarantees a final flush on :on-end."
  [send-fn {:keys [interval-ms] :or {interval-ms 500}}]
  (let [buf       (atom "")
        last-sent (atom 0)]
    {:on-chunk
     (fn [chunk]
       (when (seq chunk)
         (swap! buf str chunk)
         (let [now  (js/Date.now)
               diff (- now @last-sent)]
           (when (>= diff interval-ms)
             (reset! last-sent now)
             (let [text @buf]
               (reset! buf "")
               (send-fn {:text text}))))))
     :on-end
     (fn [_]
       (when (seq @buf)
         (let [text @buf]
           (reset! buf "")
           (send-fn {:text text}))))}))

(defn wrap-batch-on-end
  "Accumulate all chunks; emit once when the full response is available.
   Best for platforms that don't support message editing or have strict rate limits."
  [send-fn]
  (let [buf (atom "")]
    {:on-chunk
     (fn [chunk]
       (when (seq chunk)
         (swap! buf str chunk)))
     :on-end
     (fn [final-text]
       (let [text (or (and (seq final-text) final-text) @buf)]
         (reset! buf "")
         (when (seq text)
           (send-fn {:text text}))))}))

(defn create-streaming-policy
  "Build a streaming policy handler from a keyword or opts map.

   Keyword shortcuts:
     :immediate    → wrap-immediate
     :batch-on-end → wrap-batch-on-end
     :debounce     → wrap-debounce (default 400 ms)
     :throttle     → wrap-throttle (default 500 ms)

   Map form:
     {:type :debounce :delay-ms 250}
     {:type :throttle :interval-ms 750}

   Defaults to :debounce with 300 ms when unrecognized."
  [send-fn policy]
  (cond
    (= policy :immediate)    (wrap-immediate send-fn)
    (= policy :batch-on-end) (wrap-batch-on-end send-fn)
    (= policy :debounce)     (wrap-debounce send-fn {})
    (= policy :throttle)     (wrap-throttle send-fn {})
    (and (map? policy) (= (:type policy) :debounce))
    (wrap-debounce send-fn policy)
    (and (map? policy) (= (:type policy) :throttle))
    (wrap-throttle send-fn policy)
    :else
    (wrap-debounce send-fn {:delay-ms 300})))
