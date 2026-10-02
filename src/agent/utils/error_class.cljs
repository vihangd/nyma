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
      (has "context length" "context_length" "context window" "too long"
           "maximum tokens" "prompt is too long")
      "context_length"

      (has "401" "403" "invalid api key" "invalid x-api-key" "invalid_api_key"
           "api key" "api_key" "unauthorized" "authentication" "forbidden")
      "auth"

      (has "429" "rate limit" "rate_limit" "rate-limit" "quota")
      "rate_limit"

      (has "overloaded" "capacity" "saturated" "503" "502" "504")
      "overloaded"

      (has "econnrefused" "enotfound" "econnreset" "etimedout" "fetch failed"
           "cannot connect" "unable to connect" "socket hang up"
           "deadline exceeded" "timed out" "timeout")
      "unavailable"

      :else "unknown")))
