(ns agent.extensions.custom-provider-claude-native.provider
  "Hand-rolled LanguageModelV3 implementation for Anthropic's Messages API.
   Bypasses @ai-sdk/anthropic to support betas (advisor, etc.) without waiting
   for SDK updates."
  (:require [agent.extensions.custom-provider-claude-native.messages :as msgs]
            [agent.extensions.custom-provider-claude-native.stream :as sse]
            [agent.extensions.custom-provider-claude-native.tools :as tools]))

(def ^:private anthropic-api-url "https://api.anthropic.com/v1")
(def ^:private anthropic-version "2023-06-01")

;; ── Request helpers ──────────────────────────────────────────

(defn- build-headers
  "Build Anthropic request headers. `extra-beta` is an optional comma-joined
   string of additional beta features (e.g. 'advisor-tool-2026-03-01')."
  [api-key & [extra-beta]]
  (let [h #js {"x-api-key"         api-key
               "anthropic-version" anthropic-version
               "content-type"      "application/json"}]
    (when (seq extra-beta)
      (aset h "anthropic-beta" extra-beta))
    h))

(defn- extract-beta-flags
  "Read providerOptions.claude-native.beta (array or string) from call options."
  [opts]
  (let [po  (.-providerOptions opts)
        cn  (when po (aget po "claude-native"))
        raw (when cn (.-beta cn))]
    (cond
      (nil? raw)   nil
      (string? raw) raw
      (array? raw)  (.join raw ",")
      :else         nil)))

;; ── doStream ─────────────────────────────────────────────────

(defn ^:async do-stream
  "Implements LanguageModelV3.doStream.
   Returns {stream, request, response, warnings}."
  [model-id api-key opts]
  (let [tools-arr (tools/tools->anthropic (.-tools opts))
        tool-ch   (tools/tool-choice->anthropic (.-toolChoice opts))
        base-map  (msgs/build-request-body model-id opts)
        body-map  (cond-> base-map
                    tools-arr (assoc "tools" tools-arr)
                    tool-ch   (assoc "tool_choice" tool-ch))
        body-str  (js/JSON.stringify (clj->js body-map))
        beta      (extract-beta-flags opts)
        headers   (build-headers api-key beta)
        abort-sig (.-abortSignal opts)
        fetch-opts #js {:method  "POST"
                        :headers headers
                        :body    body-str}
        _         (when abort-sig (aset fetch-opts "signal" abort-sig))
        resp      (js-await (js/fetch (str anthropic-api-url "/messages")
                                      fetch-opts))]
    (when-not (.-ok resp)
      (let [err-body (js-await (.text resp))]
        (throw (js/Error. (str "Anthropic API " (.-status resp) ": " err-body)))))
    ;; Build a ReadableStream and kick off SSE piping
    (let [ctrl-box  (atom nil)
          out-stream (js/ReadableStream. #js {:start (fn [c] (reset! ctrl-box c))})
          ctrl      @ctrl-box]
      ;; Fire-and-forget: SSE pipe runs concurrently, writes into out-stream
      (-> (sse/pipe-sse (.-body resp) ctrl)
          (.catch (fn [e]
                    (try
                      (.enqueue ctrl #js {:type "error" :error e})
                      (.close ctrl)
                      (catch :default _ nil)))))
      #js {:stream   out-stream
           :request  #js {:body body-str}
           :response #js {:id      (or (.get (.-headers resp) "x-request-id") "")
                          :modelId model-id}
           :warnings #js []})))

;; ── doGenerate (accumulates doStream) ───────────────────────

(defn ^:async do-generate
  "Implements LanguageModelV3.doGenerate by accumulating the stream.
   Collects text parts and tool-call parts; returns V3 GenerateResult shape."
  [model-id api-key opts]
  (let [stream-res  (js-await (do-stream model-id api-key opts))
        stream      (.-stream stream-res)
        reader      (.getReader stream)
        text-buf    (atom [])
        tool-calls  (atom [])
        finish-ref  (atom nil)
        usage-ref   (atom nil)]
    (loop []
      (let [chunk (js-await (.read reader))]
        (when-not (.-done chunk)
          (let [part (.-value chunk)
                t    (.-type part)]
            (cond
              (= t "text-delta")
              (swap! text-buf conj (.-delta part))

              (= t "tool-call")
              (swap! tool-calls conj #js {:type       "tool-call"
                                          :toolCallId (.-toolCallId part)
                                          :toolName   (.-toolName part)
                                          :input      (.-input part)})

              (= t "finish")
              (do (reset! finish-ref (.-finishReason part))
                  (reset! usage-ref  (.-usage part)))

              (= t "error")
              (throw (.-error part))))
          (recur))))
    (let [text    (apply str @text-buf)
          tcs     @tool-calls
          content (let [arr #js []]
                    (when (seq text)
                      (.push arr #js {:type "text" :text text}))
                    (doseq [tc tcs] (.push arr tc))
                    arr)]
      #js {:text         text
           :toolCalls    (clj->js tcs)
           :finishReason (or @finish-ref
                             #js {:unified "stop" :raw "end_turn"})
           :usage        (or @usage-ref
                             #js {:inputTokens  #js {:total 0 :noCache 0 :cacheRead 0 :cacheWrite 0}
                                  :outputTokens #js {:total 0 :text 0 :reasoning js/undefined}})
           :content      content
           :warnings     #js []
           :request      (.-request stream-res)
           :response     (.-response stream-res)})))

;; ── Factory ──────────────────────────────────────────────────

(defn create-model
  "Return a LanguageModelV3-shaped JS object for the given model-id.
   `api-key` is resolved at factory call time."
  [model-id api-key]
  #js {:specificationVersion "v3"
       :provider             "claude-native"
       :modelId              model-id
       :supportedUrls        #js {}
       :doStream             (fn [opts] (do-stream model-id api-key opts))
       :doGenerate           (fn [opts] (do-generate model-id api-key opts))})
