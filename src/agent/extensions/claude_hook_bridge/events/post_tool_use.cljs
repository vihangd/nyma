(ns agent.extensions.claude-hook-bridge.events.post-tool-use
  "PostToolUse and PostToolUseFailure hook events.

   Mapping:
     - nyma's `tool_complete` event (emit-collect, fired after every
       tool call) maps to Claude Code's PostToolUse for normal results
       and PostToolUseFailure for `:isError true`.

   Inbound payload (CC):
     {
       \"session_id\":      string,
       \"transcript_path\": string,
       \"cwd\":             string,
       \"permission_mode\": string,
       \"hook_event_name\": \"PostToolUse\" | \"PostToolUseFailure\",
       \"tool_name\":       string,
       \"tool_input\":      object,
       \"tool_use_id\":     string,
       \"tool_result\":     string  (success path),
       \"error_message\":   string  (failure path)
     }

   Outbound → nyma effects:
     - decision: \"block\"          → {result: reason}  (the assistant
                                       sees the block reason as the
                                       new tool output, so it can
                                       react in the next turn)
     - additionalContext            → appended to the result string"
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]
            [agent.extensions.claude-hook-bridge.tool-names :as tool-names]))

(def ^:private bridge-priority 200)

(defn- payload [event-data is-error?]
  #js {:session_id      "session"
       :transcript_path ""
       :cwd             (js/process.cwd)
       :permission_mode "default"
       :hook_event_name (if is-error? "PostToolUseFailure" "PostToolUse")
       :tool_name       (tool-names/cc-name (or (.-toolName event-data)
                                                (.-name event-data) ""))
       :tool_input      (or (.-args event-data) #js {})
       :tool_use_id     (or (.-toolCallId event-data) "")
       :tool_result     (when-not is-error? (str (or (.-result event-data) "")))
       :error_message   (when is-error?
                          (str (or (.-result event-data)
                                   (.-errorMessage event-data) "")))})

(defn register!
  [{:keys [api hooks-atom cwd]}]
  (let [
        handler
        (^:async fn [data]
          (let [is-error? (boolean (or (.-isError data) (.-is-error data)))
                tool-name (str (or (.-toolName data) (.-name data) ""))
                disc      (tool-names/cc-name tool-name)
                stdin     (payload data is-error?)
                merged    (js-await
                           (dispatch/dispatch
                            {:hooks-map     @hooks-atom
                             :event-name    (if is-error? "PostToolUseFailure" "PostToolUse")
                             :discriminator disc
                             :stdin-payload stdin
                             :abort-signal  (when-let [a (.-abortController api)]
                                              (.-signal a))
                             :cwd           cwd
                             :api           api}))]
            (when merged
              (let [out  #js {}
                    base (str (or (.-result data) ""))
                    extra (str/join "\n" (filter seq [base (:additional-context merged)]))]
                (cond-> out
                  (:decision-block? merged)
                  (doto (aset "result" (str extra "\n[Hook block] "
                                            (or (:decision-block-reason merged) ""))))

                  (and (not (:decision-block? merged))
                       (seq (:additional-context merged)))
                  (aset "result" extra))
                out))))]
    (.on api "tool_complete" handler bridge-priority)
    (fn []
      (.off api "tool_complete" handler))))
