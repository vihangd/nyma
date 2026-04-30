(ns agent.extensions.claude-hook-bridge.events.pre-tool-use
  "PreToolUse hook event.

   Mapping:
     - nyma's `before_tool_call` event (emit-collect, priority high so
       the bridge can deny before other extensions fire) maps to
       Claude Code's PreToolUse. The discriminator is the CC-cased
       tool name.

   Inbound payload to the hook (CC schema):
     {
       \"session_id\":      string,
       \"transcript_path\": string,
       \"cwd\":             string,
       \"permission_mode\": string,
       \"hook_event_name\": \"PreToolUse\",
       \"tool_name\":       string (CC TitleCase),
       \"tool_input\":      object (the tool args),
       \"tool_use_id\":     string
     }

   Outbound merged response → nyma effects:
     - permissionDecision: \"deny\"  → return {block: true, reason}
     - permissionDecision: \"allow\" → no-op (other handlers still run)
     - permissionDecision: \"ask\"   → no-op for now (let nyma's
                                       permission_request fire); reason
                                       passed through additionalContext
     - permissionDecision: \"defer\" → treat as \"ask\" (no SDK yet)
     - decision: \"block\"           → {block: true, reason}
     - updatedInput                  → {args: <new>}
     - additionalContext             → injected as system reminder via
                                       inject-messages

   Priority: subscribes at high priority so a deny short-circuits
   before-hook-compat / permission-check / approval-check."
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]
            [agent.extensions.claude-hook-bridge.tool-names :as tool-names]
            [clojure.string :as str]))

(def ^:private bridge-priority 200)

(defn- session-info [api]
  (let [state (try (.getState api) (catch :default _e nil))]
    {:session-id      (or (and state (.-sessionId state)) "session")
     :transcript-path (or (and state (.-transcriptPath state)) "")
     :permission-mode (or (and state (.-permissionMode state)) "default")}))

(defn- payload-for-hook
  "Translate a nyma before_tool_call event into CC PreToolUse stdin."
  [api event-data nyma-tool-name args]
  (let [info  (session-info api)]
    #js {:session_id      (:session-id info)
         :transcript_path (:transcript-path info)
         :cwd             (js/process.cwd)
         :permission_mode (:permission-mode info)
         :hook_event_name "PreToolUse"
         :tool_name       (tool-names/cc-name nyma-tool-name)
         :tool_input      (clj->js args)
         :tool_use_id     (or (when event-data (.-execId event-data))
                              (when event-data (.-exec-id event-data))
                              "")}))

(defn- merged->effects
  "Translate a merged response map into the JS object nyma's
   `before_tool_call` (emit-collect) consumes."
  [merged]
  (let [out      #js {}
        decision (:permission-decision merged)
        block?   (or (= decision "deny") (:decision-block? merged))
        reason   (or (:permission-reason merged)
                     (:decision-block-reason merged)
                     "blocked by hook")]
    (cond-> out
      block?
      (doto (aset "block" true)
        (aset "reason" reason))

      (some? (:updated-input merged))
      (aset "args" (:updated-input merged))

      (seq (:additional-context merged))
      (aset "additionalContext" (:additional-context merged))

      true identity)
    out))

(defn register!
  [{:keys [api hooks-map cwd]}]
  (let [events (.-events api)
        ;; The actual handler. Returns a JS object that emit-collect
        ;; merges with other handlers' returns.
        handler
        (fn [data]
          (let [tool-name (str (or (.-name data) (.-toolName data) ""))
                args      (or (.-args data) #js {})
                stdin     (payload-for-hook api data tool-name args)
                ;; Discriminator is the CC-cased tool name.
                disc      (tool-names/cc-name tool-name)
                merged    (js-await
                           (dispatch/dispatch
                            {:hooks-map     hooks-map
                             :event-name    "PreToolUse"
                             :discriminator disc
                             :stdin-payload stdin
                             :abort-signal  (when-let [a (.-abortController api)]
                                              (.-signal a))
                             :cwd           cwd}))]
            (when merged
              (merged->effects merged))))]
    ((:on events) "before_tool_call" handler bridge-priority)
    (fn []
      ((:off events) "before_tool_call" handler))))
