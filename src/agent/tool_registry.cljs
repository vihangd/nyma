(ns agent.tool-registry
  (:require ["ai" :refer [jsonSchema]]
            [agent.protocols :refer [IToolProvider_provide_tools
                                     IToolProvider_register_tool
                                     IToolProvider_unregister_tool
                                     IToolProvider_set_active_tools
                                     IToolProvider_get_active_tools]]))

(defn- raw-json-schema?
  "True if `s` looks like a plain JSON Schema object — has a `type`
   field but lacks the AI-SDK Standard Schema marker (Zod / wrapped
   schemas have a `~standard` property). These crash inside `asSchema`
   later because AI SDK calls `schema()` expecting a callable."
  [s]
  (and s
       (= (js* "typeof ~{}" s) "object")
       (some? (.-type s))
       (nil? (aget s "~standard"))
       (nil? (.-validate s))
       (nil? (.-jsonSchema s))))

(defn- normalize-tool!
  "Mutate `t` so it has a callable `inputSchema` the AI SDK can use:
   - migrate `:parameters` → `:inputSchema` (old AI SDK key)
   - wrap raw JSON Schema objects with `jsonSchema(...)`
   Idempotent: safe to call on already-wrapped or Zod-shaped schemas."
  [t]
  (when t
    (when (and (nil? (.-inputSchema t)) (some? (.-parameters t)))
      (set! (.-inputSchema t) (.-parameters t)))
    (let [s (.-inputSchema t)]
      (when (raw-json-schema? s)
        (set! (.-inputSchema t) (jsonSchema s))))
    t))

(defn create-registry
  "Manage tools: built-in + extension-registered. Tracks active state.
   Conforms to IToolProvider protocol."
  [initial-tools]
  (let [_          (doseq [[_ t] initial-tools] (normalize-tool! t))
        tools      (atom initial-tools)
        active     (atom (set (keys initial-tools)))
        overridden (atom {})  ;; name → original tool (preserved on override)

        register-fn   (fn [name t]
                        (normalize-tool! t)
                        ;; If overriding an existing tool, preserve the original
                        (when (and (contains? @tools name) (not (contains? @overridden name)))
                          (swap! overridden assoc name (get @tools name)))
                        ;; Attach __original so overriding tools can chain
                        (when-let [orig (get @overridden name)]
                          (set! (.-__original t) orig))
                        (swap! tools assoc name t)
                        (swap! active conj name))
        unregister-fn (fn [name]
                        ;; Restore original if one was saved (tool override pattern)
                        (if-let [original (get @overridden name)]
                          (do (swap! tools assoc name original)
                              (swap! overridden dissoc name))
                          (do (swap! tools dissoc name)
                              (swap! active disj name))))
        set-active-fn (fn [names] (reset! active (set names)))
        get-active-fn (fn []
                        (let [ts @tools
                              ac @active]
                          (into {} (filter (fn [[k _]] (contains? ac k)) ts))))
        all-fn        (fn [] @tools)

        reg {:register   register-fn
             :unregister unregister-fn
             :set-active set-active-fn
             :get-active get-active-fn
             :all        all-fn}]

    ;; Protocol conformance
    (aset reg IToolProvider_provide_tools (fn [_] (all-fn)))
    (aset reg IToolProvider_register_tool (fn [_ name t] (register-fn name t)))
    (aset reg IToolProvider_unregister_tool (fn [_ name] (unregister-fn name)))
    (aset reg IToolProvider_set_active_tools (fn [_ names] (set-active-fn names)))
    (aset reg IToolProvider_get_active_tools (fn [_] (get-active-fn)))
    reg))
