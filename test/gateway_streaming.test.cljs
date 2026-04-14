(ns gateway-streaming.test
  "Unit tests for gateway.streaming — text-streaming policy wrappers.
   Each test drives :on-chunk / :on-end calls and asserts what the
   underlying send-fn sees."
  (:require ["bun:test" :refer [describe it expect]]
            [gateway.streaming :as stream]))

(defn- capture-send []
  (let [calls (atom [])]
    {:calls calls
     :send  (fn [content] (swap! calls conj content))}))

;;; ─── wrap-immediate ──────────────────────────────────────────

(describe "gateway.streaming/wrap-immediate" (fn []
                                               (it "emits every chunk individually"
                                                   (fn []
                                                     (let [{:keys [calls send]} (capture-send)
                                                           policy (stream/wrap-immediate send)]
                                                       ((:on-chunk policy) "hello ")
                                                       ((:on-chunk policy) "world")
                                                       ((:on-end policy) "hello world")
                                                       (-> (expect (count @calls)) (.toBe 2))
                                                       (-> (expect (:text (first @calls))) (.toBe "hello "))
                                                       (-> (expect (:text (second @calls))) (.toBe "world")))))

                                               (it "skips empty chunks"
                                                   (fn []
                                                     (let [{:keys [calls send]} (capture-send)
                                                           policy (stream/wrap-immediate send)]
                                                       ((:on-chunk policy) "")
                                                       ((:on-chunk policy) "a")
                                                       ((:on-end policy) nil)
                                                       (-> (expect (count @calls)) (.toBe 1)))))))

;;; ─── wrap-batch-on-end ───────────────────────────────────────

(describe "gateway.streaming/wrap-batch-on-end" (fn []
                                                  (it "accumulates all chunks and emits once at end with :final-text"
                                                      (fn []
                                                        (let [{:keys [calls send]} (capture-send)
                                                              policy (stream/wrap-batch-on-end send)]
                                                          ((:on-chunk policy) "a")
                                                          ((:on-chunk policy) "b")
                                                          ((:on-chunk policy) "c")
                                                          (-> (expect (count @calls)) (.toBe 0))
                                                          ((:on-end policy) "abc")
                                                          (-> (expect (count @calls)) (.toBe 1))
                                                          (-> (expect (:text (first @calls))) (.toBe "abc")))))

                                                  (it "uses buffered text when :on-end is called with nil/empty"
                                                      (fn []
                                                        (let [{:keys [calls send]} (capture-send)
                                                              policy (stream/wrap-batch-on-end send)]
                                                          ((:on-chunk policy) "x")
                                                          ((:on-chunk policy) "y")
                                                          ((:on-end policy) nil)
                                                          (-> (expect (count @calls)) (.toBe 1))
                                                          (-> (expect (:text (first @calls))) (.toBe "xy")))))

                                                  (it "does not emit when both buffer and final-text are empty"
                                                      (fn []
                                                        (let [{:keys [calls send]} (capture-send)
                                                              policy (stream/wrap-batch-on-end send)]
                                                          ((:on-end policy) "")
                                                          (-> (expect (count @calls)) (.toBe 0)))))))

;;; ─── wrap-throttle ───────────────────────────────────────────

(describe "gateway.streaming/wrap-throttle" (fn []
                                              (it "emits first chunk immediately, buffers subsequent, flushes on :on-end"
                                                  (fn []
                                                    (let [{:keys [calls send]} (capture-send)
                                                          policy (stream/wrap-throttle send {:interval-ms 60000})]
          ;; wrap-throttle initialises last-sent to 0, so the very first
          ;; chunk always satisfies (>= diff interval-ms) and flushes
          ;; immediately — a standard leading-edge throttle pattern.
                                                      ((:on-chunk policy) "a")
                                                      ((:on-chunk policy) "b")
                                                      ((:on-end policy) nil)
                                                      (-> (expect (count @calls)) (.toBe 2))
                                                      (-> (expect (:text (first @calls))) (.toBe "a"))
                                                      (-> (expect (:text (second @calls))) (.toBe "b")))))

                                              (it "skips empty chunks without flushing"
                                                  (fn []
                                                    (let [{:keys [calls send]} (capture-send)
                                                          policy (stream/wrap-throttle send {:interval-ms 60000})]
                                                      ((:on-chunk policy) "")
                                                      ((:on-end policy) nil)
                                                      (-> (expect (count @calls)) (.toBe 0)))))

                                              (it ":on-end is a no-op when buffer is empty"
                                                  (fn []
                                                    (let [{:keys [calls send]} (capture-send)
                                                          policy (stream/wrap-throttle send {:interval-ms 60000})]
                                                      ((:on-end policy) nil)
                                                      (-> (expect (count @calls)) (.toBe 0)))))))

;;; ─── wrap-debounce ───────────────────────────────────────────

(describe "gateway.streaming/wrap-debounce" (fn []
                                              (it "flushes buffered content on :on-end even before delay elapses"
                                                  (fn []
                                                    (let [{:keys [calls send]} (capture-send)
                                                          policy (stream/wrap-debounce send {:delay-ms 60000})]
                                                      ((:on-chunk policy) "a")
                                                      ((:on-chunk policy) "b")
                                                      ((:on-end policy) nil)
                                                      (-> (expect (count @calls)) (.toBe 1))
                                                      (-> (expect (:text (first @calls))) (.toBe "ab")))))

                                              (it "does not fire send-fn when chunks are empty"
                                                  (fn []
                                                    (let [{:keys [calls send]} (capture-send)
                                                          policy (stream/wrap-debounce send {:delay-ms 60000})]
                                                      ((:on-chunk policy) "")
                                                      ((:on-chunk policy) "")
                                                      ((:on-end policy) nil)
                                                      (-> (expect (count @calls)) (.toBe 0)))))))

;;; ─── create-streaming-policy dispatch ────────────────────────

(describe "gateway.streaming/create-streaming-policy" (fn []
                                                        (it ":immediate returns a policy whose :on-chunk fires immediately"
                                                            (fn []
                                                              (let [{:keys [calls send]} (capture-send)
                                                                    policy (stream/create-streaming-policy send :immediate)]
                                                                ((:on-chunk policy) "x")
                                                                (-> (expect (count @calls)) (.toBe 1)))))

                                                        (it ":batch-on-end returns a policy that holds until :on-end"
                                                            (fn []
                                                              (let [{:keys [calls send]} (capture-send)
                                                                    policy (stream/create-streaming-policy send :batch-on-end)]
                                                                ((:on-chunk policy) "x")
                                                                (-> (expect (count @calls)) (.toBe 0))
                                                                ((:on-end policy) "x")
                                                                (-> (expect (count @calls)) (.toBe 1)))))

                                                        (it ":debounce (bare keyword) returns a debounce policy"
                                                            (fn []
                                                              (let [{:keys [calls send]} (capture-send)
                                                                    policy (stream/create-streaming-policy send :debounce)]
                                                                ((:on-chunk policy) "x")
                                                                ((:on-end policy) nil)
                                                                (-> (expect (count @calls)) (.toBe 1)))))

                                                        (it "{:type :debounce :delay-ms N} returns a debounce policy"
                                                            (fn []
                                                              (let [{:keys [calls send]} (capture-send)
                                                                    policy (stream/create-streaming-policy send {:type :debounce :delay-ms 60000})]
                                                                ((:on-chunk policy) "x")
                                                                ((:on-end policy) nil)
                                                                (-> (expect (count @calls)) (.toBe 1)))))

                                                        (it "{:type :throttle :interval-ms N} returns a throttle policy"
                                                            (fn []
                                                              (let [{:keys [calls send]} (capture-send)
                                                                    policy (stream/create-streaming-policy send {:type :throttle :interval-ms 60000})]
                                                                ((:on-chunk policy) "x")
                                                                ((:on-end policy) nil)
                                                                (-> (expect (count @calls)) (.toBe 1)))))

                                                        (it "unrecognized policy falls back to debounce"
                                                            (fn []
                                                              (let [{:keys [calls send]} (capture-send)
                                                                    policy (stream/create-streaming-policy send :bogus)]
                                                                ((:on-chunk policy) "x")
                                                                ((:on-end policy) nil)
                                                                (-> (expect (count @calls)) (.toBe 1)))))))
