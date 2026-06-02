(ns agent.extensions.custom-provider-local.toolcall-adapter
  "Rescue parser — normalize malformed tool-call formats to OpenAI JSON.

   Small local models emit tool calls in non-standard formats that the AI SDK
   cannot parse. This module wraps the provider :fetch to intercept the raw
   SSE response and normalise tool calls before the SDK sees them.

   Ported from Forge (antoinezambelli/forge, MIT):
     forge/src/forge/prompts/templates.py — rescue_tool_call, _parse_qwen_xml_tool_calls,
     _parse_mistral_bracket_tool_calls, extract_tool_call

   Formats supported (applied in priority order):
     1. OpenAI JSON in code fences  — ```json\\n{\"name\":\"tool\",\"arguments\":{...}}```
     2. Qwen3-Coder XML             — <function=name><parameter=k>v</parameter></function>
     3. Mistral bracket-tag         — [TOOL_CALLS]name{...}
     4. Rehearsal syntax            — tool_name[ARGS]{...}  (reasoning-model thinking leakage)

   Activated per-provider by wrapping the :fetch function. Enable via:
     settings.json → {\"local-models\": [{\"name\": \"ollama\", ..., \"rescueParsing\": true}]}
   or globally: {\"small-model\": {\"toolcall-adapter\": {\"enabled\": true}}}
  "
  (:require [clojure.string :as str]))

;; ── Think-tag stripping ──────────────────────────────────────────
;; Reasoning models leak tool calls inside <think> blocks; strip before parsing.

(def ^:private think-re
  (js/RegExp. "\\[THINK\\][\\s\\S]*?\\[/THINK\\]|<think(?:ing)?>[\\s\\S]*?<\\/think(?:ing)?>" "gi"))

(defn- strip-think-tags [text]
  (.replace (str text) think-re ""))

;; ── Format 1: JSON in code fences / embedded JSON ────────────────

(defn- try-parse-json-tool-call [s available]
  (try
    (let [obj (js/JSON.parse s)]
      (when (and (object? obj)
                 (or (.-tool obj) (.-name obj)))
        (let [tool-name (or (.-tool obj) (.-name obj))
              args      (or (.-args obj) (.-arguments obj) #js {})]
          (when (contains? available tool-name)
            {:tool tool-name :args args}))))
    (catch :default _ nil)))

(defn- extract-json-tool-calls [text available]
  (let [;; Strip code fences
        cleaned (-> (str text)
                    (.replace (js/RegExp. "```(?:json)?\\s*\\n?" "g") "")
                    (.replace (js/RegExp. "```" "g") ""))
        results (atom [])
        i       (atom 0)]
    (while (< @i (.-length cleaned))
      (if (= "{" (.charAt cleaned @i))
        (let [depth (atom 0)
              found (atom false)]
          (let [j (atom @i)]
            (while (and (< @j (.-length cleaned)) (not @found))
              (let [ch (.charAt cleaned @j)]
                (cond
                  (= ch "{") (swap! depth inc)
                  (= ch "}")
                  (do (swap! depth dec)
                      (when (zero? @depth)
                        (let [candidate (.slice cleaned @i (inc @j))
                              result    (try-parse-json-tool-call candidate available)]
                          (when result (swap! results conj result))
                          (reset! i (inc @j))
                          (reset! found true)))))
                (when-not @found (swap! j inc))))
            (when-not @found (swap! i inc))))
        (swap! i inc)))
    @results))

;; ── Format 2: Qwen3-Coder XML ────────────────────────────────────
;; <function=name><parameter=key>value</parameter></function>
;; Pattern adapted from Qwen's reference parser.

(def ^:private qwen-fn-re
  (js/RegExp. "<function=([^>\\s]+)>([\\s\\S]*?)<\\/function>" "g"))

(def ^:private qwen-param-re
  (js/RegExp. "<parameter=([^>\\s]+)>([\\s\\S]*?)(?:<\\/parameter>|(?=<parameter=)|(?=<\\/function>)|$)" "g"))

(defn- parse-qwen-xml [text available]
  (let [results (atom [])]
    (.lastIndex qwen-fn-re 0)
    (loop []
      (when-let [fn-m (.exec qwen-fn-re text)]
        (let [tool-name (str (.trim (aget fn-m 1)))
              body      (str (aget fn-m 2))
              args      #js {}]
          (when (contains? available tool-name)
            (.lastIndex qwen-param-re 0)
            (loop []
              (when-let [pm (.exec qwen-param-re body)]
                (let [k (str (.trim (aget pm 1)))
                      v (str (aget pm 2))]
                  ;; Strip the first and last newline (matches Qwen's parser)
                  (let [v (if (.startsWith v "\n") (.slice v 1) v)
                        v (if (.endsWith v "\n") (.slice v 0 -1) v)]
                    (aset args k v)))
                (recur)))
            (swap! results conj {:tool tool-name :args args})))
        (recur)))
    @results))

;; ── Format 3: Mistral bracket-tag ────────────────────────────────
;; [TOOL_CALLS]name{...}   (Devstral-Small-2, Mistral-Small-3.x)

(def ^:private mistral-re
  (js/RegExp. "\\[TOOL_CALLS\\](\\w+)\\s*(?=\\{)" "g"))

(defn- parse-mistral-bracket [text available]
  (let [results (atom [])]
    (.lastIndex mistral-re 0)
    (loop []
      (when-let [m (.exec mistral-re text)]
        (let [tool-name (str (aget m 1))
              i         (.-lastIndex mistral-re)]
          (when (and (contains? available tool-name)
                     (< i (.-length text))
                     (= "{" (.charAt text i)))
            ;; Brace-balance scan
            (let [depth     (atom 0)
                  in-str    (atom false)
                  escape    (atom false)
                  j         (atom i)
                  done      (atom false)]
              (while (and (< @j (.-length text)) (not @done))
                (let [ch (.charAt text @j)]
                  (cond
                    @escape           (reset! escape false)
                    (= ch "\\")       (reset! escape true)
                    @in-str           (when (= ch "\"") (reset! in-str false))
                    (= ch "\"")       (reset! in-str true)
                    (= ch "{")        (swap! depth inc)
                    (= ch "}")
                    (do (swap! depth dec)
                        (when (zero? @depth)
                          (let [candidate (.slice text i (inc @j))]
                            (try
                              (let [args (js/JSON.parse candidate)]
                                (when (object? args)
                                  (swap! results conj {:tool tool-name :args args})))
                              (catch :default _ nil)))
                          (reset! done true))))
                  (swap! j inc))))))
        (recur)))
    @results))

;; ── Format 4: Rehearsal syntax ────────────────────────────────────
;; tool_name[ARGS]{...}  — reasoning models sometimes leak tool calls in thinking

(def ^:private rehearsal-re
  (js/RegExp. "(\\w+)\\[ARGS\\](\\{[\\s\\S]*\\})" "g"))

(defn- parse-rehearsal [text available]
  (let [results (atom [])]
    (.lastIndex rehearsal-re 0)
    (loop []
      (when-let [m (.exec rehearsal-re text)]
        (let [tool-name (str (aget m 1))
              args-str  (str (aget m 2))]
          (when (contains? available tool-name)
            (try
              (let [args (js/JSON.parse args-str)]
                (when (object? args)
                  (swap! results conj {:tool tool-name :args args})))
              (catch :default _ nil))))
        (recur)))
    @results))

;; ── Main rescue entry point ───────────────────────────────────────

(defn rescue-tool-calls
  "Try all rescue strategies on a bare-text model response.
   Returns a (possibly empty) seq of {:tool name :args obj} maps."
  [text available-set]
  (let [cleaned (strip-think-tags text)]
    (or (seq (extract-json-tool-calls cleaned available-set))
        (seq (parse-rehearsal cleaned available-set))
        (seq (parse-qwen-xml cleaned available-set))
        (seq (parse-mistral-bracket cleaned available-set))
        [])))

;; ── OpenAI tool-call format builder ──────────────────────────────

(defn- rescue->openai-tool-call [idx {:keys [tool args]}]
  #js {:id       (str "rescue_" idx "_" (.floor js/Math (* 1000000 (js/Math.random))))
       :type     "function"
       :function #js {:name      tool
                      :arguments (try (js/JSON.stringify args)
                                      (catch :default _ "{}"))}})

;; ── SSE response transformer ──────────────────────────────────────
;;
;; Intercepts the raw SSE stream. When we see a message_stop / finish chunk
;; with no tool_calls but a non-empty text delta, try rescue parsing and
;; inject synthetic tool_calls into the final delta chunk.

(defn- inject-tool-calls-into-chunk [chunk-obj tool-calls]
  (let [choice (aget (.-choices chunk-obj) 0)]
    (when choice
      (let [delta (.-delta choice)
            tc-js (clj->js (vec (map-indexed rescue->openai-tool-call tool-calls)))]
        (aset delta "tool_calls" tc-js)
        (aset delta "content" nil)
        (aset choice "finish_reason" "tool_calls"))))
  chunk-obj)

(defn wrap-fetch-with-rescue
  "Wrap a fetch function to intercept SSE and rescue malformed tool calls.
   `available-tools` is a set of valid tool names (checked at call time)."
  [base-fetch get-active-tools]
  (fn [url init]
    (-> (base-fetch url init)
        (.then
         (fn [response]
           (let [ct (or (.. response -headers (get "content-type")) "")]
             (if-not (and (.includes ct "text/event-stream") (.-body response))
               response
               ;; Transform the SSE stream
               (let [decoder  (js/TextDecoder.)
                     encoder  (js/TextEncoder.)
                     buffer   (atom "")
                     ;; Track accumulated text delta and whether we've seen any tool calls
                     acc-text (atom "")
                     has-tc   (atom false)
                     ts (js/TransformStream.
                         #js {:transform
                              (fn [chunk ctrl]
                                (swap! buffer str (.decode decoder chunk #js {:stream true}))
                                (let [parts (.split @buffer "\n\n")
                                      done  (.slice parts 0 -1)
                                      rest- (aget parts (dec (.-length parts)))]
                                  (reset! buffer rest-)
                                  (doseq [evt done]
                                    (let [line (str evt "\n\n")]
                                      ;; Parse SSE data line
                                      (let [data-line (first (filter #(.startsWith % "data: ")
                                                                     (.split line "\n")))]
                                        (when data-line
                                          (let [data (.slice data-line 6)]
                                            (when-not (.startsWith data "[DONE]")
                                              (try
                                                (let [obj    (js/JSON.parse data)
                                                      choice (when (.-choices obj)
                                                               (aget (.-choices obj) 0))]
                                                  (when choice
                                                    (let [delta  (.-delta choice)
                                                          finish (.-finish_reason choice)]
                                                      ;; Track tool calls in this stream
                                                      (when (and delta (.-tool_calls delta))
                                                        (reset! has-tc true))
                                                      ;; Accumulate text
                                                      (when (and delta (.-content delta))
                                                        (swap! acc-text str (.-content delta)))
                                                      ;; On finish with no tool calls — attempt rescue
                                                      (when (and finish
                                                                 (not @has-tc)
                                                                 (seq @acc-text))
                                                        (let [active (try (get-active-tools)
                                                                          (catch :default _ #{}))
                                                              found  (rescue-tool-calls @acc-text active)]
                                                          (when (seq found)
                                                            ;; Replace this chunk with injected tool calls
                                                            (inject-tool-calls-into-chunk obj found)
                                                            (aset choice "finish_reason" "tool_calls")))))))
                                                (catch :default _ nil))))))
                                      (.enqueue ctrl (.encode encoder line))))))
                              :flush
                              (fn [ctrl]
                                (when (seq @buffer)
                                  (.enqueue ctrl (.encode encoder @buffer))))})]
                 (js/Response. (.pipeThrough (.-body response) ts)
                               #js {:status     (.-status response)
                                    :statusText (.-statusText response)
                                    :headers    (.-headers response)})))))))))
