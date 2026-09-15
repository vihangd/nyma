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
   :prefer-summary-only false
   ;; Truncation used to simply destroy the tail: the model could not ask for
   ;; the rest, whatever it was. Every tool below except bash (which brings its
   ;; own handle via bash_suite) lost content with no way back — including every
   ;; MCP and extension-registered tool, which all land on the 12000 default.
   ;; With this on, the cut content is stored and the notice carries an id.
   :handle-on-truncate  true})

(def ^:private builtin-policies
  "Tighter limits for tools that typically produce large list output.
   Search/list tools cap at 4–8k chars.
   Note: bash limit is registered dynamically by bash_suite/output_handling
   when that extension is active; falls back to default-policy (12000) when not."
  {"ls"         {:max-string-length 4000}
   "glob"       {:max-string-length 4000}
   "grep"       {:max-string-length 8000}
   "web_search" {:max-string-length 8000}
   ;; read is line-capped at the tool itself (2000 numbered lines, ~7 chars of
   ;; prefix per line), so the byte cap here is a backstop for pathological
   ;; single-line files, not the primary limit — 12000 would truncate EVERY
   ;; default read and eat the "read with range …" continuation hint.
   "read"       {:max-string-length 200000}
   "think"      {:max-string-length 4000}
   ;; The recall tool's own output is a tool result and comes back through
   ;; here. :handle-on-truncate false is load-bearing — otherwise an
   ;; overshooting chunk mints a handle for a handle.
   "retrieve_result" {:max-string-length 12000 :handle-on-truncate false}})

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

;;; ─── Truncated-result store ──────────────────────────────

(def ^:private max-store-chars
  "Ceiling on everything held at once; oldest entries evict past it."
  (* 8 1024 1024))

(def ^:private result-store
  "id → {:s full-string :tool tool-name :at ms}.

   In memory, not the session JSONL, because a handle's useful life is ONE
   turn: the truncation notice lives only inside the AI SDK's internal step
   messages and is discarded at the turn boundary (tool results never enter
   `state :messages` — see `sessions/manager.cljs`). Durability across
   restarts would buy nothing, and an on-disk store would still need this
   fallback for headless `-p`, the gateway, and tests."
  (atom {}))

(defn reset-store!
  "Drop every stored result. Test helper, mirroring `reset-policies!`."
  []
  (reset! result-store {}))

(defn- evict-if-needed!
  "Keep the store under `max-store-chars`, dropping oldest first."
  []
  (let [total (reduce + 0 (map #(count (:s %)) (vals @result-store)))]
    (when (> total max-store-chars)
      (swap! result-store
             (fn [m]
               (loop [entries (sort-by :at (map (fn [[k v]] (assoc v :id k)) m))
                      acc     m
                      sz      total]
                 (if (or (<= sz max-store-chars) (empty? entries))
                   acc
                   (let [e (first entries)]
                     (recur (rest entries)
                            (dissoc acc (:id e))
                            (- sz (count (:s e)))))))))) ))

(defn store!
  "Store `s` and return its id.

   Keyed by CONTENT hash, never by time or a counter. The truncation notice is
   re-sent on every internal step of the turn, so an id that drifted between
   steps would change the prompt prefix each time and break the provider's
   cache. Identical output also dedups for free."
  [s tool-name]
  (let [id (.toString (js/Bun.hash (str s)) 16)]
    (swap! result-store assoc id {:s (str s) :tool (str tool-name) :at (js/Date.now)})
    (evict-if-needed!)
    id))

(defn store-read
  "Read a page of a stored result.

   Offsets are CHARACTER offsets. The value never round-trips through bytes
   here, so character slicing cannot split a multi-byte sequence, and it lines
   up exactly with `:max-string-length`, which is also a character count.
   Cuts back to the last newline when not at the end, so pages land on whole
   lines."
  [id offset limit]
  (if-let [entry (get @result-store (str id))]
    (let [s      (:s entry)
          total  (count s)
          start  (max 0 (min (or offset 0) total))
          want   (max 1 (or limit 8000))
          raw-end (min total (+ start want))
          nl      (.lastIndexOf (.slice s start raw-end) "\n")
          end     (if (and (< raw-end total) (> nl 0)) (+ start nl 1) raw-end)]
      {:found? true :tool (:tool entry) :total total
       :start start :end end :body (.slice s start end) :eof? (>= end total)})
    {:found? false}))

;;; ─── String helpers ──────────────────────────────────────

(defn- line-start-before
  "The start of the line containing `idx`, or `idx` when there is no newline
   before it."
  [s idx]
  (let [nl (.lastIndexOf s "\n" idx)]
    (if (pos? nl) (inc nl) idx)))

(defn- line-start-after
  "The start of the next line at or after `idx`, or `idx` when none follows.

   Both cut points are line-aligned because `store-read` snaps a page's start
   back to the last newline — an arbitrary character cut would make the recalled
   middle overlap the head, and head ++ middle ++ tail would no longer be the
   original. The head rounds BACK and the tail rounds FORWARD so line alignment
   can only shrink the kept text, never push it past the budget."
  [s idx]
  (let [nl (.indexOf s "\n" idx)]
    (if (neg? nl) idx (inc nl))))

(defn- truncate-at
  "Truncate `s` to about `max-len` chars, keeping the HEAD and the TAIL and
   eliding the middle. Returns the input unchanged when it fits.

   Head-only truncation threw away the half that usually matters: a test run's
   failure summary, a build log's error, a stack trace's cause all live at the
   END. bash escapes this via bash_suite's own middle-truncation; every other
   tool — grep, web_fetch, deep_research, every MCP and extension tool — landed
   here and lost its tail. Shape borrowed from mini-swe-agent's observation
   template (head + elided count + tail), including its coaching: the notice
   says how to not truncate next time, because the cheapest fix is a narrower
   command.

   With `tool-name` and a truthy `handle?`, the full string is stored first and
   the notice carries the id plus the offset of the GAP, so the model can read
   exactly what was elided and nothing it has already seen."
  ([s max-len] (truncate-at s max-len nil false))
  ([s max-len tool-name handle?]
   (if (<= (count s) max-len)
     s
     (let [id        (when handle? (store! s tool-name))
           ;; 60/40: the head carries what the command was doing, the tail
           ;; carries how it came out.
           head-end  (line-start-before s (js/Math.floor (* max-len 0.6)))
           tail-len  (max 0 (- max-len head-end))
           tail-start (line-start-after s (max head-end (- (count s) tail-len)))
           elided    (- tail-start head-end)]
       (if (<= elided 0)
         ;; Line alignment ate the gap — nothing to elide, keep it whole.
         s
         (str (.slice s 0 head-end)
              "\n… (" elided " chars truncated from the middle, offset "
              head-end "–" tail-start ")\n"
              (.slice s tail-start)
              (if id
                (str "\nMiddle: retrieve_result(id=\"" id "\", offset=" head-end "). ")
                "\n")
              "To avoid truncating: narrow the pattern, read a line range, or "
              "write the output to a file and search that."))))))

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
        max-len (:max-string-length policy)
        handle? (not (false? (:handle-on-truncate policy)))]
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
         :error   (truncate-at msg max-len tool-name handle?)
         :error-kind kind})

      ;; String (the normal case after normalize-tool-result)
      :else
      (let [s   (if (string? raw) raw (str raw))
            dat (truncate-at s max-len tool-name handle?)]
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
