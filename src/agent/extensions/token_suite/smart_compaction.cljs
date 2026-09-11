(ns agent.extensions.token-suite.smart-compaction
  (:require ["ai" :refer [generateText]]
            [agent.extensions.token-suite.shared :as shared]
            [agent.sessions.compaction :as compaction]
            [agent.ui.think-tag-parser :refer [strip-think-tags]]
            [clojure.string :as str]))

;; ── Structured Summary Template ────────────────────────────────

(def structured-prompt
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

;; ── Tagged Serialization ────────────────────────────────────────

(defn- serialize-for-summary
  "Serialize messages to tagged text for summarization.
   Uses [Role]: prefix to prevent the model from continuing the conversation.
   Truncates tool results to max-chars."
  [messages max-chars]
  (let [limit (or max-chars 2000)]
    (->> messages
         (map (fn [m]
                (let [role    (shared/msg-role m)
                      content (str (shared/msg-content m))]
                  (case role
                    "user"       (str "[User]: " content)
                    "assistant"  (str "[Assistant]: " (strip-think-tags content))
                    "tool_call"  (let [meta (or (when (map? m) (:metadata m))
                                                (when (object? m) (.-metadata m)))
                                       tname (when meta
                                               (or (when (map? meta) (:tool-name meta))
                                                   (when (object? meta) (aget meta "tool-name"))))]
                                   (str "[Tool Call]: " (or tname "unknown")))
                    "tool_result" (str "[Tool Result]: "
                                       (if (> (count content) limit)
                                         (str (subs content 0 limit) "...[truncated]")
                                         content))
                    "compaction" (str "[Previous Summary]: " content)
                    (str "[" role "]: " content)))))
         (str/join "\n\n"))))

;; ── File Section Parsing ───────────────────────────────────────

(defn- parse-file-section
  "Extract file paths from a structured summary section like '## Files Read'."
  [summary section-name]
  (when summary
    (let [pattern (js/RegExp. (str "## " section-name "\\n([\\s\\S]*?)(?:\\n##|$)"))
          match (.exec pattern summary)]
      (when match
        (->> (.split (aget match 1) "\n")
             (map str/trim)
             (filter #(and (seq %) (not (.startsWith % "[")))))))))

;; ── Background Summary Builder (no LLM calls) ─────────────────

(defn- extract-user-intent [messages]
  (let [first-user (->> messages
                        (filter #(= (shared/msg-role %) "user"))
                        first)]
    (when first-user
      (let [content (str (shared/msg-content first-user))]
        (if (> (count content) 500)
          (subs content 0 500)
          content)))))

(defn- extract-file-ops [messages]
  (let [ops (atom {:read [] :edit [] :write []})]
    (doseq [msg messages]
      (let [role (shared/msg-role msg)
            content (str (shared/msg-content msg))]
        ;; Look for tool_call messages with metadata
        (when (= role "tool_call")
          (let [meta (or (when (map? msg) (:metadata msg))
                         (when (object? msg) (.-metadata msg)))
                tool-name (when meta
                            (or (when (map? meta) (:tool-name meta))
                                (when (object? meta) (aget meta "tool-name"))))
                args (when meta
                       (or (when (map? meta) (:args meta))
                           (when (object? meta) (.-args meta))))
                fpath (when args
                        (or (when (map? args) (:path args))
                            (when (object? args) (.-path args))))]
            (when (and tool-name fpath)
              (cond
                (= tool-name "read")
                (swap! ops update :read #(if (some #{fpath} %) % (conj % fpath)))
                (contains? #{"edit" "write" "multi_edit"} tool-name)
                (swap! ops update :edit #(if (some #{fpath} %) % (conj % fpath)))))))))
    @ops))

(defn- extract-errors [messages]
  (let [errors (atom [])]
    (doseq [msg messages]
      (when (= (shared/msg-role msg) "tool_result")
        (let [content (str (shared/msg-content msg))]
          (when (shared/has-error-pattern? content)
            (swap! errors conj
                   (if (> (count content) 200) (subs content 0 200) content))))))
    @errors))

(defn- extract-active-work [messages]
  (let [last-assistant (->> messages
                            (filter #(= (shared/msg-role %) "assistant"))
                            last)]
    (when last-assistant
      (let [content (str (shared/msg-content last-assistant))]
        (if (> (count content) 300) (subs content 0 300) content)))))

(defn- build-background-summary [messages]
  (let [intent (extract-user-intent messages)
        ops    (extract-file-ops messages)
        errors (extract-errors messages)
        active (extract-active-work messages)
        all-paths (distinct (concat (:read ops) (:edit ops)))]
    (str "## User Intent\n"
         (or intent "[not yet captured]") "\n\n"

         "## Completed Work\n"
         (if (seq (:edit ops))
           (str/join "\n" (map #(str "- edit → " %) (:edit ops)))
           "- [no edits yet]")
         "\n\n"

         "## Errors & Corrections\n"
         (if (seq errors)
           (str/join "\n" (map #(str "- " %) errors))
           "- [none]")
         "\n\n"

         "## Active Work\n"
         (or active "[starting]") "\n\n"

         "## Key References\n"
         (if (seq all-paths)
           (str/join "\n" all-paths)
           "[none yet]"))))

;; ── Activate / Deactivate ──────────────────────────────────────

(defn activate [api]
  (let [config (shared/load-config)
        sc-cfg (:smart-compaction config)]

    ;; Hook C: Structured compaction (before_compact, priority 100)
    (.on api "before_compact"
         (fn [evt-ctx _ctx]
           (let [;; Prefer the metadata-bearing span. extract-file-ops needs
                 ;; :metadata tool-name/args, and messages-to-summarize comes
                 ;; from build-context, which entry->core-message has stripped —
                 ;; so file extraction silently found NOTHING and this
                 ;; summariser reported "[no edits yet]" for a session that
                 ;; edited 159 files, producing 157 characters for an entire
                 ;; session of work.
                 to-summarize (or (aget evt-ctx "entries-to-summarize")
                                  (aget evt-ctx "messages-to-summarize")
                                  (.-context evt-ctx))
                 messages (if (sequential? to-summarize) to-summarize
                              (when (and to-summarize (.-length to-summarize))
                                (vec (map (fn [i] (aget to-summarize i))
                                          (range (.-length to-summarize))))))
              ;; Read previous summary for iterative updates
                 previous-summary (or (aget evt-ctx "previous-summary")
                                      (->> messages
                                           (filter #(= (shared/msg-role %) "compaction"))
                                           last
                                           shared/msg-content))
              ;; Use pre-extracted file lists when available, else extract
                 current-files-read (or (when-let [fr (aget evt-ctx "files-read")]
                                          (vec fr))
                                        (when (seq messages)
                                          (compaction/extract-files-read messages)))
                 current-files-modified (or (when-let [fm (aget evt-ctx "files-modified")]
                                              (vec fm))
                                            (when (seq messages)
                                              (compaction/extract-files-modified messages)))
              ;; Accumulate file lists from previous compaction
                 prev-files-read (when previous-summary
                                   (parse-file-section previous-summary "Files Read"))
                 prev-files-modified (when previous-summary
                                       (parse-file-section previous-summary "Files Modified"))
                 files-read (vec (distinct (concat (or prev-files-read [])
                                                   (or current-files-read []))))
                 files-modified (vec (distinct (concat (or prev-files-modified [])
                                                       (or current-files-modified []))))
              ;; Build summary from messages
                 summary (when (seq messages)
                           (build-background-summary messages))]
             (when summary
               (let [enhanced (str ;; Carry forward previous context
                               (when previous-summary
                                 (str "## Previous Context\n" previous-summary "\n\n"))
                               summary
                               ;; Cumulative file lists
                               (when (seq files-read)
                                 (str "\n\n## Files Read\n" (str/join "\n" files-read)))
                               (when (seq files-modified)
                                 (str "\n\n## Files Modified\n" (str/join "\n" files-modified))))]
                 (aset evt-ctx "summary" enhanced)
                 (swap! shared/suite-stats update-in [:smart-compaction :full-compactions] inc)))))
         100)

    ;; Hook E: Optional LLM-enhanced summary (before_compact, priority 99 — runs after Hook C)
    (when (:use-llm-summary sc-cfg)
      (.on api "before_compact"
           (^:async fn [evt-ctx ctx]
          ;; Only run if Hook C already set a summary (we enhance it)
             (when-let [extraction-summary (.-summary evt-ctx)]
               (let [to-summarize (or (aget evt-ctx "messages-to-summarize") (.-context evt-ctx))
                     messages (if (sequential? to-summarize) to-summarize
                                  (when (and to-summarize (.-length to-summarize))
                                    (vec (map (fn [i] (aget to-summarize i))
                                              (range (.-length to-summarize))))))
                  ;; Resolve summarization model
                     model-spec (:summarization-model sc-cfg)
                     model-registry (when ctx (.-modelRegistry ctx))
                     model (when (and model-spec model-registry)
                             (try
                               (let [parts (.split (str model-spec) "/")
                                     provider (when (> (count parts) 1) (first parts))
                                     model-id (if (> (count parts) 1) (second parts) (str model-spec))]
                                 (when-let [resolve (.-resolve model-registry)]
                                   (resolve provider model-id)))
                               (catch :default _ nil)))]
                 (when (and model (seq messages))
                   (try
                     (let [conversation-text (serialize-for-summary messages 2000)
                           previous-summary (aget evt-ctx "previous-summary")
                           prompt (str structured-prompt "\n\n"
                                       (when previous-summary
                                         (str "<previous-summary>\n" previous-summary
                                              "\n</previous-summary>\n\n"
                                              "Update the previous summary with new information.\n\n"))
                                       "<conversation>\n" conversation-text "\n</conversation>\n\n"
                                       "<extraction-summary>\n" extraction-summary
                                       "\n</extraction-summary>\n\n"
                                       "Use the extraction summary as a reference. "
                                       "Produce a comprehensive structured summary.")
                           result (js-await
                                   (generateText
                                    #js {:model    model
                                         :messages #js [#js {:role "user" :content prompt}]
                                         :maxTokens 4096}))]
                       (when-let [text (.-text result)]
                         (when (seq (.trim text))
                           (aset evt-ctx "summary" text))))
                     (catch :default _e
                    ;; Fallback: keep the pure extraction summary from Hook C
                       nil))))))
           99))

    ;; Return deactivator
    (fn [] nil)))
