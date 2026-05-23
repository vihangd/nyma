(ns agent.extensions.agent-shell.acp.client
  "ACP JSON-RPC 2.0 client over stdio (NDJSON).
   Ported from orca2/src/engine/acp.cljs, adapted for Bun streams."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.debug :as dbg]))

;;; ─── Stderr logging ────────────────────────────────────────
;;
;; The Ink TUI renders to the same stdout the agent's stderr would
;; corrupt — historically we silently dropped every line. That made
;; backend crashes invisible. Tee stderr to ~/.nyma/logs/agent-shell-<agent>.log
;; instead: still off-screen, but recoverable. File creation is
;; lazy + best-effort; if the write itself fails we don't add another
;; failure mode by re-trying.

(defn- stderr-log-path [agent-key]
  (let [home (or (.. js/process -env -HOME) "/tmp")
        dir  (path/join home ".nyma" "logs")]
    (try (fs/mkdirSync dir #js {:recursive true}) (catch :default _ nil))
    (path/join dir (str "agent-shell-" (name agent-key) ".log"))))

(defn- append-stderr! [agent-key chunk]
  (try
    (let [ts   (.toISOString (js/Date.))
          line (str "[" ts "] " chunk
                    (when-not (str/ends-with? chunk "\n") "\n"))]
      (fs/appendFileSync (stderr-log-path agent-key) line "utf8"))
    (catch :default _ nil)))

;;; ─── NDJSON parser ─────────────────────────────────────────

(defn parse-ndjson-buffer
  "Parse an NDJSON buffer into complete lines and remainder.
   Returns {:complete [\"line1\" ...] :remainder \"partial\"}."
  [buf]
  (if (empty? buf)
    {:complete [] :remainder ""}
    (let [ends-with-newline (str/ends-with? buf "\n")
          lines             (str/split buf #"\n")
          complete          (if ends-with-newline lines (butlast lines))
          remainder         (if ends-with-newline "" (or (last lines) ""))]
      {:complete  (vec (or complete []))
       :remainder remainder})))

;;; ─── Stdin writer (Bun FileSink) ───────────────────────────

(defn safe-write
  "Write string data to a Bun stdin FileSink with error protection.
   Logs the failure via dbg/warn instead of swallowing — without this,
   a closed stdin (agent crashed) produces a silent desync."
  [stdin data]
  (try
    (.write stdin data)
    (.flush stdin)
    (catch :default e
      (dbg/warn "agent-shell/acp"
                (str "stdin write failed: " (or (.-message e) (str e))))
      nil)))

;;; ─── JSON-RPC transport ────────────────────────────────────

(defn next-id
  "Get next request ID for a connection."
  [conn]
  (swap! (:id-counter conn) inc))

(defn send-request
  "Send a JSON-RPC request and return a promise of the result.
   Registers a pending handler before writing to stdin."
  [conn request-id method params]
  (let [msg (str (js/JSON.stringify
                  (clj->js {:jsonrpc "2.0"
                            :id      request-id
                            :method  method
                            :params  (or params {})}))
                 "\n")]
    (js/Promise.
     (fn [resolve reject]
       (swap! (:state conn) assoc-in [:pending request-id]
              {:resolve resolve :reject reject})
       (try
         (safe-write (:stdin conn) msg)
         (catch :default e
           (swap! (:state conn) update :pending dissoc request-id)
           (reject (js/Error. (str "ACP write failed: " (.-message e))))))))))

(defn send-response
  "Send a JSON-RPC response back to the agent (for reverse requests)."
  [conn request-id result]
  (let [msg (str (js/JSON.stringify
                  (clj->js {:jsonrpc "2.0"
                            :id      request-id
                            :result  (or result {})}))
                 "\n")]
    (safe-write (:stdin conn) msg)))

(defn send-error-response
  "Send a JSON-RPC error response back to the agent."
  [conn request-id code message]
  (let [msg (str (js/JSON.stringify
                  (clj->js {:jsonrpc "2.0"
                            :id      request-id
                            :error   {:code code :message message}}))
                 "\n")]
    (safe-write (:stdin conn) msg)))

(defn send-notification
  "Send a JSON-RPC notification (no id, no response expected)."
  [conn method params]
  (let [msg (str (js/JSON.stringify
                  (clj->js {:jsonrpc "2.0"
                            :method  method
                            :params  (or params {})}))
                 "\n")]
    (safe-write (:stdin conn) msg)))

;;; ─── Message router ────────────────────────────────────────

(defn- handle-response
  "Handle a response to one of our outbound requests."
  [conn parsed]
  (let [rid     (.-id parsed)
        pending (get-in @(:state conn) [:pending rid])]
    (when pending
      (swap! (:state conn) update :pending dissoc rid)
      (if (.-error parsed)
        (let [err (.-error parsed)
              detail (when (.-data err)
                       (try (str " — " (js/JSON.stringify (.-data err)))
                            (catch :default _ nil)))]
          ((:reject pending) (js/Error. (str "ACP error: " (.-message err) (or detail "")))))
        ((:resolve pending) (.-result parsed))))))

(defn route-message
  "Route an incoming NDJSON message. Dispatches to:
   - handle-response for responses to our requests
   - reverse-request-handler for agent→client requests
   - notification-handler for agent→client notifications

   Handlers are passed via conn map:
   :on-reverse-request (fn [conn parsed])
   :on-notification    (fn [conn parsed])"
  [conn parsed]
  (let [has-id     (some? (.-id parsed))
        has-method (some? (.-method parsed))
        has-result (or (some? (.-result parsed)) (some? (.-error parsed)))]
    (cond
      ;; Response to one of our requests
      (and has-id has-result)
      (handle-response conn parsed)

      ;; Reverse request from agent (has both id and method)
      (and has-id has-method)
      (when-let [handler (:on-reverse-request conn)]
        (handler conn parsed))

      ;; Notification (method only, no id)
      has-method
      (when-let [handler (:on-notification conn)]
        (handler conn parsed)))))

;;; ─── Bun stdout stream reader ──────────────────────────────

(defn setup-stdout-handler
  "Attach NDJSON parser to a Bun ReadableStream stdout.
   Routes each complete JSON line through route-message."
  [conn]
  (let [buffer  (atom "")
        reader  (.getReader (:stdout conn))
        decoder (js/TextDecoder.)]
    (letfn [(read-loop []
              (-> (.read reader)
                  (.then
                   (fn [result]
                     (when-not (.-done result)
                       (let [chunk (let [v (.-value result)]
                                     (if (string? v) v (.decode decoder v)))]
                         (swap! buffer str chunk)
                         (let [{:keys [complete remainder]} (parse-ndjson-buffer @buffer)]
                           (reset! buffer remainder)
                           (doseq [line complete]
                             (when (seq (str/trim line))
                               (try
                                 (route-message conn (js/JSON.parse line))
                                 (catch :default e
                                   ;; Log truncated payload — a malformed
                                   ;; frame silently desyncs the JSON-RPC
                                   ;; stream, so we want a breadcrumb.
                                   (dbg/warn "agent-shell/acp"
                                             (str "NDJSON parse failed for "
                                                  (:agent-key conn)
                                                  ": " (or (.-message e) (str e))
                                                  " | bytes: "
                                                  (.slice (str line) 0 256)))))))))
                       (read-loop))))
                  (.catch (fn [_] nil))))]  ;; stream closed — expected on exit
      (read-loop))))

(defn setup-stderr-handler
  "Tee the ACP agent's stderr to ~/.nyma/logs/agent-shell-<agent>.log.
   We can't print to our own stderr/stdout because the Ink TUI owns
   them; dropping silently (the previous behaviour) made backend
   crashes invisible. File logging keeps the screen clean while
   leaving a trail for `tail -f` debugging."
  [conn]
  (let [reader    (.getReader (:stderr conn))
        decoder   (js/TextDecoder.)
        agent-key (or (:agent-key conn) "unknown")]
    (letfn [(read-loop []
              (-> (.read reader)
                  (.then
                   (fn [result]
                     (when-not (.-done result)
                       (let [chunk (let [v (.-value result)]
                                     (if (string? v) v (.decode decoder v)))]
                         (when (seq chunk)
                           (append-stderr! agent-key chunk)))
                       (read-loop))))
                  (.catch (fn [e]
                            (let [msg (or (.-message e) "")]
                              (when-not (or (= msg "")
                                            (.includes msg "cancel")
                                            (.includes msg "closed"))
                                (dbg/warn "agent-shell/acp"
                                          (str "stderr reader error for "
                                               agent-key ": " msg))))
                            nil))))]
      (read-loop))))

;;; ─── Prompt sending ────────────────────────────────────────

(defn send-prompt
  "Send a prompt to an established ACP connection.
   Returns a promise of {:text, :stop-reason, :usage, :tool-calls}.
   In-process agents (Agent SDK runner) delegate to :sdk-query on the conn."
  [conn prompt-text & [timeout-ms]]
  ;; Reset per-prompt accumulators
  (reset! (:prompt-state conn) {:text "" :tool-calls []})
  (if (:in-process? conn)
    ((:sdk-query conn) conn prompt-text)
    (let [sid     @(:session-id conn)
          timeout (or timeout-ms 600000)
          req-id  (next-id conn)
          prompt-promise
          (-> (send-request conn req-id "session/prompt"
                            {:sessionId sid
                             :prompt    [{:type "text" :text prompt-text}]})
              (.then
               (fn [result]
                 (let [{:keys [text tool-calls]} @(:prompt-state conn)
                       usage (.-usage result)]
                   {:text        text
                    :stop-reason (or (.-stopReason result) "end_turn")
                    :usage       {:input-tokens  (if usage (or (.-inputTokens usage) (or (.-input_tokens usage) 0)) 0)
                                  :output-tokens (if usage (or (.-outputTokens usage) (or (.-output_tokens usage) 0)) 0)
                                  :total-tokens  (if usage (or (.-totalTokens usage) (or (.-total_tokens usage) 0)) 0)
                                  :cached-read   (if usage (or (.-cachedReadTokens usage) (or (.-cached_read_tokens usage) 0)) 0)
                                  :thought       (if usage (or (.-thoughtTokens usage) (or (.-thought_tokens usage) 0)) 0)}
                    :tool-calls  tool-calls}))))
          timeout-promise
          (js/Promise.
           (fn [_ reject]
             (js/setTimeout
              (fn []
                (swap! (:state conn) update :pending dissoc req-id)
                (reject (js/Error. (str "ACP prompt timed out after " timeout "ms"))))
              timeout)))]
      (js/Promise.race #js [prompt-promise timeout-promise]))))

;;; ─── Connection cancel ─────────────────────────────────────

(defn cancel-prompt
  "Send session/cancel notification to abort in-flight generation."
  [conn]
  (when-let [sid @(:session-id conn)]
    (send-notification conn "session/cancel" {:sessionId sid})))
