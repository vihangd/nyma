(ns ext-claude-native-stream.test
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.custom-provider-claude-native.stream :as stream]))

;; ── Test helpers ─────────────────────────────────────────────

(defn- sse-lines->body
  "Turn a seq of SSE data-json strings into a fake ReadableStream body."
  [lines]
  (let [text (str (str/join "\n"
                            (map #(str "data: " %) lines))
                  "\n")]
    (.-body (js/Response. text))))

(defn ^:async collect-parts
  "Drive pipe-sse and collect all enqueued parts into a vector."
  [lines]
  (let [parts    (atom [])
        ctrl-box (atom nil)
        stream   (js/ReadableStream. #js {:start (fn [c] (reset! ctrl-box c))})
        body     (sse-lines->body lines)]
    (js-await (stream/pipe-sse body @ctrl-box))
    (let [reader (.getReader stream)]
      (loop []
        (let [r (js-await (.read reader))]
          (when-not (.-done r)
            (swap! parts conj (.-value r))
            (recur)))))
    @parts))

;; ── Fixtures ─────────────────────────────────────────────────

(def ^:private msg-start
  (js/JSON.stringify
   #js {:type    "message_start"
        :message #js {:id    "msg_01"
                      :type  "message"
                      :role  "assistant"
                      :model "claude-sonnet-4-6"
                      :usage #js {:input_tokens 10 :output_tokens 1}}}))

(defn- block-start [idx btype]
  (js/JSON.stringify
   #js {:type          "content_block_start"
        :index         idx
        :content_block #js {:type btype :text ""}}))

(defn- text-delta [idx text]
  (js/JSON.stringify
   #js {:type  "content_block_delta"
        :index idx
        :delta #js {:type "text_delta" :text text}}))

(defn- block-stop [idx]
  (js/JSON.stringify
   #js {:type "content_block_stop" :index idx}))

(defn- msg-delta-line [stop-reason]
  (js/JSON.stringify
   #js {:type  "message_delta"
        :delta #js {:stop_reason stop-reason}
        :usage #js {:input_tokens 10 :output_tokens 5}}))

(def ^:private msg-stop
  (js/JSON.stringify #js {:type "message_stop"}))

(def ^:private ping-line
  (js/JSON.stringify #js {:type "ping"}))

(defn- simple-text-lines [& text-chunks]
  (concat [msg-start (block-start 0 "text")]
          (map #(text-delta 0 %) text-chunks)
          [(block-stop 0) (msg-delta-line "end_turn") msg-stop]))

;; ── Async test functions ──────────────────────────────────────

(defn ^:async test-emits-expected-types []
  (let [parts (js-await (collect-parts (simple-text-lines "Hello" " world")))
        types (mapv #(.-type %) parts)]
    (-> (expect (first types)) (.toBe "stream-start"))
    (-> (expect (contains? (set types) "text-start")) (.toBe true))
    (-> (expect (contains? (set types) "text-delta")) (.toBe true))
    (-> (expect (contains? (set types) "text-end"))   (.toBe true))
    (-> (expect (last types)) (.toBe "finish"))))

(defn ^:async test-assembles-text []
  (let [parts  (js-await (collect-parts (simple-text-lines "Hello" " world")))
        deltas (filter #(= (.-type %) "text-delta") parts)
        text   (apply str (map #(.-delta %) deltas))]
    (-> (expect text) (.toBe "Hello world"))))

(defn ^:async test-finish-stop-reason []
  (let [parts  (js-await (collect-parts (simple-text-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.. finish -finishReason -unified)) (.toBe "stop"))
    ;; V3 spec: field is "raw", not "provider"
    (-> (expect (.. finish -finishReason -raw)) (.toBe "end_turn"))))

(defn ^:async test-finish-usage []
  (let [parts  (js-await (collect-parts (simple-text-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))]
    ;; V3 spec: usage is nested {inputTokens: {total, ...}, outputTokens: {total, ...}}
    (-> (expect (.. finish -usage -inputTokens -total))  (.toBeGreaterThanOrEqual 0))
    (-> (expect (.. finish -usage -outputTokens -total)) (.toBeGreaterThan 0))))

(defn ^:async test-ping-ignored []
  (let [lines (concat [msg-start ping-line (block-start 0 "text")]
                      [(text-delta 0 "Hi") ping-line (block-stop 0)
                       (msg-delta-line "end_turn") msg-stop])
        parts (js-await (collect-parts lines))
        types (set (map #(.-type %) parts))]
    (-> (expect (contains? types "ping"))       (.toBe false))
    (-> (expect (contains? types "text-delta")) (.toBe true))))

(defn ^:async test-multi-block-distinct-ids []
  (let [lines [msg-start
               (block-start 0 "text") (text-delta 0 "A") (block-stop 0)
               (block-start 1 "text") (text-delta 1 "B") (block-stop 1)
               (msg-delta-line "end_turn") msg-stop]
        parts    (js-await (collect-parts lines))
        starts   (filter #(= (.-type %) "text-start") parts)
        ids      (mapv #(.-id %) starts)]
    (-> (expect (count ids)) (.toBe 2))
    (-> (expect (= (first ids) (second ids))) (.toBe false))))

(defn ^:async test-stream-start-once []
  (let [parts  (js-await (collect-parts (simple-text-lines "X")))
        starts (filter #(= (.-type %) "stream-start") parts)]
    (-> (expect (count starts)) (.toBe 1))))

(defn ^:async test-error-event []
  (let [err-line (js/JSON.stringify
                  #js {:type  "error"
                       :error #js {:type "overloaded_error" :message "Overloaded"}})
        parts     (js-await (collect-parts [msg-start err-line]))
        err-parts (filter #(= (.-type %) "error") parts)]
    (-> (expect (count err-parts)) (.toBeGreaterThan 0))
    (-> (expect (.-message (.-error (first err-parts)))) (.toBe "Overloaded"))))

(defn ^:async test-malformed-json-recovers []
  (let [lines (concat [msg-start "not-valid-json" (block-start 0 "text")]
                      [(text-delta 0 "OK") (block-stop 0)
                       (msg-delta-line "end_turn") msg-stop])
        parts (js-await (collect-parts lines))
        types (set (map #(.-type %) parts))]
    (-> (expect (contains? types "text-delta")) (.toBe true))
    (-> (expect (contains? types "finish"))     (.toBe true))))

(defn ^:async test-stop-reason-max-tokens []
  (let [lines (concat [msg-start (block-start 0 "text")]
                      [(text-delta 0 "...") (block-stop 0)
                       (msg-delta-line "max_tokens") msg-stop])
        parts  (js-await (collect-parts lines))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.. finish -finishReason -unified)) (.toBe "length"))))

(defn ^:async test-stop-reason-tool-use []
  (let [lines (concat [msg-start (block-start 0 "text")]
                      [(text-delta 0 "using tool") (block-stop 0)
                       (msg-delta-line "tool_use") msg-stop])
        parts  (js-await (collect-parts lines))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.. finish -finishReason -unified)) (.toBe "tool-calls"))))

;; ── Tool-use helpers ─────────────────────────────────────────

(defn- tool-block-start [idx id name]
  (js/JSON.stringify
   #js {:type          "content_block_start"
        :index         idx
        :content_block #js {:type "tool_use" :id id :name name :input #js {}}}))

(defn- input-json-delta [idx chunk]
  (js/JSON.stringify
   #js {:type  "content_block_delta"
        :index idx
        :delta #js {:type "input_json_delta" :partial_json chunk}}))

;; ── Tool-use async tests ──────────────────────────────────────

(defn ^:async test-tool-call-emits-parts []
  (let [lines [msg-start
               (tool-block-start 0 "toolu_01" "bash")
               (input-json-delta 0 "{\"cmd\"")
               (input-json-delta 0 ":\"ls\"}")
               (block-stop 0)
               (msg-delta-line "tool_use")
               msg-stop]
        parts (js-await (collect-parts lines))
        types (set (map #(.-type %) parts))]
    (-> (expect (contains? types "tool-input-start")) (.toBe true))
    (-> (expect (contains? types "tool-input-delta")) (.toBe true))
    (-> (expect (contains? types "tool-input-end"))   (.toBe true))
    (-> (expect (contains? types "tool-call"))        (.toBe true))))

(defn ^:async test-tool-call-id-and-name []
  (let [lines [msg-start
               (tool-block-start 0 "toolu_01" "bash")
               (input-json-delta 0 "{}")
               (block-stop 0)
               (msg-delta-line "tool_use") msg-stop]
        parts   (js-await (collect-parts lines))
        tc-part (first (filter #(= (.-type %) "tool-call") parts))]
    (-> (expect (.-toolCallId tc-part)) (.toBe "toolu_01"))
    (-> (expect (.-toolName   tc-part)) (.toBe "bash"))))

(defn ^:async test-tool-call-input-assembled []
  (let [lines [msg-start
               (tool-block-start 0 "toolu_01" "bash")
               (input-json-delta 0 "{\"cmd\"")
               (input-json-delta 0 ":\"ls -la\"}")
               (block-stop 0)
               (msg-delta-line "tool_use") msg-stop]
        parts   (js-await (collect-parts lines))
        tc-part (first (filter #(= (.-type %) "tool-call") parts))]
    (-> (expect (.-input tc-part)) (.toBe "{\"cmd\":\"ls -la\"}"))))

(defn ^:async test-tool-call-input-start-id []
  (let [lines [msg-start
               (tool-block-start 0 "toolu_abc" "read")
               (input-json-delta 0 "{}")
               (block-stop 0)
               (msg-delta-line "tool_use") msg-stop]
        parts  (js-await (collect-parts lines))
        starts (filter #(= (.-type %) "tool-input-start") parts)
        ends   (filter #(= (.-type %) "tool-input-end") parts)]
    (-> (expect (count starts)) (.toBe 1))
    (-> (expect (.-id (first starts))) (.toBe "toolu_abc"))
    (-> (expect (.-toolName (first starts))) (.toBe "read"))
    (-> (expect (.-id (first ends)))   (.toBe "toolu_abc"))))

(defn ^:async test-mixed-text-and-tool []
  (let [lines [msg-start
               (block-start 0 "text")
               (text-delta 0 "I'll run bash")
               (block-stop 0)
               (tool-block-start 1 "toolu_01" "bash")
               (input-json-delta 1 "{\"cmd\":\"pwd\"}")
               (block-stop 1)
               (msg-delta-line "tool_use") msg-stop]
        parts (js-await (collect-parts lines))
        types (mapv #(.-type %) parts)]
    (-> (expect (contains? (set types) "text-delta")) (.toBe true))
    (-> (expect (contains? (set types) "tool-call"))  (.toBe true))
    ;; text-start must precede tool-input-start
    (-> (expect (.indexOf (clj->js types) "text-start"))
        (.toBeLessThan (.indexOf (clj->js types) "tool-input-start")))))

;; ── Test registration ─────────────────────────────────────────

(describe "claude-native/stream — text-only response" (fn []
                                                        (it "emits stream-start, text-start, text-deltas, text-end, finish" test-emits-expected-types)
                                                        (it "assembles correct text from deltas"                             test-assembles-text)
                                                        (it "finish part has correct unified stop reason"                    test-finish-stop-reason)
                                                        (it "finish part carries usage tokens"                               test-finish-usage)
                                                        (it "ping events are silently ignored"                               test-ping-ignored)
                                                        (it "multiple text blocks get distinct ids"                          test-multi-block-distinct-ids)
                                                        (it "stream-start is emitted exactly once"                           test-stream-start-once)))

(describe "claude-native/stream — error handling" (fn []
                                                    (it "emits error part on API error event"                       test-error-event)
                                                    (it "recovers from malformed JSON between valid events"          test-malformed-json-recovers)))

(describe "claude-native/stream — stop reason mapping" (fn []
                                                         (it "maps max_tokens to length"   test-stop-reason-max-tokens)
                                                         (it "maps tool_use to tool-calls" test-stop-reason-tool-use)))

(describe "claude-native/stream — tool_use blocks" (fn []
                                                     (it "emits tool-input-start, deltas, tool-input-end, tool-call" test-tool-call-emits-parts)
                                                     (it "tool-call part has correct id and name"                    test-tool-call-id-and-name)
                                                     (it "assembles complete JSON string from delta chunks"          test-tool-call-input-assembled)
                                                     (it "tool-input-start carries block id and toolName"            test-tool-call-input-start-id)
                                                     (it "mixed text + tool blocks both arrive in order"             test-mixed-text-and-tool)))
