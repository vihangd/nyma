(ns model-roles-modes.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            [agent.extension-loader :refer [deactivate-all]]
            [agent.events :refer [combine-decision]]
            [agent.extensions.model-roles.policy :as policy]
            [agent.extensions.model-roles.status-segment :as status-seg]
            [agent.extensions.model-roles.index :as mr]
            [agent.settings.manager :refer [defaults]]))

;; get-roles refinements: field-level merge + provider-aware inherit.
(def ^:private floor-roles
  {"default" {:provider "anthropic" :model "sonnet"}
   "deep"    {:provider "anthropic" :model "opus"}
   "plan"    {:provider "anthropic" :model "opus"
              :allowed-tools ["read" "grep"] :permissions {"write" "deny"}}
   "accept-edits" {:policy {"write" "allow"}}})

(describe "model-roles-modes:build-roles" (fn []
                                            (it "field-merge: a model-only user override keeps the shipped read-only gating"
                                                (fn []
                                                  (let [user  {"plan" {"provider" "minimax" "model" "m3"}}
                                                        roles (policy/build-roles floor-roles user "opencode-zen" "big-pickle")
                                                        plan  (get roles "plan")]
        ;; user's model wins, but allowed-tools/permissions survive
                                                    (-> (expect (:model plan)) (.toBe "m3"))
                                                    (-> (expect (:provider plan)) (.toBe "minimax"))
                                                    (-> (expect (count (:allowed-tools plan))) (.toBe 2))
                                                    (-> (expect (get (:permissions plan) "write")) (.toBe "deny")))))

                                            (it "provider-aware: a shipped role on a non-default provider inherits the default model"
                                                (fn []
                                                  (let [roles (policy/build-roles floor-roles {} "opencode-zen" "big-pickle")
                                                        deep  (get roles "deep")]
        ;; anthropic deep ≠ opencode-zen default → inherits big-pickle (no leak)
                                                    (-> (expect (:provider deep)) (.toBe "opencode-zen"))
                                                    (-> (expect (:model deep)) (.toBe "big-pickle")))))

                                            (it "no regression: matching-provider shipped presets are kept"
                                                (fn []
                                                  (let [roles (policy/build-roles floor-roles {} "anthropic" "sonnet")
                                                        deep  (get roles "deep")]
                                                    (-> (expect (:model deep)) (.toBe "opus")))))

                                            (it "a user-set role is always kept, even cross-provider"
                                                (fn []
                                                  (let [user  {"advisor" {"provider" "deepseek" "model" "v4"}}
                                                        roles (policy/build-roles floor-roles user "opencode-zen" "big-pickle")]
                                                    (-> (expect (or (:model (get roles "advisor")) (get (get roles "advisor") "model"))) (.toBe "v4")))))

                                            (it "model-less roles (permission modes) are left alone"
                                                (fn []
                                                  (let [roles (policy/build-roles floor-roles {} "opencode-zen" "big-pickle")
                                                        ae    (get roles "accept-edits")]
                                                    (-> (expect (:provider ae)) (.toBeNil))
                                                    (-> (expect (:policy ae)) (.toBeDefined)))))))

;; A1: tool-access combines the two axes' :allowed-tools as an intersection.
;; A DISJOINT intersection must be [] (zero tools), NOT nil (all tools).
(describe "model-roles-modes:combine-allowed-tools" (fn []
                                                      (it "disjoint mode+role sets → [] (deny all), not nil"
                                                          (fn []
                                                            (-> (expect (policy/combine-allowed-tools ["read" "grep"] ["bash" "write"]))
                                                                (.toEqual #js []))))
                                                      (it "overlapping sets → the intersection"
                                                          (fn []
                                                            (-> (expect (vec (policy/combine-allowed-tools ["read" "grep" "ls"] ["read" "bash"])))
                                                                (.toEqual #js ["read"]))))
                                                      (it "one axis restricts → that axis"
                                                          (fn []
                                                            (-> (expect (vec (policy/combine-allowed-tools ["read"] nil))) (.toEqual #js ["read"]))
                                                            (-> (expect (vec (policy/combine-allowed-tools nil ["bash"]))) (.toEqual #js ["bash"]))))
                                                      (it "neither restricts → nil (no restriction)"
                                                          (fn []
                                                            (-> (expect (policy/combine-allowed-tools nil nil)) (.toBeNil))
                                                            (-> (expect (policy/combine-allowed-tools [] [])) (.toBeNil))))))

;; A4: the startup permission-mode honors a valid flag, else a headless-aware
;; default; an INVALID flag falls back to that default (no headless dead-end).
(describe "model-roles-modes:resolve-initial-mode" (fn []
                                                     (it "no flag → headless full-auto / interactive default"
                                                         (fn []
                                                           (-> (expect (policy/resolve-initial-mode nil true)) (.toBe "full-auto"))
                                                           (-> (expect (policy/resolve-initial-mode nil false)) (.toBe "default"))))
                                                     (it "valid flag wins regardless of transport"
                                                         (fn []
                                                           (-> (expect (policy/resolve-initial-mode "accept-edits" true)) (.toBe "accept-edits"))))
                                                     (it "INVALID flag headless → full-auto (not default), so it doesn't dead-end"
                                                         (fn []
                                                           (-> (expect (policy/resolve-initial-mode "full_auto" true)) (.toBe "full-auto"))
                                                           (-> (expect (policy/resolve-initial-mode "bogus" false)) (.toBe "default"))))))

;; A2: a permissive MODE must not shadow a restrictive ROLE (and vice-versa).
;; This mirrors on-permission: combine the mode + role decisions by precedence.
(describe "model-roles-modes:two-axis-decision" (fn []
                                                  (it "full-auto mode allow does NOT shadow a role's per-tool bash deny"
                                                      (fn []
                                                        (let [mode-cfg (:full-auto (:roles defaults))            ; exec → allow
                                                              role-cfg {:permissions {"bash" "deny"}}
                                                              mode-d   (policy/resolve-decision mode-cfg "bash" "exec")
                                                              role-d   (policy/resolve-decision role-cfg "bash" "exec")]
                                                          (-> (expect mode-d) (.toBe "allow"))
                                                          (-> (expect role-d) (.toBe "deny"))
                                                          (-> (expect (combine-decision mode-d role-d)) (.toBe "deny")))))
                                                  (it "plan mode write-deny survives a role that would allow write"
                                                      (fn []
                                                        (let [mode-cfg (:plan (:roles defaults))                 ; write → deny
                                                              role-cfg {:permissions {"write" "allow"}}
                                                              d (combine-decision (policy/resolve-decision mode-cfg "write" "write")
                                                                                  (policy/resolve-decision role-cfg "write" "write"))]
                                                          (-> (expect d) (.toBe "deny")))))))

;;; ─── Modes: tool_access_check ───────────────────────────

(describe "model-roles-modes:tool-access-check" (fn []
                                                  (it "plan role has allowed-tools in defaults"
                                                      (fn []
                                                        (let [plan-role (:plan (:roles defaults))]
                                                          (-> (expect (:allowed-tools plan-role)) (.toBeDefined))
                                                          (-> (expect (count (:allowed-tools plan-role))) (.toBeGreaterThan 0)))))

                                                  (it "plan role does not include write/edit/bash in allowed-tools"
                                                      (fn []
                                                        (let [allowed (set (:allowed-tools (:plan (:roles defaults))))]
                                                          (-> (expect (contains? allowed "write")) (.toBe false))
                                                          (-> (expect (contains? allowed "edit")) (.toBe false))
                                                          (-> (expect (contains? allowed "bash")) (.toBe false)))))

                                                  (it "plan role includes read-only tools"
                                                      (fn []
                                                        (let [allowed (set (:allowed-tools (:plan (:roles defaults))))]
                                                          (-> (expect (contains? allowed "read")) (.toBe true))
                                                          (-> (expect (contains? allowed "grep")) (.toBe true))
                                                          (-> (expect (contains? allowed "glob")) (.toBe true)))))

                                                  (it "default role has no allowed-tools restriction"
                                                      (fn []
                                                        (let [default-role (:default (:roles defaults))]
                                                          (-> (expect (:allowed-tools default-role)) (.toBeUndefined)))))

                                                  (it "commit role has specific allowed-tools"
                                                      (fn []
                                                        (let [commit-role (:commit (:roles defaults))]
                                                          (-> (expect (:allowed-tools commit-role)) (.toBeDefined))
                                                          (-> (expect (some #{"bash"} (:allowed-tools commit-role))) (.toBeTruthy)))))))

;;; ─── Modes: permission_request ──────────────────────────

(describe "model-roles-modes:permissions" (fn []
                                            (it "plan role has permissions that deny write/edit/bash"
                                                (fn []
                                                  (let [perms (:permissions (:plan (:roles defaults)))]
                                                    (-> (expect (get perms "write")) (.toBe "deny"))
                                                    (-> (expect (get perms "edit")) (.toBe "deny"))
                                                    (-> (expect (get perms "bash")) (.toBe "deny")))))

                                            (it "default role has no permissions map"
                                                (fn []
                                                  (let [default-role (:default (:roles defaults))]
                                                    (-> (expect (:permissions default-role)) (.toBeUndefined)))))

                                            (it "tool_access_check event returns allowed set for restricted roles"
                                                (fn []
                                                  (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                                        events (:events agent)]
        ;; Simulate a handler that returns restricted tools
                                                    ((:on events) "tool_access_check"
                                                                  (fn [_] #js {:allowed #js ["read" "grep"]}))
                                                    (let [p ((:emit-collect events) "tool_access_check"
                                                                                    #js {:tools #js ["read" "write" "bash" "grep"]})]
                                                      (.then p (fn [result]
                                                                 (let [allowed (get result "allowed")]
                                                                   (-> (expect (count allowed)) (.toBe 2)))))))))))

;;; ─── Phase 3: permission-MODE policies (modes-as-roles) ──
(describe "model-roles-modes:mode-policies" (fn []
                                              (it "default role asks before write/exec/network"
                                                  (fn []
                                                    (let [p (:policy (:default (:roles defaults)))]
                                                      (-> (expect (get p "write")) (.toBe "ask"))
                                                      (-> (expect (get p "exec")) (.toBe "ask"))
                                                      (-> (expect (get p "network")) (.toBe "ask")))))

                                              (it "accept-edits allows writes but still asks before shell"
                                                  (fn []
                                                    (let [p (:policy (:accept-edits (:roles defaults)))]
                                                      (-> (expect (get p "write")) (.toBe "allow"))
                                                      (-> (expect (get p "exec")) (.toBe "ask")))))

                                              (it "full-auto allows every category"
                                                  (fn []
                                                    (let [p (:policy (:full-auto (:roles defaults)))]
                                                      (-> (expect (get p "write")) (.toBe "allow"))
                                                      (-> (expect (get p "exec")) (.toBe "allow"))
                                                      (-> (expect (get p "network")) (.toBe "allow")))))

                                              (it "accept-edits and full-auto are model-less (preserve active model)"
                                                  (fn []
                                                    (-> (expect (:model (:accept-edits (:roles defaults)))) (.toBeUndefined))
                                                    (-> (expect (:model (:full-auto (:roles defaults)))) (.toBeUndefined))))))

;;; ─── Phase 3: policy/resolve-decision (gate's category→decision logic) ──
(defn- decide [role tool category]
  (policy/resolve-decision (get (:roles defaults) role) tool category))

(describe "model-roles-modes:resolve-decision" (fn []
                                                 (it "default mode → write asks"
                                                     (fn []
                                                       (-> (expect (decide "default" "write" "write")) (.toBe "ask"))))

                                                 (it "accept-edits → write allows, exec asks"
                                                     (fn []
                                                       (-> (expect (decide "accept-edits" "write" "write")) (.toBe "allow"))
                                                       (-> (expect (decide "accept-edits" "bash" "exec")) (.toBe "ask"))))

                                                 (it "full-auto → exec allows"
                                                     (fn []
                                                       (-> (expect (decide "full-auto" "bash" "exec")) (.toBe "allow"))))

                                                 (it "a model role with no policy yields no decision (gate default allow)"
                                                     (fn []
                                                       (-> (expect (decide "deep" "write" "write")) (.toBeUndefined))))

                                                 (it "per-tool :permissions overrides the category policy (plan denies write)"
                                                     (fn []
      ;; plan role has :permissions {write deny}; even though no write policy,
      ;; the per-tool rule wins.
                                                       (-> (expect (decide "plan" "write" "write")) (.toBe "deny"))))))

;;; ─── Phase 3: mode cycle order (Shift+Tab analog) ──
(describe "model-roles-modes:mode-cycle" (fn []
                                           (it "cycles default → accept-edits → plan → full-auto → default"
                                               (fn []
                                                 (-> (expect (policy/next-mode "default")) (.toBe "accept-edits"))
                                                 (-> (expect (policy/next-mode "accept-edits")) (.toBe "plan"))
                                                 (-> (expect (policy/next-mode "plan")) (.toBe "full-auto"))
                                                 (-> (expect (policy/next-mode "full-auto")) (.toBe "default"))))

                                           (it "unknown current mode wraps to the first"
                                               (fn []
                                                 (-> (expect (policy/next-mode "deep")) (.toBe "default"))))

                                           (it "mode? recognizes the four modes, rejects model roles"
                                               (fn []
                                                 (-> (expect (policy/mode? "accept-edits")) (.toBe true))
                                                 (-> (expect (policy/mode? "full-auto")) (.toBe true))
                                                 (-> (expect (boolean (policy/mode? "deep"))) (.toBe false))))))

;;; ─── Phase 4: mode status-line segment (color-coded) ──

(describe "model-roles-modes:status-segment" (fn []
                                               (it "default mode + default role are both hidden (no badge)"
                                                   (fn []
                                                     (-> (expect (:visible? (status-seg/render-mode "default"))) (.toBeFalsy))
                                                     (-> (expect (:visible? (status-seg/render-role "default"))) (.toBeFalsy))
                                                     (-> (expect (:visible? (status-seg/render-role ""))) (.toBeFalsy))))

                                               (it "plan / accept-edits / full-auto each render a visible, colored mode badge"
                                                   (fn []
                                                     (doseq [m ["plan" "accept-edits" "full-auto"]]
                                                       (let [seg (status-seg/render-mode m)]
                                                         (-> (expect (:visible? seg)) (.toBe true))
                                                         (-> (expect (pos? (count (:content seg)))) (.toBe true))
                                                         (-> (expect (.startsWith (:color seg) "#")) (.toBe true))))))

                                               (it "full-auto badge falls back to red hex when theme omits the key"
                                                   (fn []
                                                     (let [seg (status-seg/render-mode "full-auto")]
                                                       (-> (expect (:color seg)) (.toBe "#f7768e"))
                                                       (-> (expect (:content seg)) (.toContain "full-auto")))))

                                               (it "badge color resolves from the theme when present (B7)"
                                                   (fn []
                                                     (let [theme {:colors {:error "#ff0000" :warning "#ffaa00"}}]
                                                       (-> (expect (:color (status-seg/render-mode "full-auto" theme))) (.toBe "#ff0000"))
                                                       (-> (expect (:color (status-seg/render-mode "accept-edits" theme))) (.toBe "#ffaa00")))))

                                               (it "a model role (deep/fast) renders visible + muted via render-role"
                                                   (fn []
                                                     (let [seg (status-seg/render-role "deep")]
                                                       (-> (expect (:visible? seg)) (.toBe true))
                                                       (-> (expect (:content seg)) (.toBe "deep"))
                                                       (-> (expect (.startsWith (:color seg) "#")) (.toBe true)))))

                                               ;; The two axes coexist: model role + permission mode show together.
                                               (it "role and mode badges coexist (fast + full-auto)"
                                                   (fn []
                                                     (-> (expect (:content (status-seg/render-role "fast"))) (.toBe "fast"))
                                                     (-> (expect (:content (status-seg/render-mode "full-auto"))) (.toContain "full-auto"))))))

;;; ─── Role cycling must not change the permission policy ─────────
;;; get-roles returns the modes-as-roles map, and on-permission resolves a
;;; decision from :active-role where allow beats ask. Cycling onto full-auto
;;; would switch off the approval prompt for writes, shell and network, with no
;;; feedback beyond a role name — and no model in the notification, since those
;;; roles carry none.

(describe "role cycling excludes permission modes"
          (fn []
            (it "offers only roles that carry a model"
                (fn []
                  (let [names (set (mr/cyclable-role-names (:roles defaults)))]
                    (-> (expect (contains? names :default)) (.toBe true))
                    (-> (expect (contains? names :fast)) (.toBe true))
                    (-> (expect (contains? names :deep)) (.toBe true)))))

            (it "never offers full-auto, which would disable the ask gate"
                (fn []
                  (let [names (set (mr/cyclable-role-names (:roles defaults)))]
                    (-> (expect (contains? names :full-auto)) (.toBe false))
                    (-> (expect (contains? names :accept-edits)) (.toBe false)))))

            (it "confirms why: full-auto's allow overrides the default mode's ask"
                (fn []
          ;; The consequence this guards against, stated as a test rather than
          ;; a comment: combine-decision is deny > allow > ask.
                  (let [mode-d (policy/resolve-decision
                                (:default (:roles defaults)) "write" "write")
                        role-d (policy/resolve-decision
                                (:full-auto (:roles defaults)) "write" "write")]
                    (-> (expect (str mode-d)) (.toBe "ask"))
                    (-> (expect (str role-d)) (.toBe "allow"))
                    (-> (expect (str (combine-decision mode-d role-d))) (.toBe "allow")))))

            (it "skips a model-less custom role too"
                (fn []
                  (let [names (set (mr/cyclable-role-names
                                    {:a {:model "m" :provider "p"}
                                     :b {:policy {"write" "allow"}}}))]
                    (-> (expect names) (.toEqual (set [:a]))))))))

;; ── role_change: the seam other extensions use instead of writing :active-role ──
;;
;; spec_driven's phase binding and agent_shell's plan handoff used to assoc
;; :active-role into the shared atom directly. They emit `role_change` now;
;; model_roles owns the key and does the switch (model + escalation reset).

(defn ^:async test-role-change-event-switches-role []
  (let [agent  (create-agent {:model "test" :system-prompt "x"})
        api    (create-extension-api agent)
        scoped (create-scoped-api api "model-roles" #{:all})
        deact  (js-await ((.-default mr) scoped))]
    (try
      (swap! (:state agent) assoc :escalated-to "deep")
      ((:emit (:events agent)) "role_change" #js {:role "fast" :source "test"})
      (-> (expect (str (:active-role @(:state agent)))) (.toBe "fast"))
      (-> (expect (:escalated-to @(:state agent))) (.toBeNil))
      ;; A role with no config still binds by name (spec_driven's "advisor"
      ;; when a user :roles map replaced the defaults) — just no model switch.
      ((:emit (:events agent)) "role_change" #js {:role "no-such-role"})
      (-> (expect (str (:active-role @(:state agent)))) (.toBe "no-such-role"))
      (finally
        (js-await (deactivate-all [{:deactivate deact :scope scoped :path "model-roles"}]))))))

(describe "model-roles:role_change event" (fn []
                                            (it "switches the role for another extension; binds unknown names without a model"
                                                test-role-change-event-switches-role)))

;; ── /mode must not throw away a plan the user has not decided about ──
;;
;; Leaving plan mode by any other route used to call cancel! on the way past —
;; silently, and `/mode cycle` is one keystroke.

(defn ^:async with-model-roles
  "Activate model_roles on a fresh agent, run `f` with {:agent :cmds :notes}."
  [f]
  (let [agent  (create-agent {:model "test" :system-prompt "x"})
        api    (create-extension-api agent)
        scoped (create-scoped-api api "model-roles" #{:all})
        deact  (js-await ((.-default mr) scoped))
        notes  (atom [])
        ctx    #js {:ui #js {:notify (fn [m & _] (swap! notes conj (str m)))}}
        run    (fn [cmd args]
                 ((.-handler (get @(:commands agent) (str "model-roles__" cmd)))
                  (clj->js args) ctx))]
    (try
      (f {:agent agent :run run :notes notes})
      (finally
        (js-await (deactivate-all [{:deactivate deact :scope scoped :path "model-roles"}]))))))

(defn ^:async test-mode-refuses-to-discard-an-unapproved-plan []
  (js-await
   (with-model-roles
     (fn [{:keys [agent run notes]}]
       (run "planmode" [])
       (-> (expect (boolean (:plan-mode @(:state agent)))) (.toBe true))
       (reset! notes [])
       (run "mode" ["default"])
       ;; refused: still planning, still read-only, and told why
       (-> (expect (boolean (:plan-mode @(:state agent)))) (.toBe true))
       (-> (expect (str (:permission-mode @(:state agent)))) (.toBe "plan"))
       (-> (expect (.includes (apply str @notes) "unapproved plan")) (.toBe true))
       ;; the cycle shortcut takes the same path — one keystroke must not
       ;; discard it either
       (reset! notes [])
       (run "mode" ["cycle"])
       (-> (expect (boolean (:plan-mode @(:state agent)))) (.toBe true))
       (-> (expect (.includes (apply str @notes) "unapproved plan")) (.toBe true))
       ;; and the refusal names the two ways out
       (run "planmode" ["cancel"])
       (-> (expect (boolean (:plan-mode @(:state agent)))) (.toBe false))
       (run "mode" ["accept-edits"])
       (-> (expect (str (:permission-mode @(:state agent)))) (.toBe "accept-edits"))))))

(describe "/mode while a plan is unapproved"
          (fn []
            (it "refuses instead of silently discarding the plan"
                test-mode-refuses-to-discard-an-unapproved-plan)))

;; ── /roles listed policy-only modes as if they were model roles ──
;;
;; accept-edits and full-auto carry no model. They appeared under "Model roles"
;; with "(inherits model)" beside them — which reads as a role you switch to
;; for a model, the one thing they never do.

(describe "model-roles:policy-line"
          (fn []
            (it "reads out write / exec / network for a mode"
                (fn []
                  (-> (expect (policy/policy-line (:accept-edits (:roles defaults))))
                      (.toBe "write allow, exec ask, network ask"))
                  (-> (expect (policy/policy-line (:full-auto (:roles defaults))))
                      (.toBe "write allow, exec allow, network allow"))
                  (-> (expect (policy/policy-line (:default (:roles defaults))))
                      (.toBe "write ask, exec ask, network ask"))))

            (it "reads plan's per-TOOL denials, which carry no :policy at all"
                (fn []
                  ;; Probing by category alone would report plan as unrestricted.
                  (let [line (policy/policy-line (:plan (:roles defaults)))]
                    (-> (expect (.includes line "write deny")) (.toBe true))
                    (-> (expect (.includes line "exec deny")) (.toBe true)))))

            (it "a role with no policy at all is reported as the gate default"
                (fn []
                  (-> (expect (policy/policy-line {:model "m" :provider "p"}))
                      (.toBe "write allow, exec allow, network allow"))))))

(defn ^:async test-roles-splits-models-from-modes []
  (js-await
   (with-model-roles
     (fn [{:keys [run notes]}]
       (reset! notes [])
       (run "roles" [])
       (let [out (apply str @notes)
             ;; everything printed under the model-roles heading
             models (first (.split out "Permission modes"))]
         (-> (expect (.includes out "Model roles:")) (.toBe true))
         (-> (expect (.includes out "Permission modes (use /mode)")) (.toBe true))
         ;; model-less modes are NOT model roles
         (-> (expect (.includes models "full-auto")) (.toBe false))
         (-> (expect (.includes models "accept-edits")) (.toBe false))
         (-> (expect (.includes models "(inherits model)")) (.toBe false))
         ;; and real model roles still are
         (-> (expect (.includes models "fast")) (.toBe true))
         (-> (expect (.includes models "deep")) (.toBe true))
         ;; the modes section says what each one actually does
         (-> (expect (.includes out "write allow, exec allow, network allow")) (.toBe true)))))))

(defn ^:async test-mode-bare-prints-each-policy []
  (js-await
   (with-model-roles
     (fn [{:keys [run notes]}]
       (reset! notes [])
       (run "mode" [])
       (let [out (apply str @notes)]
         (doseq [m policy/mode-cycle]
           (-> (expect #js [m (boolean (.includes out (str "  " m " → ")))])
               (.toEqual #js [m true])))
         (-> (expect (.includes out "write ask, exec ask, network ask")) (.toBe true))
         (-> (expect (.includes out "write allow, exec allow, network allow")) (.toBe true))
         ;; and it still marks where you are
         (-> (expect (.includes out "◀")) (.toBe true)))))))

(describe "/roles and /mode say what each entry does"
          (fn []
            (it "/roles lists model roles and permission modes separately"
                test-roles-splits-models-from-modes)
            (it "/mode with no argument prints each mode's policy"
                test-mode-bare-prints-each-policy)))
