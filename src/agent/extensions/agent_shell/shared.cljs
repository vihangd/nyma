(ns agent.extensions.agent-shell.shared
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]))

;;; ─── Shared state ──────────────────────────────────────────

(def active-agent
  "Current active agent key (:claude, :gemini, etc.) or nil."
  (atom nil))

(def connections
  "Pool of live ACP connections: {pool-key -> connection-map}.
   Pool keys are `\"<agent-key>@<resolved-cwd>\"` so the same agent can be
   running against multiple project directories in parallel — required for
   gateway-driven multi-project routing. Interactive UI flows omit cwd and
   default to `(js/process.cwd)`, so the key is stable across the lifetime
   of a single nyma process."
  (atom {}))

(def agent-state
  "Per-agent runtime state:
   {agent-key -> {:models [], :mode \"\", :usage {}, :turn-usage {},
                  :session-title \"\", :session-id \"\",
                  :dynamic-commands [], :config-options []}}."
  (atom {}))

(def deactivators
  "Cleanup functions collected from sub-module activation."
  (atom []))

(def stream-callback
  "Atom holding a callback (fn [text-delta]) for streaming chunks to the UI.
   Set by the input router before sending a prompt, cleared after."
  (atom nil))

(def thought-callback
  "Atom holding a callback (fn [thought-text]) for streaming thinking chunks.
   Set by the input router before sending a prompt, cleared after."
  (atom nil))

(def plan-callback
  "Atom holding a callback (fn [plan-data]) for rendering plan updates.
   Set by the input router before sending a prompt, cleared after."
  (atom nil))

(def mcp-servers
  "Discovered MCP server configs in ACP array format.
   [{:name \"server\" :command \"npx\" :args [...] :env {...}}]"
  (atom []))

(def api-ref
  "Stores the scoped API reference for lazy UI access.
   UI is not available at extension activation time (useEffect sets it later),
   so modules that need ui.setFooter etc. must read this atom."
  (atom nil))

(def footer-set?
  "Whether the custom footer has been installed."
  (atom false))

;;; ─── Config ────────────────────────────────────────────────

(def default-config
  {:default-agent nil
   :auto-approve  false
   :auto-connect  false
   :agents        {}})

;;; ─── JS interop (Squint has clj->js but NOT js->clj) ──────

(defn js->clj*
  "Convert a JS object to a Clojure-ish map via JSON round-trip.
   Squint does not provide js->clj, so we use JSON.parse(JSON.stringify(x))
   which gives us plain JS objects that Squint's get/assoc work with."
  [x]
  (when x
    (js/JSON.parse (js/JSON.stringify x))))

(defn load-config
  "Load agent-shell config from .nyma/settings.json."
  []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")]
    (if (fs/existsSync settings-path)
      (try
        (let [raw     (fs/readFileSync settings-path "utf8")
              parsed  (js/JSON.parse raw)
              section (.-agent-shell parsed)]
          (if section
            (merge default-config (js->clj* section))
            default-config))
        (catch :default _e default-config))
      default-config)))

;;; ─── Helpers ───────────────────────────────────────────────

(defn kw-name
  "Convert a keyword to its string name, e.g. :claude -> \"claude\".
   In Squint, keywords are strings like \":claude\", so we strip the colon."
  [k]
  (let [s (str k)]
    (if (and (string? s) (.startsWith s ":"))
      (.slice s 1)
      s)))

(defn format-k
  "Format a number as 'Nk' for thousands."
  [n]
  (if (and n (> n 0))
    (if (>= n 1000)
      (str (.toFixed (/ n 1000) 1) "k")
      (str n))
    "0"))

(defn pool-key
  "Compose the connections-atom key from an agent-key and a working directory.
   Used by pool/get-or-create + the gateway's run_in_project tool. Path is
   `path.resolve`-d so callers can pass `~/…` or relative paths."
  [agent-key cwd]
  (let [resolved (try (path/resolve cwd) (catch :default _ cwd))]
    (str (kw-name agent-key) "@" resolved)))

(defn find-conn-by-agent
  "Return any live connection for the given agent-key, ignoring cwd. Used by
   single-active-agent UI flows (session_clear, header rendering) that don't
   care which project the connection points at."
  [agent-key]
  (let [prefix (str (kw-name agent-key) "@")]
    (some (fn [[k v]]
            (when (and (string? k) (.startsWith k prefix) (map? v))
              v))
          @connections)))

(defn update-agent-state!
  "Update a field in the active agent's state."
  [agent-key field value]
  (swap! agent-state assoc-in [agent-key field] value))

(defn get-agent-state
  "Get a field from an agent's state."
  [agent-key field]
  (get-in @agent-state [agent-key field]))

(defn- ->camel-case
  "kebab-case-string → camelCaseString."
  [s]
  (let [parts (str/split (str s) #"-")]
    (str (first parts)
         (str/join "" (map (fn [p]
                             (if (zero? (count p))
                               ""
                               (str (.toUpperCase (subs p 0 1)) (subs p 1))))
                           (rest parts))))))

(defn- cap-key-strings
  "Lookup candidates for an agent capability key, in priority order.
   Accepts a kebab-case keyword (`:load-session`) or string. Returns
   string candidates only — `js->clj*` builds plain JS objects, so
   keys are strings. We try kebab-case first, then camelCase, since
   different agents use different conventions."
  [k]
  (let [raw     (str k)
        s       (if (.startsWith raw ":") (subs raw 1) raw)
        camel-s (->camel-case s)]
    (if (= s camel-s) [s] [s camel-s])))

(defn agent-supports?
  "Check whether the agent (as reported in `agentCapabilities` from the
   `initialize` handshake) declares support for a capability.

   Returns boolean. Defensive on missing data: if no capabilities have
   been recorded yet (pre-handshake) OR the specific capability isn't
   in the map at all, returns `true` — better to optimistically
   attempt the call and surface the agent's own error than to refuse
   based on stale local state."
  [agent-key cap]
  (let [caps (get-agent-state agent-key :capabilities)]
    (cond
      (nil? caps)        true
      (not (object? caps)) true
      :else
      (let [candidates (cap-key-strings cap)
            ;; Walk candidates; first one that's actually present wins.
            hit-key    (some (fn [k]
                               (when (some? (aget caps k)) k))
                             candidates)]
        (cond
          hit-key (boolean (aget caps hit-key))
          ;; Spec-default for absent capability: assume supported.
          :else   true)))))

(defn agent-supports-resume?
  "Check if agent supports session resume.
   Accepts either new ACP spec (sessionCapabilities.resume) or old flat loadSession."
  [agent-key]
  (let [caps (get-agent-state agent-key :capabilities)]
    (cond
      (nil? caps)          true
      (not (object? caps)) true
      :else
      (let [session-caps (aget caps "sessionCapabilities")
            new-resume   (and session-caps (aget session-caps "resume"))
            old-load     (aget caps "loadSession")]
        (cond
          (some? new-resume) (boolean new-resume)
          (some? old-load)   (boolean old-load)
          ;; Neither declared — optimistic
          :else true)))))

(defn- footer-factory
  "Returns footer text string or nil (for default fallback)."
  []
  (let [agent-key @active-agent]
    (when agent-key
      (let [state (get @agent-state agent-key)
            parts (cond-> [(str "[" (kw-name agent-key) "]")]
                    (:model state) (conj (:model state))
                    (:mode state)  (conj (str "| " (:mode state)))
                    (and (:used (:usage state)) (:size (:usage state)))
                    (conj (str "| ctx:" (format-k (:used (:usage state)))
                               "/" (format-k (:size (:usage state)))))
                    (:cost (:usage state))
                    (conj (str "| $" (.toFixed (:amount (:cost (:usage state))) 2)))
                    (:input-tokens (:turn-usage state))
                    (conj (str "| in:" (format-k (:input-tokens (:turn-usage state)))
                               " out:" (format-k (:output-tokens (:turn-usage state))))))]
        (.join (clj->js parts) " ")))))

(defn- header-factory
  "Returns header text string or nil (for default fallback)."
  []
  (let [agent-key @active-agent]
    (when agent-key
      (let [state (get @agent-state agent-key)
            parts (cond-> [(str "nyma × " (kw-name agent-key))]
                    (:model state) (conj (str "| " (:model state)))
                    (:mode state)  (conj (str "| " (:mode state)))
                    (:session-title state) (conj (str "| " (:session-title state))))]
        (.join (clj->js parts) " ")))))

(defn setup-ui!
  "Install the custom header. Called lazily on first agent connect,
   because api.ui is not available at extension activation time.

   Footer was historically installed here via .setFooter, but ACP
   status now flows through registerStatusSegment in
   features/status_segments.cljs (registered from index.cljs on
   session_ready). Only the Header remains here because it's a
   different slot with different UX requirements."
  []
  (when-not @footer-set?
    (when-let [api @api-ref]
      (when (and (.-ui api) (.-available (.-ui api)))
        (reset! footer-set? true)
        (.setHeader (.-ui api) header-factory)))))
