(ns ext-claude-native-v3-shape.test
  "Compliance tests for LanguageModelV3 spec shapes.
   Guards finishReason.raw, nested usage, response-metadata, and tool-call input flexibility."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.custom-provider-claude-native.stream :as stream]
            [agent.extensions.custom-provider-claude-native.messages :as msgs]))

;; ── Helpers ───────────────────────────────────────────────────

(defn- sse-lines->body [lines]
  (let [text (str (str/join "\n" (map #(str "data: " %) lines)) "\n")]
    (.-body (js/Response. text))))

(defn ^:async collect-parts [lines]
  (let [parts    (atom [])
        ctrl-box (atom nil)
        s        (js/ReadableStream. #js {:start (fn [c] (reset! ctrl-box c))})
        body     (sse-lines->body lines)]
    (js-await (stream/pipe-sse body @ctrl-box))
    (let [reader (.getReader s)]
      (loop []
        (let [r (js-await (.read reader))]
          (when-not (.-done r)
            (swap! parts conj (.-value r))
            (recur)))))
    @parts))

(defn- make-opts [prompt]
  #js {:prompt (clj->js prompt) :maxOutputTokens nil :temperature nil})

(defn- user-msg [text]
  #js {:role "user" :content #js [#js {:type "text" :text text}]})

;; ── SSE fixtures ─────────────────────────────────────────────

(def ^:private msg-start-with-cache
  (js/JSON.stringify
   #js {:type    "message_start"
        :message #js {:id    "msg_test_01"
                      :model "claude-sonnet-4-6"
                      :type  "message"
                      :role  "assistant"
                      :usage #js {:input_tokens                5
                                  :output_tokens               1
                                  :cache_read_input_tokens     3
                                  :cache_creation_input_tokens 1}}}))

(defn- msg-delta-line [stop-reason]
  (js/JSON.stringify
   #js {:type  "message_delta"
        :delta #js {:stop_reason stop-reason}
        :usage #js {:input_tokens 5 :output_tokens 7}}))

(def ^:private msg-stop
  (js/JSON.stringify #js {:type "message_stop"}))

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
  (js/JSON.stringify #js {:type "content_block_stop" :index idx}))

(defn- simple-lines [& texts]
  (concat [msg-start-with-cache (block-start 0 "text")]
          (map #(text-delta 0 %) texts)
          [(block-stop 0) (msg-delta-line "end_turn") msg-stop]))

;; ── finishReason tests ────────────────────────────────────────

(defn ^:async test-finish-reason-raw-field []
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.-raw (.-finishReason finish)))      (.toBe "end_turn"))
    (-> (expect (.-provider (.-finishReason finish))) (.toBeUndefined))))

(defn ^:async test-max-tokens-raw []
  (let [lines  (concat [msg-start-with-cache (block-start 0 "text")
                        (text-delta 0 "...") (block-stop 0)
                        (msg-delta-line "max_tokens") msg-stop])
        parts  (js-await (collect-parts lines))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.-unified (.-finishReason finish))) (.toBe "length"))
    (-> (expect (.-raw     (.-finishReason finish))) (.toBe "max_tokens"))))

(defn ^:async test-tool-use-raw []
  (let [lines  (concat [msg-start-with-cache (block-start 0 "text")
                        (text-delta 0 "...") (block-stop 0)
                        (msg-delta-line "tool_use") msg-stop])
        parts  (js-await (collect-parts lines))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.-unified (.-finishReason finish))) (.toBe "tool-calls"))
    (-> (expect (.-raw     (.-finishReason finish))) (.toBe "tool_use"))))

;; ── usage shape tests ─────────────────────────────────────────

(defn ^:async test-usage-inputTokens-nested []
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))
        it-obj (.-inputTokens (.-usage finish))]
    (-> (expect (= "object" (js/typeof it-obj))) (.toBe true))
    (-> (expect (number? (.-total    it-obj))) (.toBe true))
    (-> (expect (number? (.-noCache  it-obj))) (.toBe true))
    (-> (expect (number? (.-cacheRead  it-obj))) (.toBe true))
    (-> (expect (number? (.-cacheWrite it-obj))) (.toBe true))))

(defn ^:async test-usage-outputTokens-nested []
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))
        ot-obj (.-outputTokens (.-usage finish))]
    (-> (expect (number? (.-total ot-obj))) (.toBe true))
    (-> (expect (number? (.-text  ot-obj))) (.toBe true))))

(defn ^:async test-cache-tokens-captured []
  ;; msg-start-with-cache: cache_read=3, cache_creation=1
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))
        it-obj (.-inputTokens (.-usage finish))]
    (-> (expect (.-cacheRead  it-obj)) (.toBe 3))
    (-> (expect (.-cacheWrite it-obj)) (.toBe 1))))

(defn ^:async test-output-tokens-from-delta []
  ;; msg-delta-line sends output_tokens: 7
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))]
    (-> (expect (.. finish -usage -outputTokens -total)) (.toBe 7))))

(defn ^:async test-usage-not-flat []
  ;; inputTokens must be an object, not a flat number (V2 shape regression guard)
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        finish (first (filter #(= (.-type %) "finish") parts))
        in-tok (.-inputTokens (.-usage finish))]
    (-> (expect (number? in-tok)) (.toBe false))))

;; ── response-metadata tests ───────────────────────────────────

(defn ^:async test-response-metadata-emitted []
  (let [parts  (js-await (collect-parts (simple-lines "Hi")))
        types  (mapv #(.-type %) parts)
        rm-idx (.indexOf (clj->js types) "response-metadata")
        ss-idx (.indexOf (clj->js types) "stream-start")]
    (-> (expect rm-idx) (.toBeGreaterThanOrEqual 0))
    (-> (expect rm-idx) (.toBeGreaterThan ss-idx))))

(defn ^:async test-response-metadata-id []
  (let [parts (js-await (collect-parts (simple-lines "Hi")))
        rm    (first (filter #(= (.-type %) "response-metadata") parts))]
    (-> (expect (.-id rm)) (.toBe "msg_test_01"))))

(defn ^:async test-response-metadata-model-id []
  (let [parts (js-await (collect-parts (simple-lines "Hi")))
        rm    (first (filter #(= (.-type %) "response-metadata") parts))]
    (-> (expect (.-modelId rm)) (.toBe "claude-sonnet-4-6"))))

(defn ^:async test-response-metadata-timestamp []
  (let [parts (js-await (collect-parts (simple-lines "Hi")))
        rm    (first (filter #(= (.-type %) "response-metadata") parts))]
    (-> (expect (instance? js/Date (.-timestamp rm))) (.toBe true))))

;; ── Tool-call input flexibility ───────────────────────────────
;; (synchronous — no js-await needed)

(describe "claude-native — V3 finishReason shape" (fn [])
          (it "finish part has 'raw' field not 'provider'"     test-finish-reason-raw-field)
          (it "max_tokens maps to 'length' with raw 'max_tokens'" test-max-tokens-raw)
          (it "tool_use maps to 'tool-calls' with raw 'tool_use'" test-tool-use-raw))

(describe "claude-native — V3 usage shape" (fn [])
          (it "inputTokens is a nested object (not a flat int)" test-usage-not-flat)
          (it "inputTokens has total/noCache/cacheRead/cacheWrite" test-usage-inputTokens-nested)
          (it "outputTokens has total/text fields"              test-usage-outputTokens-nested)
          (it "cache token counts captured from message_start"  test-cache-tokens-captured)
          (it "outputTokens.total comes from message_delta"     test-output-tokens-from-delta))

(describe "claude-native — response-metadata stream part" (fn [])
          (it "emits response-metadata after stream-start"     test-response-metadata-emitted)
          (it "carries message id from message_start"          test-response-metadata-id)
          (it "carries modelId from message_start"             test-response-metadata-model-id)
          (it "has a Date timestamp"                           test-response-metadata-timestamp))

(describe "claude-native — tool-call input flexibility" (fn [])
          (it "accepts JSON string input in tool-call part"
              (fn []
                (let [tc   #js {:type "tool-call" :toolCallId "tc1" :toolName "bash"
                                :input "{\"cmd\":\"ls\"}"}
                      am   #js {:role "assistant" :content #js [tc]}
                      body (msgs/build-request-body "claude-sonnet-4-6"
                                                    (make-opts [(user-msg "go") am]))
                      tu   (aget (.-content (aget (get body "messages") 1)) 0)]
                  (-> (expect (.-type tu)) (.toBe "tool_use"))
                  (-> (expect (.-cmd (.-input tu))) (.toBe "ls")))))

          (it "accepts already-parsed object input in tool-call part"
              (fn []
                (let [tc   #js {:type "tool-call" :toolCallId "tc1" :toolName "bash"
                                :input #js {:cmd "pwd"}}
                      am   #js {:role "assistant" :content #js [tc]}
                      body (msgs/build-request-body "claude-sonnet-4-6"
                                                    (make-opts [(user-msg "go") am]))
                      tu   (aget (.-content (aget (get body "messages") 1)) 0)]
                  (-> (expect (.-cmd (.-input tu))) (.toBe "pwd")))))

          (it "handles nil input gracefully"
              (fn []
                (let [tc   #js {:type "tool-call" :toolCallId "tc1" :toolName "bash"
                                :input nil}
                      am   #js {:role "assistant" :content #js [tc]}
                      body (msgs/build-request-body "claude-sonnet-4-6"
                                                    (make-opts [(user-msg "go") am]))
                      tu   (aget (.-content (aget (get body "messages") 1)) 0)]
                  (-> (expect (some? (.-input tu))) (.toBe true))))))

(describe "claude-native — temperature edge cases" (fn [])
          (it "temperature 0 is included in request body"
              (fn []
                (let [opts #js {:prompt (clj->js [(user-msg "hi")])
                                :maxOutputTokens nil :temperature 0}
                      body (msgs/build-request-body "claude-sonnet-4-6" opts)]
                  (-> (expect (contains? body "temperature")) (.toBe true))
                  (-> (expect (get body "temperature")) (.toBe 0)))))

          (it "nil temperature is omitted from request body"
              (fn []
                (let [opts #js {:prompt (clj->js [(user-msg "hi")])
                                :maxOutputTokens nil :temperature nil}
                      body (msgs/build-request-body "claude-sonnet-4-6" opts)]
                  (-> (expect (contains? body "temperature")) (.toBe false))))))
