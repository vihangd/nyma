(ns agent.sessions.compaction
  (:require ["ai" :refer [generateText]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]
            [agent.token-estimation :as te]
            [agent.utils.debug :as d]
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
  "Extract file paths from tool_call entries for 'write' and 'edit' tools."
  [messages]
  (->> messages
       (filter #(and (= (:role %) "tool_call")
                     (contains? #{"write" "edit"} (get-in % [:metadata :tool-name]))))
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
- File paths must be EXACT (e.g., src/agent/loop.cljs:142, NOT \"the loop file\")
- Error messages must be VERBATIM
- Configuration values must be exact numbers
- Section 6 MUST contain at least one verbatim quote per pending task")

(defn- build-compact-user-prompt
  [{:keys [custom-instructions previous-summary to-summarize files-read files-modified]}]
  (str (when custom-instructions (str custom-instructions "\n\n"))
       (when previous-summary
         (str "<previous-summary>\n" previous-summary "\n</previous-summary>\n\n"
              "Update the previous summary with new information below.\n\n"))
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

(defn validate-compaction
  "Return a vector of error strings. Empty vector = valid."
  [summary files-read files-modified]
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

(defn ^:async compact-with-retry
  "Call generateText with compact-system-prompt + user-prompt. Validate the
   result and run one fix-retry if validation fails. Always returns a string
   (uses unvalidated fallback rather than blocking compaction).
   Optional gen-fn replaces generateText (used in tests)."
  [model user-prompt files-read files-modified & [gen-fn]]
  (let [gen          (or gen-fn generateText)
        first-result (js-await
                      (gen
                       #js {:model    model
                            :system   compact-system-prompt
                            :messages #js [#js {:role "user" :content user-prompt}]}))
        first-text   (.-text first-result)
        first-errors (validate-compaction first-text files-read files-modified)]
    (if (empty? first-errors)
      first-text
      (let [_ (d/warn "compaction" "validation failed, retrying"
                      #js {:errors (clj->js first-errors)})
            fix-result (js-await
                        (gen
                         #js {:model    model
                              :system   compact-system-prompt
                              :messages #js [#js {:role    "user"
                                                  :content (build-fix-user-prompt
                                                            first-text first-errors)}]}))
            fix-text   (.-text fix-result)
            fix-errors (validate-compaction fix-text files-read files-modified)]
        (when (seq fix-errors)
          (d/warn "compaction" "validation failed after retry — using unvalidated summary"
                  #js {:errors (clj->js fix-errors)}))
        fix-text))))

(defn- write-precompact-dump
  "Write to-summarize + to-keep to two tmpfiles (JSON + text) before the
   before_compact event fires. Returns {:json-path ... :text-path ...} or nil."
  [session to-summarize to-keep]
  (try
    (let [tmp-dir      (path/join (os/tmpdir) "nyma-precompact")
          _            (fs/mkdirSync tmp-dir #js {:recursive true})
          session-name (try
                         (let [fp ((:get-file-path session))]
                           (if fp (path/basename (str fp) ".jsonl") "session"))
                         (catch :default _ "session"))
          ts           (js/Date.now)
          base         (str session-name "-" ts)
          json-path    (path/join tmp-dir (str base ".json"))
          text-path    (path/join tmp-dir (str base ".txt"))
          payload      #js {:toSummarize (clj->js to-summarize)
                            :toKeep      (clj->js to-keep)}]
      (fs/writeFileSync json-path (js/JSON.stringify payload nil 2))
      (fs/writeFileSync text-path (format-messages to-summarize))
      {:json-path json-path :text-path text-path})
    (catch :default e
      (d/warn "compaction" "precompact-dump failed" #js {:error (str e)})
      nil)))

(defn- cleanup-precompact-dump [dump]
  (when dump
    (try (fs/unlinkSync (:json-path dump)) (catch :default _ nil))
    (try (fs/unlinkSync (:text-path dump)) (catch :default _ nil))))

(defn ^:async compact
  "Summarize older messages when context approaches model limits.
   Extensions can intercept via 'before_compact' event.
   Accepts optional model-registry for accurate context windows."
  [session model events & [{:keys [custom-instructions model-registry gen-fn]}]]
  (let [context ((:build-context session))
        usage   (te/estimate-messages-tokens context)
        limit   (if model-registry
                  ((:context-window model-registry) (or (.-modelId model) "unknown"))
                  100000)]

    (when (> usage (* limit 0.85))
      (let [split-point     (find-split-point context (* limit 0.3))
            to-summarize    (vec (take split-point context))
            to-keep         (vec (drop split-point context))
            prev-compaction (->> context (filter #(= (:role %) "compaction")) last)
            files-read      (extract-files-read to-summarize)
            files-modified  (extract-files-modified to-summarize)
            evt-ctx #js {:context               context
                         :usage                 usage
                         :summary               nil
                         :split-point           split-point
                         :messages-to-summarize (clj->js to-summarize)
                         :messages-to-keep      (clj->js to-keep)
                         :previous-summary      (:content prev-compaction)
                         :files-read            (clj->js files-read)
                         :files-modified        (clj->js files-modified)}
            dump    (write-precompact-dump session to-summarize to-keep)
            _       (when dump
                      (aset evt-ctx "precompactJsonPath" (:json-path dump))
                      (aset evt-ctx "precompactTextPath" (:text-path dump)))]
        (js-await ((:emit-async events) "before_compact" evt-ctx))

        (if (.-summary evt-ctx)
          ;; Extension-provided summary path: validate and warn, but never block
          (let [ext-summary (.-summary evt-ctx)
                ext-errors  (validate-compaction ext-summary files-read files-modified)]
            (when (seq ext-errors)
              (d/warn "compaction" "extension-provided summary failed validation"
                      #js {:errors (clj->js ext-errors)}))
            ((:append session)
             {:role     "compaction"
              :content  ext-summary
              :metadata {:tokens-before  usage
                         :first-kept     (first to-keep)
                         :files-read     files-read
                         :files-modified files-modified}})
            (cleanup-precompact-dump dump))

          ;; Main path: use compact-with-retry (validates + one fix-retry)
          (let [user-prompt  (build-compact-user-prompt
                              {:custom-instructions custom-instructions
                               :previous-summary    (:content prev-compaction)
                               :to-summarize        to-summarize
                               :files-read          files-read
                               :files-modified      files-modified})
                summary-text (js-await
                              (compact-with-retry model user-prompt files-read files-modified gen-fn))]

            ((:append session)
             {:role     "compaction"
              :content  summary-text
              :metadata {:tokens-before  usage
                         :first-kept     (first to-keep)
                         :files-read     files-read
                         :files-modified files-modified}})
            (cleanup-precompact-dump dump)

            ((:emit events) "compact"
                            {:summary summary-text})))))))

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
