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
        ;; name → STACK of displaced tools, oldest first. A stack rather than one
        ;; slot because two extensions can override the same tool, and this used
        ;; to be written once: the second overrider's `__original` was then the
        ;; TRUE NATIVE rather than the wrapper it displaced, and that wrapper was
        ;; dropped from `tools` with nothing said.
        ;;
        ;; It cost a session-long silent failure. small-model's read-guard wraps
        ;; `read` to record which paths have been read and `write`/`edit` to
        ;; refuse an unread overwrite; mcp_client then overrides `read` and `edit`
        ;; onto lean-ctx, and activates later (async, after servers connect). So
        ;; the recorder never ran, the edit guard was discarded, and the write
        ;; guard survived consulting a permanently empty set — every write onto an
        ;; existing file refused, all session, while reporting success.
        overridden (atom {})

        register-fn   (fn [name t]
                        (normalize-tool! t)
                        ;; Returns who owes the restore, so a scope sweep can
                        ;; unwind exactly its own layer:
                        ;;   :new        — name did not exist; unregister removes it
                        ;;   :owner      — displaced the original
                        ;;   :reoverride — displaced someone else's override
                        ;; The last two both owe exactly one pop.
                        (let [displaced (get @tools name)
                              existed?  (contains? @tools name)
                              depth     (count (get @overridden name []))]
                          ;; Chain to what we displaced, not past it.
                          (when existed?
                            (swap! overridden update name (fnil conj []) displaced)
                            (set! (.-__original t) displaced))
                          (swap! tools assoc name t)
                          (swap! active conj name)
                          (cond (pos? depth) :reoverride existed? :owner :else :new)))
        unregister-fn (fn [name]
                        ;; Pop ONE layer. Restoring straight to the native would
                        ;; delete every wrapper installed after it.
                        (let [stack (get @overridden name)]
                          (if (seq stack)
                            (let [under (peek stack)
                                  rest' (pop stack)]
                              (swap! tools assoc name under)
                              (if (seq rest')
                                (swap! overridden assoc name rest')
                                (swap! overridden dissoc name)))
                            (do (swap! tools dissoc name)
                                (swap! active disj name)))))
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
