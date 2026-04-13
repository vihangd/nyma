(ns agent.extensions.agent-shell.acp.handlers
  "Reverse request handlers for ACP agent→client requests.
   Handles: permission, fs, terminal operations."
  (:require [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.shared :as shared]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

;;; ─── Path safety ───────────────────────────────────────────

(defn- safe-path?
  "Check that resolved path is within the project root (prevent path traversal)."
  [project-root target-path]
  (let [resolved (.resolve path target-path)
        root     (.resolve path project-root)]
    (str/starts-with? resolved (str root "/"))))

;;; ─── Permission handling ───────────────────────────────────
;; TODO: Integrate with shared permission_request event (emit-collect) when
;; ACP handlers support async. Currently synchronous for JSON-RPC response timing.
;; Future: (let [result (js-await ((:emit-collect events) "permission_request" ...))]
;;           (case (get result "decision") "allow" ... "deny" ...))

(defn handle-permission-request
  "Handle permission request from agent. Shows UI dialog or auto-approves."
  [conn parsed api]
  (let [options    (when-let [opts (.. parsed -params -options)]
                     (seq opts))
        auto?      (:auto-approve (shared/load-config))
        ;; Find preferred auto-approve option
        allow-always (some #(when (= (.-kind %) "allow_always") (.-optionId %)) options)
        allow-once   (some #(when (= (.-kind %) "allow_once") (.-optionId %)) options)
        first-id     (when (seq options) (.-optionId (first options)))]
    (if (or auto? (not (and api (.-ui api) (.-available (.-ui api)))))
      ;; Auto-approve: pick allow_always > allow_once > first option
      (client/send-response conn (.-id parsed)
        {:outcome "selected" :optionId (or allow-always allow-once first-id)})
      ;; Show UI dialog
      (let [labels (mapv (fn [opt]
                           #js {:label (str (.-name opt) " (" (.-kind opt) ")")
                                :value (.-optionId opt)})
                         options)
            tool-call (.. parsed -params -toolCall)
            title     (if tool-call
                        (str "Permission: " (or (.-title tool-call) (.-name tool-call) "tool call"))
                        "Permission Request")]
        (-> (.select (.-ui api) title (clj->js labels))
            (.then (fn [selected]
                     (if (some? selected)
                       (client/send-response conn (.-id parsed)
                         {:outcome "selected" :optionId (.-value selected)})
                       (client/send-response conn (.-id parsed)
                         {:outcome "cancelled"}))))
            (.catch (fn [_]
                      (client/send-response conn (.-id parsed)
                        {:outcome "cancelled"}))))))))

;;; ─── Filesystem handlers ───────────────────────────────────

(defn handle-fs-read
  "Read a text file for the agent. Reads allowed from anywhere."
  [conn parsed]
  (let [target-path (.. parsed -params -path)]
    (try
      (let [content (fs/readFileSync target-path "utf-8")]
        (client/send-response conn (.-id parsed) {:content content}))
      (catch :default e
        (client/send-error-response conn (.-id parsed) -32000
          (str "Read failed: " (.-message e)))))))

(defn handle-fs-write
  "Write a text file for the agent. Restricted to project root."
  [conn parsed]
  (let [target-path  (.. parsed -params -path)
        content      (.. parsed -params -content)
        project-root (:project-root conn)]
    (if (and project-root (not (safe-path? project-root target-path)))
      (client/send-error-response conn (.-id parsed) -32000
        (str "Write denied: path outside project root: " target-path))
      (try
        ;; Ensure parent directory exists
        (let [dir (path/dirname target-path)]
          (when-not (fs/existsSync dir)
            (fs/mkdirSync dir #js {:recursive true})))
        (fs/writeFileSync target-path content "utf-8")
        (client/send-response conn (.-id parsed) {})
        (catch :default e
          (client/send-error-response conn (.-id parsed) -32000
            (str "Write failed: " (.-message e))))))))

;;; ─── Terminal handlers ─────────────────────────────────────

(defn handle-terminal-create
  "Spawn a subprocess for the agent's terminal/create request."
  [conn parsed]
  (let [params       (.-params parsed)
        command      (.-command params)
        args         (or (shared/js->clj* (.-args params)) [])
        cwd          (.-cwd params)
        project-root (:project-root conn)
        tid          (str "t-" (js/Date.now) "-" (js/Math.round (* (js/Math.random) 10000)))
        cmd-args     (into [command] args)
        proc         (js/Bun.spawn (clj->js cmd-args)
                       #js {:cwd   (or cwd project-root (js/process.cwd))
                            :stdout "pipe"
                            :stderr "pipe"
                            :stdin  "pipe"})
        output       (atom "")
        decoder      (js/TextDecoder.)]
    ;; Collect stdout + stderr
    (let [read-stream (fn [stream]
                        (let [reader (.getReader stream)]
                          (letfn [(loop' []
                                    (-> (.read reader)
                                        (.then (fn [result]
                                                 (when-not (.-done result)
                                                   (let [chunk (let [v (.-value result)]
                                                                 (if (string? v) v (.decode decoder v)))]
                                                     (swap! output str chunk))
                                                   (loop'))))))]
                            (loop'))))]
      (read-stream (.-stdout proc))
      (read-stream (.-stderr proc)))
    ;; Store terminal in connection state
    (swap! (:state conn) assoc-in [:terminals tid] {:proc proc :output output})
    (client/send-response conn (.-id parsed) {:terminalId tid})))

(defn handle-terminal-output
  "Return accumulated output from a terminal."
  [conn parsed]
  (let [tid      (.. parsed -params -terminalId)
        terminal (get-in @(:state conn) [:terminals tid])]
    (if terminal
      (client/send-response conn (.-id parsed)
        {:output @(:output terminal)})
      (client/send-error-response conn (.-id parsed) -32000 "Terminal not found"))))

(defn handle-terminal-wait
  "Wait for a terminal process to exit."
  [conn parsed]
  (let [tid      (.. parsed -params -terminalId)
        terminal (get-in @(:state conn) [:terminals tid])]
    (if terminal
      (let [proc (:proc terminal)]
        (-> (.-exited proc)
            (.then (fn [exit-code]
                     (client/send-response conn (.-id parsed)
                       {:exitCode (or exit-code 0) :output @(:output terminal)})))))
      (client/send-error-response conn (.-id parsed) -32000 "Terminal not found"))))

(defn handle-terminal-kill
  "Kill a terminal process."
  [conn parsed]
  (let [tid      (.. parsed -params -terminalId)
        terminal (get-in @(:state conn) [:terminals tid])]
    (if terminal
      (do (.kill (:proc terminal))
          (client/send-response conn (.-id parsed) {}))
      (client/send-error-response conn (.-id parsed) -32000 "Terminal not found"))))

(defn handle-terminal-release
  "Release a terminal from tracking."
  [conn parsed]
  (let [tid (.. parsed -params -terminalId)]
    (swap! (:state conn) update :terminals dissoc tid)
    (client/send-response conn (.-id parsed) {})))

;;; ─── Elicitation handler (ACP draft RFD) ───────────────────

(defn handle-elicitation
  "Handle session/elicitation — agent asks the user a question.
   Supports form mode (JSON Schema fields) and url mode (OAuth/external).
   Draft spec: agentclientprotocol.com/rfds/elicitation"
  [conn parsed api]
  (let [params (.-params parsed)
        mode   (.-mode params)]
    (case mode
      ;; Form mode: render each field as a UI input
      "form"
      (let [schema      (.-jsonSchema params)
            title       (or (.-title params) "Agent Question")
            description (.-description params)
            properties  (when schema (.-properties schema))
            required-ks (when schema (or (seq (.-required schema)) []))]
        (if (and (.-ui api) (.-available (.-ui api)) (.-input (.-ui api)) properties)
          ;; Collect answers for each property
          (let [field-keys (js/Object.keys properties)
                answers    (atom {})]
            (-> (reduce
                  (fn [chain field-key]
                    (.then chain
                      (fn [_]
                        (let [field (aget properties field-key)
                              label (or (.-title field) (.-description field) field-key)
                              hint  (or (.-default field) "")]
                          (-> (.input (.-ui api) (str title ": " label) (str hint))
                              (.then (fn [value]
                                       (when (some? value)
                                         (swap! answers assoc field-key value)))))))))
                  (js/Promise.resolve nil)
                  field-keys)
                (.then (fn [_]
                         (if (empty? @answers)
                           (client/send-response conn (.-id parsed)
                             {:action "cancel"})
                           (client/send-response conn (.-id parsed)
                             {:action "accept" :content (clj->js @answers)}))))
                (.catch (fn [_]
                          (client/send-response conn (.-id parsed)
                            {:action "cancel"})))))
          ;; No UI — decline
          (client/send-response conn (.-id parsed)
            {:action "decline"})))

      ;; URL mode: show URL to user
      "url"
      (let [url (.-url params)]
        (when (and (.-ui api) (.-available (.-ui api)))
          (.notify (.-ui api) (str "Open this URL: " url) "info"))
        ;; Accept after showing URL
        (client/send-response conn (.-id parsed)
          {:action "accept"}))

      ;; Unknown mode — cancel
      (client/send-response conn (.-id parsed)
        {:action "cancel"}))))

;;; ─── Reverse request dispatcher ────────────────────────────

(defn dispatch-reverse-request
  "Route a reverse request to the appropriate handler."
  [conn parsed api]
  (case (.-method parsed)
    "session/request_permission" (handle-permission-request conn parsed api)
    "fs/read_text_file"          (handle-fs-read conn parsed)
    "fs/write_text_file"         (handle-fs-write conn parsed)
    "terminal/create"            (handle-terminal-create conn parsed)
    "terminal/output"            (handle-terminal-output conn parsed)
    "terminal/wait_for_exit"     (handle-terminal-wait conn parsed)
    "terminal/kill"              (handle-terminal-kill conn parsed)
    "terminal/release"           (handle-terminal-release conn parsed)
    "session/elicitation"        (handle-elicitation conn parsed api)
    ;; Unknown method
    (client/send-error-response conn (.-id parsed) -32601
      (str "Method not found: " (.-method parsed)))))
