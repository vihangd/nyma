(ns agent.core
  (:require ["ai" :refer [streamText generateText]]
            [agent.events :refer [create-event-bus]]
            [agent.tools :refer [builtin-tools]]
            [agent.tool-registry :refer [create-registry]]))

(defn create-agent
  "Create an agent instance. Config is a plain JS object with:
   :model, :system-prompt, :tools, :max-steps, :extensions"
  [{:keys [model system-prompt tools max-steps extensions]
    :or   {max-steps 20}}]
  (let [events        (create-event-bus)
        tool-registry (create-registry (merge builtin-tools
                                              (or tools {})))
        steer-queue   (atom [])
        follow-queue  (atom [])
        commands      (atom {})
        shortcuts     (atom {})
        state         (atom {:messages      []
                             :model         model
                             :active-tools  (set (keys builtin-tools))})]
    {:events        events
     :config        {:model         model
                     :system-prompt system-prompt
                     :max-steps     max-steps}
     :tool-registry tool-registry
     :steer-queue   steer-queue
     :follow-queue  follow-queue
     :commands      commands
     :shortcuts     shortcuts
     :state         state
     :extensions    extensions}))
