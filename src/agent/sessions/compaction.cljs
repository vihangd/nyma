(ns agent.sessions.compaction
  (:require ["ai" :refer [generateText]]
            [clojure.string :as str]
            [agent.token-estimation :as te]))

(defn- find-split-point [context limit]
  (loop [i 0 tokens 0]
    (if (or (>= i (count context))
            (>= tokens limit))
      i
      (recur (inc i)
             (+ tokens (te/estimate-tokens (str (:content (nth context i)))))))))

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

(defn format-messages [messages]
  (->> messages
       (map (fn [m] (str (:role m) ": " (:content m))))
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
      (let [evt-ctx #js {:context context :usage usage :summary nil}]
        (js-await ((:emit-async events) "before_compact" evt-ctx))

        (if (.-summary evt-ctx)
          ((:append session)
            {:role    "compaction"
             :content (.-summary evt-ctx)})

          (let [split-point  (find-split-point context (* limit 0.3))
                to-summarize (take split-point context)
                summary-result
                (js-await
                  (generateText
                    #js {:model  model
                         :messages
                         #js [#js {:role    "user"
                                   :content (str "Summarize this conversation concisely. "
                                              "Preserve key decisions, code changes, "
                                              "file paths, and technical context.\n\n"
                                              (when custom-instructions
                                                (str custom-instructions "\n\n"))
                                              (format-messages to-summarize))}]}))]

            ((:append session)
              {:role     "compaction"
               :content  (.-text summary-result)
               :metadata {:tokens-before usage
                          :first-kept    (when (< split-point (count context))
                                           (nth context split-point))}})

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
