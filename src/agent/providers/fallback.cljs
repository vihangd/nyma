(ns agent.providers.fallback
  "Which failures are worth trying a different model for, and which one to try.

   All pure. The wiring is a `provider_error` handler in `model_roles`, which is
   a seam that already existed: `loop.cljs` emits that event as a collect and
   re-runs `streamText` when a handler answers `{retry: true}`. So a fallback
   chain needs no change to the run loop — it swaps the model on the config and
   says retry.

   The line this draws is availability versus request. A model that is
   unreachable, overloaded, missing or failed to load is a reason to try another
   one. A rejected key, a forbidden request or a prompt that will not fit is not:
   a second model would fail the same way, or the real fix is compaction, and
   falling back would hide a setup error behind a model nobody chose. That is why
   `should-fall-back?` says no to 401 and to context overflow even though both
   stop the turn dead."
  (:require [clojure.string :as str]
            [agent.model-info :as model-info]))

;; ── how ──────────────────────────────────────────────────────────

(defn retarget!
  "Point the request `cfg` at `model`. Its other fields were built for the model
   that failed: providerOptions carry that provider's thinking dialect, and a
   max_tokens above the new model's limit is a 400 on the retry."
  [cfg model spec]
  (aset cfg "model" model)
  (aset cfg "providerOptions" #js {})
  (when-let [cap (aget cfg "maxOutputTokens")]
    (aset cfg "maxOutputTokens" (min cap (or (model-info/max-output spec) 8000)))))

;; ── when ─────────────────────────────────────────────────────────

(def ^:private availability-patterns
  ;; Unreachable, refused, timed out, or the server is in no state to answer.
  ["econnrefused" "enotfound" "eai_again" "econnreset" "etimedout"
   "fetch failed" "failed to fetch" "network error" "socket hang up"
   ;; What @ai-sdk/* ACTUALLY says for a refused connection, found by pointing
   ;; a provider at a closed port and reading the message: "Cannot connect to
   ;; API: Unable to connect. Is the computer able to access the url?". The
   ;; raw errno never reaches it — the SDK wraps and retries first, so the
   ;; message is "Failed after 6 attempts. Last error: AI_APICallError: Cannot
   ;; connect to API…". Matching only "econnrefused" missed every real case.
   "cannot connect" "unable to connect"
   "timeout" "timed out"
   ;; Go's canonical timeout string — ollama and vLLM gateways report an
   ;; unreachable or wedged backend this way, which is squarely what the chain
   ;; is for. A bare "context" in request-patterns used to swallow it.
   "deadline exceeded"
   "503" "502" "504" "500" "overloaded" "capacity" "saturated"
   "service unavailable" "bad gateway" "internal server error"
   "no healthy upstream"
   ;; The model itself is not there: a 404 on the model route, or a local
   ;; server that lists a model it cannot load — both seen on this machine.
   "model not found" "model_not_found" "unknown model" "does not exist"
   "failed to load"
   ;; "model type X not supported" is a load failure (seen verbatim from mlx).
   ;; Matched on "model type" rather than a bare "not supported", which also
   ;; caught "tool use is not supported by this model" — a capability mismatch,
   ;; where swapping models silently is a surprise rather than a recovery.
   "model type"])

(def ^:private request-patterns
  ;; The caller's problem, not the endpoint's. A different model does not help,
  ;; and falling back would bury the thing that needs fixing.
  ["401" "403" "invalid api key" "invalid x-api-key" "invalid_api_key"
   "unauthorized" "authentication" "permission denied" "forbidden"
   ;; "context length"/"context_length", NOT a bare "context": Go-based local
   ;; servers (ollama, vLLM gateways) report a timeout as "context deadline
   ;; exceeded", which is an availability failure and exactly what the chain is
   ;; for — a bare substring suppressed it.
   "context length" "context_length" "context window" "too long" "maximum tokens"])

(defn- matches?
  "Does lower-cased message `m` contain pattern `p`? A status code must stand
   alone: \"retry after 5000ms\" on a 429, or a request id with 502 in it,
   matched the bare \"500\"/\"502\" and moved the turn to another model."
  [m p]
  (if (.test #"^\d+$" p)
    (.test (js/RegExp. (str "(?:^|[^\\d.])" p "(?![\\d.])")) m)
    (.includes m p)))

(defn should-fall-back?
  "Is this failure a reason to try the next model in the chain?

   Request-shaped failures are checked FIRST and win. The orders matters: a
   context overflow arrives from Anthropic as an `invalid_request_error`, and
   several providers put a 503-ish body behind a message that also mentions a
   key. Availability is the permissive branch, so the restrictive one goes first."
  [message]
  (let [m (str/lower-case (str (or message "")))
        has (fn [pats] (boolean (some (fn [p] (matches? m p)) pats)))]
    (cond
      (str/blank? m)              false
      (has request-patterns)      false
      (has availability-patterns) true
      :else                       false)))

;; ── which ────────────────────────────────────────────────────────

(defn chain
  "The fallback entries on a role config, normalised to `[{:provider :model}]`.

   Tolerates keyword and string keys, because this comes from user JSON as often
   as from the shipped defaults. Entries missing either half are dropped rather
   than half-resolved — `{provider: \"x\"}` with no model is a typo, not a request
   to keep the current model on a different provider."
  [role-cfg]
  (->> (or (:fallback role-cfg) (get role-cfg "fallback") [])
       (map (fn [e]
              {:provider (str (or (:provider e) (get e "provider") ""))
               :model    (str (or (:model e) (get e "model") ""))}))
       (remove (fn [e] (or (str/blank? (:provider e)) (str/blank? (:model e)))))
       vec))

(defn spec-of [e] (str (:provider e) "/" (:model e)))

(defn next-entry
  "The first chain entry not yet attempted, or nil when the chain is exhausted.

   `attempted` is a set of `provider/model` specs, and it must include the
   PRIMARY — otherwise a chain that names the primary again would retry the model
   that just failed."
  [entries attempted]
  (first (remove (fn [e] (contains? (set attempted) (spec-of e))) entries)))

(defn announcement
  "What the user is told, once, when the model changes underneath them.

   Not optional. Answering from a model the status line does not name is worse
   than failing — the whole value of the transcript is that it says what
   happened."
  [from to reason]
  (str "⚠ " from " failed — falling back to " to
       (when (seq (str (or reason ""))) (str " (" reason ")"))))
