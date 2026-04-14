(ns agent.tool-metadata
  "Per-tool safety metadata. Separate from the tool definitions in
   `agent.tools` because each nyma tool is wrapped in the Vercel AI
   SDK's `tool()` constructor, which strips any fields that aren't
   part of its contract. This module keeps the safety profile as
   pure data, keyed by tool name, so permission flows and UI badges
   can look up 'is this tool destructive?' without monkey-patching
   the tool object.

   Borrowed from cc-kit's `isReadOnly` / `isDestructive` /
   `requiresConfirmation` fields on its tool definitions
   (packages/tools/src/bash.ts:159-162) — adapted to nyma's map-based
   shape and extended with :network? and :long-running? so downstream
   consumers can make routing decisions.

   Schema (all fields optional; omitted = false/nil, category = nil):

     {:read-only?             tool only reads; no mutation
      :destructive?           may overwrite, delete, or mutate state
      :requires-confirmation? prompt user before running
      :network?               tool makes network calls (hide in air-gap)
      :long-running?          may exceed normal tool timeouts
      :category               one of #{:file :search :shell :network :meta}
      :result-policy          optional override map for agent.tool-result-policy;
                              keys: :max-string-length (int), :prefer-summary-only (bool)
      :capabilities           set of capability keywords — used by gateway to filter
                              tools by what they can do; e.g. #{:filesystem :read}
                              common values: :filesystem :read :write :execution
                                             :shell :network
      :modes                  set of runtime-mode keywords where this tool is allowed;
                              nil = allowed in all modes (no restriction)
                              common values: :tui :gateway
      :cost                   relative cost hint for rate-limit / quota decisions:
                              :free | :low | :medium | :high | nil
      :timeout-ms             suggested per-call timeout in milliseconds, or nil}

   Unknown tools fall back to a conservative 'nothing known' default:
   not read-only, not destructive, no confirmation required. Callers
   that want a default-deny policy should wrap the result themselves."
  (:require [clojure.string :as str]))

;;; ─── Default metadata for tools shipped in agent.tools ─

(def builtin-metadata
  "Safety metadata for the tools defined in agent.tools. Built-in
   names match the registered tool identifiers exactly."
  {"read"       {:read-only? true  :category :file
                 :capabilities #{:filesystem :read}
                 :modes        #{:tui :gateway}
                 :cost         :free
                 :timeout-ms   10000}
   "write"      {:destructive? true :requires-confirmation? true :category :file
                 :capabilities #{:filesystem :write}
                 :modes        #{:tui}
                 :cost         :free
                 :timeout-ms   10000}
   "edit"       {:destructive? true :requires-confirmation? true :category :file
                 :capabilities #{:filesystem :write}
                 :modes        #{:tui}
                 :cost         :free
                 :timeout-ms   10000}
   "bash"       {:destructive? true :requires-confirmation? true
                 :category :shell :long-running? true
                 :capabilities #{:filesystem :execution :shell}
                 :modes        #{:tui}
                 :cost         :medium
                 :timeout-ms   30000}
   "think"      {:read-only? true :category :meta
                 :capabilities #{}
                 :modes        #{:tui :gateway}
                 :cost         :free
                 :timeout-ms   5000}
   "ls"         {:read-only? true :category :file
                 :capabilities #{:filesystem :read}
                 :modes        #{:tui :gateway}
                 :cost         :free
                 :timeout-ms   5000}
   "glob"       {:read-only? true :category :search
                 :capabilities #{:filesystem :read}
                 :modes        #{:tui :gateway}
                 :cost         :free
                 :timeout-ms   10000}
   "grep"       {:read-only? true :category :search
                 :capabilities #{:filesystem :read}
                 :modes        #{:tui :gateway}
                 :cost         :free
                 :timeout-ms   10000}
   "web_fetch"  {:read-only? true :network? true :category :network
                 :capabilities #{:network :read}
                 :modes        #{:tui :gateway}
                 :cost         :medium
                 :timeout-ms   30000}
   "web_search" {:read-only? true :network? true :category :network
                 :capabilities #{:network :read}
                 :modes        #{:tui :gateway}
                 :cost         :medium
                 :timeout-ms   30000}})

;;; ─── Extension-contributed metadata ─────────────────────

(def ^:private extension-metadata
  "Additional metadata contributed at runtime by extensions for tools
   they register. Keyed by tool name. An entry here OVERRIDES any
   matching entry in `builtin-metadata` — so an extension can mark a
   tool it wraps as more (or less) restrictive than the default."
  (atom {}))

(defn register-metadata!
  "Associate safety metadata with an extension-provided tool. Safe to
   call multiple times — later calls overwrite earlier ones, so an
   extension can refresh its declarations on reload."
  [tool-name metadata]
  (swap! extension-metadata assoc (str tool-name) metadata)
  nil)

(defn unregister-metadata!
  "Remove metadata for a tool name. Used by extension deactivation."
  [tool-name]
  (swap! extension-metadata dissoc (str tool-name))
  nil)

(defn reset-extension-metadata!
  "Test helper: wipe the extension-contributed metadata registry."
  []
  (reset! extension-metadata {}))

;;; ─── Lookups ───────────────────────────────────────────

(def ^:private default-safety
  {:read-only?             false
   :destructive?           false
   :requires-confirmation? false
   :network?               false
   :long-running?          false
   :category               nil
   :result-policy          nil
   ;; Gateway / mode-filtering fields — nil means "no restriction"
   :capabilities           #{}
   :modes                  nil
   :cost                   nil
   :timeout-ms             nil})

(defn tool-safety
  "Return the merged safety map for a tool name. Order of precedence
   (later wins): built-in defaults → built-in metadata → extension
   metadata. Unknown tools return the all-false default.

   Returned map ALWAYS includes every safety key, so callers can
   destructure without worrying about missing fields."
  [tool-name]
  (let [name (str tool-name)]
    (merge default-safety
           (get builtin-metadata name {})
           (get @extension-metadata name {}))))

(defn read-only?
  "Convenience — is this tool marked read-only?"
  [tool-name]
  (boolean (:read-only? (tool-safety tool-name))))

(defn destructive?
  "Convenience — is this tool marked destructive?"
  [tool-name]
  (boolean (:destructive? (tool-safety tool-name))))

(defn requires-confirmation?
  "Convenience — should the user confirm before running this tool?"
  [tool-name]
  (boolean (:requires-confirmation? (tool-safety tool-name))))

(defn network?
  "Convenience — does this tool make network calls?"
  [tool-name]
  (boolean (:network? (tool-safety tool-name))))

(defn long-running?
  "Convenience — may this tool exceed normal timeouts?"
  [tool-name]
  (boolean (:long-running? (tool-safety tool-name))))

(defn category
  "Return the tool's category keyword, or nil."
  [tool-name]
  (:category (tool-safety tool-name)))

(defn capabilities
  "Return the tool's capability set, or #{} if none declared."
  [tool-name]
  (or (:capabilities (tool-safety tool-name)) #{}))

(defn modes
  "Return the tool's allowed runtime-mode set, or nil (= all modes allowed)."
  [tool-name]
  (:modes (tool-safety tool-name)))

(defn cost
  "Return the tool's cost hint keyword (:free/:low/:medium/:high), or nil."
  [tool-name]
  (:cost (tool-safety tool-name)))

(defn timeout-ms
  "Return the tool's suggested timeout in ms, or nil."
  [tool-name]
  (:timeout-ms (tool-safety tool-name)))

(defn has-capability?
  "True if the tool declares the given capability keyword."
  [tool-name cap]
  (contains? (capabilities tool-name) cap))

(defn allowed-in-mode?
  "True if the tool is allowed in the given runtime mode.
   Tools with nil :modes are allowed in every mode."
  [tool-name mode]
  (let [m (modes tool-name)]
    (or (nil? m) (contains? m mode))))
