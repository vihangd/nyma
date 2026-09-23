(ns agent.tool-registry
  "Active/inactive tool management."
  (:require ["ai" :refer [jsonSchema]]))

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
  "Manage tools: built-in + extension-registered. Tracks active state."
  [initial-tools]
  (let [_          (doseq [[_ t] initial-tools] (normalize-tool! t))
        tools      (atom initial-tools)
        active     (atom (set (keys initial-tools)))
        overridden (atom {})  ;; name → original tool (preserved on override)

        register-fn   (fn [name t]
                        (normalize-tool! t)
                        ;; Returns who owns the restore, so a scope sweep can
                        ;; tell "I saved the original" from "someone else did":
                        ;;   :new        — name did not exist; unregister removes it
                        ;;   :owner      — saved the original; unregister restores it
                        ;;   :reoverride — an original was already saved (the
                        ;;                 stub→wrapper double-override pattern)
                        (let [existed?  (contains? @tools name)
                              had-orig? (contains? @overridden name)]
                          ;; If overriding an existing tool, preserve the original
                          (when (and existed? (not had-orig?))
                            (swap! overridden assoc name (get @tools name)))
                          ;; Attach __original so overriding tools can chain
                          (when-let [orig (get @overridden name)]
                            (set! (.-__original t) orig))
                          (swap! tools assoc name t)
                          (swap! active conj name)
                          (cond had-orig? :reoverride existed? :owner :else :new)))
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

    reg))
