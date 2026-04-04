(ns agent.providers.registry)

(defn create-provider-registry
  "Create a provider registry for managing LLM providers.
   Initial providers is a map of {name → {:create-model (fn [model-id] → model-obj), ...}}.
   Returns a map with register/unregister/get/list/resolve functions."
  [initial-providers]
  (let [providers (atom (or initial-providers {}))]
    {:register   (fn [name config]
                   (swap! providers assoc name config))
     :unregister (fn [name]
                   (swap! providers dissoc name))
     :get        (fn [name]
                   (get @providers name))
     :list       (fn []
                   @providers)
     :resolve    (fn [provider-name model-id]
                   (if-let [p (get @providers provider-name)]
                     ((:create-model p) model-id)
                     (throw (js/Error. (str "Unknown provider: " provider-name)))))}))
