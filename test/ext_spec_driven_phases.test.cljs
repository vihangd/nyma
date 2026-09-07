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

  (it "defaults to plan — decomposition is the plan phase's work"
      (fn []
        ;; Was "execute", on the reasoning that an imported plan means planning
        ;; already happened. But decomposition — turning the captured document
        ;; into spec.md / plan.md / tasks.md — IS this phase, and it is exactly
        ;; the step a model skips: a real run read PLAN.md and wrote 233 lines
        ;; of implementation while tasks.md still said "First task".
        (-> (expect (:import-phase (:loop (p/config nil)))) (.toBe "plan"))))

  (it "is settings-driven, like every other loop dial"
      (fn []
        (let [cfg (p/config #js {"spec" #js {"loop" #js {"import-phase" "plan"}}})]
          (-> (expect (:import-phase (:loop cfg))) (.toBe "plan")))))))

;;; ─── The registered render, not just the pure one ──────────────────────────
;;
;; `render-spec` was tested directly and passed, while the segment never once
;; appeared on screen. The wrapper `register!` installs read the theme with
;; `(.-theme ctx)` and ran it through `js->clj`, which squint does not provide
;; — so the render threw on EVERY frame. status_bar isolates segment errors in
;; a catch, so it failed silently for a whole release.
;;
;; The lesson is the seam: test what gets registered, with the ctx status_bar
;; actually passes (a CLJS map whose :theme is already a CLJS map).

(defn- registered-render
  "The render fn `register!` hands to the host."
  [state-fn]
  (let [captured (atom nil)
        api #js {:registerStatusSegment (fn [_id cfg] (reset! captured cfg))
                 :unregisterStatusSegment (fn [_id] nil)}]
    (seg/register! api state-fn)
    (.-render @captured)))

(def ^:private bar-ctx
  ;; What status_bar builds: (assoc seg-ctx :theme theme).
  {:activity true :theme {:colors {:secondary "#9ece6a"}}})

(describe "spec status segment: as registered" (fn []

  (it "renders through the registered wrapper"
      (fn []
        (let [r (registered-render
                 (fn [] {:spec "apps" :phase "execute" :role "fast"
                         :progress {:total 8 :checked 3}
                         :pending? false :armed? true}))
              out (r bar-ctx)]
          (-> (expect (:visible? out)) (.toBe true))
          (-> (expect (:content out)) (.toBe "⏵ apps · execute · 3/8 · fast")))))

  (it "does not throw when the host passes no theme"
      (fn []
        ;; The failure mode was an exception, so absence of a throw IS the
        ;; assertion.
        (let [r (registered-render (fn [] {:spec "apps"}))]
          (-> (expect (:visible? (r {}))) (.toBe true)))))

  (it "hides with no active spec"
      (fn []
        (let [r (registered-render (fn [] {:spec nil}))]
          (-> (expect (:visible? (r bar-ctx))) (.toBe false)))))

  (it "survives a state-fn that blows up"
      (fn []
        ;; state-fn touches the filesystem; a deleted spec mid-render must not
        ;; take the status bar with it.
        (let [r (registered-render (fn [] (throw (js/Error. "spec vanished"))))]
          (-> (expect (fn? r)) (.toBe true)))))))

;;; ─── The other silent command ──────────────────────────────────────────────
;;
;; `/spec analyze` is a direct generateText, not an agent turn: no spinner, no
;; turn counter, no streamed text. On a slow model it looked exactly like a
;; command that had done nothing — the same complaint `decomposing…` was added
;; for, one command over.

(describe "spec status segment: analyzing" (fn []

  (it "shows analyzing while the pass is in flight"
      (fn []
        (let [out (seg/render-spec {:spec "apps" :phase "execute" :analyzing? true} {})]
          (-> (expect (:visible? out)) (.toBe true))
          (-> (expect (.includes (:content out) "analyzing…")) (.toBe true)))))

  (it "shows both when a decomposition is queued and analyze is running"
      (fn []
        ;; Reachable: /spec import --run queues, you analyze before sending a
        ;; message. Neither state should mask the other.
        (let [c (:content (seg/render-spec {:spec "apps" :pending? true :analyzing? true} {}))]
          (-> (expect (.includes c "decomposing…")) (.toBe true))
          (-> (expect (.includes c "analyzing…")) (.toBe true)))))

  (it "is absent once the pass settles"
      (fn []
        (-> (expect (.includes (:content (seg/render-spec
                                          {:spec "apps" :phase "execute"
                                           :progress {:total 8 :checked 1}
                                           :armed? true :role "fast"} {}))
                               "analyzing"))
            (.toBe false))))

  (it "still needs an active spec"
      (fn []
        ;; analyze can run against a named spec that is not the active one;
        ;; the segment is about the ACTIVE spec and stays hidden either way.
        (-> (expect (:visible? (seg/render-spec {:spec nil :analyzing? true} {})))
            (.toBe false))))))

;;; ─── The phase gate ────────────────────────────────────────────────────────
;;
;; A real run read PLAN.md and wrote 233 lines of apps.py while tasks.md still
;; said "First task". Nothing stopped it: the phase picked a model and nothing
;; else. The literature calls this the agent-pause assumption — humans stop when
;; a spec does not parse, agents fill the hole with the most defensible-looking
;; value and keep building.
;;
;; No comparable tool enforces this mechanically: spec-kit is convention, BMAD
;; says "start anywhere", the gated-skill packs use human approval. The only
;; precedent is Claude Code's plan mode, which is globally read-only and so
;; cannot write the spec files either. Hence a phase-scoped, path-scoped gate.

(def ^:private spec-dir ".specify/specs/apps")

(defn- real-tasks
  "Progress for `n` genuinely-named tasks — not the scaffold placeholders."
  [n]
  (p/progress (mapv (fn [i] {:checked? false :text (str "Scan source " i)}) (range n))
              (.join (clj->js (mapv (fn [i] (str "- [ ] Scan source " i)) (range n))) "\n")))

(defn- allows [phase tool path]
  (:allowed? (p/phase-allows? {:phase phase :tool tool :path path :spec-dir spec-dir})))

(describe "phases/phase-allows?" (fn []

  (it "lets the plan phase write the spec's own files"
      (fn []
        (-> (expect (allows "plan" "write" ".specify/specs/apps/tasks.md")) (.toBe true))
        (-> (expect (allows "plan" "edit"  ".specify/specs/apps/spec.md"))  (.toBe true))
        ;; reads are never restricted — decomposition has to read the source
        (-> (expect (allows "plan" "read" "PLAN.md")) (.toBe true))
        (-> (expect (allows "plan" "grep" "anything")) (.toBe true))))

  (it "refuses implementation during the plan phase"
      (fn []
        ;; The exact call that shipped 233 lines of apps.py.
        (-> (expect (allows "plan" "write" "apps.py")) (.toBe false))
        (-> (expect (allows "plan" "edit" "src/main.py")) (.toBe false))
        (-> (expect (allows "plan" "multi_edit" "apps.py")) (.toBe false))))

  (it "refuses bash outright in the plan phase"
      (fn []
        ;; Without this the gate is decorative — `echo > apps.py` walks past a
        ;; path check on the write tools.
        (-> (expect (allows "plan" "bash" nil)) (.toBe false))))

  (it "is not fooled by .. traversal"
      (fn []
        (-> (expect (allows "plan" "write" ".specify/specs/apps/../../apps.py"))
            (.toBe false))
        (-> (expect (allows "plan" "write" ".specify/specs/apps/./tasks.md"))
            (.toBe true))
        ;; a sibling spec is still outside this spec's dir
        (-> (expect (allows "plan" "write" ".specify/specs/other/tasks.md"))
            (.toBe false))))

  (it "restricts nothing outside the plan phase"
      (fn []
        (doseq [ph ["execute" "verify" "ship"]]
          (-> (expect (allows ph "write" "apps.py")) (.toBe true))
          (-> (expect (allows ph "bash" nil)) (.toBe true)))))

  (it "explains itself to the model rather than just refusing"
      (fn []
        ;; The reason is returned as a tool RESULT, so the model reads it and
        ;; self-corrects. An opaque cancellation just gets retried.
        (let [r (p/phase-allows? {:phase "plan" :tool "write" :path "apps.py"
                                  :spec-dir spec-dir})]
          (-> (expect (.includes (:reason r) "plan")) (.toBe true))
          (-> (expect (.includes (:reason r) spec-dir)) (.toBe true)))))))

(describe "phases/open-clarifications" (fn []

  (it "counts unresolved markers across documents"
      (fn []
        (-> (expect (p/open-clarifications "a [NEEDS CLARIFICATION: x] b"
                                           "c [NEEDS CLARIFICATION: y]"))
            (.toBe 2))
        (-> (expect (p/open-clarifications "clean" nil "")) (.toBe 0))))))

(describe "phases/decide — the plan phase can refuse" (fn []

  (let [base {:armed? true :phase "plan" :profile "routed"
              :phases ["plan" "execute" "verify" "ship"]
              :iteration 0 :max-iterations 25}]

    (it "keeps decomposing while tasks are still the scaffold"
        (fn []
          ;; `prog` builds task maps with no :text, which template-tasks? cannot
          ;; see — build the real shape here.
          (let [tmpl (p/progress [{:checked? false :text "First task"}
                                  {:checked? false :text "Second task"}]
                                 "- [ ] First task\n- [ ] Second task")
                d    (p/decide (assoc base :progress tmpl))]
            (-> (expect (p/template-tasks? tmpl)) (.toBe true))
            (-> (expect (str (:action d))) (.toBe "continue")))))

    (it "advances once the spec holds real tasks"
        (fn []
          (let [d (p/decide (assoc base :progress (real-tasks 9)))]
            (-> (expect (str (:action d))) (.toBe "advance"))
            (-> (expect (:next-phase d)) (.toBe "execute")))))

    (it "HOLDS on unresolved clarification markers"
        (fn []
          ;; Elmore's point: a gate that cannot fail is theatre, and an open
          ;; question should block rather than be filled in. The import seed
          ;; already asks the model to insert these instead of inventing.
          (let [d (p/decide (assoc base :progress (real-tasks 9)
                                        :open-clarifications 2))]
            (-> (expect (str (:action d))) (.toBe "hold"))
            (-> (expect (.includes (:reason d) "clarify")) (.toBe true)))))

    (it "leaves the other phases' behaviour unchanged"
        (fn []
          (let [d (p/decide (assoc base :phase "execute"
                                        :progress (prog 9 3 "- [ ] x")))]
            (-> (expect (str (:action d))) (.toBe "continue"))))))))
