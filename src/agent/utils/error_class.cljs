(ns agent.utils.error-class
  "What KIND of provider failure is this? One pure classifier, several readers.

   Three places in the codebase were already classifying provider errors by
   substring, each for its own purpose and each with its own list:

     `modes/interactive/classify-error-message`  → a user-facing hint
     `providers/fallback/should-fall-back?`      → retry on another model?
     `claude_hook_bridge/events/stop`            → the StopFailure matcher

   The third one had no classifier at all: it read `(.-errorType data)` off the
   `provider_error` payload, and **nothing in the codebase has ever set that
   field**. So every StopFailure hook dispatched with the literal `\"unknown\"`,
   and a user's `matchers: {\"rate_limit\": …}` could never fire. The generated
   event map is the evidence — its `provider_error` row lists
   `config, error, message, model, source` and no `errorType`.

   Deriving the type from the message is the right fix rather than adding the
   field to the producer: the message is what the provider actually gives us,
   and a second producer-side vocabulary would be one more thing to keep in
   step.

   ORDER MATTERS, and the reason is recorded in `classify-error-message`:
   Anthropic returns a context overflow as an `invalid_request_error`, so a
   bare \"invalid\" test — or putting auth first — sends someone whose prompt is
   too long off to check their API key. Likewise \"rate\" alone matches
   \"generate\"."
  (:require [clojure.string :as str]))

(def overflow-patterns
  "Every way a provider says \"your prompt does not fit\", lower-case. ONE list:
   compaction's overflow recovery, this classifier and the fallback chain each
   kept their own, and they disagreed (Anthropic's \"exceed context limit\" and
   Gemini's \"exceeds the maximum number of tokens\" recovered but classified
   as unknown). Never a bare \"context\": Go servers say \"context deadline
   exceeded\" for a timeout."
  ["context length" "context_length" "maximum context" "context window"
   "too many tokens" "prompt is too long" "input is too long"
   "reduce the length" "exceeds the maximum number of tokens" "exceed context"
   "token limit" "maximum tokens"
   ;; llama.cpp: "request (N tokens) exceeds the available context size"
   "context size"
   ;; z.ai 1261, DashScope, xAI, Anthropic's stop reason
   "prompt exceeds max length" "range of input length" "maximum prompt length"
   "model_context_window_exceeded"])

(defn context-overflow?
  "Does this message say the prompt did not fit? Pure."
  [msg]
  (let [m (str/lower-case (str (or msg "")))]
    (boolean (some #(.includes m %) overflow-patterns))))

(defn- has-code?
  "A status code standing alone in `m`, not inside a request id or a token
   count (\"req_429…\", \"1403 tokens\")."
  [m code]
  (.test (js/RegExp. (str "(?:^|[^\\d.])" code "(?![\\d.])")) m))

(def types
  "The classifications, which double as Claude-Code hook matcher values."
  #{"context_length" "auth" "rate_limit" "overloaded" "unavailable" "unknown"})

(defn error-type
  "A provider error message → one of `types`. Pure.

   `\"unknown\"` rather than nil, because the one consumer that needs a
   discriminator needs a string, and a nil matcher silently matches nothing."
  [msg]
  (let [m   (str/lower-case (str (or msg "")))
        has (fn [& subs] (boolean (some (fn [x] (.includes m x)) subs)))]
    (cond
      (str/blank? m) "unknown"

      ;; First: a prompt that will not fit. See the ordering note above.
      (or (context-overflow? m) (has "too long"))
      "context_length"

      (or (has-code? m "401") (has-code? m "403")
          (has "invalid api key" "invalid x-api-key" "invalid_api_key"
               "api key" "api_key" "unauthorized" "authentication" "forbidden"))
      "auth"

      (or (has-code? m "429") (has "rate limit" "rate_limit" "rate-limit" "quota"))
      "rate_limit"

      ;; openlux's capacity 429 carries neither the code nor English:
      ;; "当前分组上游负载已饱和" (this group's upstream load is saturated).
      (or (has-code? m "503") (has-code? m "502") (has-code? m "504") (has-code? m "529")
          (has "overloaded" "capacity" "saturated" "负载已饱和"))
      "overloaded"

      (has "econnrefused" "enotfound" "econnreset" "etimedout" "fetch failed"
           "cannot connect" "unable to connect" "socket hang up"
           "deadline exceeded" "timed out" "timeout")
      "unavailable"

      :else "unknown")))
