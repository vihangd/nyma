(ns agent.extensions.claude-hook-bridge.events.permission-request
  "PermissionRequest hook event.

   nyma's `permission_request` (emit-collect, fired by middleware
   when a tool needs approval) maps to CC's PermissionRequest. The
   discriminator is the CC-cased tool name.

   Outbound effects:
     - decision.behavior \"allow\" → return {decision: \"allow\"}
     - decision.behavior \"deny\"  → return {decision: \"deny\", reason}
     - updatedInput                → return {updatedArgs: <new>}
   The middleware's permission-check handler will treat a missing
   decision as 'fall through to user prompt' (existing behavior)."
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]
            [agent.extensions.claude-hook-bridge.tool-names :as tool-names]))

(def ^:private bridge-priority 200)

(defn- payload [data]
  (let [tool-name (str (or (.-tool data) ""))]
    #js {:session_id      "session"
         :transcript_path ""
         :cwd             (js/process.cwd)
         :permission_mode "default"
         :hook_event_name "PermissionRequest"
         :tool_name       (tool-names/cc-name tool-name)
         :tool_input      (or (.-args data) #js {})}))

(defn register!
  [{:keys [api hooks-atom cwd]}]
  (let [events (.-events api)
        handler
        (fn [data]
          (let [tool-name (str (or (.-tool data) ""))
                disc      (tool-names/cc-name tool-name)
                merged    (js-await
                           (dispatch/dispatch
                            {:hooks-map     @hooks-atom
                             :event-name    "PermissionRequest"
                             :discriminator disc
                             :stdin-payload (payload data)
                             :abort-signal  (when-let [a (.-abortController api)]
                                              (.-signal a))
                             :cwd           cwd
                             :api           api}))]
            (when merged
              (let [hso (:hook-specific merged)
                    nested (when (and hso (object? hso)) (.-decision hso))
                    behavior (when (and nested (object? nested)) (.-behavior nested))
                    reason   (or (when (and nested (object? nested)) (.-message nested))
                                 (:decision-block-reason merged))
                    out #js {}]
                (cond
                  (= behavior "allow")
                  (doto out (aset "decision" "allow"))

                  (= behavior "deny")
                  (doto out (aset "decision" "deny") (aset "reason" (or reason "")))

                  ;; Some configs return a top-level permissionDecision
                  ;; via PreToolUse-style shape. Fall back to that.
                  (= (:permission-decision merged) "allow")
                  (doto out (aset "decision" "allow"))

                  (= (:permission-decision merged) "deny")
                  (doto out (aset "decision" "deny")
                        (aset "reason" (or (:permission-reason merged) "")))

                  :else nil)))))]
    ((:on events) "permission_request" handler bridge-priority)
    (fn []
      ((:off events) "permission_request" handler))))
