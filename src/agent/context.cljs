(ns agent.context
  "Message filtering, context building.")

(defn- message-entry? [entry]
  (or (contains? #{"user" "assistant" "tool_call" "tool_result"} (:role entry))
      ;; An activated skill's instructions are stored as a `role "system"`
      ;; entry tagged `:skill` (resources/skills `skill-message`). Without this
      ;; the entry was filtered out here and the body never reached the
      ;; provider: `/skill` applied the skill's `allowed-tools` grant and
      ;; delivered none of its instructions, and only the `skill` TOOL appeared
      ;; to work, because it also returns the body as its tool result. The loop
      ;; already passes `allowSystemInMessages` for exactly this kind of entry.
      ;; Keyed on the `:skill` tag, not on role "system", so nothing else that
      ;; happens to be stored as a system message starts reaching the model.
      (some? (:skill entry))))

(defn- llm-visible?
  "A message participates in the LLM context unless it is tagged
   `:local-only true`. Editor bash mode (`!!cmd`) uses this tag to
   render bash output on-screen without leaking it to the model on
   the next turn."
  [entry]
  (not (:local-only entry)))

(defn build-context
  "Build the message list for the current conversation turn.
   Walks the active state and filters to LLM-relevant messages.
   Messages tagged `:local-only` are rendered by the UI but excluded
   here so they never reach the provider."
  [agent]
  (let [messages (:messages @(:state agent))]
    (->> messages
         (filter message-entry?)
         (filter llm-visible?)
         vec)))

(defn get-active-tools
  "Return the currently active tools from the registry."
  [agent]
  ((:get-active (:tool-registry agent))))

(defn ^:async get-active-tools-filtered
  "Return active tools, filtered by tool_access_check event.
   Extensions (e.g., modes) return {allowed: ['read', 'grep', ...]} to restrict."
  [agent]
  (let [all-tools (get-active-tools agent)
        events    (:events agent)
        state     @(:state agent)
        result    (js-await
                   ((:emit-collect events) "tool_access_check"
                                           #js {:tools      (clj->js (vec (keys all-tools)))
                                                :activeRole (or (:active-role state) :default)
                                                :context    "generation"}))]
    (if-let [allowed (get result "allowed")]
      (let [allowed-set (set (map str allowed))]
        (into {} (filter (fn [[k _]] (contains? allowed-set k)) all-tools)))
      all-tools)))

