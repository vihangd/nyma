(ns agent.extensions.model-roles
  "Model roles: named presets (default, fast, deep, plan, commit) that map
   to provider/model pairs. Switch with /role <name>."
  (:require [clojure.string :as str]
            [agent.debug :as d]
            [agent.events :as events]
            [agent.extensions.model-roles.policy :as policy]
            [agent.extensions.model-roles.status-segment :as status-seg]
            [agent.extensions.model-roles.features.plan-mode :as plan-mode]
            [agent.extensions.model-roles.features.escalate :as escalate]))

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

(defn cycle-binding
  "Key that cycles to the next role. Settings-driven so it can be moved or
   disabled (empty string) without a code change; ctrl+r is deliberately not
   the default because prompt_history registers it."
  [api]
  (let [settings (.settings api)
        ;; Top-level, same as `roles` (index/get-roles) and as documented in
        ;; the README's settings table. A nested "model-roles" map is also
        ;; accepted so either spelling works.
        mr       (get settings "model-roles")
        k        (or (get settings "cycle-key")
                     (when mr (get mr "cycle-key")))]
    (if (some? k) (str k) "ctrl+g")))

(defn cyclable-role-names
  "Role names the cycle shortcut may visit: MODEL roles only.

   `get-roles` also returns the permission MODES (accept-edits, full-auto,
   plan), and `on-permission` resolves a decision from `:active-role` where
   allow beats ask. Cycling onto `full-auto` would therefore switch off the
   approval prompt for writes, shell and network — with no feedback beyond a
   role name, and no model in the notification since those roles carry none.
   Landing on `plan` is the mirror image, silently denying them.

   A role without a model is not a model switch, so it is not cyclable."
  [roles]
  (vec (keep (fn [[k cfg]]
               (when (:model cfg) k))
             roles)))

(defn- get-roles
  "Read roles from settings, merged onto the built-in defaults via
   policy/build-roles: FIELD-level merge (a model-only override keeps the shipped
   role's :allowed-tools/:permissions) + provider-aware inherit (a shipped role on
   a non-default provider inherits the default model, so no cross-provider leak).
   Accepts both CLJS maps and plain JS objects (from JSON.parse)."
  [api]
  (let [settings   (.settings api)
        raw-roles  (get settings "roles")
        def-prov   (:provider settings)
        def-model  (:model settings)
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
  (let [provider (:provider role-config)
        model-id (:model role-config)]
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
                           (:model rconf)
                           (str (or (:provider rconf) "?")
                                "/" (:model rconf))
                           ;; model-less role (a permission mode) — no model pin.
                           :else "(inherits model)")
                 ;; rname (map key) and active-role (state) are both strings.
                         marker   (if (= rname active-role) " ◀" "")]
                     (let [allowed (:allowed-tools rconf)
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
        esc-deactivate  (atom nil)
        seg-deactivate  (atom nil)

        ;; THE role switch. /role, the cycle shortcut and the `role_change`
        ;; event all come here, so every path sets the model and clears the
        ;; escalation together. Other extensions (spec_driven's phase binding,
        ;; agent_shell's plan handoff) emit `role_change` instead of writing
        ;; :active-role — this extension owns the key. Returns true when the
        ;; role was known.
        activate-role!
        (fn [role-name notify & [{:keys [keep-model?]}]]
          (let [roles (get-roles api)
                name  (str role-name)]
            (cond
              ;; "reset" / "default" — revert to the CONFIGURED default model
              ;; (-m / settings :model), not the role's hardcoded model.
              (or (= name "reset") (= name "default"))
              (let [spec (plan-mode/default-model-spec api)]
                (.dispatch api "role-changed" {:role :default})
                (swap! (.-__state-atom api) assoc :active-role :default :escalated-to nil)
                ;; /role reset reverts to the configured model. A phase that
                ;; merely maps to "default" (spec_driven via role_change)
                ;; must not undo a mid-session /model choice.
                (when (and spec (not keep-model?)) (.setModel api spec))
                (notify (str "Role: default" (when-not keep-model? (str " → " (or spec "default model")))) "info")
                true)

              (get roles name)
              (let [role-cfg (get roles name)
                    model-id (:model role-cfg)
                    provider (:provider role-cfg)]
                (swap! (.-__state-atom api) assoc :active-role name :escalated-to nil)
                (when (and provider model-id)
                  (.setModel api (str provider "/" model-id)))
                (notify (str "Role: " name
                             (when (and provider model-id) (str " → " provider "/" model-id)))
                        "info")
                true)

              :else false)))
        ;; The scoped ui getter always returns an object; only the TUI fills
        ;; in :notify. Headless hosts (-p, rpc) get the stub.
        ui-notify (fn [m lvl] (let [ui (.-ui api)]
                                (when (and ui (.-notify ui)) (.notify ui m lvl))))
        on-role-change
        (fn [data]
          (let [r (and data (or (.-role data) (aget data "role")))]
            (when (and r (not (activate-role! r ui-notify {:keep-model? true})))
              ;; A role with no config (spec_driven's "advisor" when the
              ;; user's :roles map replaced the defaults): still bind the
              ;; name so the status line and policy see it; no model switch.
              (swap! (.-__state-atom api) assoc :active-role (str r) :escalated-to nil)
              (d/warn "model-roles" (str "role_change: no model configured for role \"" r "\"")))))

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
              (let [provider (:provider role-cfg)
                    model-id (:model role-cfg)]
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
                ma       (:allowed-tools mode-cfg)
                ra       (:allowed-tools role-cfg)
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
    (.on api "role_change" on-role-change)
    (swap! handlers conj ["role_change" on-role-change])

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

                 ;; reset/default or a known role — the one switch path.
                                 (activate-role! role-name (fn [m lvl] (.notify (.-ui ctx) m lvl)))
                                 nil

                 ;; Unknown role
                                 :else
                                 (.notify (.-ui ctx)
                                          (str "Unknown role: \"" role-name "\". Available: "
                                               (str/join ", " (keys roles)))
                                          "error"))))})

    ;; One-keystroke role cycling. oh-my-pi binds alt+p to cycle models for the
    ;; active role; nyma's roles each carry one model, so cycling roles IS the
    ;; model switch. Binding is settings-driven (`model-roles.cycle-key`) rather
    ;; than hardcoded; the default avoids ctrl+r, which prompt_history owns.
    ;; `.registerShortcut` is capability-gated and absent on non-TUI hosts
    ;; (RPC, headless, tests), where it comes through undefined.
    (let [cycle-key (cycle-binding api)]
      (when (and (seq cycle-key) (.-registerShortcut api))
        (.registerShortcut
         api cycle-key
         (fn []
           (let [roles   (get-roles api)
                 names   (cyclable-role-names roles)
                 current (str (or (:active-role (.getState api)) "default"))
                 idx     (.indexOf (clj->js names) current)
                 next-r  (when (seq names)
                           (nth names (mod (inc idx) (count names))))]
             (when next-r
               (activate-role! next-r ui-notify)))))))

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

    ;; Escalation: stall → stronger model, provider error → next in the chain.
    (reset! esc-deactivate (escalate/activate api))

    ;; Status-line segments: the model role (plain) + the permission mode
    ;; (color-coded), shown together — replaces the core inline [role].
    (reset! seg-deactivate
            (status-seg/register! api
                                  (fn [] (current-role api))
                                  (fn [] (current-mode api))
                                  (fn [] (:escalated-to (.getState api)))))

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterCommand api "role")
      (.unregisterCommand api "roles")
      (.unregisterCommand api "mode")
      ;; Without this the binding survives deactivate/reload pointing at a
      ;; stale closure, and re-activating double-binds it.
      (let [k (cycle-binding api)]
        (when (and (seq k) (.-unregisterShortcut api))
          (.unregisterShortcut api k)))
      (when @plan-deactivate (@plan-deactivate))
      (when @esc-deactivate (@esc-deactivate))
      (when @seg-deactivate (@seg-deactivate)))))
