(ns agent.extensions.model-roles.policy
  "Pure permission-policy resolution for modes-as-roles. A role may carry:
     :permissions {tool      → allow|ask|deny}   — per-tool (highest precedence)
     :policy      {category  → allow|ask|deny}   — by tool CATEGORY (the mode axis)
   categorize-tool (middleware) supplies the category as data.category on
   permission_request: exec|write|read|network|other.")

;; The permission-mode roles, in cycle order (Shift+Tab analog).
(def mode-cycle ["default" "accept-edits" "plan" "full-auto"])
(def mode-set #{"default" "accept-edits" "plan" "full-auto"})

(defn mode? [x] (contains? mode-set (str x)))

(defn next-mode
  "The next mode after `cur` in cycle order (wraps; unknown → first)."
  [cur]
  (let [i (.indexOf mode-cycle (str cur))]
    (nth mode-cycle (mod (inc i) (count mode-cycle)))))

(defn resolve-initial-mode
  "The startup :permission-mode: an explicit VALID `requested` mode wins; else
   the headless-aware default (full-auto for headless / default for interactive).
   An invalid `requested` falls back to that default — never dead-ends a headless
   run on an ask it cannot prompt for."
  [requested headless?]
  (let [fallback (if headless? "full-auto" "default")]
    (cond
      (nil? requested)   fallback
      (mode? requested)  requested
      :else              fallback)))

(defn inherit-default-model
  "A SHIPPED role (user-set? false) whose provider differs from the user's
   default provider can't actually run (no creds for it) — re-point it at the
   configured default model so it inherits a usable model instead of leaking a
   cross-provider preset. Model-less roles (no :provider — permission modes) are
   left alone (they preserve the active model). Pure."
  [cfg user-set? def-provider def-model]
  (let [prov (:provider cfg)]
    (if (and (not user-set?) def-provider prov
             (not= (str prov) (str def-provider)))
      (assoc cfg :provider def-provider :model def-model)
      cfg)))

(defn build-roles
  "Merge `user-roles` onto `default-roles` with FIELD-level merge (a user role
   overrides only the keys it sets — so a model-only override keeps the shipped
   role's :allowed-tools/:permissions) + provider-aware inherit for shipped roles
   on a non-default provider. Pure; exposed for tests."
  [default-roles user-roles def-provider def-model]
  (let [names (distinct (concat (keys default-roles) (keys user-roles)))]
    (into {}
          (map (fn [nm]
                 (let [usr    (get user-roles nm)
                       merged (merge (get default-roles nm) usr)]
                   [nm (inherit-default-model merged (some? usr) def-provider def-model)]))
               names))))

(defn combine-allowed-tools
  "Combine two :allowed-tools restrictions (mode axis `ma`, role axis `ra`) into
   the effective allowed set. When BOTH restrict → their intersection (most
   restrictive; may be EMPTY = no tools). When one restricts → that one. When
   neither → nil (no restriction). An empty vector and nil are deliberately
   distinct: [] means 'zero tools allowed', nil means 'no opinion'."
  [ma ra]
  (cond
    (and (seq ma) (seq ra)) (vec (filter (set ma) ra))
    (seq ma) (vec ma)
    (seq ra) (vec ra)
    :else nil))

(defn resolve-decision
  "Decision for (role-cfg, tool, category): an explicit per-tool :permissions
   entry wins; else the mode :policy by category; else nil (gate default allow).
   Tolerates keyword (CLJS) and string (user JSON) keys."
  [role-cfg tool category]
  (let [perms  (:permissions role-cfg)
        policy (:policy role-cfg)]
    (or (get perms (str tool))
        (get policy (str category)))))

;; ── what a mode actually does, in one line ───────────────────────────────
;; `/mode` listed four names and nothing else, so choosing between them meant
;; reading model_roles' source. The three categories the gate can ask about,
;; each probed through the tool the gate would actually see: :plan states its
;; restrictions per-TOOL (write/edit/bash deny) rather than as a :policy, so
;; probing by category alone would report it as unrestricted.

(def policy-categories ["write" "exec" "network"])

(def ^:private category-probe
  {"write" "write" "exec" "bash" "network" "web_fetch"})

(defn policy-summary
  "Pure: role-cfg → [[category decision] …] over `policy-categories`, in that
   fixed order. No entry from either axis means the gate's default, allow."
  [cfg]
  (mapv (fn [c] [c (str (or (resolve-decision cfg (get category-probe c) c) "allow"))])
        policy-categories))

(defn policy-line
  "Pure: role-cfg → \"write ask, exec ask, network ask\"."
  [cfg]
  (.join (.map (clj->js (policy-summary cfg))
               (fn [pair] (str (aget pair 0) " " (aget pair 1))))
         ", "))
