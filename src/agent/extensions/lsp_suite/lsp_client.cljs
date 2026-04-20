(ns agent.extensions.lsp-suite.lsp-client
  "Per-server LSP client. Manages process lifecycle, initialize handshake,
   request/notify, retry on ContentModified (-32801), and file tracking.
   Uses node:child_process for Node.js-compatible streams + vscode-jsonrpc."
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            ["vscode-jsonrpc/node.js" :refer [createMessageConnection
                                              StreamMessageReader
                                              StreamMessageWriter]]
            [agent.extensions.lsp-suite.lsp-formatters :as fmt]))

;; ── State ─────────────────────────────────────────────────────────

(defn create
  "Create a new LSP client for the given server config.
   Returns a client map. Call start! to actually spawn the server."
  [cfg]
  {:config       cfg
   :state        (atom :stopped)  ; :stopped :starting :running :stopping :error
   :conn         (atom nil)       ; vscode-jsonrpc MessageConnection
   :proc         (atom nil)       ; ChildProcess
   :open-files   (atom {})        ; uri -> version counter
   :diag-handler (atom nil)       ; fn called with [uri diagnostics-array]
   :restarts     (atom 0)
   :cwd          (atom nil)})

;; ── Helpers ───────────────────────────────────────────────────────

(defn- build-env [extra-env-js]
  (if extra-env-js
    (let [merged (js/Object.assign #js {} js/process.env)]
      (.forEach (js/Object.keys extra-env-js)
                (fn [k] (aset merged k (aget extra-env-js k))))
      merged)
    js/process.env))

(defn- sleep-ms [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- command-on-path?
  "Returns true if cmd is findable via 'which' (macOS/Linux)."
  [cmd]
  (when (and (string? cmd) (pos? (.-length cmd)))
    (try
      (= 0 (.-status (.spawnSync cp "which" #js [cmd] #js {:encoding "utf8"})))
      (catch :default _ false))))

(defn- client-capabilities []
  #js {:textDocument
       #js {:hover            #js {:contentFormat #js ["markdown" "plaintext"]}
            :synchronization  #js {:didSave true}
            :definition       #js {:linkSupport true}
            :references       #js {}
            :documentSymbol   #js {:hierarchicalDocumentSymbolSupport true}
            :workspaceSymbol  #js {}}
       :workspace
       #js {:workspaceFolders false :configuration false}
       :general
       #js {:positionEncodings #js ["utf-16"]}})

;; ── Core request function ─────────────────────────────────────────

(defn ^:async send-request!
  "Send an LSP request on conn, retrying up to 3 times on ContentModified (-32801)."
  [conn method params]
  (loop [attempt 0]
    (let [result
          (try
            (let [r (js-await (.sendRequest conn method params))]
              [:ok r])
            (catch :default err
              (if (and (< attempt 3) (= (.-code err) -32801))
                [:retry err]
                [:err err])))]
      (case (first result)
        :ok    (second result)
        :err   (throw (second result))
        :retry (do
                 (js-await (sleep-ms (* 500 (js/Math.pow 2 attempt))))
                 (recur (inc attempt)))))))

;; ── Lifecycle ─────────────────────────────────────────────────────

(defn ^:async start!
  "Spawn the language server process, run the LSP initialize handshake,
   and transition to :running. Returns the client."
  [client cwd]
  (let [{:keys [config state conn proc open-files diag-handler]} client
        {:keys [command env initOptions startupTimeout]} config]
    (reset! (:cwd client) cwd)
    (reset! state :starting)
    ;; Fail fast if binary isn't on PATH — avoids unhandled error events
    (when-not (command-on-path? (first command))
      (reset! state :error)
      (throw (js/Error. (str "LSP server not found in PATH: " (first command)))))
    (try
      (let [proc-inst (.spawn cp
                              (first command)
                              (clj->js (rest command))
                              #js {:cwd   cwd
                                   :env   (build-env env)
                                   :stdio #js ["pipe" "pipe" "pipe"]})
            reader    (StreamMessageReader. (.-stdout proc-inst))
            writer    (StreamMessageWriter. (.-stdin proc-inst))
            conn-inst (createMessageConnection reader writer)]

        ;; Absorb process error events so they don't crash the host process
        (.on proc-inst "error"
             (fn [_err]
               (when (#{:starting :running} @state)
                 (reset! state :error))))

        ;; Absorb connection-level errors from vscode-jsonrpc
        (.onError conn-inst (fn [_] nil))
        (.onClose conn-inst (fn [] nil))

        ;; Register diagnostics notification handler
        (.onNotification conn-inst "textDocument/publishDiagnostics"
                         (fn [params]
                           (let [h @diag-handler]
                             (when h (h (.-uri params) (.-diagnostics params))))))

        (.listen conn-inst)
        (reset! proc proc-inst)
        (reset! conn conn-inst)

        ;; Handle unexpected exit — schedule restart with backoff
        (.on proc-inst "exit"
             (fn [_code _signal]
               (when (= @state :running)
                 (reset! state :error)
                 ;; maybe-restart! is defined below; JS closures capture by reference
                 (-> (maybe-restart! client)
                     (.catch (fn [_] nil))))))

        ;; Send initialize
        (let [init-params #js {:processId nil
                               :rootUri   (fmt/path->uri cwd)
                               :capabilities (client-capabilities)
                               :workspaceFolders nil}
              init-params (if initOptions
                            (doto init-params
                              (aset "initializationOptions" initOptions))
                            init-params)]
          (js-await (send-request! conn-inst "initialize" init-params)))

        ;; Send initialized notification
        (.sendNotification conn-inst "initialized" #js {})
        (reset! state :running)
        client)

      (catch :default err
        (reset! state :error)
        (throw err)))))

(defn ^:async maybe-restart!
  "Restart client with exponential backoff after unexpected exit.
   Respects :maxRestarts from config. No-ops if stop! was called."
  [client]
  (let [max-restarts (or (-> client :config :maxRestarts) 3)
        n            @(:restarts client)]
    (when (< n max-restarts)
      (swap! (:restarts client) inc)
      (js-await (sleep-ms (* 500 (js/Math.pow 2 n))))
      ;; Bail if stop! was called while sleeping
      (when (= @(:state client) :error)
        (reset! (:open-files client) {})
        (try
          (js-await (start! client @(:cwd client)))
          (catch :default _ nil))))))

(defn ^:async stop!
  "Gracefully shut down the language server (shutdown + exit).
   Also cancels any pending restart by transitioning out of :error."
  [client]
  (let [{:keys [state conn proc]} client]
    (when (#{:running :error :starting} @state)
      (reset! state :stopping)
      (try
        (when-let [c @conn]
          (js-await (.sendRequest c "shutdown" nil))
          (.sendNotification c "exit" nil))
        (catch :default _ nil))
      (when-let [p @proc]
        (try (.kill p) (catch :default _ nil)))
      (reset! state :stopped)
      (reset! conn nil)
      (reset! proc nil))))

;; ── File synchronization ──────────────────────────────────────────

(defn ^:async ensure-open!
  "Send textDocument/didOpen if the URI isn't already tracked.
   Reads the file from disk to get content."
  [client uri lang-id]
  (let [{:keys [conn open-files state]} client
        c @conn]
    (when (and c (= @state :running) (not (get @open-files uri)))
      (try
        (let [fpath (fmt/uri->path uri)
              content (try
                        (let [fs (js/require "node:fs")]
                          (.readFileSync fs fpath "utf8"))
                        (catch :default _ ""))
              version 1]
          (.sendNotification c "textDocument/didOpen"
                             #js {:textDocument
                                  #js {:uri        uri
                                       :languageId (or lang-id "plaintext")
                                       :version    version
                                       :text       content}})
          (swap! open-files assoc uri version))
        (catch :default _ nil)))))

(defn did-change!
  "Send textDocument/didChange + textDocument/didSave after a file write."
  [client uri new-content]
  (let [{:keys [conn open-files state]} client
        c @conn]
    (when (and c (= @state :running))
      (let [version (inc (get @open-files uri 0))]
        (swap! open-files assoc uri version)
        (.sendNotification c "textDocument/didChange"
                           #js {:textDocument   #js {:uri uri :version version}
                                :contentChanges #js [#js {:text new-content}]})
        (.sendNotification c "textDocument/didSave"
                           #js {:textDocument #js {:uri uri}})))))

(defn did-close!
  "Send textDocument/didClose and remove the file from tracking."
  [client uri]
  (let [{:keys [conn open-files state]} client
        c @conn]
    (when (and c (= @state :running) (get @open-files uri))
      (.sendNotification c "textDocument/didClose"
                         #js {:textDocument #js {:uri uri}})
      (swap! open-files dissoc uri))))

;; ── Request wrappers ──────────────────────────────────────────────

(defn ^:async request!
  "Send an LSP request. Throws if not running."
  [client method params]
  (let [{:keys [conn state]} client]
    (when-not (= @state :running)
      (throw (js/Error. (str "LSP server not running: " (get-in client [:config :id])))))
    (js-await (send-request! @conn method params))))

(defn notify!
  "Send an LSP notification (fire-and-forget)."
  [client method params]
  (let [{:keys [conn state]} client]
    (when (and @conn (= @state :running))
      (.sendNotification @conn method params))))

(defn set-diagnostic-handler!
  "Register a fn(uri, diagnostics-array) called when diagnostics arrive."
  [client handler]
  (reset! (:diag-handler client) handler))

(defn running? [client]
  (= @(:state client) :running))

(defn state [client]
  @(:state client))
