(ns model-roles-modes.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.events :refer [combine-decision]]
            [agent.extensions.model-roles.policy :as policy]
            [agent.extensions.model-roles.status-segment :as status-seg]
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
                                                    (-> (expect (or (:model plan) (get plan "model"))) (.toBe "m3"))
                                                    (-> (expect (or (:provider plan) (get plan "provider"))) (.toBe "minimax"))
                                                    (-> (expect (count (:allowed-tools plan))) (.toBe 2))
                                                    (-> (expect (get (:permissions plan) "write")) (.toBe "deny")))))

                                            (it "provider-aware: a shipped role on a non-default provider inherits the default model"
                                                (fn []
                                                  (let [roles (policy/build-roles floor-roles {} "opencode-zen" "big-pickle")
                                                        deep  (get roles "deep")]
        ;; anthropic deep ≠ opencode-zen default → inherits big-pickle (no leak)
                                                    (-> (expect (or (:provider deep) (get deep "provider"))) (.toBe "opencode-zen"))
                                                    (-> (expect (or (:model deep) (get deep "model"))) (.toBe "big-pickle")))))

                                            (it "no regression: matching-provider shipped presets are kept"
                                                (fn []
                                                  (let [roles (policy/build-roles floor-roles {} "anthropic" "sonnet")
                                                        deep  (get roles "deep")]
                                                    (-> (expect (or (:model deep) (get deep "model"))) (.toBe "opus")))))

                                            (it "a user-set role is always kept, even cross-provider"
                                                (fn []
                                                  (let [user  {"advisor" {"provider" "deepseek" "model" "v4"}}
                                                        roles (policy/build-roles floor-roles user "opencode-zen" "big-pickle")]
                                                    (-> (expect (or (:model (get roles "advisor")) (get (get roles "advisor") "model"))) (.toBe "v4")))))

                                            (it "model-less roles (permission modes) are left alone"
                                                (fn []
                                                  (let [roles (policy/build-roles floor-roles {} "opencode-zen" "big-pickle")
                                                        ae    (get roles "accept-edits")]
                                                    (-> (expect (or (:provider ae) (get ae "provider"))) (.toBeNil))
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
