(ns agent.ui.autocomplete-builtins
  "Three built-in completion providers that ship with nyma:

     * slash-commands     — lists nyma /commands; trigger :slash
     * slash-forward      — lists //commands forwarded to the connected
                            ACP agent; trigger :slash-forward. Filters to
                            commands tagged `:forward-to \"agent-shell\"`
                            and falls back to the full command list if
                            none are tagged (e.g. no agent connected yet).
     * at-file-mentions   — runs the first mention provider; trigger :at

   Each provider is registered into the agent's
   :autocomplete-registry when `register-all!` is called.

   History: an earlier version also shipped a `path-complete` provider
   bound to a `:path` trigger that fired on `endsWith '/'`. That
   trigger ambiguously overlapped with `:slash` (a bare `/` lit up
   both pickers) and has been removed in phase 21 — file references
   go through @-mentions exclusively, matching cc-kit and claude code."
  (:require [agent.commands.parser :as cmd-parser]))

(defn- forward-to
  "Read the :forward-to tag off a command spec, tolerating both CLJ
   maps (from static extensions) and #js objects (from the ACP
   notifications handler, which uses raw JS interop)."
  [cmd]
  (or (:forward-to cmd)
      (when (some? cmd)
        (try (.-forward-to cmd) (catch :default _ nil)))))

(defn- agent-forwarded? [cmd]
  (= "agent-shell" (forward-to cmd)))

;;; ─── /command provider ─────────────────────────────────

(defn- slash-provider
  "Lists every registered /command as an autocomplete item.

   Delegates to commands.parser/command-suggestions, which handles
   alias matching, hidden/disabled filtering, and alphabetical order.
   Passes the partial editor text so filtering happens upfront
   instead of client-side on every re-render."
  [agent]
  {:trigger  :slash
   :priority 100
   :complete
   (fn [ctx]
     (js/Promise.resolve
      (let [cmds    (or @(:commands agent) {})
            ;; The provider context passes :text (the full editor
            ;; string) and :query (the extracted token). Fall back to
            ;; a bare '/' so a bare-slash trigger still shows every
            ;; visible command.
            partial (or (get ctx :text) "/")
            suggs   (cmd-parser/command-suggestions partial cmds)]
        (clj->js
         (vec (for [{:keys [display-name command]} suggs]
                ;; The picker shows :display-name (e.g. `/agent` rather
                ;; than `/agent-shell__agent`). The same value is
                ;; inserted into the editor on select — command
                ;; resolution at send-time handles the short→canonical
                ;; mapping via agent.commands.resolver/resolve-command.
                ;; Trim trailing padding (from collision alignment) so
                ;; the editor text stays clean.
                (let [shown (str "/" (.trimEnd display-name))]
                  {:label       shown
                   :value       shown
                   :description (or (:description command) "")})))))))})

;;; ─── //command provider (agent-forwarded slash commands) ──

(defn- slash-forward-provider
  "Lists commands that should be forwarded to the active ACP agent.
   Fires on the `//` trigger. Prefers commands tagged
   `:forward-to \"agent-shell\"` (registered by the agent-shell ACP
   notifications handler when the agent publishes its command list).
   If no forwarded commands are available (e.g. no agent is connected
   yet), falls back to the full visible command set so the picker is
   still useful rather than silently empty."
  [agent]
  {:trigger  :slash-forward
   :priority 100
   :complete
   (fn [ctx]
     (js/Promise.resolve
      (let [cmds    (or @(:commands agent) {})
            ;; Reuse command-suggestions by feeding it a `/<query>`
            ;; string — that way alias matching, hidden filtering, and
            ;; display-name logic all apply unchanged.
            query   (or (get ctx :query) "")
            partial (str "/" query)
            suggs   (cmd-parser/command-suggestions partial cmds)
            forward (filter (fn [s] (agent-forwarded? (:command s))) suggs)
            picked  (if (seq forward) forward suggs)]
        (clj->js
         (vec (for [{:keys [display-name command]} picked]
                (let [shown (str "//" (.trimEnd display-name))]
                  {:label       shown
                   :value       shown
                   :description (or (:description command) "")})))))))})

;;; ─── @file provider (delegates to mention-providers) ──

(defn- at-file-provider
  "Delegates to the first registered mention-provider's search fn."
  [agent]
  {:trigger  :at
   :priority 100
   :complete
   (fn [ctx]
     (let [providers @(:mention-providers agent)
           provider  (first (vals providers))
           search    (when provider (:search provider))]
       (if search
         (try
           (-> (search (get ctx :query))
               (.then (fn [items] (or items (clj->js []))))
               (.catch (fn [_] (clj->js []))))
           (catch :default _ (js/Promise.resolve (clj->js []))))
         (js/Promise.resolve (clj->js [])))))})

;;; ─── Register all ──────────────────────────────────────

(defn register-all!
  "Register every built-in provider into the agent's autocomplete
   registry. Idempotent — re-registering overwrites previous entries."
  [agent]
  (when-let [reg (:autocomplete-registry agent)]
    ((:register reg) "builtin.slash"         (slash-provider agent))
    ((:register reg) "builtin.slash-forward" (slash-forward-provider agent))
    ((:register reg) "builtin.at-file"       (at-file-provider agent))))
