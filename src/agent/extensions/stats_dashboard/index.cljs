(ns agent.extensions.stats-dashboard
  "Usage stats dashboard: /stats shows cost, tokens, model breakdown, daily trends."
  (:require [agent.pricing :refer [format-cost format-tokens]]
            [clojure.string :as str]))

(defn- bar-chart
  "Render an ASCII bar for a value relative to a max value."
  [value max-val width]
  (let [filled (if (zero? max-val) 0 (js/Math.round (* (/ value max-val) width)))]
    (apply str (repeat filled "█"))))

(defn- format-dashboard
  "Format all usage data into a readable dashboard string."
  [{:keys [totals by-model by-day recent-turns]}]
  (let [;; Totals section
        totals-section
        (str "  Totals\n"
             "    Sessions:  " (or (:session-count totals) 0)
             "        Turns:  " (or (:turn-count totals) 0) "\n"
             "    Input:     " (format-tokens (or (:total-input totals) 0)) " tok"
             "  Output:  " (format-tokens (or (:total-output totals) 0)) " tok\n"
             "    Cost:      " (format-cost (or (:total-cost totals) 0)))

        ;; By model section
        model-section
        (when (seq by-model)
          (str "\n\n  By Model\n"
               (str/join "\n"
                 (map (fn [m]
                        (str "    " (or (:model m) "unknown")
                             "  " (format-cost (:total-cost m))
                             "  (" (:turns m) " turns)"))
                   by-model))))

        ;; Daily section
        max-daily-cost (if (seq by-day) (apply max (map :total-cost by-day)) 0)
        day-section
        (when (seq by-day)
          (str "\n\n  Daily (last " (count by-day) " days)\n"
               (str/join "\n"
                 (map (fn [d]
                        (str "    " (:day d)
                             "  " (format-cost (:total-cost d))
                             "  " (bar-chart (:total-cost d) max-daily-cost 20)))
                   (take 10 by-day)))))]

    (str "─── Usage Stats ─────────────────────────────\n\n"
         totals-section
         (or model-section "")
         (or day-section ""))))

(defn ^:export default [api]
  (let [tool-metrics (atom {}) ;; {tool-name → {:calls :total-ms :errors}}

        on-tool-complete
        (fn [data]
          (let [tool (str (.-toolName data))
                dur  (or (.-duration data) 0)
                err  (boolean (.-isError data))]
            (swap! tool-metrics update tool
              (fn [m]
                (let [m (or m {:calls 0 :total-ms 0 :errors 0})]
                  (-> m
                      (update :calls inc)
                      (update :total-ms + dur)
                      (cond-> err (update :errors inc))))))))]

    (.on api "tool_complete" on-tool-complete)

    ;; /stats command with subcommands
    (.registerCommand api "stats"
      #js {:description "Show usage statistics. Subcommands: tools"
           :handler
           (fn [args ctx]
             (if (= (first args) "tools")
               ;; Per-tool performance metrics
               (let [metrics @tool-metrics]
                 (if (empty? metrics)
                   (.notify (.-ui ctx) "No tool calls recorded" "info")
                   (.notify (.-ui ctx)
                     (str "Tool Performance:\n"
                          (str/join "\n"
                            (map (fn [[tname m]]
                                   (str "  " tname ": " (:calls m) " calls, "
                                        (js/Math.round (/ (:total-ms m) (max 1 (:calls m)))) "ms avg"
                                        (when (> (:errors m) 0) (str ", " (:errors m) " errors"))))
                              (sort-by (fn [[_ m]] (- (:calls m))) metrics))))
                     "info")))
               ;; Default: full dashboard
               (if-let [store (.-__sqlite-store api)]
                 (let [totals   ((:get-usage-totals store))
                       by-model ((:get-usage-by-model store))
                       by-day   ((:get-usage-by-day store) 14)
                       dashboard (format-dashboard {:totals totals :by-model by-model :by-day by-day})]
                   (if (and (.-ui ctx) (.-showOverlay (.-ui ctx)))
                     (.showOverlay (.-ui ctx) dashboard)
                     (.notify (.-ui ctx) dashboard "info")))
                 (.notify (.-ui ctx) "Stats require SQLite storage" "error"))))})

    ;; /stats-session — current session only
    (.registerCommand api "stats-session"
      #js {:description "Show usage stats for current session"
           :handler
           (fn [_args ctx]
             (let [state (.getState api)
                   input  (:total-input-tokens state)
                   output (:total-output-tokens state)
                   cost   (:total-cost state)
                   turns  (:turn-count state)]
               (.notify (.-ui ctx)
                 (str "Session: " turns " turns | "
                      (format-tokens input) " in / " (format-tokens output) " out | "
                      (format-cost cost)))))})

    ;; Cleanup
    (fn []
      (.off api "tool_complete" on-tool-complete)
      (.unregisterCommand api "stats")
      (.unregisterCommand api "stats-session"))))
