(ns agent.extensions.stats-dashboard
  "Usage stats dashboard: /stats shows cost, tokens, model breakdown, daily trends."
  (:require [agent.pricing :refer [format-cost format-tokens]]
            [agent.commands.resolver :refer [resolve-command]]
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

(def ^:private number-commands
  "The other commands that answer a question about numbers, and what each
   one answers. /stats is where people go looking for all of them, so it
   points at the ones that are registered — and only those: half of these
   come from extensions that may be disabled, and a footer advertising a
   command that does not exist is worse than no footer."
  [["stats-session"  "this session's turns, tokens and cost"]
   ["token-stats"    "token usage broken down by message"]
   ["bash-stats"     "shell commands run, and how long they took"]
   ["headroom-stats" "how much context window is left"]
   ["debug"          "raw agent state"]
   ["session"        "session file, branch and message counts"]])

(defn stats-index-footer
  "The `/stats` footer: one line per number command that is actually
   registered. Resolution goes through `resolve-command`, so an extension's
   namespaced `headroom__headroom-stats` is found by its short name, the
   same name the footer prints.

   Returns nil when none of them are registered. Pure — takes the commands
   map, not the api."
  [commands]
  (let [rows (keep (fn [[name what]]
                     (when (resolve-command (or commands {}) name)
                       (str "    /" name
                            (.repeat " " (max 1 (- 18 (count name))))
                            what)))
                   number-commands)]
    (when (seq rows)
      (str "\n\n  See also\n" (str/join "\n" rows)))))

(defn- pct [part whole]
  (if (pos? whole) (js/Math.round (* 100 (/ part whole))) 0))

(defn format-tool-report
  "Per-tool timings plus how much context each tool actually consumed.

   `model` is the figure that matters: bytes that entered the context window
   after the per-tool result policy. `raw` is what the tool produced, so the
   gap shows how much the existing caps already save — without both, a change
   aimed at tool output cannot be told apart from noise."
  [metrics]
  ;; Descending by model-bytes — the biggest context consumers first, which is
  ;; the entire point of the report. `(- a b)` here was two-arg SUBTRACTION,
  ;; not negation, so it sorted ascending on `bytes - calls` and buried the
  ;; worst offenders at the bottom.
  (let [rows        (sort-by (fn [[_ m]] (- (or (:model-bytes m) 0)))
                             metrics)
        total-model (reduce + 0 (map (fn [[_ m]] (or (:model-bytes m) 0)) metrics))
        total-raw   (reduce + 0 (map (fn [[_ m]] (or (:raw-bytes m) 0)) metrics))]
    (str "Tool Performance:\n"
         (str/join "\n"
                   (map (fn [[tname m]]
                          (let [calls (or (:calls m) 0)
                                model (or (:model-bytes m) 0)
                                raw   (or (:raw-bytes m) 0)]
                            (str "  " tname ": " calls " calls, "
                                 (js/Math.round (/ (or (:total-ms m) 0) (max 1 calls))) "ms avg"
                                 (when (> (or (:errors m) 0) 0)
                                   (str ", " (:errors m) " errors"))
                                 (when (pos? model)
                                   (str "\n      context " (format-tokens model) " chars"
                                        " (" (pct model total-model) "% of tool output)"
                                        (when (> raw model)
                                          (str ", capped from " (format-tokens raw))))))))
                        rows))
         (when (pos? total-model)
           (str "\n\n  Tool output entering context: " (format-tokens total-model) " chars"
                (when (> total-raw total-model)
                  (str " (policy saved " (format-tokens (- total-raw total-model))
                       ", " (pct (- total-raw total-model) total-raw) "%)")))))))

(defn ^:export default [api]
  (let [;; {tool-name → {:calls :total-ms :errors :raw-bytes :model-bytes}}
        tool-metrics (atom {})

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
                           (cond-> err (update :errors inc))))))))

        ;; Bytes come from tool_execution_end, which fires AFTER the per-tool
        ;; result policy — the only point where both the raw size and the
        ;; model-visible size exist. tool_complete fires before it and sees
        ;; only the raw string.
        on-tool-bytes
        (fn [data]
          (let [tool  (str (.-toolName data))
                raw   (or (.-rawBytes data) 0)
                model (or (.-modelBytes data) 0)]
            (when (pos? (+ raw model))
              (swap! tool-metrics update tool
                     (fn [m]
                       (let [m (or m {:calls 0 :total-ms 0 :errors 0})]
                         (-> m
                             (update :raw-bytes (fnil + 0) raw)
                             (update :model-bytes (fnil + 0) model))))))))]

    (.on api "tool_complete" on-tool-complete)
    (.on api "tool_execution_end" on-tool-bytes)

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
                                   (.notify (.-ui ctx) (format-tool-report metrics) "info")))
               ;; Default: full dashboard
                               (if-let [store (aget api "__sqlite-store")]
                                 (let [totals   ((:get-usage-totals store))
                                       by-model ((:get-usage-by-model store))
                                       by-day   ((:get-usage-by-day store) 14)
                                       dashboard (str (format-dashboard {:totals totals :by-model by-model :by-day by-day})
                                                      (or (stats-index-footer (.getCommands api)) ""))]
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
                                   input  (or (:total-input-tokens state) 0)
                                   output (:total-output-tokens state)
                                   cost   (:total-cost state)
                                   turns  (:turn-count state)
                                   ;; Prompt-cache hit rate: the number that says
                                   ;; whether the system-prompt layout and the
                                   ;; kv_cache breakpoints are earning their keep.
                                   cread  (or (:total-cache-read-tokens state) 0)
                                   cwrite (or (:total-cache-write-tokens state) 0)]
                               (.notify (.-ui ctx)
                                        (str "Session: " turns " turns | "
                                             (format-tokens input) " in / " (format-tokens output) " out | "
                                             (format-cost cost)
                                             (when (pos? input)
                                               (str " | cache: " (js/Math.round (* 100 (/ cread input)))
                                                    "% of input read from cache"
                                                    (when (pos? cwrite)
                                                      (str ", " (format-tokens cwrite) " written"))))))))})

    ;; Cleanup
    (fn []
      (.off api "tool_complete" on-tool-complete)
      (.unregisterCommand api "stats")
      (.unregisterCommand api "stats-session"))))
