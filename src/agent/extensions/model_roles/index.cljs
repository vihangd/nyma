(ns agent.extensions.model-roles
  "Model roles: named presets (default, fast, deep, plan, commit) that map
   to provider/model pairs. Switch with /role <name>."
  (:require [clojure.string :as str]
            [agent.events :as events]
            [agent.extensions.model-roles.policy :as policy]
            [agent.extensions.model-roles.status-segment :as status-seg]
            [agent.extensions.model-roles.features.plan-mode :as plan-mode]))

;; Permission MODES are roles too (modes-as-roles): a role may carry a :policy
;; mapping a tool CATEGORY (exec|write|read|network — from categorize-tool, the
;; value arrives as data.category on permission_request) to a decision
;; allow|ask|deny. The gate resolves a per-tool :permissions entry first, then
;; the :policy by category. Roles without a :policy yield no decision (the gate
;; defaults to allow) — so switching a MODEL role (fast/deep/…) or a read-only
;; subagent role never silently denies. accept-edits/full-auto are model-LESS
;; so they preserve whatever model is active (on-resolve skips setModel).
(def ^:private default-roles
  {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"
             :policy {"write" "ask" "exec" "ask" "network" "ask"}}
   :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
   :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
   ;; accept-edits: auto-approve edits, still ask before shell/network.
   :accept-edits {:policy {"write" "allow" "exec" "ask" "network" "ask"}}
   ;; full-auto: allow everything (Claude's bypass). No model → keep current.
   :full-auto    {:policy {"read" "allow" "write" "allow" "exec" "allow" "network" "allow"}}
   ;; :plan MUST carry its read-only restrictions here, not only in
   ;; settings/manager defaults: settings :roles is shallow-merged, so a user
   ;; who defines any custom roles REPLACES the manager defaults wholesale.
   ;; get-roles merges these defaults UNDER settings.roles, so this is the
   ;; floor that keeps plan mode read-only even when the user's roles map
   ;; omits :plan. Without it, enter! would switch to a :plan role with no
   ;; allowed-tools/permissions and plan mode could edit/write/bash.
   :plan    {:provider "anthropic" :model "claude-opus-4-20250514"
             :allowed-tools ["read" "glob" "grep" "ls" "think" "web_search" "web_fetch"]
             :permissions {"write" "deny" "edit" "deny" "bash" "deny"}}
   :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"}})

;; Permission-mode names + cycle order live in the policy ns (pure, testable).
(def ^:private mode-cycle policy/mode-cycle)
(def ^:private mode-set policy/mode-set)

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
  "Read roles from settings, merged onto the built-in defaults via
   policy/build-roles: FIELD-level merge (a model-only override keeps the shipped
   role's :allowed-tools/:permissions) + provider-aware inherit (a shipped role on
   a non-default provider inherits the default model, so no cross-provider leak).
   Accepts both CLJS maps and plain JS objects (from JSON.parse)."
  [api]
  (let [settings   (when-let [get-fn (.-getSettings api)] (get-fn))
        raw-roles  (or (when settings (get settings "roles"))
                       (when settings (get settings :roles)))
        def-prov   (when settings (or (:provider settings) (get settings "provider")))
        def-model  (when settings (or (:model settings) (get settings "model")))
        user-roles (cond
                     (map? raw-roles)    raw-roles
                     (some? raw-roles)   (js-obj->map raw-roles)
                     :else               nil)
        user-norm  (when user-roles
                     (into {} (map (fn [[k v]] [k (role-entry->clj v)]) user-roles)))]
    (if-not user-norm
      default-roles
      (policy/build-roles default-roles user-norm def-prov def-model))))

(defn- resolve-role-model
  "Given a role config {:provider :model}, resolve the model object via provider registry."
  [api role-config]
  (let [provider (or (:provider role-config) (get role-config "provider"))
        model-id (or (:model role-config) (get role-config "model"))]
    (when (and provider model-id)
      ;; Use setModel which handles resolution through the provider registry
      (.setModel api (str provider "/" model-id)))))

(defn- format-role-list
  "Format roles map for display. `default-spec` (a 'provider/model' string) is
   shown for the 'default' row so it reflects the CONFIGURED default (-m /
   settings :model), not the role's hardcoded model."
  [roles active-role & [default-spec]]
  (str/join "\n"
            (map (fn [[rname rconf]]
                   (let [;; Squint: keywords ARE strings, so :default == "default".
                         model-piece
                         (cond
                           (and (= rname "default") default-spec) (str default-spec)
                           (or (:model rconf) (get rconf "model"))
                           (str (or (:provider rconf) (get rconf "provider") "?")
                                "/" (or (:model rconf) (get rconf "model")))
                           ;; model-less role (a permission mode) — no model pin.
                           :else "(inherits model)")
                 ;; rname (map key) and active-role (state) are both strings.
                         marker   (if (= rname active-role) " ◀" "")]
                     (let [allowed (or (:allowed-tools rconf) (get rconf "allowed-tools"))
                           tools-hint (when (seq allowed) (str " [" (count allowed) " tools]"))]
                       (str "  " rname " → " model-piece
                            (or tools-hint "") marker))))
                 roles)))

;; ── permission modes (orthogonal to the model role) ──
;; :permission-mode is a SECOND state slot, independent of :active-role (the
;; model role). The mode drives the approval policy + plan gate; the role drives
;; the model. Both show on the status line, so "fast + full-auto" coexist.
(defn- current-mode
  "The active permission mode string. enter!/execute!/cancel! always set
   :permission-mode in lockstep with :plan-mode (enter! → \"plan\"), so the slot
   alone is authoritative — no separate :plan-mode branch needed."
  [api]
  (str (or (:permission-mode (.getState api)) "default")))

(defn- current-role
  "The active MODEL role string."
  [api]
  (str (or (:active-role (.getState api)) "default")))

(defn- next-mode [cur] (policy/next-mode cur))

(defn- switch-mode!
  "Switch the permission mode (NOT the model role). \"plan\" engages native plan
   mode (enter!); any other mode leaves plan mode first (discarding the draft)
   then sets :permission-mode. The model role is untouched."
  [api mode ctx]
  (let [state    (.getState api)
        in-plan? (:plan-mode state)
        ui       (.-ui ctx)]
    (cond
      (not (contains? mode-set mode))
      (.notify ui (str "Unknown mode: \"" mode "\". Modes: " (str/join ", " mode-cycle)) "error")

      (= mode "plan")
      (if in-plan? (.notify ui "Already in plan mode." "info") (plan-mode/enter! api))

      :else
      (do
        (when in-plan? (plan-mode/cancel! api))
        (swap! (.-__state-atom api) assoc :permission-mode mode)
        (.notify ui (str "Mode: " mode) "info")))))

(defn ^:export default [api]
  (let [handlers (atom [])
        plan-deactivate (atom nil)
        seg-deactivate  (atom nil)

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

        ;; tool_access_check — restrict tools from BOTH axes: the permission
        ;; mode (plan → read-only) AND the model role (commit → a subset). When
        ;; both restrict, the allowed set is their intersection (most restrictive
        ;; wins); when one restricts, use it; when neither, no restriction.
        on-tool-access
        (fn [_data]
          (let [state    (.getState api)
                roles    (get-roles api)
                mode-cfg (get roles (or (:permission-mode state) "default"))
                role-cfg (get roles (or (:active-role state) :default))
                ma       (or (:allowed-tools mode-cfg) (get mode-cfg "allowed-tools"))
                ra       (or (:allowed-tools role-cfg) (get role-cfg "allowed-tools"))
                ;; nil → no restriction; a vector (possibly EMPTY) → restrict to
                ;; it. An empty intersection MUST be returned ({:allowed []} =
                ;; zero tools), not dropped — else it resolves to "all tools".
                allowed  (policy/combine-allowed-tools ma ra)]
            (when (some? allowed)
              #js {:allowed (clj->js allowed)})))

        ;; permission_request — resolve a decision from BOTH axes and COMBINE them
        ;; by precedence (deny > allow > ask), so a permissive axis can't shadow a
        ;; restrictive one: a role's per-tool deny survives even under full-auto,
        ;; and plan's read-only deny survives a permissive role. (A plain `or`
        ;; would let whichever axis answered first win.) resolve-decision checks
        ;; per-tool :permissions first, then :policy by category; nil from both →
        ;; the gate default (allow).
        on-permission
        (fn [data]
          (let [state    (.getState api)
                roles    (get-roles api)
                mode-cfg (get roles (or (:permission-mode state) "default"))
                role-cfg (get roles (or (:active-role state) :default))
                tool     (str (.-tool data))
                category (str (or (.-category data) (get data "category")))
                mode-d   (policy/resolve-decision mode-cfg tool category)
                role-d   (policy/resolve-decision role-cfg tool category)]
            (when-let [decision (events/combine-decision mode-d role-d)]
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
                                                (format-role-list roles current (plan-mode/default-model-spec api))
                                                "\n\nUsage: /role <name>")]
                                   (.notify (.-ui ctx) msg "info"))

                 ;; "reset" / "default" — revert to the CONFIGURED default model
                 ;; (-m / settings :model), not the role's hardcoded model.
                                 (or (= role-name "reset") (= role-name "default"))
                                 (let [spec (plan-mode/default-model-spec api)]
                                   (.dispatch api "role-changed" {:role :default})
                                   (swap! (.-__state-atom api) assoc :active-role :default)
                                   (when spec (.setModel api spec))
                                   (.notify (.-ui ctx) (str "Role: default → " (or spec "default model")) "info"))

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
                                        (str "Model Roles:\n"
                                             (format-role-list roles current (plan-mode/default-model-spec api))))))})

    ;; /mode command — permission modes (modes-as-roles).
    (.registerCommand api "mode"
                      #js {:description "Switch permission mode: default | accept-edits | plan | full-auto | cycle"
                           :handler
                           (fn [args ctx]
                             (let [arg (.toLowerCase (str (or (first args) "")))]
                               (cond
                                 (= arg "")
                                 (.notify (.-ui ctx)
                                          (str "Active mode: " (current-mode api)
                                               "\nModes: " (str/join ", " mode-cycle)
                                               "\nUsage: /mode <name> | /mode cycle")
                                          "info")
                                 (= arg "cycle")
                                 (switch-mode! api (next-mode (current-mode api)) ctx)
                                 :else
                                 (switch-mode! api arg ctx))))})

    ;; Native plan mode (layered on the :plan role).
    (reset! plan-deactivate (plan-mode/activate api))

    ;; Status-line segments: the model role (plain) + the permission mode
    ;; (color-coded), shown together — replaces the core inline [role].
    (reset! seg-deactivate
            (status-seg/register! api
                                  (fn [] (current-role api))
                                  (fn [] (current-mode api))))

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterCommand api "role")
      (.unregisterCommand api "roles")
      (.unregisterCommand api "mode")
      (when @plan-deactivate (@plan-deactivate))
      (when @seg-deactivate (@seg-deactivate)))))
