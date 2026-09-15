(ns test-util.sse
  "A `Response` whose body is a text/event-stream, for stubbing `fetch`
   in front of an OpenAI-compatible streaming provider.")

(defn sse-response
  "Each chunk is enqueued as its own stream read, so a consumer that splits
   on read boundaries is exercised the way a real network stream would."
  [chunks]
  (let [encoder (js/TextEncoder.)
        body    (js/ReadableStream.
                 #js {:start (fn [controller]
                               (doseq [c chunks]
                                 (.enqueue controller (.encode encoder c)))
                               (.close controller))})]
    (js/Response. body #js {:headers #js {"content-type" "text/event-stream"}})))
