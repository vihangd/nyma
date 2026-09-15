(ns agent.modes.print
  (:require [agent.loop :refer [run]]
            [agent.ui.think-tag-parser :refer [strip-think-tags]]
            [agent.utils.event-json :refer [step-usage]]
            [clojure.string :as str]))

(defn ^:async start-json [agent prompt]
  (when prompt
    (js-await (run agent prompt))
    (let [messages (:messages @(:state agent))]
      (println (js/JSON.stringify (clj->js messages) nil 2)))))

;; ── claude-style result object (`-p --output-format json`) ──
;; A single JSON object matching the de-facto `claude -p --output-format json`
;; contract, so headless orchestrators (e.g. cw) can consume nyma like claude:
;;   { type, is_error, result, session_id, total_cost_usd, usage{…}, duration_ms }

(defn- strip-reasoning
  "Drop <think> blocks from one-shot output — unless that leaves nothing.

   A reasoning model answering \"say ok\" returns the whole reply inside
   <think>…</think>; printing that as the result hands an orchestrator the
   model's deliberation instead of its answer. Blanking it would be worse, so a
   reply that is ONLY reasoning is passed through intact."
  [text]
  (let [stripped (str/trim (str (strip-think-tags (str text))))]
    (if (seq stripped) stripped (str text))))

(defn last-assistant-text
  "The final assistant message's text (string or array-of-{type text} content),
   with reasoning stripped.

   Both one-shot paths go through this. Text mode used to print
   `(:content (last messages))` instead: whatever message happened to be last —
   a tool_result, a user echo — with `<think>` blocks intact. Two printers for
   one contract is how `-p` and `-p --output-format json` came to disagree about
   what the answer even was."
  [messages]
  (let [a (last (filter #(= "assistant" (or (:role %) (get % "role"))) messages))
        c (when a (:content a))]
    (strip-reasoning
     (cond
       (string? c) c
       (and (js/Array.isArray c) (pos? (count c)))
       (str/join "\n" (->> c
                           (filter #(= "text" (or (.-type %) (get % "type"))))
                           (map #(or (.-text %) (get % "text")))))
       (some? c) (str c)
       :else ""))))

(defn ^:async start
  "`-p` / `--mode print`: run once and print the assistant's answer as plain
   text."
  [agent prompt]
  (when prompt
    (js-await (run agent prompt))
    (println (last-assistant-text (:messages @(:state agent))))))

(defn- session-id [agent]
  (or (when-let [s @(:session agent)]
        (when-let [gfp (:get-file-path s)]
          (when-let [fp (gfp)]
            (-> (str fp) (.split "/") last (.replace ".jsonl" "")))))
      (when-let [s @(:session agent)]
        (when-let [gl (:get-leaf-id s)] (gl)))
      ;; Fallback id when there is no session file and no leaf id. Was nanoid;
      ;; the platform has this built in, so the dependency went.
      (.randomUUID js/crypto)))

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
         ;; num_turns and the cache figures were absent or hardcoded to zero.
         ;; Both are now read from the store: the cache split is the biggest
         ;; cost lever there is, and a turn count is what a turn budget has to
         ;; be calibrated against.
         :num_turns      (or (:total-steps st) 0)
         :num_runs       (or (:turn-count st) 0)
         :usage          #js {:input_tokens                inp
                              :output_tokens               out
                              :cache_read_input_tokens     (or (:total-cache-read-tokens st) 0)
                              :cache_creation_input_tokens (or (:total-cache-write-tokens st) 0)
                              :total_tokens                (+ inp out)}
         :duration_ms    duration-ms}))

(defn- ^:async run-then-result!
  "Run one prompt, then print the claude-style result object as the last line
   of stdout. `off` is called once `run` settles, success or not, so a stream
   subscriber never outlives the run it reports on.

   Catches run errors so a structured {is_error:true} object is always
   emitted — the JSON still reaches stdout intact — but the process exits 1
   when `is_error` is set.

   Text mode has always exited 1 on failure. JSON mode exited 0 on every
   outcome, so `nyma -p --output-format json && deploy` deployed on a failed
   run: a shell's only error channel is the exit code, and a structured object
   nobody parses is not one. `finish-one-shot!` exits with `process.exitCode`,
   so setting it here is the whole wiring."
  [agent prompt off]
  (let [t0  (js/Date.now)
        err (atom nil)]
    (try
      (js-await (run agent prompt))
      (catch :default e (reset! err (or (.-message e) (str e))))
      (finally (off)))
    (let [result (result-object agent (- (js/Date.now) t0) @err)]
      (when (.-is_error result)
        (set! (.-exitCode js/process) 1))
      (println (js/JSON.stringify result)))))

(defn ^:async start-result
  "`-p --output-format json`: a single claude-style result JSON object."
  [agent prompt]
  (when prompt
    (js-await (run-then-result! agent prompt (fn [])))))

;; ── `-p --output-format stream-json` ──
;; One JSON object per line while the run is in flight, then the same result
;; object json mode prints. An orchestrator that wants progress (tokens as they
;; arrive, which tool is running) had only the TUI or the raw rpc firehose;
;; this is the curated subset, keyed by nyma's own event names.

(defn- write-line! [m]
  (println (js/JSON.stringify (clj->js m))))

(defn subscribe-stream-json!
  "Wire the events one headless consumer needs → JSONL on stdout. Field names
   are the event payloads' own (toolName/execId from the middleware, text from
   the AI SDK delta), so a reader of the event docs reads this too. Returns an
   unsubscribe thunk."
  [agent]
  (let [{:keys [on off]} (:events agent)
        handlers
        [["message_start"
          (fn [_] (write-line! {:type "message_start"}))]
         ["message_update"
          ;; `.text` first: the AI SDK v7 fullStream part; `.textDelta` is the
          ;; object stream's field. Same order pi-rpc reads them in.
          (fn [chunk]
            (write-line! {:type "message_update"
                          :text (or (and chunk (.-text chunk))
                                    (and chunk (.-textDelta chunk))
                                    "")}))]
         ["message_end"
          (fn [_] (write-line! {:type "message_end"}))]
         ["tool_execution_start"
          (fn [d]
            (write-line! {:type "tool_execution_start"
                          :toolName (:toolName d)
                          :execId (:execId d)
                          :args (or (:args d) {})}))]
         ["tool_execution_end"
          (fn [d]
            (write-line! {:type "tool_execution_end"
                          :toolName (:toolName d)
                          :execId (:execId d)
                          :result (str (or (:result d) ""))
                          :isError (boolean (:isError d))}))]
         ;; No agent_end line: the bus carries it twice per run (the AI SDK
         ;; finish chunk and the loop's own emit) and `result` is the terminal
         ;; line a reader should wait for.
         ["turn_end"
          ;; Usage lives on the StepResult; absent stays absent so "no usage
          ;; reported" is not mistaken for a free turn.
          (fn [step]
            (when-let [u (step-usage step)]
              (write-line! (assoc u :type "usage"))))]]]
    (doseq [[ev h] handlers] (on ev h))
    (fn [] (doseq [[ev h] handlers] (off ev h)))))

(defn ^:async start-stream-json
  "`-p --output-format stream-json`: JSONL progress events, then the result
   object as the final line. Same exit-code contract as `start-result`."
  [agent prompt]
  (when prompt
    (js-await (run-then-result! agent prompt (subscribe-stream-json! agent)))))
