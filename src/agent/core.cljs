(ns agent.core
  (:require ["ai" :refer [streamText generateText]]
            [agent.events :refer [create-event-bus]]
            [agent.tools :refer [builtin-tools]]
            [agent.tool-registry :refer [create-registry]]
            [agent.middleware :refer [create-pipeline]]
            [agent.state :refer [create-agent-store]]
            [agent.providers.registry :refer [create-provider-registry]]
            [agent.providers.builtins :refer [builtin-providers]]
            [agent.model-info :refer [create-model-registry]]
            [agent.tool-metadata :as tool-metadata]
            [agent.keybinding-registry :as kbr]
            [agent.ui.autocomplete-provider :as ac]))

(defn- filter-tools-by-policy
  "Filter a tools map using capability/mode constraints.
   All three opts are optional — nil means no restriction on that axis.

   :require-capabilities — tool MUST declare ALL of these capability keywords
   :exclude-capabilities — tool MUST NOT declare ANY of these capability keywords
   :modes                — tool MUST be allowed in at least one of these modes
                           (tools with nil :modes pass every mode check)"
  [tools {:keys [require-capabilities exclude-capabilities modes]}]
  (if (and (nil? require-capabilities) (nil? exclude-capabilities) (nil? modes))
    tools  ;; fast path — no filters, preserve original map
    (into {}
          (filter (fn [[tool-name _]]
                    (let [caps   (tool-metadata/capabilities tool-name)
                          tmodes (tool-metadata/modes tool-name)]
                      (and
                       ;; Must have every required capability
                       (or (nil? require-capabilities)
                           (every? #(contains? caps %) require-capabilities))
                       ;; Must not have any excluded capability
                       (or (nil? exclude-capabilities)
                           (not (some #(contains? caps %) exclude-capabilities)))
                       ;; Must be allowed in at least one of the requested modes
                       (or (nil? modes)
                           (nil? tmodes)
                           (some #(contains? tmodes %) modes)))))
                  tools))))

(defn create-agent
  "Create an agent instance. Config is a plain JS object with:
   :model, :system-prompt, :tools, :max-steps, :extensions, :settings

   Optional gateway/mode keys (all default nil = no filtering):
   :require-capabilities — #{kw} tool must declare ALL of these capabilities
   :exclude-capabilities — #{kw} tool must NOT declare ANY of these capabilities
   :modes                — #{kw} tool must be allowed in at least one of these modes"
  [{:keys [model system-prompt tools max-steps extensions settings
           require-capabilities exclude-capabilities modes]
    :or   {max-steps 20}}]
  (let [tool-filter-opts  {:require-capabilities require-capabilities
                           :exclude-capabilities exclude-capabilities
                           :modes                modes}
        events            (create-event-bus)
        ;; :tools must be a map of {name → tool-object} or nil.
        ;; Vectors/arrays are silently ignored to prevent merge corruption.
        extra-tools       (if (and tools (map? tools)) tools {})
        ;; Apply capability/mode filters before building the registry.
        ;; When all filter opts are nil this is a no-op (fast path).
        filtered-builtins (filter-tools-by-policy builtin-tools tool-filter-opts)
        filtered-extras   (filter-tools-by-policy extra-tools tool-filter-opts)
        tool-registry     (create-registry (merge filtered-builtins filtered-extras))
        ;; agent-ref is an atom set after agent creation so the pipeline
        ;; can lazily create extension contexts during tool execution.
        agent-ref         (atom nil)
        middleware        (create-pipeline events nil agent-ref settings)
        steer-queue       (atom [])
        follow-queue      (atom [])
        retry-state       (atom nil)   ;; nil | {:reason str :inject [msg]}
        hooks-cleanup     (atom nil)   ;; cleanup thunk from register-hooks, nil until hooks are loaded
        commands          (atom {})
        shortcuts         (atom {})
        ;; Keybinding registry — action-id → combo, built from defaults
        ;; + optional user overrides (applied later by cli.cljs).
        keybinding-registry (atom (kbr/create-registry))
        ;; Autocomplete provider registry — fresh per agent.
        autocomplete-registry (ac/create-provider-registry)
        ;; Phase 2 additions
        provider-registry (create-provider-registry builtin-providers)
        thinking-level    (atom "off")
        abort-controller  (atom (js/AbortController.))
        inter-events      (create-event-bus)  ;; Inter-extension communication bus
        flags             (atom {})           ;; Extension CLI flags {name → {:description :type :default :value}}
        context-providers (atom {})           ;; name → {:priority :estimate :provide}
        model-registry    (create-model-registry)
        block-renderers   (atom {})
        mention-providers (atom {})
        state             (atom {:messages           []
                                 :model              model
                                 :active-tools       (set (keys filtered-builtins))
                                 :total-input-tokens  0
                                 :total-output-tokens 0
                                 :total-cost          0.0
                                 :turn-count          0
                                 :active-executions   #{}
                                 :tool-calls          {}
                                 :active-skills       #{}
                                 :active-role         :default})
        ;; Event-sourced store shares the same atom as :state
        store             (create-agent-store @state state)]
    (let [agent {:events            events
                 :config            {:model                model
                                     :system-prompt        system-prompt
                                     :max-steps            max-steps
                                     :require-capabilities require-capabilities
                                     :exclude-capabilities exclude-capabilities
                                     :modes                modes}
                 :tool-registry     tool-registry
                 :middleware        middleware
                 :steer-queue       steer-queue
                 :follow-queue      follow-queue
                 :retry-state       retry-state
                 :hooks-cleanup     hooks-cleanup
                 :commands          commands
                 :shortcuts         shortcuts
                 :keybinding-registry keybinding-registry
                 :autocomplete-registry autocomplete-registry
                 :state             state
                 :store             store
                 :extensions        extensions
                 ;; Phase 2 additions
                 :provider-registry provider-registry
                 :thinking-level    thinking-level
                 :abort-controller  abort-controller
                 :inter-events      inter-events
                 :flags             flags
                 :context-providers context-providers
                 :model-registry    model-registry
                 :block-renderers   block-renderers
                 :mention-providers  mention-providers
                 ;; Settings manager (injected by cli.cljs)
                 :settings          settings
                 ;; Session is attached later by cli.cljs
                 :session           (atom nil)}]
      ;; Set agent-ref so the middleware pipeline can create extension contexts
      (reset! agent-ref agent)
      agent)))
