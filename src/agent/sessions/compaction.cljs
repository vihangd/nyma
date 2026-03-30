(ns agent.sessions.compaction
  (:require ["ai" :refer [generateText]]))

(defn- estimate-tokens [messages]
  ;; rough estimate: 4 chars per token
  (/ (reduce + (map #(count (str (:content %))) messages)) 4))

(defn- get-model-limit [_model]
  ;; Default to 100k context window
  100000)

(defn- find-split-point [context limit]
  (loop [i 0 tokens 0]
    (if (or (>= i (count context))
            (>= tokens limit))
      i
      (recur (inc i)
             (+ tokens (/ (count (str (:content (nth context i)))) 4))))))

(defn- format-messages [messages]
  (->> messages
       (map (fn [m] (str (:role m) ": " (:content m))))
       (clojure.string/join "\n\n")))

(defn ^:async compact
  "Summarize older messages when context approaches model limits.
   Extensions can intercept via 'before_compact' event."
  [session model events & [{:keys [custom-instructions]}]]
  (let [context ((:build-context session))
        usage   (estimate-tokens context)
        limit   (get-model-limit model)]

    (when (> usage (* limit 0.85))
      (let [ext-result ((:emit events) "before_compact"
                         {:context context :usage usage})]

        (if (:summary ext-result)
          ((:append session)
            {:role    "compaction"
             :content (:summary ext-result)})

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
                          :first-kept    (nth context split-point)}})

            ((:emit events) "compact"
              {:summary (.-text summary-result)})))))))
