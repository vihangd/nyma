(ns agent.extensions.model-roles
  "Model roles: named presets (default, fast, deep, plan, commit) that map
   to provider/model pairs. Switch with /role <name>."
  (:require [clojure.string :as str]))

(def ^:private default-roles
  {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
   :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
   :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
   :plan    {:provider "anthropic" :model "claude-opus-4-20250514"}
   :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"}})

(defn- js-obj->map
  "Shallow-convert a plain JS object (from JSON.parse) to a CLJS map.
   Values are left as-is (may themselves be JS objects)."
  [obj]
  (when obj
    (reduce (fn [m k] (assoc m k (aget obj k)))
            {}
            (js/Object.keys obj))))

(defn- role-entry->clj
  "Normalize a single role config — accepts both CLJS maps and JS objects."
  [v]
  (if (map? v) v (js-obj->map v)))

(defn- get-roles
  "Read roles map from settings, merged on top of built-in defaults.
   User-defined roles override defaults; new role names are additive.
   Accepts both CLJS maps and plain JS objects (from JSON.parse)."
  [api]
  (let [settings   (when-let [get-fn (.-getSettings api)] (get-fn))
        raw-roles  (or (when settings (get settings "roles"))
                       (when settings (get settings :roles)))
        ;; JSON.parse returns plain JS objects; (map? js-obj) is false in squint.
        user-roles (cond
                     (map? raw-roles)    raw-roles
                     (some? raw-roles)   (js-obj->map raw-roles)
                     :else               nil)]
    (if user-roles
      (merge default-roles
             (into {} (map (fn [[k v]] [k (role-entry->clj v)]) user-roles)))
      default-roles)))

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

        ;; Subscribe to model_resolve — ensure config.model reflects the active role.
        ;; /role already calls .setModel which updates config.model; we return nil
        ;; so the loop uses config.model rather than accidentally overriding it with
        ;; the stale default that arrived in data.default.
        on-resolve
        (fn [_data]
          (let [state    (.getState api)
                role     (or (:active-role state) :default)
                roles    (get-roles api)
                role-cfg (get roles role)]
            (when (and role-cfg (not= role "default"))
              (let [provider (or (:provider role-cfg) (get role-cfg "provider"))
                    model-id (or (:model role-cfg) (get role-cfg "model"))]
                (when (and provider model-id)
                  ;; Re-apply setModel each turn so the correct provider model is
                  ;; always in config even if something else reset it.
                  (.setModel api (str provider "/" model-id)))))
            ;; Return nil — loop falls back to config.model which setModel just set.
            nil))

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
