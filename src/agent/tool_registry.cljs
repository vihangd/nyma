(ns agent.tool-registry)

(defn create-registry
  "Manage tools: built-in + extension-registered. Tracks active state."
  [initial-tools]
  (let [tools  (atom initial-tools)
        active (atom (set (keys initial-tools)))]
    {:register   (fn [name t]
                   (swap! tools assoc name t)
                   (swap! active conj name))
     :unregister (fn [name]
                   (swap! tools dissoc name)
                   (swap! active disj name))
     :set-active (fn [names] (reset! active (set names)))
     :get-active (fn []
                   (let [ts @tools
                         ac @active]
                     (into {} (filter (fn [[k _]] (contains? ac k)) ts))))
     :all        (fn [] @tools)}))
