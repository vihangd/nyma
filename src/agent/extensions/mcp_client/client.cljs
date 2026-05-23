(ns agent.extensions.mcp-client.client
  "Per-server MCP client. Mirrors the proven LSP-suite lifecycle
   pattern (lsp_client.cljs) — state machine, restart-with-backoff,
   capped retries — adapted for MCP's @modelcontextprotocol/sdk
   surface.

   The SDK handles the transport spawning, JSON-RPC framing, and
   protocol-level handshake (initialize / initialized). We own:
     - lifecycle state transitions (:stopped → :starting → :running
       → :restarting → :stopped-error)
     - tools/list cache (one fetch at handshake; refresh on restart)
     - reconnect on unexpected close, capped at config max-restarts
     - graceful shutdown that survives a hung subprocess

   Public API:
     (create cfg)              — pure; build a client map
     (start! client)           — spawn + connect + tools/list
     (stop! client)            — close + kill, idempotent
     (call-tool! client name args opts)
     (list-tools client)       — cached, pure read
     (state client)            — :stopped|:starting|:running|...
     (last-error client)       — string or nil
     (on-state-change! client f) — register a one-arg listener; useful
                                   for the status line atom"
  (:require ["@modelcontextprotocol/sdk/client/index.js"          :as sdk-client]
            ["@modelcontextprotocol/sdk/client/stdio.js"          :as sdk-stdio]
            ["@modelcontextprotocol/sdk/client/streamableHttp.js" :as sdk-http]
            ["@modelcontextprotocol/sdk/client/sse.js"            :as sdk-sse]))

(def ^:private default-max-restarts 3)
(def ^:private default-call-timeout-ms 30000)
(def ^:private default-startup-timeout-ms 30000)

(defn- now [] (.now js/Date))
(defn- sleep-ms [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- env-merge
  "Shallow-merge user env over process.env at the JS layer (squint
   has no js->clj). Mirrors the same pattern in
   claude_hook_bridge/handlers/command.cljs."
  [extra-env]
  (let [base (js/Object.assign #js {} js/process.env)]
    (when extra-env
      (doseq [k (js-keys extra-env)]
        (aset base k (str (aget extra-env k)))))
    base))

(defn- normalize-tools
  "MCP `listTools` returns `{tools: [{name, description, inputSchema}]}`.
   Normalize to a CLJS-shaped vector so callers don't deal with
   nested JS access."
  [list-result]
  (let [tools (.-tools list-result)
        n     (or (and tools (.-length tools)) 0)]
    (vec
     (for [i (range n)]
       (let [t (aget tools i)]
         {:name         (.-name t)
          :description  (.-description t)
          :input-schema (.-inputSchema t)})))))

;; ── State ────────────────────────────────────────────────────────

(defn create
  "Pure constructor. cfg shape:
     {:name string :command string :args [...] :env {...}? :cwd string?
      :max-restarts int? :call-timeout-ms int? :startup-timeout-ms int?}"
  [cfg]
  {:config         cfg
   :state          (atom :stopped)
   :client         (atom nil)
   :transport      (atom nil)
   :tools          (atom [])
   :restarts       (atom 0)
   :last-error     (atom nil)
   :state-listeners (atom [])
   :stopping?      (atom false)})

(defn state [client] @(:state client))

(defn list-tools [client] @(:tools client))

(defn last-error [client] @(:last-error client))

(defn on-state-change!
  "Register a 0-arg listener invoked after each :state transition.
   Returns an unregister thunk."
  [client f]
  (swap! (:state-listeners client) conj f)
  (fn [] (swap! (:state-listeners client)
                (fn [ls] (vec (filter #(not= % f) ls))))))

(defn- transition!
  "Atomically transition state and notify listeners."
  [client new-state]
  (reset! (:state client) new-state)
  (doseq [f @(:state-listeners client)]
    (try (f) (catch :default _e nil))))

(defn- record-error! [client e]
  (reset! (:last-error client)
          (str (or (.-message e) e))))

;; ── Transport selection ─────────────────────────────────────────

(defn- normalize-headers
  "Convert a CLJS map or JS object of headers to a plain JS object,
   stripping nil/empty values. Returns nil if the input has no usable
   entries (so we can omit `requestInit` entirely when not needed)."
  [headers]
  (cond
    (nil? headers) nil
    (map? headers)
    (let [out  #js {}
          seen (atom false)]
      (doseq [[k v] headers]
        (when (and v (not= v ""))
          (let [s     (str k)
                k-str (if (and (pos? (count s)) (= (.charAt s 0) ":"))
                        (subs s 1) s)]
            (reset! seen true)
            (aset out k-str (str v)))))
      (when @seen out))
    (object? headers)
    (let [out  #js {}
          seen (atom false)]
      (doseq [k (js-keys headers)]
        (let [v (aget headers k)]
          (when (and v (not= v ""))
            (reset! seen true)
            (aset out k (str v)))))
      (when @seen out))
    :else nil))

(defn transport-kind
  "Decide which transport to construct for cfg. Mirrors Claude Code's
   precedence: explicit `:type` wins; else `:command` → stdio; else
   `:url` → streamable-http (current standard). `http` and
   `streamableHttp` are synonyms."
  [cfg]
  (let [t (some-> (:type cfg) (.toLowerCase))]
    (cond
      (= t "stdio")                            :stdio
      (or (= t "sse"))                         :sse
      (or (= t "http") (= t "streamablehttp")) :http
      (:command cfg)                           :stdio
      (:url cfg)                               :http
      :else                                    :stdio)))

(defn build-transport
  "Construct the SDK transport object from a client config.
   Throws with a clear message if required fields are missing."
  [cfg]
  (case (transport-kind cfg)
    :stdio
    (do
      (when-not (:command cfg)
        (throw (js/Error. (str "MCP server '" (:name cfg)
                               "' has no :command (stdio transport requires command/args)."))))
      (sdk-stdio/StdioClientTransport.
       #js {:command (:command cfg)
            :args    (clj->js (or (:args cfg) []))
            :env     (env-merge (:env cfg))
            :cwd     (or (:cwd cfg) (js/process.cwd))
            :stderr  "pipe"}))

    :http
    (do
      (when-not (:url cfg)
        (throw (js/Error. (str "MCP server '" (:name cfg)
                               "' has no :url (http transport requires url)."))))
      (let [hdrs (normalize-headers (:headers cfg))
            opts (cond-> #js {}
                   hdrs (doto (aset "requestInit" #js {:headers hdrs})))]
        (sdk-http/StreamableHTTPClientTransport. (js/URL. (:url cfg)) opts)))

    :sse
    (do
      (when-not (:url cfg)
        (throw (js/Error. (str "MCP server '" (:name cfg)
                               "' has no :url (sse transport requires url)."))))
      (let [hdrs (normalize-headers (:headers cfg))
            opts (cond-> #js {}
                   hdrs (doto (aset "requestInit" #js {:headers hdrs})))]
        (sdk-sse/SSEClientTransport. (js/URL. (:url cfg)) opts)))))

;; ── Lifecycle ────────────────────────────────────────────────────

(declare maybe-restart!)

(defn ^:async start!
  "Spawn or connect to the configured server (stdio / http / sse) and
   fetch the tools list. Returns the client (with state == :running) on
   success. Throws and leaves state :stopped-error on failure."
  [client]
  (let [cfg (:config client)]
    (reset! (:stopping? client) false)
    (transition! client :starting)
    (try
      (let [transport (build-transport cfg)
            sdk-c     (sdk-client/Client.
                       #js {:name    (str "nyma-mcp-client/" (:name cfg))
                            :version "0.1.0"})]
        (reset! (:transport client) transport)
        (reset! (:client client) sdk-c)

        ;; Wire onclose BEFORE connect so a quick crash doesn't slip
        ;; past us. onerror is reactive only.
        (set! (.-onclose sdk-c)
              (fn []
                (when (and (not @(:stopping? client))
                           (= :running @(:state client)))
                  (record-error! client (js/Error. "MCP server connection closed"))
                  (transition! client :restarting)
                  (-> (maybe-restart! client)
                      (.catch (fn [_] nil))))))
        (set! (.-onerror sdk-c)
              (fn [err]
                (record-error! client err)))

        ;; Connect with a startup timeout so a misbehaving server
        ;; can't block the activation flow forever.
        (let [connect-promise (.connect sdk-c transport)
              timeout-ms (or (:startup-timeout-ms cfg) default-startup-timeout-ms)
              timeout-promise
              (js/Promise. (fn [_resolve reject]
                             (js/setTimeout
                              (fn [] (reject (js/Error.
                                              (str "MCP startup timeout after "
                                                   timeout-ms "ms"))))
                              timeout-ms)))]
          (js-await (js/Promise.race #js [connect-promise timeout-promise])))

        ;; Pull tools list once, cache.
        (let [list-result (js-await (.listTools sdk-c))]
          (reset! (:tools client) (normalize-tools list-result)))

        (transition! client :running)
        ;; Reset restart counter only after a fully successful start.
        (reset! (:restarts client) 0)
        (reset! (:last-error client) nil)
        client)
      (catch :default e
        (record-error! client e)
        (transition! client :stopped-error)
        ;; Tear down the half-built transport so we don't leak a
        ;; subprocess after a partial start.
        (try
          (when-let [c @(:client client)] (.close c))
          (catch :default _ nil))
        (reset! (:client client) nil)
        (reset! (:transport client) nil)
        (throw e)))))

(defn ^:async stop!
  "Idempotent shutdown. Cancels any pending restart, closes the
   SDK client (which kills the subprocess), and parks state at
   :stopped. Safe to call multiple times."
  [client]
  (reset! (:stopping? client) true)
  (when (#{:starting :running :restarting} @(:state client))
    (try
      (when-let [c @(:client client)]
        (js-await (.close c)))
      (catch :default _ nil))
    (reset! (:client client) nil)
    (reset! (:transport client) nil)
    (transition! client :stopped)))

(defn ^:async maybe-restart!
  "Restart with exponential backoff. Caps at config max-restarts.
   No-ops if stop! has been called in the meantime."
  [client]
  (let [cfg          (:config client)
        max-restarts (or (:max-restarts cfg) default-max-restarts)
        n            @(:restarts client)]
    (cond
      @(:stopping? client)
      (transition! client :stopped)

      (>= n max-restarts)
      (do
        (record-error! client (js/Error. (str "exceeded max restarts (" max-restarts ")")))
        (transition! client :stopped-error))

      :else
      (do
        (swap! (:restarts client) inc)
        (js-await (sleep-ms (* 500 (js/Math.pow 2 n))))
        (when-not @(:stopping? client)
          (try
            (js-await (start! client))
            (catch :default _e
              ;; start! already recorded the error and set
              ;; :stopped-error; nothing to do here.
              nil)))))))

;; ── Tool invocation ──────────────────────────────────────────────

(defn ^:async call-tool!
  "Invoke an MCP tool and return its result.

   Args:
     :tool-name   string — bare tool name (no `mcp__server__` prefix)
     :arguments   JS object — tool input
     :timeout-ms  optional, defaults to client's call-timeout-ms

   Result shape (per the SDK):
     {:content [{:type :text :text} ...]  — array of content items
      :is-error? bool
      :structured-content any-or-nil}

   Throws if the client isn't :running, or if the SDK call rejects."
  [client {:keys [tool-name arguments timeout-ms]}]
  (when-not (= :running @(:state client))
    (throw (js/Error. (str "[mcp-client " (:name (:config client))
                           "] cannot call tool — state is "
                           (str @(:state client))))))
  (let [c (or @(:client client)
              (throw (js/Error. "[mcp-client] client missing")))
        opts #js {:timeout (or timeout-ms
                               (:call-timeout-ms (:config client))
                               default-call-timeout-ms)}
        ;; SDK signature is (params, resultSchema?, options?). The
        ;; second arg is a zod schema for response validation; we
        ;; want the SDK's default schema, so it must be JS undefined
        ;; (the SDK's safeParse trips on null vs undefined). options
        ;; goes third.
        result (js-await (.callTool c
                                    #js {:name      tool-name
                                         :arguments arguments}
                                    js/undefined
                                    opts))
        content (.-content result)
        items   (when content
                  (vec (for [i (range (.-length content))]
                         (let [it (aget content i)]
                           {:type (.-type it)
                            :text (.-text it)
                            :raw  it}))))]
    {:content items
     :is-error? (boolean (.-isError result))
     :structured-content (.-structuredContent result)}))
