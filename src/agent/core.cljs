(ns agent.core
  (:require ["ai" :refer [streamText generateText]]
            [agent.events :refer [create-event-bus]]
            [agent.tools :refer [builtin-tools]]
            [agent.tool-registry :refer [create-registry]]
            [agent.middleware :refer [create-pipeline]]
            [agent.state :refer [create-agent-store]]
            [agent.providers.registry :refer [create-provider-registry]]
            [agent.providers.builtins :refer [builtin-providers]]))

(defn create-agent
  "Create an agent instance. Config is a plain JS object with:
   :model, :system-prompt, :tools, :max-steps, :extensions"
  [{:keys [model system-prompt tools max-steps extensions]
    :or   {max-steps 20}}]
  (let [events            (create-event-bus)
        ;; :tools must be a map of {name → tool-object} or nil.
        ;; Vectors/arrays are silently ignored to prevent merge corruption.
        extra-tools       (if (and tools (map? tools)) tools {})
        tool-registry     (create-registry (merge builtin-tools extra-tools))
        ;; agent-ref is an atom set after agent creation so the pipeline
        ;; can lazily create extension contexts during tool execution.
        agent-ref         (atom nil)
        middleware        (create-pipeline events nil agent-ref)
        steer-queue       (atom [])
        follow-queue      (atom [])
        commands          (atom {})
        shortcuts         (atom {})
        ;; Phase 2 additions
        provider-registry (create-provider-registry builtin-providers)
        message-renderers (atom {})
        thinking-level    (atom "off")
        abort-controller  (atom (js/AbortController.))
        inter-events      (create-event-bus)  ;; Inter-extension communication bus
        flags             (atom {})           ;; Extension CLI flags {name → {:description :type :default :value}}
        state             (atom {:messages           []
                                 :model              model
                                 :active-tools       (set (keys builtin-tools))
                                 :total-input-tokens  0
                                 :total-output-tokens 0
                                 :total-cost          0.0
                                 :turn-count          0
                                 :active-executions   #{}
                                 :tool-calls          {}})
        ;; Event-sourced store shares the same atom as :state
        store             (create-agent-store @state state)]
    (let [agent {:events            events
                 :config            {:model         model
                                     :system-prompt system-prompt
                                     :max-steps     max-steps}
                 :tool-registry     tool-registry
                 :middleware        middleware
                 :steer-queue       steer-queue
                 :follow-queue      follow-queue
                 :commands          commands
                 :shortcuts         shortcuts
                 :state             state
                 :store             store
                 :extensions        extensions
                 ;; Phase 2 additions
                 :provider-registry provider-registry
                 :message-renderers message-renderers
                 :thinking-level    thinking-level
                 :abort-controller  abort-controller
                 :inter-events      inter-events
                 :flags             flags
                 ;; Session is attached later by cli.cljs
                 :session           (atom nil)}]
      ;; Set agent-ref so the middleware pipeline can create extension contexts
      (reset! agent-ref agent)
      agent)))
