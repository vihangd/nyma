(ns ext-spec-driven-phases.test
  "Pure phase-loop core. Every case here maps to a documented failure mode of
   loops of this shape — premature termination, runaway iteration, advancing
   past an unresolved verify, and treating a broken tasks file as finished."
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/extensions/spec_driven/phases.mjs" :as p]
            ["./agent/extensions/spec_driven/status_segment.mjs" :as seg]))

(defn- prog [total checked & [raw]]
  (p/progress (vec (concat (repeat checked {:checked? true})
                           (repeat (- total checked) {:checked? false})))
              (or raw (if (pos? total) "- [ ] x" ""))))

(defn- decide [m] (p/decide (merge {:armed? true :phase "execute" :profile "routed"
                                    :iteration 0 :max-iterations 25
                                    :verify-pending? false} m)))

;; ── config ───────────────────────────────────────────────────────

(describe "phases/config" (fn []

                            (it "ships routed/thrifty/free and sane loop defaults"
                                (fn []
                                  (let [c (p/config nil)]
                                    (-> (expect (:mode (:loop c))) (.toBe "off"))          ; never self-arms
                                    (-> (expect (:profile (:loop c))) (.toBe "routed"))
                                    (-> (expect (:max-iterations (:loop c))) (.toBe 25))
                                    (-> (expect (contains? (:profiles c) "thrifty")) (.toBe true)))))

                            (it "merges user profiles OVER shipped ones by name"
                                (fn []
                                  (let [c (p/config #js {:spec #js {:profiles #js {:routed #js {:execute "fast"}
                                                                                   :mine   #js {:execute "build"}}}})]
          ;; redefining routed replaces it...
                                    (-> (expect (get-in c [:profiles "routed" "execute"])) (.toBe "fast"))
          ;; ...while the other shipped presets survive
                                    (-> (expect (contains? (:profiles c) "thrifty")) (.toBe true))
                                    (-> (expect (get-in c [:profiles "mine" "execute"])) (.toBe "build")))))

                            (it "reads loop overrides and ignores a non-numeric max-iterations"
                                (fn []
                                  (let [c (p/config #js {:spec #js {:loop #js {:mode "on" :profile "thrifty"
                                                                               :max-iterations "lots"}}})]
                                    (-> (expect (:mode (:loop c))) (.toBe "on"))
                                    (-> (expect (:profile (:loop c))) (.toBe "thrifty"))
                                    (-> (expect (:max-iterations (:loop c))) (.toBe 25)))))))

;; ── role resolution: must never degrade silently ─────────────────

(describe "phases/resolve-role" (fn []

                                  (let [c (p/config nil)
                                        known ["default" "advisor" "fast" "deep" "commit"]]

                                    (it "binds a phase to its profile role"
                                        (fn []
                                          (let [r (p/resolve-role c "routed" "execute" known)]
                                            (-> (expect (:role r)) (.toBe "fast"))
                                            (-> (expect (:fell-back? r)) (.toBe false)))))

                                    (it "falls back AND explains when the role is not defined"
                                        (fn []
          ;; routed binds verify→deep; a user whose roles omit `deep` must get
          ;; a NAMED fallback. This is the shape of the advisor's silent-
          ;; fallback bug (advisor/index.cljs:164-168), which must not repeat.
                                          (let [r (p/resolve-role c "routed" "verify" ["default" "advisor" "fast"])]
                                            (-> (expect (:role r)) (.toBe "default"))
                                            (-> (expect (:fell-back? r)) (.toBe true))
                                            (-> (expect (.includes (:reason r) "deep")) (.toBe true)))))

                                    (it "falls back on an unknown profile"
                                        (fn []
                                          (let [r (p/resolve-role c "nope" "execute" known)]
                                            (-> (expect (:role r)) (.toBe "default"))
                                            (-> (expect (:fell-back? r)) (.toBe true)))))

                                    (it "falls back when a profile omits the phase"
                                        (fn []
                                          (let [c2 (p/config #js {:spec #js {:profiles #js {:partial #js {:plan "advisor"}}}})
                                                r  (p/resolve-role c2 "partial" "execute" known)]
                                            (-> (expect (:role r)) (.toBe "default"))
                                            (-> (expect (:fell-back? r)) (.toBe true)))))

                                    (it "skips the known-roles check when the caller cannot enumerate roles"
                                        (fn []
          ;; model_roles absent → empty list → trust the profile rather than
          ;; collapsing every phase to default.
                                          (-> (expect (:role (p/resolve-role c "routed" "execute" []))) (.toBe "fast")))))))

;; ── progress: an empty list is NOT completion ────────────────────

(describe "phases/progress" (fn []

                              (it "counts open and checked"
                                  (fn []
                                    (let [r (prog 4 1)]
                                      (-> (expect (:total r)) (.toBe 4))
                                      (-> (expect (:open r)) (.toBe 3))
                                      (-> (expect (:status r)) (.toBe "in-progress")))))

                              (it "reports complete only when every box is ticked"
                                  (fn []
                                    (-> (expect (:status (prog 3 3))) (.toBe "complete"))))

                              (it "distinguishes a file with no checkboxes from an all-done file"
                                  (fn []
        ;; parse-tasks drops non-checkbox lines, so a reformatted or corrupted
        ;; tasks.md yields zero tasks. Reading that as `complete` would have the
        ;; loop declare victory on a broken file.
                                    (let [r (p/progress [] "# Tasks\nsome prose, no checkboxes\n")]
                                      (-> (expect (:status r)) (.toBe "no-tasks"))
                                      (-> (expect (:empty-file? r)) (.toBe false)))))

                              (it "flags a genuinely empty file separately"
                                  (fn []
                                    (-> (expect (:empty-file? (p/progress [] "   "))) (.toBe true))))))

;; ── decide: one case per failure mode ────────────────────────────

(describe "phases/decide" (fn []

                            (it "does nothing unless armed"
                                (fn []
                                  (-> (expect (:action (decide {:armed? false :progress (prog 3 3)}))) (.toBe "stop"))))

                            (it "continues while tasks remain — finish reason is never consulted"
                                (fn []
        ;; Premature termination: the agent may report `stop` with work left.
                                  (let [d (decide {:progress (prog 5 2)})]
                                    (-> (expect (:action d)) (.toBe "continue"))
                                    (-> (expect (.includes (:reason d) "2/5")) (.toBe true)))))

                            (it "advances to the next phase when the current one completes"
                                (fn []
                                  (let [d (decide {:phase "execute" :progress (prog 5 5)})]
                                    (-> (expect (:action d)) (.toBe "advance"))
                                    (-> (expect (:next-phase d)) (.toBe "verify")))))

                            (it "is done after the last phase"
                                (fn []
                                  (-> (expect (:action (decide {:phase "ship" :progress (prog 2 2)}))) (.toBe "done"))))

                            (it "stops at the iteration cap"
                                (fn []
        ;; The follow-queue drain upstream is an unbounded recur
        ;; (loop.cljs:586-594), so this bound is the only one that applies.
                                  (let [d (decide {:iteration 25 :max-iterations 25 :progress (prog 5 1)})]
                                    (-> (expect (:action d)) (.toBe "stop"))
                                    (-> (expect (.includes (:reason d) "25/25")) (.toBe true)))))

                            (it "refuses to advance while verify still has attempts outstanding"
                                (fn []
        ;; verify_gate enqueues its own fix follow-up; advancing here is how a
        ;; loop 'completes' work that does not build. HOLD rather than stop —
        ;; disarming would mean never resuming once the build goes green.
                                  (let [d (decide {:verify-pending? true :progress (prog 5 5)})]
                                    (-> (expect (:action d)) (.toBe "hold"))
                                    (-> (expect (.includes (:reason d) "red")) (.toBe true)))))

                            (it "stops rather than completing on a tasks file with no checkboxes"
                                (fn []
                                  (let [d (decide {:progress (p/progress [] "# Tasks\nprose only\n")})]
                                    (-> (expect (:action d)) (.toBe "stop"))
                                    (-> (expect (.includes (:reason d) "refusing")) (.toBe true)))))

                            (it "the iteration cap outranks a complete phase"
                                (fn []
        ;; Guard order matters: a runaway loop must stop even on a clean phase.
                                  (-> (expect (:action (decide {:iteration 99 :max-iterations 25
                                                                :progress (prog 3 3)})))
                                      (.toBe "stop"))))))

;; ── phase order ──────────────────────────────────────────────────

(describe "phases/phase-order" (fn []

                                 (it "keeps the canonical order for known phases"
                                     (fn []
                                       (-> (expect (p/phase-order {"verify" "a" "plan" "b" "execute" "c"}))
                                           (.toEqual #js ["plan" "execute" "verify"]))))

                                 (it "appends user-declared phases after the known ones"
                                     (fn []
                                       (-> (expect (p/phase-order {"plan" "a" "audit" "b"}))
                                           (.toEqual #js ["plan" "audit"]))))))

;; ── Regressions from code review ─────────────────────────────────

(describe "phases — review regressions" (fn []

  (it "every shipped profile names only roles that exist without user config"
      (fn []
        ;; `routed` shipped with execute->build, and `build` is not a role
        ;; anywhere (model_roles/index.cljs:19-38, settings/manager.cljs). Out
        ;; of the box the flagship profile's main phase degraded to `default`
        ;; and announced its own failure. This asserts the whole matrix.
        (let [c (p/config nil)]
          (doseq [[pname pmap] (:profiles c)]
            (doseq [[phase role] pmap]
              (-> (expect #js [pname phase role
                               (contains? p/builtin-role-names (str role))])
                  (.toEqual #js [pname phase role true])))))))

  (it "stops instead of reporting done when the phase is not in the profile"
      (fn []
        ;; idx -1 -> nxt nil -> a completed task list fell through to :done,
        ;; announcing "all phases complete" for a phase the profile never had.
        ;; Reachable by switching profiles mid-run.
        (let [d (p/decide {:armed? true :phase "audit" :profile "routed"
                           :phases ["plan" "execute"] :iteration 0
                           :max-iterations 25 :verify-pending? false
                           :progress (p/progress [{:checked? true}] "- [x] a")})]
          (-> (expect (:action d)) (.toBe "stop"))
          (-> (expect (.includes (:reason d) "not in profile")) (.toBe true)))))

  (it "settings#spec.loop.mode \"on\" actually arms the loop"
      (fn []
        ;; :mode was parsed and never read, so opting in permanently did nothing.
        (-> (expect (p/armed-by-settings? (p/config #js {:spec #js {:loop #js {:mode "on"}}})))
            (.toBe true))
        (-> (expect (p/armed-by-settings? (p/config nil))) (.toBe false))))))

;;; ─── Scaffold placeholders are not real tasks ──────────────────────────────

(describe "phases/template-tasks?" (fn []

  (it "recognises the untouched scaffold"
      (fn []
        ;; /spec import scaffolds tasks.md from a template and queues an LLM
        ;; turn to fill it in. Arming the loop before that lands would set it
        ;; to work on "First task" / "Second task".
        (-> (expect (p/template-tasks?
                     (p/progress [{:checked? false :text "First task"}
                                  {:checked? false :text "Second task"}]
                                 "- [ ] First task")))
            (.toBe true))))

  (it "is false once any real task is present"
      (fn []
        (-> (expect (p/template-tasks?
                     (p/progress [{:checked? false :text "First task"}
                                  {:checked? false :text "Add TokenStore in src/auth/store.ts"}]
                                 "- [ ] x")))
            (.toBe false))))

  (it "is false for an empty task list, which is a different problem"
      (fn []
        ;; :no-tasks already covers that; conflating them would let a corrupted
        ;; file look like a fresh scaffold.
        (-> (expect (p/template-tasks? (p/progress [] ""))) (.toBe false))))

  (it "progress carries the task texts the check needs"
      (fn []
        (-> (expect (:texts (p/progress [{:checked? false :text "a"}] "- [ ] a")))
            (.toEqual #js ["a"]))))))

;;; ─── Status segment ────────────────────────────────────────────────────────

(describe "spec status segment" (fn []

  (let [theme {:colors {:secondary "#9ece6a"}}
        show  (fn [st] (let [r (seg/render-spec st theme)]
                         (if (:visible? r) (:content r) nil)))]

    (it "is invisible with no active spec"
        (fn []
          ;; A user who never touches /spec must see no change at all.
          (-> (expect (show {})) (.toBeNil))))

    (it "shows the decomposing state that used to be silent"
        (fn []
          ;; After `/spec import --run` the decomposition is a queued follow-up,
          ;; so it runs a turn later. With nothing on screen the run looked
          ;; dead — a real session had the user typing "go" repeatedly.
          (-> (expect (show {:spec "write-script-today-date" :pending? true}))
              (.toBe "⏵ write-script-today-date · decomposing…"))))

    (it "shows phase, progress and role once the loop is armed"
        (fn []
          (-> (expect (show {:spec "auth" :phase "execute" :role "fast" :armed? true
                             :progress {:total 11 :checked 7}}))
              (.toBe "⏵ auth · execute · 7/11 · fast"))))

    (it "omits the role when the loop is not armed"
        (fn []
          ;; The role only governs the loop; showing it otherwise implies the
          ;; loop is running when it is not.
          (-> (expect (show {:spec "auth" :phase "plan" :role "fast"
                             :progress {:total 11 :checked 0}}))
              (.toBe "⏵ auth · plan · 0/11")))))))

;;; ─── Entry phase ───────────────────────────────────────────────────────────
;;
;; `/spec import --run` used to enter at the first phase, which under `routed`
;; is `plan`/`advisor`. But `decide` returns :continue for :in-progress, so a
;; phase advances only at 100% completion — meaning the entry phase is not the
;; first of four, it is the ONLY one for the whole run. Entering at `plan` for
;; a plan that arrived from Claude Code ran every task under the planning role.

(describe "phases/entry-phase" (fn []

  (it "takes the requested phase when the profile has it"
      (fn []
        (let [r (p/entry-phase {:wanted "execute"
                                     :order ["plan" "execute" "verify" "ship"]})]
          (-> (expect (:phase r)) (.toBe "execute"))
          (-> (expect (:fell-back? r)) (.toBe false)))))

  (it "lets an explicit phase win over the request"
      (fn []
        ;; Choosing a phase before the decomposition lands is deliberate;
        ;; promotion must not overwrite it.
        (let [r (p/entry-phase {:current "verify" :wanted "execute"
                                     :order ["plan" "execute" "verify" "ship"]})]
          (-> (expect (:phase r)) (.toBe "verify"))
          (-> (expect (:fell-back? r)) (.toBe false)))))

  (it "falls back to the first phase and SAYS SO when the profile lacks it"
      (fn []
        ;; A profile may define any phase set. Naming one it does not contain
        ;; would strand the loop on decide's phase-not-in-profile guard.
        (let [r (p/entry-phase {:wanted "execute" :order ["design" "build"]})]
          (-> (expect (:phase r)) (.toBe "design"))
          (-> (expect (:fell-back? r)) (.toBe true))
          (-> (expect (.includes (:reason r) "not in this profile")) (.toBe true)))))

  (it "does not call it a fallback when nothing was requested"
      (fn []
        (let [r (p/entry-phase {:order ["plan" "execute"]})]
          (-> (expect (:phase r)) (.toBe "plan"))
          (-> (expect (:fell-back? r)) (.toBe false))
          (-> (expect (:reason r)) (.toBeNil)))))

  (it "survives a profile with no phases at all"
      (fn []
        (let [r (p/entry-phase {:wanted "execute" :order []})]
          (-> (expect (:phase r)) (.toBeNil))
          (-> (expect (:fell-back? r)) (.toBe true)))))))

(describe "phases/config import-phase" (fn []

  (it "defaults to execute"
      (fn []
        (-> (expect (:import-phase (:loop (p/config nil)))) (.toBe "execute"))))

  (it "is settings-driven, like every other loop dial"
      (fn []
        (let [cfg (p/config #js {"spec" #js {"loop" #js {"import-phase" "plan"}}})]
          (-> (expect (:import-phase (:loop cfg))) (.toBe "plan")))))))
