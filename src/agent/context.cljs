(ns agent.context)

(defn- message-entry? [entry]
  (contains? #{"user" "assistant" "tool_call" "tool_result"} (:role entry)))

(defn build-context
  "Build the message list for the current conversation turn.
   Walks the active state and filters to LLM-relevant messages."
  [agent]
  (let [messages (:messages @(:state agent))]
    (->> messages
         (filter message-entry?)
         vec)))

(defn get-active-tools
  "Return the currently active tools from the registry."
  [agent]
  ((:get-active (:tool-registry agent))))
