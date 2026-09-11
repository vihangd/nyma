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
                  :dynamic-commands [], :config-options [],
                  :subagents {id -> {:name :task :state}}}}."
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

(def tool-callback
  "Atom holding a callback (fn [{:id :title :kind :status :path}]) for
   rendering tool activity. Set by the input router before sending a prompt,
   cleared after.

   Added because a turn looked hung: `acp_tool_start` / `acp_tool_update` were
   emitted as events (declared in events.cljs) that NOTHING subscribed to, and
   Claude Code spends most of a turn reading and editing before it emits any
   text. The whole working period rendered as a blank screen."
  (atom nil))

(def replay-callback
  "Atom holding (fn [{:role :text}]) used while a session is replaying, so the
   restored conversation lands in the transcript pane. Separate from
   `stream-callback` because replay is history: it must not look like the
   agent is answering right now."
  (atom nil))

(def mcp-servers
  "Discovered MCP server configs in ACP array format.
   [{:name \"server\" :command \"npx\" :args [...] :env {...}}]"
  (atom []))

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
              section (aget parsed "agent-shell")]
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

;;; ─── Session transcript ─────────────────────────────────────
;;
;; Deliberately NOT a field inside agent-state. That map is keyed by agent-key
;; alone, while connections are keyed by [agent, cwd] (see pool-key) — the
;; gateway fans one agent out across several projects, so a transcript kept per
;; agent would interleave two projects' conversations into one plan.
;;
;; Nothing else records the conversation: agent-state holds only :plan, :mode,
;; :model, :usage and friends, `(:prompt-state conn)` is reset at the start of
;; every prompt (client/send-prompt), and the ACP stream drops user turns
;; outright (`user_message_chunk` → nil, "replay only"). So this is the only
;; place a captured plan can come from.

(def transcripts
  "pool-key → [{:role \"user\"|\"assistant\" :text s} …], oldest first."
  (atom {}))

(def ^:private max-turns 40)
(def ^:private max-chars 200000)

(defn- trim-transcript
  "Drop oldest turns until the transcript is within both caps. A planning
   session is unbounded otherwise, and the useful part is always the tail."
  [turns]
  (loop [ts (vec turns)]
    (if (or (> (count ts) max-turns)
            (and (seq ts) (> (reduce + 0 (map (fn [t] (count (str (:text t)))) ts)) max-chars)))
      (recur (vec (rest ts)))
      ts)))

(defn append-turn!
  "Append one turn to `pool-key`'s transcript. Blank text is ignored — a
   whitespace-only assistant reply must not create a phantom turn, and
   `seq` is not enough for that since \"   \" is a non-empty string."
  [pool-key role text]
  (when (and pool-key (not (str/blank? (str text))))
    (swap! transcripts update (str pool-key)
           (fn [ts] (trim-transcript (conj (vec ts) {:role (str role) :text (str text)})))))
  nil)

(defn get-transcript
  "Turns for `pool-key`, oldest first. [] when there is none."
  [pool-key]
  (vec (get @transcripts (str pool-key) [])))

;;; ─── Remembered sessions ───────────────────────────────────
;;
;; agent_shell declares the `state` capability in extension.json and never used
;; it, so `:session-id` lived only in a per-conn atom and every session died
;; with the process. Refining a captured plan the next day meant starting cold.

(defn store-key
  "Sessions are remembered per [agent, project] — the same key the connection
   pool uses, because one agent commonly drives several checkouts."
  [agent-key cwd]
  (str "session:" (pool-key agent-key cwd)))

(defn remember-session!
  "Persist the current session id for this agent+cwd. Silent on failure: a
   read-only or missing ext-state dir must not break the session itself."
  [api agent-key session-id & [title]]
  (when (and session-id (.-state api))
    (try
      (.set (.-state api) (store-key agent-key (js/process.cwd))
            #js {:sessionId (str session-id)
                 :title     (or title "")
                 :updated   (.toISOString (js/Date.))})
      (catch :default _ nil))))

(defn recall-session
  "The remembered session for this agent+cwd, or nil."
  [api agent-key]
  (when (.-state api)
    (try (.get (.-state api) (store-key agent-key (js/process.cwd)))
         (catch :default _ nil))))

(def mutating-tool-kinds
  "ACP ToolKind values that change the working tree. `read`, `search`, `think`
   and `fetch` do not."
  #{"edit" "delete" "move"})

(def ^:private edit-counts
  "pool-key → number of COMPLETED mutating tool calls this session."
  (atom {}))

(defn record-tool-call!
  "Count a tool call against `pool-key` when it both mutates and completed.

   This is what tells a planning session apart from one that already did the
   work. Mode is only a proxy — an agent in default mode may be denied every
   write, while one in an auto-approving mode quietly makes the changes the
   plan is supposed to describe. Counting completed edits answers it directly."
  [pool-key kind status]
  (when (and pool-key
             (contains? mutating-tool-kinds (str kind))
             (= "completed" (str status)))
    (swap! edit-counts update (str pool-key) (fn [n] (inc (or n 0)))))
  nil)

(defn edit-count
  "Completed mutating tool calls for `pool-key`."
  [pool-key]
  (or (get @edit-counts (str pool-key)) 0))

(defn clear-transcript!
  "Forget `pool-key`'s conversation. Called on disconnect and on a fresh
   session — otherwise a reconnect would let /plan-capture write a plan from
   the PREVIOUS session, which agent-state never cleared either."
  [pool-key]
  (swap! transcripts dissoc (str pool-key))
  (swap! edit-counts dissoc (str pool-key))
  nil)

(defn clear-transcripts-for-agent!
  "Forget every project's transcript for `agent-key` (the `/agent disconnect`
   shape, which tears down all cwds for that agent)."
  [agent-key]
  (let [prefix (str (kw-name agent-key) "@")]
    (let [drop-prefixed (fn [m] (into {} (remove (fn [[k _]] (and (string? k) (.startsWith k prefix))) m)))]
      (swap! transcripts drop-prefixed)
      (swap! edit-counts drop-prefixed)))
  nil)

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


;; A `header-factory` + `setup-ui!` pair lived here, installing a rich
;; "nyma × claude | model | mode" header through `api.ui.setHeader`. No TUI ever
;; implemented that slot — extensions.cljs declared it as a nil placeholder and
;; interactive.cljs never assigned it — so the guard bailed on every call and
;; the header was never drawn. features/status_segments.cljs already puts the
;; agent, model and mode on the status line, which is a slot that exists.

