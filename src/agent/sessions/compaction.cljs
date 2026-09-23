(ns agent.sessions.compaction
  "Context window compaction."
  (:require ["ai" :refer [generateText]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]
            [agent.tool-metadata :as tool-metadata]
            [agent.state :as state]
            [agent.token-estimation :as te]
            [agent.debug :as d]
            [agent.model-info :as model-info]
            [agent.utils.spill-file :as spill]
            [agent.ui.think-tag-parser :refer [strip-think-tags]]))

(defn- valid-cut-position?
  "A valid cut position is before a user or assistant message —
   never between a tool_call and its tool_result."
  [context i]
  (if (>= i (count context))
    true
    (contains? #{"user" "assistant" "compaction"} (:role (nth context i)))))

(defn- find-split-point
  "Find where to split context for summarization. Walks forward counting tokens
   until limit is reached, then adjusts to a valid turn boundary."
  [context limit]
  (let [raw-point (loop [i 0 tokens 0]
                    (if (or (>= i (count context))
                            (>= tokens limit))
                      i
                      (recur (inc i)
                             (+ tokens (te/estimate-tokens (str (:content (nth context i))))))))
        ;; Adjust forward to a valid boundary (user/assistant/compaction start)
        adjusted (loop [i raw-point]
                   (cond
                     (>= i (count context)) raw-point
                     (valid-cut-position? context i) i
                     :else (recur (inc i))))]
    adjusted))

(defn new-span-after-last-compaction
  "Given the messages selected for summarization (0..split-point), if a
   prior compaction message exists in the slice, return only the
   messages after it — this is the 'new span' for anchored iterative
   summarization. Otherwise return the slice unchanged.

   Returns a map {:span vector :prev-compaction message-or-nil} so
   callers can also retrieve the previous summary."
  [to-summarize]
  (let [v          (vec to-summarize)
        last-comp  (loop [i (dec (count v))]
                     (cond
                       (< i 0) nil
                       (= "compaction" (:role (nth v i))) i
                       :else (recur (dec i))))]
    (if (some? last-comp)
      {:span (vec (drop (inc last-comp) v))
       :prev-compaction (nth v last-comp)}
      {:span v
       :prev-compaction nil})))

(defn extract-files-read
  "Extract file paths from tool_call entries for the 'read' tool."
  [messages]
  (->> messages
       (filter #(and (= (:role %) "tool_call")
                     (= (get-in % [:metadata :tool-name]) "read")))
       (map #(get-in % [:metadata :args :path]))
       (filter some?)
       distinct
       vec))

(defn extract-files-modified
  "Extract file paths from tool_call entries of file-editing tools."
  [messages]
  (->> messages
       (filter #(and (= (:role %) "tool_call")
                     (tool-metadata/file-editing? (get-in % [:metadata :tool-name]))))
       (map #(get-in % [:metadata :args :path]))
       (filter some?)
       distinct
       vec))

(defn format-messages
  "Serialize messages as tagged text for summarization.
   Uses [Role]: prefix to prevent the model from continuing the conversation.
   Strips inline <think> tags from assistant content so reasoning noise does
   not appear in compaction summaries."
  [messages]
  (->> messages
       (map (fn [m]
              (let [role    (:role m)
                    content (cond-> (str (:content m))
                              (= role "assistant") strip-think-tags)]
                (str "[" (str/capitalize (or role "unknown")) "]: "
                     (if (and (= role "tool_result") (> (count content) 2000))
                       (str (subs content 0 2000) "...[truncated]")
                       content)))))
       (str/join "\n\n")))

(def compact-system-prompt
  "You are summarizing a coding agent conversation for continuity.
Do NOT continue the conversation. ONLY output a structured summary
with every section below present.

## 1. Previous Conversation
[What the user originally asked for and how the session has evolved]

## 2. Current Work
[What is being actively worked on right now — exact file paths, exact line numbers]

## 3. Key Technical Concepts
[Libraries, patterns, architectural decisions that inform ongoing work]

## 4. Relevant Files and Code
[EXACT file paths, EXACT line numbers, relevant function names VERBATIM.
 Every path from <files-read>/<files-modified> must appear here.]

## 5. Problem Solving
[Errors encountered (VERBATIM), fixes applied, open issues, things ruled out]

## 6. Pending Tasks and Next Steps
[Unfinished work in execution order. For EACH pending task, include a
 VERBATIM QUOTE from the user's messages or from my most recent work.
 Format:
   - [task description]
     Quote: \"[exact words from conversation]\"
 Do NOT paraphrase these quotes. Do NOT invent next steps the user did not state.]

## Critical Rules
- File paths must be EXACT (e.g., src/agent/loop.cljs, NOT \"the loop file\")
- Error messages must be VERBATIM
- Configuration values must be exact numbers
- Section 6 MUST contain at least one verbatim quote per pending task")

(defn build-compact-user-prompt
  "Build the user-side prompt for compact-with-retry.

   When `previous-summary` is present, this triggers anchored iterative
   mode: the model is instructed to MERGE new-span entries into the
   existing 6-section summary, preserving sections unaffected by the new
   span. Otherwise it produces a fresh summary of `to-summarize`.

   Anchored iterative was shown by Factory.ai's evaluation across 36k
   production sessions to outperform regenerate-each-time on accuracy,
   completeness, and continuity — repeatedly summarizing entire
   histories causes details to drift; merging anchors prior decisions."
  [{:keys [custom-instructions previous-summary to-summarize files-read files-modified]}]
  (str (when custom-instructions (str custom-instructions "\n\n"))
       (when previous-summary
         (str "<previous-summary>\n" previous-summary "\n</previous-summary>\n\n"
              "MERGE the new conversation span below into the previous summary.\n"
              "- Each section either gains new entries from the span, or is left unchanged.\n"
              "- Do NOT regenerate sections that aren't affected by the new span.\n"
              "- Preserve verbatim quotes and exact file paths from the previous summary.\n"
              "- If the new span contradicts a fact in the previous summary, prefer the\n"
              "  newer fact and record the contradiction in section 5 (Problem Solving).\n"
              "- Output the COMPLETE merged summary with all 6 sections present.\n\n"))
       "<conversation>\n" (format-messages to-summarize) "\n</conversation>"
       (when (seq files-read)
         (str "\n\n<files-read>\n" (str/join "\n" files-read) "\n</files-read>"))
       (when (seq files-modified)
         (str "\n\n<files-modified>\n" (str/join "\n" files-modified) "\n</files-modified>"))))

(def ^:private required-sections
  ["## 1. Previous Conversation"
   "## 2. Current Work"
   "## 3. Key Technical Concepts"
   "## 4. Relevant Files and Code"
   "## 5. Problem Solving"
   "## 6. Pending Tasks and Next Steps"])

(defn- collapse-ws
  "Whitespace-insensitive form, for comparing a quote against its source."
  [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn- unverifiable-quotes
  "Quotes in `summary` that do not occur in `source`.

   Section 6 already had to contain a `Quote: \"…\"` — but nothing ever checked
   the quoted words against the conversation, so a summary could invent the one
   thing the format exists to make trustworthy: the verbatim anchor the next
   turn plans from. Borrowed from SoL-Pi's receipt validator, which requires
   every retained quotation to be an exact substring of the archived source.

   Exact match first; a whitespace-collapsed comparison is the fallback, since
   the model re-wraps long lines and that is not a fabrication. Quotes under
   `min-quote-chars` are skipped — too short to be evidence either way, and
   short strings match by accident."
  [summary source]
  (let [min-quote-chars 12
        src             (str source)
        src-collapsed   (delay (collapse-ws src))]
    (->> (re-seq #"Quote:\s*\"([^\"]+)\"" summary)
         (map second)
         (filter #(>= (count (str/trim %)) min-quote-chars))
         (remove (fn [q]
                   (or (str/includes? src q)
                       (str/includes? @src-collapsed (collapse-ws q)))))
         distinct
         vec)))

(defn validate-compaction
  "Return a vector of error strings. Empty vector = valid.

   `source` is the text the summary was built from; when supplied, every
   section-6 quote must actually occur in it."
  [summary files-read files-modified & [source]]
  (let [errors (atom [])]
    ;; All required headers present in order
    (loop [remaining required-sections
           cursor    0]
      (when (seq remaining)
        (let [header (first remaining)
              idx    (.indexOf summary header cursor)]
          (if (neg? idx)
            (swap! errors conj (str "missing section header: " header))
            (recur (rest remaining) (+ idx (count header)))))))
    ;; Every referenced file path appears in the summary
    (doseq [p (distinct (concat files-read files-modified))]
      (when (and (seq p) (not (str/includes? summary p)))
        (swap! errors conj (str "file path missing from summary: " p))))
    ;; Section 6 must have at least one verbatim quote if bullets exist
    (let [s6-start (.indexOf summary "## 6.")
          s6-body  (when (>= s6-start 0) (subs summary s6-start))
          has-bullets (when s6-body (boolean (re-find #"(?m)^\s*-\s" s6-body)))
          has-quote   (when s6-body (boolean (re-find #"Quote:\s*\"" s6-body)))]
      (when (and has-bullets (not has-quote))
        (swap! errors conj "section 6 has pending tasks but no verbatim quotes")))
    ;; Section 6 quotes must be real
    (when (seq (str (or source "")))
      (doseq [q (unverifiable-quotes summary source)]
        (swap! errors conj (str "quote not found in the conversation: \"" q "\""))))
    @errors))

(defn build-fix-user-prompt [prior-summary errors]
  (str "The previous summary failed validation. DO NOT recompress. "
       "DO NOT shorten. ONLY fix the specific errors listed below by "
       "editing the previous summary in place.\n\n"
       "<previous-summary>\n" prior-summary "\n</previous-summary>\n\n"
       "<errors>\n"
       (str/join "\n" (map #(str "- " %) errors))
       "\n</errors>\n\n"
       "Output the complete corrected summary, keeping every section."))

(def ^:private context-roles
  "Roles build-context keeps — the projection the summarized span is aligned to."
  #{"user" "assistant" "tool_call" "tool_result" "compaction" "branch-summary"})

(def max-tracked-files
  "Most-recent file paths carried into the summary prompt and required by
   validation. Bounded because validate-compaction demands each one appear."
  25)

(def default-min-messages-between
  "New messages required before compacting again.

   Without this, compaction re-fires as soon as the trigger is true — and since
   appending a summary does not shrink what the trigger measures, that is
   immediately. A real session compacted 5 times, twice within 26 and 54
   entries, each costing a summarization call and reducing nothing."
  30)

(defn messages-since-last-compaction
  "How many messages follow the most recent compaction entry. The whole
   context when there has never been one."
  [context]
  (let [v (vec context)
        i (loop [i (dec (count v))]
            (cond (< i 0) nil
                  (= "compaction" (:role (nth v i))) i
                  :else (recur (dec i))))]
    (if (some? i) (- (count v) i 1) (count v))))

(defn compacted-messages
  "The live context after a compaction: the summary, then the kept span.

   Mirrors `session->seed-messages` (manager.cljs), which produces the
   same `[Earlier conversation summary]` user message on resume — so the screen,
   the model and a later resume are describing the same conversation.

   KNOWN LIMITATION, measured not assumed. The compaction entry is appended at
   the LEAF, i.e. AFTER the kept span, so on resume session->seed-messages folds
   it last and resets to the summary ALONE — a real 309-entry session seeded
   exactly one message and its transcript looked empty. The kept span is lost on
   resume; the live session keeps it.

   Two fixes were tried and rejected. Re-appending the kept span after the
   summary compounds every compaction (a measured run grew the tree 120 -> 221
   entries in one pass). Keeping a fixed tail in seed-messages re-expands the
   SUMMARIZED span whenever the kept span is shorter than the tail, which
   session_resume.test rightly forbids — a summary and the messages it
   summarized must not both be present.

   The correct fix is a :kept-count on the compaction entry, which
   seed-messages cannot currently read because entry->core-message strips
   :metadata before it ever sees the entry. That plumbing is the real work."
  [summary-text kept]
  (into [{:role "user"
          :content (str "[Earlier conversation summary]\n" summary-text)}]
        (filter #(contains? #{"user" "assistant"} (:role %)) kept)))

(defn apply-to-live-state!
  "Replace the running conversation with the compacted form.

   THE missing step. `compact` computed the split, appended the summary and
   stopped — it never touched `state :messages`, which is what
   agent.context/build-context reads on every turn (context.cljs). So the
   summary only took effect at the next RESUME while the live session kept
   growing: five compactions in one real session, 714k -> 956k tokens, none of
   which shrank anything.

   Nothing is lost: the JSONL is append-only with no delete or rewrite path, so
   every summarized entry stays on disk for later analysis."
  [state-atom summary-text kept]
  (when state-atom
    (let [msgs (compacted-messages summary-text kept)]
      (swap! state-atom
             (fn [st]
               ;; A skill's instructions live in a `:skill`-tagged message, so
               ;; compaction can drop them. Without this the skill stayed
               ;; "active" with its allowed-tools grant still lifting permission
               ;; prompts, and the `skill` tool went on reporting its
               ;; instructions as being in context.
               (state/prune-skill-state
                (assoc st
                       :messages msgs
                       ;; Where the next "30 new messages" is measured from.
                       :compacted-at-count (count msgs))))))
    true))

(def ^:private min-new-summary-chars
  "New content an extension summary must add beyond the summary it carries
   forward. Observed failure: 157 characters."
  400)

(defn extension-summary-usable?
  "Does this extension summary say anything of its own?

   Validation alone is not enough. token_suite's hook carries the PREVIOUS
   summary forward inside a `## Previous Context` block, so its output inherits
   the six required headers and validates no matter how little it adds. A real
   compaction produced exactly this:

     ## User Intent
     yes
     ## Completed Work
     - [no edits yet]

   157 characters of new content standing in for a whole session of work —
   Kite SDK, NPS, the tests, all of it recorded as \"yes\" and \"[no edits yet]\".
   The previous summary rode along, so the result looked well-formed and passed
   every check while the session's own work was simply gone.

   So: valid AND materially more than what it inherited."
  [summary previous-summary files-read files-modified]
  (let [s        (str summary)
        carried? (boolean (and (seq (str previous-summary))
                               (str/includes? s (str previous-summary))))
        added    (if carried? (str/replace s (str previous-summary) "") s)]
    (and (empty? (validate-compaction s files-read files-modified))
         ;; The floor applies ONLY to a carry-forward. A short standalone
         ;; summary is fine — the point is to catch a summary that INHERITED
         ;; its structure rather than to demand verbosity.
         (or (not carried?)
             (>= (count (str/trim added)) min-new-summary-chars)))))

(def default-strategy
  "What compaction did before strategies existed: the six-section summary
   prompt and its user-prompt builder."
  {:name "default"
   :system-prompt compact-system-prompt
   :build-user-prompt build-compact-user-prompt})

(def ^:private strategies
  "name → {:system-prompt str :build-user-prompt (fn [opts] str)}, contributed
   by extensions. Until 2026-09-23 the only way to change how compaction
   summarised was the `before_compact` ctx mutation — a whole summary handed
   over blind, which is why `extension-summary-usable?` exists. A strategy
   replaces the PROMPTS and keeps the loop's split, validation and retry."
  (atom {}))

(defn register-strategy!
  "Register a compaction strategy under `name`. Either key may be omitted
   and falls back to the default's."
  [name {:keys [system-prompt build-user-prompt]}]
  (swap! strategies assoc (str name)
         {:name (str name)
          :system-prompt     (or system-prompt (:system-prompt default-strategy))
          :build-user-prompt (or build-user-prompt (:build-user-prompt default-strategy))})
  nil)

(defn unregister-strategy! [name] (swap! strategies dissoc (str name)) nil)
(defn reset-strategies! [] (reset! strategies {}))

(defn strategy-for
  "The strategy `compaction.strategy` names, else the default. An unknown
   name warns once per call and falls back — a typo must not turn
   compaction off."
  [name]
  (if (and (seq (str (or name ""))) (not= (str name) "default"))
    (or (get @strategies (str name))
        (do (d/warn "compaction" (str "unknown compaction strategy `" name "` — using default"))
            default-strategy))
    default-strategy))

(defn ^:async compact-with-retry
  "Call generateText with the strategy's system prompt + user-prompt. Validate
   the result and run one fix-retry if validation fails. Always returns a
   string (uses unvalidated fallback rather than blocking compaction).
   Optional gen-fn replaces generateText (used in tests)."
  [model user-prompt files-read files-modified & [gen-fn strategy]]
  (let [gen          (or gen-fn generateText)
        sys          (:system-prompt (or strategy default-strategy))
        first-result (js-await
                      (gen
                       #js {:model    model
                            :system   sys
                            :messages #js [#js {:role "user" :content user-prompt}]}))
        first-text   (.-text first-result)
        first-errors (validate-compaction first-text files-read files-modified user-prompt)]
    (if (empty? first-errors)
      first-text
      (let [_ (d/warn "compaction" "validation failed, retrying"
                      #js {:errors (clj->js first-errors)})
            fix-result (js-await
                        (gen
                         #js {:model    model
                              :system   sys
                              :messages #js [#js {:role    "user"
                                                  :content (build-fix-user-prompt
                                                            first-text first-errors)}]}))
            fix-text   (.-text fix-result)
            fix-errors (validate-compaction fix-text files-read files-modified user-prompt)]
        (when (seq fix-errors)
          (d/warn "compaction" "validation failed after retry — using unvalidated summary"
                  #js {:errors (clj->js fix-errors)}))
        fix-text))))

(defn- write-precompact-dump
  "Write to-summarize + to-keep to two tmpfiles (JSON + text) before the
   before_compact event fires. Returns {:json-path ... :text-path ...} or nil."
  [session to-summarize to-keep]
  (try
    ;; This dump is the whole span being summarized: every prompt, every
    ;; assistant message, whatever the model read out of the repo. It used to go
    ;; to a fixed directory under a name guessable to the millisecond, written
    ;; with the default mode — 0644 in a world-readable /tmp. A fresh 0700
    ;; mkdtemp directory per compaction, with 0600 exclusive writes, keeps it to
    ;; this user; nothing needs to find it again, since cleanup below holds the
    ;; paths.
    (let [tmp-dir      (spill/private-temp-dir! "nyma-precompact")
          _            (when-not tmp-dir (throw (js/Error. "no private temp dir")))
          json-path    (path/join tmp-dir (spill/random-name ".json"))
          text-path    (path/join tmp-dir (spill/random-name ".txt"))
          payload      #js {:toSummarize (clj->js to-summarize)
                            :toKeep      (clj->js to-keep)}]
      (when-not (spill/write! json-path (js/JSON.stringify payload nil 2))
        (throw (js/Error. "precompact json write failed")))
      (when-not (spill/write! text-path (format-messages to-summarize))
        (throw (js/Error. "precompact text write failed")))
      {:json-path json-path :text-path text-path :dir tmp-dir})
    (catch :default e
      (d/warn "compaction" "precompact-dump failed" #js {:error (str e)})
      nil)))

(defn- cleanup-precompact-dump [dump]
  (when dump
    (try (fs/unlinkSync (:json-path dump)) (catch :default _ nil))
    (try (fs/unlinkSync (:text-path dump)) (catch :default _ nil))
    ;; The mkdtemp directory is ours alone, and empty by now — rmdir (never a
    ;; recursive rm) so it can only remove a real, empty directory.
    (try (fs/rmdirSync (:dir dump)) (catch :default _ nil))))

(def default-threshold
  "Fraction of the window at which to compact.

   0.85 sits in the 85-90% band the field converged on; 95% (Claude Code,
   Codex CLI) is documented as too late, and firing at 96-99% (OpenCode) risks
   'context anxiety' — the model rushing its summary and abandoning the task."
  0.85)

(def default-reserve-tokens
  "Absolute headroom kept free, as pi does with `reserveTokens`.

   A percentage alone does not survive a 6x range of window sizes: 0.85 of a
   200k window leaves 30k, but 0.85 of the 32,768-token window on a local model
   leaves ~5k for the reply AND the summary. Whichever limit binds first wins."
  16384)

(def default-max-working-context
  "Hard ceiling on how much context is allowed to accumulate, whatever the
   model's declared window.

   A percentage stops making sense at the top end. deepseek-v4-pro declares
   1,048,576 tokens, where 0.85 comes to 891k and a 16k reserve is noise — so a
   purely proportional trigger would let context grow past the point where every
   measured model has already degraded badly (30-50% accuracy loss well before
   the documented limit). The window is what the model ACCEPTS; this is what it
   still works well within.

   200k matches the Claude models already in use here and the band the published
   evidence covers. Settings-driven so a genuinely long-context workload can
   raise it deliberately."
  200000)

(def max-reserve-fraction
  "Cap on the reserve as a share of the window — see compaction-point."
  0.25)

(defn compaction-point
  "Token count at which compaction should fire: the lower of the percentage
   threshold and the reserve floor. Pure — this is the whole trigger decision,
   so it can be replayed against a recorded session offline."
  [limit threshold reserve & [max-working]]
  (let [limit     (or limit 0)
        threshold (or threshold default-threshold)
        ;; Clamp the reserve to a share of the window. A flat 16k reserve is
        ;; right for a 200k window and nonsense for a small one: on a 20k
        ;; window it would fire at 3.6k, i.e. 18% used, compacting a
        ;; conversation that has barely started. Clamped, big windows still get
        ;; the absolute floor and small ones fall back to ~75%.
        reserve   (min (or reserve default-reserve-tokens)
                       (* limit max-reserve-fraction))
        by-pct    (* limit threshold)
        by-res    (- limit reserve)]
    ;; A reserve wider than the window would make by-res <= 0 and compact
    ;; forever; the percentage is the floor in that case.
    ;; Whichever binds FIRST: the percentage, the reserve, or the absolute
    ;; ceiling. Each covers a range the others get wrong — percentage for
    ;; ordinary windows, reserve for small ones, ceiling for very large ones.
    (min (if (pos? by-res) (min by-pct by-res) by-pct)
         (or max-working default-max-working-context))))

(defn should-compact?
  "Pure trigger predicate. `opts` may carry :threshold/:reserve/:enabled?
   (from settings); omitted values fall back to the defaults above."
  [usage limit {:keys [threshold reserve enabled? max-working]}]
  (boolean (and (not (false? enabled?))
                (pos? (or limit 0))
                (> (or usage 0) (compaction-point limit threshold reserve max-working)))))

(defn resolve-settings
  "`(:settings agent)` is the settings MANAGER, not a settings map — a record of
   :get/:set-override/:apply-overrides (`settings/manager.cljs`). Reading
   `:compaction` straight off it is always nil, so every option below silently
   fell back to its default and `{\"compaction\": {\"enabled\": false}}` did
   nothing at all. `core.cljs:114-117` guards the same trap for its own reads.

   Tests pass a plain map, so accept both — same shape as that guard."
  [settings]
  (if (and settings (fn? (:get settings)))
    ((:get settings))
    (or settings {})))

(defn settings->opts
  "Read the `:compaction` settings section into compact's option keys.

   That section (`settings/manager.cljs`, `{:enabled true :threshold 0.85}`)
   shipped as a default that NOTHING read — the 0.85 was hardcoded inside
   `compact`, so turning compaction off or retuning it did nothing at all.
   Keyword and string keys are the same string under squint, so a
   CLJS default and a user's JSON both read through one `get`."
  [settings]
  (let [c (:compaction settings)
        g (fn [k] (get c k))]
    {:enabled?  (let [v (g :enabled)] (if (some? v) v true))
     :threshold (let [v (g :threshold)] (if (number? v) v default-threshold))
     :reserve   (let [v (g :reserve-tokens)] (if (number? v) v default-reserve-tokens))
     :max-working (let [v (g :max-working-context)]
                    (if (number? v) v default-max-working-context))
     ;; Name of a registered strategy (`registerCompactionStrategy`); absent
     ;; or "default" → the built-in prompts.
     :strategy  (let [v (g :strategy)] (when (string? v) v))}))

(defn ^:async compact
  "Summarize older messages when context approaches model limits.
   Extensions can intercept via 'before_compact' event.
   Accepts optional model-registry for accurate context windows.

   `:threshold`/`:reserve`/`:enabled?` come from the `:compaction` settings
   section. Until now the 0.85 was hardcoded here and that settings section was
   read by NOBODY, so turning compaction off or retuning it did nothing."
  [session model events & [{:keys [custom-instructions model-registry gen-fn
                                   model-key threshold reserve enabled? force? observed-usage
                                   state-atom max-working strategy]}]]
  (let [context ((:build-context session))
        ;; Prefer the provider's own count of the last request. The estimate
        ;; below walks the whole session tree; what is actually sent is pruned
        ;; at context_assembly and capped by priority_assembly. A real session
        ;; compacted five times off a tree estimate climbing 714k -> 956k while
        ;; the requests themselves fit a 200k window fine.
        usage   (or observed-usage (te/estimate-messages-tokens context))
        ;; `model-key` is the provider-qualified key; callers that have the
        ;; agent config should pass it, since a bare model id is ambiguous across
        ;; providers and resolves to whichever registered last.
        lookup  (or model-key (or (.-modelId model) "unknown"))
        limit   (if model-registry
                  ((:context-window model-registry) lookup)
                  100000)]

    (when (and (or force?
                   ;; Enough NEW material to be worth summarizing.
                   ;;
                   ;; Counted against the live conversation when we have it, not
                   ;; against entries after the compaction entry: re-anchoring
                   ;; the kept span appends ~30 entries there, which made the
                   ;; file-based count look like fresh work and defeated this
                   ;; guard entirely.
                   (>= (if state-atom
                         (- (count (:messages @state-atom))
                            (or (:compacted-at-count @state-atom) 0))
                         (messages-since-last-compaction context))
                       default-min-messages-between))
               (or force?
                   (should-compact? usage limit {:threshold   threshold
                                                 :reserve     reserve
                                                 :enabled?    enabled?
                                                 :max-working max-working})))
      (let [split-point     (find-split-point context (* limit 0.3))
            slice           (vec (take split-point context))
            to-keep         (vec (drop split-point context))
            ;; Anchored iterative: when a prior compaction message lives
            ;; inside the slice, only the messages after it are the new
            ;; span. The prior compaction message itself supplies the
            ;; previous-summary anchor that the LLM merges into.
            {:keys [span prev-compaction]} (new-span-after-last-compaction slice)
            to-summarize    span
            ;; From the TREE, not from `context`: entry->core-message strips
            ;; :metadata, and these filter on [:metadata :tool-name], so both
            ;; lists were ALWAYS empty here. Every compaction in the last real
            ;; session recorded files-read: 0, files-modified: 0 for exactly
            ;; this reason, which gutted the one part of the summary aimed at
            ;; artifact tracking.
            tree-entries    (if-let [gt (:get-tree session)] (gt) [])
            ;; Capped to the most RECENT paths. validate-compaction requires
            ;; every listed path to appear in the summary, and a real session
            ;; touches hundreds — 244 in the one that prompted this. Demanding
            ;; all of them guarantees a failed validation, a wasted retry call,
            ;; and then "using unvalidated summary" anyway. Asking for the
            ;; recent ones keeps the check meaningful and satisfiable.
            files-read      (take-last max-tracked-files (extract-files-read tree-entries))
            files-modified  (take-last max-tracked-files (extract-files-modified tree-entries))
            ;; "manual" is a /compact; anything else is the threshold.
            trigger         (if force? "manual" "auto")
            evt-ctx #js {:context               context
                         :usage                 usage
                         :trigger               trigger
                         ;; nil unless a caller passed text; /compact takes none.
                         :customInstructions    (or custom-instructions nil)
                         :summary               nil
                         :split-point           split-point
                         :messages-to-summarize (clj->js to-summarize)
                         ;; Same span, WITH :metadata. Extensions that extract
                         ;; file operations need tool-name/args, and
                         ;; entry->core-message strips both — which is why
                         ;; token_suite's summariser reported "[no edits yet]"
                         ;; for a session that edited 159 files, and produced
                         ;; 157 characters for a whole session of work.
                         :entries-to-summarize
                         (clj->js (vec (take (count to-summarize)
                                             (filter #(contains? context-roles (:role %))
                                                     tree-entries))))
                         :messages-to-keep      (clj->js to-keep)
                         :previous-summary      (:content prev-compaction)
                         :files-read            (clj->js files-read)
                         :files-modified        (clj->js files-modified)}
            dump    (write-precompact-dump session to-summarize to-keep)
            _       (when dump
                      (aset evt-ctx "precompactJsonPath" (:json-path dump))
                      (aset evt-ctx "precompactTextPath" (:text-path dump)))]
        (js-await ((:emit-async events) "before_compact" evt-ctx))

        (if (and (.-summary evt-ctx)
                 ;; An extension summary is used only if it VALIDATES. It used
                 ;; to be written regardless ("warn, but never block"), which in
                 ;; practice meant token_suite's extraction summary — a
                 ;; different template entirely — silently replaced the
                 ;; six-section one. Observed result: 586 characters standing in
                 ;; for ~900k tokens, with empty file lists, on every
                 ;; compaction. The six-section template exists because artifact
                 ;; tracking (which files changed, which calls ran) is the
                 ;; least-solved problem in this area and the thing "did you
                 ;; actually build anything?" is asking about. Falling back to
                 ;; it costs one summarization call; accepting a summary that
                 ;; drops the file lists costs the information.
                 (extension-summary-usable? (.-summary evt-ctx)
                                            (:content prev-compaction)
                                            files-read files-modified))
          (let [ext-summary (.-summary evt-ctx)]
            (d/info "compaction" "using extension-provided summary")
            ((:append session)
             {:role     "compaction"
              :content  ext-summary
              :metadata {:tokens-before  usage
                         :files-read     files-read
                         :files-modified files-modified}})
            (apply-to-live-state! state-atom ext-summary to-keep)
            (cleanup-precompact-dump dump)
            ;; Both paths must announce it: subscribers (kv_cache's cache-miss
            ;; reset) treat "compact" as the point where a full cache miss is
            ;; expected, and only the built-in path was emitting.
            (let [after (te/estimate-messages-tokens
                         (into [{:role "compaction" :content ext-summary}] to-keep))]
              ((:emit events) "compact"
                              {:summary ext-summary
                               :trigger trigger
                               :before  usage
                               :after   after})
              {:before usage :after after :summariser :extension}))

          ;; Main path: use compact-with-retry (validates + one fix-retry).
          ;; Also reached when an extension offered a summary that did not
          ;; validate — say so, or the fallback looks like the extension never
          ;; ran.
          (let [rejected-errors
                (when-let [rejected (.-summary evt-ctx)]
                  (let [errors (vec (validate-compaction rejected files-read files-modified))]
                    ;; warn-quiet: this fires from /compact while the TUI is
                    ;; mounted, and the receipt below carries the same text.
                    (d/warn-quiet "compaction" "extension summary rejected, using built-in summariser"
                                  #js {:errors (clj->js errors) :length (count rejected)})
                    errors))
                strat        (strategy-for strategy)
                user-prompt  ((:build-user-prompt strat)
                              {:custom-instructions custom-instructions
                               :previous-summary    (:content prev-compaction)
                               :to-summarize        to-summarize
                               :files-read          files-read
                               :files-modified      files-modified})
                summary-text (js-await
                              (compact-with-retry model user-prompt files-read files-modified gen-fn strat))]

            ((:append session)
             {:role     "compaction"
              :content  summary-text
              :metadata {:tokens-before  usage
                         :files-read     files-read
                         :files-modified files-modified}})
            (cleanup-precompact-dump dump)
            (apply-to-live-state! state-atom summary-text to-keep)

            ;; Log every compaction. It is a HARD semantic break for prompt
            ;; caching — the cached prefix is a prefix match, so replacing early
            ;; history invalidates everything after it and the next request is a
            ;; guaranteed full miss. That is a cost, not a bug, but it is
            ;; invisible without a record, and an unexplained cost spike or
            ;; behaviour change after a silent compaction is exactly the kind of
            ;; thing that burns an afternoon.
            (let [after (te/estimate-messages-tokens
                         (into [{:role "compaction" :content summary-text}] to-keep))]
              (d/warn-quiet "compaction"
                            (str "compacted " usage " -> " after " tokens")
                            #js {:before usage :after after :splitPoint split-point
                                 :summarized (count to-summarize) :kept (count to-keep)})
              ((:emit events) "compact"
                              {:summary summary-text
                               :trigger trigger
                               :before  usage
                               :after   after})
              {:before usage :after after :summariser :built-in
               :rejected rejected-errors})))))))

(defn compaction-receipt
  "What /compact tells the user, from what `compact` returned.

   The receipt used to compare token estimates of the live message list before
   and after: a two-turn session whose six-section summary was longer than the
   turns it replaced printed `Nothing to compact` while the session file got a
   summary and the next resume showed it. Only a nil result means nothing ran."
  [result]
  (if-not result
    "Nothing to compact"
    (let [{:keys [before after summariser rejected]} result]
      (str "Compacted ~" before " → ~" after " tokens"
           (when (= summariser :built-in)
             (str " (built-in summariser"
                  (when (seq rejected)
                    (str "; the extension summary was rejected: " (str/join "; " rejected)))
                  ")"))))))

(def ^:private branch-summary-prompt
  "Summarize this conversation branch in a structured format. Include:

## Goal
What was the user trying to accomplish?

## Progress
### Done
- List completed items

### In Progress
- List items that were started but not finished

## Key Decisions
- Important choices made during the conversation

## Next Steps
- What remains to be done

## Critical Context
- Technical details, constraints, or gotchas that should be preserved

Keep the summary concise but preserve all actionable information.")

(defn ^:async summarize-branch
  "Summarize a branch of conversation when switching away from it.
   Collects messages from the given leaf back to root, extracts file operations,
   and generates a structured LLM summary."
  [session model branch-leaf-id]
  (let [all-entries  ((:get-tree session))
        by-id        (into {} (map (fn [e] [(:id e) e]) all-entries))
        ;; Walk from leaf to root
        branch-msgs  (loop [current branch-leaf-id
                            path    []]
                       (if-let [entry (get by-id current)]
                         (recur (:parent-id entry) (cons entry path))
                         (vec path)))
        files-read     (extract-files-read branch-msgs)
        files-modified (extract-files-modified branch-msgs)
        conversation   (format-messages
                        (filter #(contains? #{"user" "assistant"} (:role %)) branch-msgs))
        prompt-text    (str branch-summary-prompt
                            "\n\n<conversation>\n" conversation "\n</conversation>"
                            (when (seq files-read)
                              (str "\n\n<read-files>\n" (str/join "\n" files-read) "\n</read-files>"))
                            (when (seq files-modified)
                              (str "\n\n<modified-files>\n" (str/join "\n" files-modified) "\n</modified-files>")))
        result         (js-await
                        (generateText
                         #js {:model    model
                              :messages #js [#js {:role "user" :content prompt-text}]}))]
    {:summary        (.-text result)
     :branch-leaf-id branch-leaf-id
     :files-read     files-read
     :files-modified files-modified}))

(defn ^:async maybe-auto-compact!
  "Compact BETWEEN turns when context has crossed the trigger point.

   Until this existed, `compact` was only ever reachable by hand — /compact, a
   pi-rpc handler, and the extension api's :compact. Nothing called it per turn,
   so its own threshold check never ran. A real session reached 615k tokens with
   ZERO compactions, and from ~turn 32 the model stopped calling tools at all:
   56% of its turns did no work and the user typed \"continue\" 44 times.
   Replaying that transcript through this trigger fires 6 compactions, the first
   at turn 15 — well before the collapse.

   Called after turn_finalize, never mid-turn: a compaction landing inside a
   task is documented to send the model off the rails."
  [agent]
  (when-let [session (some-> (:session agent) deref)]
    (try
      (js-await (compact session
                         (:model (:config agent))
                         (:events agent)
                         (merge {:model-registry (:model-registry agent)
                                 :model-key (model-info/config-model-key (:config agent))
                                 ;; What the provider actually counted last
                                 ;; request. Preferred over the tree estimate,
                                 ;; which measures a different thing entirely.
                                 :observed-usage (:last-input-tokens @(:state agent))
                                 ;; Without this the compaction is recorded but
                                 ;; the running conversation never shrinks.
                                 :state-atom (:state agent)}
                                (settings->opts (resolve-settings (:settings agent))))))
      (catch :default e
        ;; Never let compaction take the turn with it — a failed summary is
        ;; recoverable, a thrown one is not.
        (d/warn "[compaction] auto-compact failed:" (.-message e))
        nil))))

;; ── Overflow recovery ──────────────────────────────────────────────────────

(def ^:private overflow-patterns
  ;; Providers phrase this a dozen ways and none of them is a stable code.
  ["context length" "context_length" "maximum context" "context window"
   "too many tokens" "prompt is too long" "input is too long"
   "reduce the length" "exceeds the maximum" "max_tokens" "token limit"])

(defn context-overflow-error?
  "Is this provider error 'your prompt does not fit'?

   Pure and string-matched on purpose: there is no portable error code for it
   across the relay, local and first-party providers this talks to."
  [e]
  (let [msg (str/lower-case (str (or (some-> e .-message) e "")))]
    (boolean (some #(str/includes? msg %) overflow-patterns))))

(defn ^:async recover-from-overflow!
  "Compact and rebuild `st-config`'s messages after a context-overflow error.

   The safety net pi and OpenCode V2 both have and nyma did not. It matters
   most where the declared context window is wrong or missing — relay and local
   providers — because then the threshold trigger cannot know it should have
   fired. Forced: the estimate already proved wrong by overflowing, so its
   opinion is worthless here.

   Mutating st-config in place is the documented contract for this object
   (loop.cljs: 'extensions can MUTATE st-config in place'); it is nyma's own
   request config, not the model's tool-call args."
  [agent st-config]
  (when-let [session (some-> (:session agent) deref)]
    (js-await (compact session
                       (:model (:config agent))
                       (:events agent)
                       (merge {:model-registry (:model-registry agent)
                               :model-key (model-info/config-model-key (:config agent))
                               :state-atom (:state agent)}
                              (settings->opts (resolve-settings (:settings agent)))
                              {:force? true})))
    (let [rebuilt ((:build-context session))]
      (aset st-config "messages" (clj->js rebuilt))
      (d/warn "[compaction] context overflow — compacted and retrying"
              {:messages (count rebuilt)})
      true)))

