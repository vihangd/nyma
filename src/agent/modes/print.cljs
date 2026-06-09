(ns agent.modes.print
  (:require [agent.loop :refer [run]]
            ["nanoid" :refer [nanoid]]
            [clojure.string :as str]))

(defn ^:async start [agent prompt]
  (when prompt
    (js-await (run agent prompt))
    (let [messages (:messages @(:state agent))
          last-msg  (last messages)]
      (println (:content last-msg)))))

(defn ^:async start-json [agent prompt]
  (when prompt
    (js-await (run agent prompt))
    (let [messages (:messages @(:state agent))]
      (println (js/JSON.stringify (clj->js messages) nil 2)))))

;; ── claude-style result object (`-p --output-format json`) ──
;; A single JSON object matching the de-facto `claude -p --output-format json`
;; contract, so headless orchestrators (e.g. cw) can consume nyma like claude:
;;   { type, is_error, result, session_id, total_cost_usd, usage{…}, duration_ms }

(defn- last-assistant-text
  "The final assistant message's text (string or array-of-{type text} content)."
  [messages]
  (let [a (last (filter #(= "assistant" (or (:role %) (get % "role"))) messages))
        c (when a (or (:content a) (get a "content")))]
    (cond
      (string? c) c
      (and (js/Array.isArray c) (pos? (count c)))
      (str/join "\n" (->> c
                          (filter #(= "text" (or (.-type %) (get % "type"))))
                          (map #(or (.-text %) (get % "text")))))
      (some? c) (str c)
      :else "")))

(defn- session-id [agent]
  (or (when-let [s @(:session agent)]
        (when-let [gfp (:get-file-path s)]
          (when-let [fp (gfp)]
            (-> (str fp) (.split "/") last (.replace ".jsonl" "")))))
      (when-let [s @(:session agent)]
        (when-let [gl (:get-leaf-id s)] (gl)))
      (nanoid)))

(defn result-object
  "Build the claude-style result object from the agent's final state.
   `error` is an error string if `run` threw (else nil). is_error is set
   when the run errored OR produced no assistant text — so orchestrators
   (e.g. cw) can trigger provider fallback on flaky/empty small-model
   replies, not just on hard errors. Exposed for tests."
  [agent duration-ms error]
  (let [st    @(:state agent)
        inp   (or (:total-input-tokens st) 0)
        out   (or (:total-output-tokens st) 0)
        text  (last-assistant-text (:messages st))
        empty (str/blank? (str text))
        err?  (boolean (or error empty))]
    #js {:type           "result"
         :is_error       err?
         :result         (cond
                           error (str "Error: " error)
                           empty "Error: empty response (no assistant output)"
                           :else text)
         :session_id     (str (session-id agent))
         :total_cost_usd (or (:total-cost st) 0)
         :usage          #js {:input_tokens            inp
                              :output_tokens           out
                              :cache_read_input_tokens 0
                              :total_tokens            (+ inp out)}
         :duration_ms    duration-ms}))

(defn ^:async start-result
  "Run one prompt and print a single claude-style result JSON object.
   Catches run errors so a structured {is_error:true} object is always
   emitted (exit 0, claude-style) rather than crashing the orchestrator."
  [agent prompt]
  (when prompt
    (let [t0  (js/Date.now)
          err (atom nil)]
      (try
        (js-await (run agent prompt))
        (catch :default e (reset! err (or (.-message e) (str e)))))
      (println (js/JSON.stringify (result-object agent (- (js/Date.now) t0) @err))))))
