(ns agent.sessions.compaction
  (:require ["ai" :refer [generateText]]
            [clojure.string :as str]
            [agent.token-estimation :as te]))

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
   Uses [Role]: prefix to prevent the model from continuing the conversation."
  [messages]
  (->> messages
       (map (fn [m]
              (let [role    (:role m)
                    content (str (:content m))]
                (str "[" (str/capitalize (or role "unknown")) "]: "
                     (if (and (= role "tool_result") (> (count content) 2000))
                       (str (subs content 0 2000) "...[truncated]")
                       content)))))
       (str/join "\n\n")))

(defn ^:async compact
  "Summarize older messages when context approaches model limits.
   Extensions can intercept via 'before_compact' event.
   Accepts optional model-registry for accurate context windows."
  [session model events & [{:keys [custom-instructions model-registry]}]]
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
            evt-ctx #js {:context              context
                         :usage                usage
                         :summary              nil
                         :split-point          split-point
                         :messages-to-summarize (clj->js to-summarize)
                         :messages-to-keep      (clj->js to-keep)
                         :previous-summary     (:content prev-compaction)
                         :files-read           (clj->js files-read)
                         :files-modified       (clj->js files-modified)}]
        (js-await ((:emit-async events) "before_compact" evt-ctx))

        (if (.-summary evt-ctx)
          ((:append session)
            {:role     "compaction"
             :content  (.-summary evt-ctx)
             :metadata {:tokens-before  usage
                        :first-kept     (first to-keep)
                        :files-read     files-read
                        :files-modified files-modified}})

          (let [summary-result
                (js-await
                  (generateText
                    #js {:model  model
                         :messages
                         #js [#js {:role    "user"
                                   :content (str "You are a context summarization assistant. "
                                              "Do NOT continue the conversation. "
                                              "ONLY output a structured summary.\n\n"
                                              "Summarize this conversation concisely. "
                                              "Preserve key decisions, code changes, "
                                              "file paths, and technical context.\n\n"
                                              (when custom-instructions
                                                (str custom-instructions "\n\n"))
                                              (when (:content prev-compaction)
                                                (str "<previous-summary>\n"
                                                     (:content prev-compaction)
                                                     "\n</previous-summary>\n\n"
                                                     "Update the previous summary with new information below.\n\n"))
                                              "<conversation>\n"
                                              (format-messages to-summarize)
                                              "\n</conversation>"
                                              (when (seq files-read)
                                                (str "\n\n<read-files>\n"
                                                     (str/join "\n" files-read)
                                                     "\n</read-files>"))
                                              (when (seq files-modified)
                                                (str "\n\n<modified-files>\n"
                                                     (str/join "\n" files-modified)
                                                     "\n</modified-files>")))}]}))]

            ((:append session)
              {:role     "compaction"
               :content  (.-text summary-result)
               :metadata {:tokens-before  usage
                          :first-kept     (first to-keep)
                          :files-read     files-read
                          :files-modified files-modified}})

            ((:emit events) "compact"
              {:summary (.-text summary-result)})))))))

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
