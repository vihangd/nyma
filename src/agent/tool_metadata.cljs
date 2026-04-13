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

   Schema (all fields optional; omitted = false, category = nil):

     {:read-only?             tool only reads; no mutation
      :destructive?           may overwrite, delete, or mutate state
      :requires-confirmation? prompt user before running
      :network?               tool makes network calls (hide in air-gap)
      :long-running?          may exceed normal tool timeouts
      :category               one of #{:file :search :shell :network :meta}}

   Unknown tools fall back to a conservative 'nothing known' default:
   not read-only, not destructive, no confirmation required. Callers
   that want a default-deny policy should wrap the result themselves."
  (:require [clojure.string :as str]))

;;; ─── Default metadata for tools shipped in agent.tools ─

(def builtin-metadata
  "Safety metadata for the tools defined in agent.tools. Built-in
   names match the registered tool identifiers exactly."
  {"read"       {:read-only? true  :category :file}
   "write"      {:destructive? true :requires-confirmation? true :category :file}
   "edit"       {:destructive? true :requires-confirmation? true :category :file}
   "bash"       {:destructive? true :requires-confirmation? true
                 :category :shell :long-running? true}
   "think"      {:read-only? true :category :meta}
   "ls"         {:read-only? true :category :file}
   "glob"       {:read-only? true :category :search}
   "grep"       {:read-only? true :category :search}
   "web_fetch"  {:read-only? true :network? true :category :network}
   "web_search" {:read-only? true :network? true :category :network}})

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
   :category               nil})

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
