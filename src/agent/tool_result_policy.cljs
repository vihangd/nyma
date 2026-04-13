(ns agent.tool-result-policy
  "Per-tool result normalization and truncation policy.
   Borrowed from helixent's tool-result-policy.ts — adapted to nyma's
   string-first tool result contract.

   Each tool has a policy map with two fields that matter today:
     :max-string-length   — hard cap on the model-visible string; default 12000
     :prefer-summary-only — reserved for future use when tools produce
                            separate summaries; treated as false today

   Applying a policy produces a structured envelope:
     {:ok         bool      — true for non-error results
      :summary    str       — short representation (≤200 chars)
      :data       str?      — full truncated content (nil for errors)
      :error      str?      — error text (nil for ok results)
      :error-kind keyword?  — :invalid / :not-found / :failed / :unknown / nil}

   The envelope lives on the middleware context as :result-envelope (metadata
   only). The model-visible string is extracted separately via `model-string`
   and replaces ctx :result in the tool-tracking leave phase. All string
   consumers downstream continue to receive a plain string — no breaking change.

   Extensions can register per-tool policies at activation time via
   `register-policy!`. Extension policies override built-in ones.

   Policies are resolved with three layers of precedence (later wins):
     default-policy → builtin-policies → tool-metadata :result-policy → ext-policies"
  (:require [agent.tool-metadata :as meta]))

;;; ─── Default and built-in policy table ──────────────────

(def ^:private default-policy
  {:max-string-length   12000
   :prefer-summary-only false})

(def ^:private builtin-policies
  "Tighter limits for tools that typically produce large list output.
   Search/list tools cap at 4–8k chars.
   Note: bash limit is registered dynamically by bash_suite/output_handling
   when that extension is active; falls back to default-policy (12000) when not."
  {"ls"         {:max-string-length 4000}
   "glob"       {:max-string-length 4000}
   "grep"       {:max-string-length 8000}
   "web_search" {:max-string-length 8000}
   "read"       {:max-string-length 12000}
   "think"      {:max-string-length 4000}})

;;; ─── Extension-contributed policies ─────────────────────

(def ^:private ext-policies
  "Runtime policies contributed by extensions. An extension that
   registers a tool can also call register-policy! to override the
   default truncation limit for that tool."
  (atom {}))

(defn register-policy!
  "Associate a result policy with a tool name. Later calls overwrite
   earlier ones. An extension should call this from its activate fn
   and unregister-policy! from its deactivate fn."
  [tool-name policy]
  (swap! ext-policies assoc (str tool-name) policy)
  nil)

(defn unregister-policy!
  "Remove the extension-contributed policy for a tool. Called on
   extension deactivation so stale overrides don't survive reloads."
  [tool-name]
  (swap! ext-policies dissoc (str tool-name))
  nil)

(defn reset-policies!
  "Test helper — wipe all extension-contributed policies."
  []
  (reset! ext-policies {}))

(defn policy-for
  "Return the merged policy for a tool name. Precedence (later wins):
   default-policy → builtin-policies → tool-metadata :result-policy → ext-policies.

   The :result-policy key on a tool's metadata entry lets extensions declare
   their result truncation limits inline with their safety metadata, without
   needing a separate register-policy! call."
  [tool-name]
  (let [meta-policy (:result-policy (meta/tool-safety tool-name))]
    (merge default-policy
           (get builtin-policies (str tool-name) {})
           (when meta-policy meta-policy)
           (get @ext-policies (str tool-name) {}))))

;;; ─── String helpers ──────────────────────────────────────

(defn- truncate-at
  "Right-truncate `s` to at most `max-len` chars, appending a
   byte-count note. Returns the input unchanged when it fits."
  [s max-len]
  (if (> (count s) max-len)
    (str (.slice s 0 max-len)
         "\n… (" (- (count s) max-len) " bytes truncated)")
    s))

(defn- short-summary
  "A ≤200-char first-line summary of `s`, used for the :summary key.
   Plain slice — no truncation marker, because summaries are hints
   rather than content; model-string returns :data for full content."
  [s]
  (let [first-line (-> (or s "") (.split "\n") (aget 0) (.trim))]
    (.slice first-line 0 200)))

(defn- infer-error-kind
  "Classify an error string into a keyword. Matches on common error
   message prefix patterns; falls back to :unknown.
   RG_NOT_FOUND is checked before the generic _NOT_FOUND pattern
   so the more-specific classifier wins."
  [s]
  (cond
    (nil? s)                              nil
    (.startsWith s "INVALID_")           :invalid
    (.includes s "RG_NOT_FOUND")         :rg-not-found
    (or (.includes s "_NOT_FOUND")
        (.includes s "Not found")
        (.includes s "not found"))       :not-found
    (.includes s "_FAILED")              :failed
    :else                                :unknown))

;;; ─── Core API ────────────────────────────────────────────

(defn apply-policy
  "Normalize `raw` (a tool result string or nil) for `tool-name`.
   Returns a structured envelope:

     {:ok         bool
      :summary    str        — ≤200-char first-line hint
      :data       str?       — full content truncated to :max-string-length
      :error      str?       — nil unless :ok is false
      :error-kind keyword?   — nil unless :ok is false}

   The caller is responsible for deciding which field to use as the
   model-visible string (see `model-string`)."
  [raw tool-name]
  (let [policy  (policy-for tool-name)
        max-len (:max-string-length policy)]
    (cond
      ;; nil / empty → silent success
      (or (nil? raw) (= raw ""))
      {:ok true :summary "" :data nil :error nil :error-kind nil}

      ;; Non-string that carries :isError (pi-compat result maps)
      ;; These arrive as Clojure maps rather than plain strings.
      (and (map? raw) (:isError raw))
      (let [msg  (str (or (:error raw) (:content raw) "tool error"))
            kind (infer-error-kind msg)]
        {:ok false
         :summary (truncate-at msg 200)
         :data    nil
         :error   (truncate-at msg max-len)
         :error-kind kind})

      ;; String (the normal case after normalize-tool-result)
      :else
      (let [s   (if (string? raw) raw (str raw))
            dat (truncate-at s max-len)]
        {:ok         true
         :summary    (short-summary s)
         :data       dat
         :error      nil
         :error-kind nil}))))

(defn model-string
  "Extract the string the model should see from a policy envelope.
   On success returns :data (which is the truncated full content).
   On error returns :error. Falls back to :summary as a last resort."
  [envelope]
  (or (:data envelope)
      (:error envelope)
      (:summary envelope)
      ""))
