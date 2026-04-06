(ns agent.extensions.token-suite.smart-compaction
  (:require ["ai" :refer [generateText]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.token-suite.shared :as shared]
            [agent.token-estimation :as te]
            [agent.sessions.compaction :as compaction]
            [clojure.string :as str]))

;; ── Structured Summary Template ────────────────────────────────

(def ^:private structured-prompt
  "You are summarizing a coding agent conversation for continuity. Generate a structured summary preserving ALL specific values.

## Required Format

### User Intent
[The user's original request — preserve exact phrasing]

### Completed Work
- [action] → [EXACT file path(s)]

### Errors & Corrections
[Error messages VERBATIM — do not paraphrase. Include user corrections.]

### Active Work
[Current task state, partial results]

### Pending Tasks
[Remaining items in order]

### Key References
[ALL file paths, line numbers, error strings, config values — one per line, VERBATIM]

## Critical Rules
- File paths must be EXACT (e.g., src/agent/loop.cljs:142, NOT \"the loop file\")
- Error messages must be VERBATIM
- Configuration values must be exact numbers
- ALL paths from <files-read> and <files-modified> MUST appear in Key References")

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
                                (when (object? meta) (.-tool-name meta))))
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

;; ── Filesystem Offloading ──────────────────────────────────────

(defn- ensure-cache-dir [cache-dir]
  (let [abs-dir (if (path/isAbsolute cache-dir)
                  cache-dir
                  (path/join (js/process.cwd) cache-dir))]
    (when-not (fs/existsSync abs-dir)
      (fs/mkdirSync abs-dir #js {:recursive true}))
    abs-dir))

(defn- offload-to-file [content cache-dir max-preview-lines]
  (let [hash (shared/hash-content content)
        abs-dir (ensure-cache-dir cache-dir)
        fpath (path/join abs-dir (str hash ".txt"))
        lines (.split (str content) "\n")
        preview-lines (.slice lines 0 (min max-preview-lines (.-length lines)))
        preview (.join preview-lines "\n")
        tokens (te/estimate-tokens content)]
    (fs/writeFileSync fpath content "utf8")
    {:hash hash :path fpath :tokens tokens :preview preview}))

;; ── Activate / Deactivate ──────────────────────────────────────

(defn activate [api]
  (let [config (shared/load-config)
        sc-cfg (:smart-compaction config)
        background-summary (atom nil)
        tool-result-cache (atom {})
        read-history (atom #{})]

    ;; Hook A: Background summary generation (after_provider_request, priority 10)
    (.on api "after_provider_request"
      (fn [event ctx]
        (let [usage-info (when ctx
                           (let [get-usage (.-getContextUsage ctx)]
                             (when (fn? get-usage) (get-usage))))
              ;; Fallback: use event data for token tracking
              input-tokens (or (when usage-info (.-inputTokens usage-info))
                               (when event (.-inputTokens (.-usage event)))
                               0)
              turn-count (or (when event (.-turnCount event)) 0)]
          ;; Only build summary after a few turns and when we have meaningful context
          (when (>= turn-count 2)
            ;; Extract messages from the context usage or build from state
            ;; For now, we build the summary from the event data
            ;; The full messages aren't in after_provider_request, so we track incrementally
            (swap! shared/suite-stats update-in [:smart-compaction :background-updates] inc))))
      10)

    ;; Hook B: Filesystem offloading (context_assembly, priority 85)
    (.on api "context_assembly"
      (fn [event _ctx]
        (let [budget (.-tokenBudget event)
              tokens-used (when budget (.-tokensUsed budget))
              input-budget (when budget (.-inputBudget budget))
              threshold (:offload-threshold sc-cfg)
              messages (.-messages event)]
          (when (and tokens-used input-budget
                     (> (/ tokens-used input-budget) threshold))
            (let [total (.-length messages)
                  cache-dir (or (:cache-dir sc-cfg) ".nyma/context-cache")
                  max-preview (:max-preview-lines sc-cfg)
                  min-tokens (:offload-min-tokens sc-cfg)]
              (doseq [i (range total)]
                (let [msg (aget messages i)
                      role (shared/msg-role msg)
                      content (str (shared/msg-content msg))]
                  (when (= role "tool_result")
                    ;; Skip already-masked results
                    (when-not (.startsWith content "[tool_result:")
                      ;; Skip error-containing results
                      (when-not (shared/has-error-pattern? content)
                        (let [est (te/estimate-tokens content)]
                          (when (> est min-tokens)
                            (let [info (offload-to-file content cache-dir max-preview)]
                              (aset msg "content"
                                (str "[Archived: " (:tokens info) " tokens — use retrieve_archived(\"" (:hash info) "\") to recover]\n"
                                     "Preview:\n" (:preview info) "\n"
                                     "Cache: " (:hash info) ".txt"))
                              (swap! tool-result-cache assoc (:hash info) info)
                              (swap! shared/suite-stats update-in [:smart-compaction :offloads] inc)
                              (swap! shared/suite-stats update-in [:smart-compaction :tokens-archived]
                                     + (:tokens info))))))))))))
          nil))
      85)

    ;; Hook C: Structured compaction (before_compact, priority 100)
    (.on api "before_compact"
      (fn [evt-ctx _ctx]
        (let [context (.-context evt-ctx)
              ;; Build background summary from context messages
              messages (if (sequential? context) context
                         (when (and context (.-length context))
                           (vec (map (fn [i] (aget context i))
                                     (range (.-length context))))))
              summary (when (seq messages)
                        (build-background-summary messages))
              files-read (when (seq messages)
                           (compaction/extract-files-read messages))
              files-modified (when (seq messages)
                               (compaction/extract-files-modified messages))]
          ;; Set the structured summary (no LLM call — pure extraction)
          (when summary
            (let [enhanced (str summary
                               (when (seq files-read)
                                 (str "\n\n## Files Read\n" (str/join "\n" files-read)))
                               (when (seq files-modified)
                                 (str "\n\n## Files Modified\n" (str/join "\n" files-modified))))]
              (aset evt-ctx "summary" enhanced)
              (swap! shared/suite-stats update-in [:smart-compaction :full-compactions] inc)))))
      100)

    ;; Hook D: Re-read detection (tool_execution_end, priority 0)
    (.on api "tool_execution_end"
      (fn [event _ctx]
        (let [tool (or (.-toolName event) "")
              args (.-args event)
              fpath (when args (or (.-path args) ""))]
          (when (and (= tool "read") (seq fpath))
            (if (contains? @read-history fpath)
              ;; Re-read detected — check if this file was previously archived
              (when (some (fn [[_hash info]]
                            (let [cached-content (when (fs/existsSync (:path info))
                                                   (fs/readFileSync (:path info) "utf8"))]
                              (when cached-content
                                (.includes cached-content fpath))))
                          @tool-result-cache)
                (swap! shared/suite-stats update-in [:smart-compaction :re-reads] inc))
              (swap! read-history conj fpath)))))
      0)

    ;; Tool: retrieve_archived
    (let [retrieve-tool
          (js/Object.assign #js {}
            #js {:description "Retrieve previously archived tool result content from the context cache. Use the hash from archive notices."
                 :parameters #js {:type "object"
                                  :properties #js {:hash #js {:type "string"
                                                              :description "The hash from the archive notice (e.g., 'a1b2c3d4')"}}}
                 :execute (fn [args]
                            (let [hash (or (.-hash args) (aget args "hash") "")
                                  cache-dir (or (:cache-dir sc-cfg) ".nyma/context-cache")
                                  abs-dir (if (path/isAbsolute cache-dir)
                                            cache-dir
                                            (path/join (js/process.cwd) cache-dir))
                                  fpath (path/join abs-dir (str hash ".txt"))]
                              (if (fs/existsSync fpath)
                                (fs/readFileSync fpath "utf8")
                                (str "No cached content found for hash: " hash))))})]
      (.registerTool api "retrieve_archived" retrieve-tool))

    ;; Return deactivator
    (fn []
      (.unregisterTool api "retrieve_archived")
      (reset! background-summary nil)
      (reset! tool-result-cache {})
      (reset! read-history #{}))))
