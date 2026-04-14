(ns agent.extensions.model-roles
  "Model roles: named presets (default, fast, deep, plan, commit) that map
   to provider/model pairs. Switch with /role <name>."
  (:require [clojure.string :as str]))

(defn- get-roles
  "Read roles map from settings, falling back to built-in defaults."
  [api]
  (let [settings (when-let [get-fn (.-getSettings api)] (get-fn))
        roles    (or (get settings "roles") (get settings :roles))]
    (if (and roles (map? roles))
      roles
      ;; Inline fallback if settings unavailable
      {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
       :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
       :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
       :plan    {:provider "anthropic" :model "claude-opus-4-20250514"}
       :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"}})))

(defn- resolve-role-model
  "Given a role config {:provider :model}, resolve the model object via provider registry."
  [api role-config]
  (let [provider (or (:provider role-config) (get role-config "provider"))
        model-id (or (:model role-config) (get role-config "model"))]
    (when (and provider model-id)
      ;; Use setModel which handles resolution through the provider registry
      (.setModel api (str provider "/" model-id)))))

(defn- format-role-list
  "Format roles map for display."
  [roles active-role]
  (str/join "\n"
            (map (fn [[rname rconf]]
                   (let [model-id (or (:model rconf) (get rconf "model") "?")
                         provider (or (:provider rconf) (get rconf "provider") "?")
                 ;; Squint: keywords ARE strings, so :default == "default".
                 ;; rname (map key) and active-role (state) are both strings.
                         marker   (if (= rname active-role) " ◀" "")]
                     (let [allowed (or (:allowed-tools rconf) (get rconf "allowed-tools"))
                           tools-hint (when (seq allowed) (str " [" (count allowed) " tools]"))]
                       (str "  " rname " → " provider "/" model-id
                            (or tools-hint "") marker))))
                 roles)))

(defn ^:export default [api]
  (let [handlers (atom [])

        ;; Subscribe to model_resolve — override model based on active role
        on-resolve
        (fn [data]
          (let [state    (.getState api)
                role     (or (:active-role state) :default)
                roles    (get-roles api)
                ;; Squint: keywords ARE strings, so (get roles "default")
                ;; matches a map keyed by :default.
                role-cfg (get roles role)]
            (when role-cfg
              (let [provider (or (:provider role-cfg) (get role-cfg "provider"))
                    model-id (or (:model role-cfg) (get role-cfg "model"))]
                ;; Only override if the role specifies a model
                (when model-id
                  #js {:model (.-default data)})))))

        ;; tool_access_check — restrict tools based on active role
        on-tool-access
        (fn [_data]
          (let [state    (.getState api)
                role     (or (:active-role state) :default)
                roles    (get-roles api)
                role-cfg (get roles role)
                allowed  (or (:allowed-tools role-cfg)
                             (get role-cfg "allowed-tools"))]
            (when (seq allowed)
              #js {:allowed (clj->js allowed)})))

        ;; permission_request — deny/allow based on role permission map
        on-permission
        (fn [data]
          (let [state    (.getState api)
                role     (or (:active-role state) :default)
                roles    (get-roles api)
                role-cfg (get roles role)
                perms    (or (:permissions role-cfg) (get role-cfg "permissions"))
                tool     (str (.-tool data))]
            (when-let [decision (get perms tool)]
              #js {:decision (str decision)})))]

    (.on api "model_resolve" on-resolve)
    (swap! handlers conj ["model_resolve" on-resolve])

    (.on api "tool_access_check" on-tool-access)
    (swap! handlers conj ["tool_access_check" on-tool-access])

    (.on api "permission_request" on-permission)
    (swap! handlers conj ["permission_request" on-permission])

    ;; /role command
    (.registerCommand api "role"
                      #js {:description "Switch model role. Usage: /role [name]"
                           :handler
                           (fn [args ctx]
                             (let [role-name (first args)
                                   roles     (get-roles api)
                                   state     (.getState api)
                                   current   (or (:active-role state) :default)]
                               (cond
                 ;; No args — show current role and list
                                 (empty? role-name)
                                 ;; Squint: keywords are strings, so `current` is already the name.
                                 (let [msg (str "Active role: " current "\n\n"
                                                "Available roles:\n"
                                                (format-role-list roles current)
                                                "\n\nUsage: /role <name>")]
                                   (.notify (.-ui ctx) msg "info"))

                 ;; "reset" — revert to default
                                 (= role-name "reset")
                                 (do
                                   (.dispatch api "role-changed" {:role :default})
                                   (swap! (.-__state-atom api) assoc :active-role :default)
                                   (.notify (.-ui ctx) "Role reset to default" "info"))

                 ;; Known role — switch (keywords are strings in squint, so
                 ;; role-name is already the lookup key).
                                 (get roles role-name)
                                 (let [role-cfg (get roles role-name)
                                       model-id (or (:model role-cfg) (get role-cfg "model"))
                                       provider (or (:provider role-cfg) (get role-cfg "provider"))]
                                   (swap! (.-__state-atom api) assoc :active-role role-name)
                                   (when (and provider model-id)
                                     (.setModel api (str provider "/" model-id)))
                                   (.notify (.-ui ctx) (str "Role: " role-name " → " provider "/" model-id)))

                 ;; Unknown role
                                 :else
                                 (.notify (.-ui ctx)
                                          (str "Unknown role: \"" role-name "\". Available: "
                                               (str/join ", " (keys roles)))
                                          "error"))))})

    ;; /roles command — list all
    (.registerCommand api "roles"
                      #js {:description "List available model roles"
                           :handler
                           (fn [_args ctx]
                             (let [roles   (get-roles api)
                                   state   (.getState api)
                                   current (or (:active-role state) :default)]
                               (.notify (.-ui ctx)
                                        (str "Model Roles:\n" (format-role-list roles current)))))})

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterCommand api "role")
      (.unregisterCommand api "roles"))))
