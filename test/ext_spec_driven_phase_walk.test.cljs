(ns ext-spec-driven-phase-walk.test
  "The loop walks plan → execute → verify → ship, and the plan phase HOLDS.

   Two gaps the existing loop tests share: no test drives more than one
   transition (the advance test fires a single agent_end), and no test asserts
   that a tool is unavailable under a phase — `:1042-1106` checks only that
   `:active-role` was written. Both are exactly the property the user asked for:
   follow the phases, do not jump.

   The gate is exercised through the REAL `before_tool_call` handler rather than
   `phases/phase-allows?` directly. That distinction is not academic: the same
   extension emitted `turn_request` through the namespace-PREFIXED
   `api.events.emit` for three features, so every unit test passed while
   production dropped the event on the floor. A predicate test cannot see a
   handler registered on the wrong channel."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs"   :as fs]
            ["node:os"   :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            ["./agent/extensions/spec_driven/index.mjs" :as spec]))

(defn- mktmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-specwalk-")))

(defn- seed-spec!
  "A spec whose tasks are REAL (not the scaffold template) and whose docs carry
   no open clarifications — the state the plan phase is allowed to leave."
  [dir]
  (let [d (path/join dir ".specify" "specs" "auth")]
    (fs/mkdirSync d #js {:recursive true})
    (fs/writeFileSync (path/join d "spec.md")  "# Spec\nauth\n")
    (fs/writeFileSync (path/join d "plan.md")  "# Plan\nplan\n")
    (fs/writeFileSync (path/join d "tasks.md")
                      "# Tasks\n- [ ] T001 Add the token store\n- [ ] T002 Wire the route\n")
    d))

(defn- harness []
  (let [notes (atom []) sent (atom []) reqs (atom [])
        st (atom {:messages ["m1" "m2" "m3"]}) shared (atom {})
        hs (atom {}) cmd (atom nil)
        api #js {:getState     (fn [] @st)
                 :__state_atom st
                 :getSettings  (fn []
                                 #js {:roles #js {:fast #js {} :deep #js {}
                                                  :advisor #js {} :commit #js {}}})
                 :state        #js {:get    (fn [k] (get @shared k))
                                    :set    (fn [k v] (swap! shared assoc k v))
                                    :delete (fn [k] (swap! shared dissoc k))
                                    :keys   (fn [] (clj->js (vec (keys @shared))))
                                    :clear  (fn [] (reset! shared {}))}
                 :on           (fn [e h] (swap! hs update e (fnil conj []) h))
                 :off          (fn [_e _h] nil)
                 ;; The channel interactive mode actually listens on.
                 :emitGlobal   (fn [ev data]
                                 (when (= "turn_request" (str ev))
                                   (swap! reqs conj (str (.-text data))))
                                 nil)
                 :registerCommand   (fn [_n c] (reset! cmd (.-handler c)))
                 :unregisterCommand (fn [_n] nil)
                 :sendUserMessage   (fn [m _o] (swap! sent conj m))
                 :ui           #js {:notify (fn [m & _] (swap! notes conj m))}}
        off ((.-default spec) api)]
    {:run  (fn [& args]
             (reset! notes [])
             (@cmd (vec args) #js {:ui #js {:notify (fn [m & _] (swap! notes conj m))}})
             (str/join "\n" @notes))
     :end  (fn []
             (reset! sent []) (reset! notes [])
             (doseq [h (get @hs "agent_end")] (h #js {:finishReason "stop"}))
             {:sent (count @sent) :notes (str/join " " @notes) :last (last @sent)})
     :fire (fn [ev] (doseq [h (get @hs ev)] (h #js {})))
     ;; The real gate, off the real event. nil = allowed.
     :tool (fn [name args]
             (some (fn [h] (h #js {:name name :args (clj->js args)}))
                   (get @hs "before_tool_call")))
     :phase (fn [] (:spec-phase @st))
     :role  (fn [] (:active-role @st))
     :reqs reqs :state st :off off}))

(defn- with-tmp [body]
  (let [tmp (mktmp) d (seed-spec! tmp) prev (js/process.cwd)]
    (try (.chdir js/process tmp) (body tmp d)
         (finally (.chdir js/process prev)
                  (try (fs/rmSync tmp #js {:recursive true :force true})
                       (catch :default _ nil))))))

(defn- refused? [r] (and r (true? (.-skip r))))

(describe "the phase ladder" (fn []

                               (it "walks plan → execute → verify → ship, one transition per turn"
                                   (fn []
                                     (with-tmp
                                       (fn [_tmp d]
                                         (let [{:keys [run end phase role tool off]} (harness)]
                                           (run "start" "auth" "--force")
                                           (run "phase" "plan")
                                           (run "run")
                                           (-> (expect (phase)) (.toBe "plan"))
                                           (-> (expect (role))  (.toBe "advisor"))

              ;; plan → execute: the tasks are real and nothing is unresolved.
                                           (let [r (end)]
                                             (-> (expect (.includes (:notes r) "execute")) (.toBe true)))
                                           (-> (expect (phase)) (.toBe "execute"))
                                           (-> (expect (role))  (.toBe "fast"))

              ;; It must not skip a rung: tasks are still open, so this turn
              ;; continues in execute rather than advancing to verify.
                                           (end)
                                           (-> (expect (phase)) (.toBe "execute"))

                                           (fs/writeFileSync (path/join d "tasks.md")
                                                             "# Tasks\n- [x] T001 Add the token store\n- [x] T002 Wire the route\n")
                                           (end)
                                           (-> (expect (phase)) (.toBe "verify"))
                                           (-> (expect (role))  (.toBe "deep"))

                                           (end)
                                           (-> (expect (phase)) (.toBe "ship"))
                                           (-> (expect (role))  (.toBe "commit"))
                                           (when off (off)))))))

                               (it "holds in plan while a clarification is open, and moves once it is resolved"
                                   (fn []
        ;; The one gate that can FAIL. The import seed has always asked the
        ;; model to write `[NEEDS CLARIFICATION: …]` rather than invent an
        ;; answer; until now nothing read them back, so "leaving items open"
        ;; counted as success and the loop advanced on a spec full of holes.
                                     (with-tmp
                                       (fn [_tmp d]
                                         (let [{:keys [run end phase off]} (harness)]
                                           (fs/writeFileSync (path/join d "spec.md")
                                                             "# Spec\n[NEEDS CLARIFICATION: which store?]\n")
                                           (run "start" "auth" "--force")
                                           (run "phase" "plan")
                                           (run "run")
                                           (let [r (end)]
                ;; Held: still in plan, and nothing queued behind it.
                                             (-> (expect (phase)) (.toBe "plan"))
                                             (-> (expect (:sent r)) (.toBe 0)))
                                           (fs/writeFileSync (path/join d "spec.md") "# Spec\nauth\n")
                                           (end)
                                           (-> (expect (phase)) (.toBe "execute"))
                                           (when off (off)))))))

                               (it "refuses implementation work in the plan phase, through the real event"
                                   (fn []
                                     (with-tmp
                                       (fn [_tmp _d]
                                         (let [{:keys [run tool off]} (harness)]
                                           (run "start" "auth" "--force")
                                           (run "phase" "plan")

              ;; Writing the feature itself — the exact failure: 233 lines of
              ;; apps.py while tasks.md was still the template.
                                           (-> (expect (refused? (tool "write" {:path "apps.py"}))) (.toBe true))
              ;; …and the escape hatch around it.
                                           (-> (expect (refused? (tool "bash" {:command "echo hi > apps.py"})))
                                               (.toBe true))
              ;; MCP-style arg naming must not slip past.
                                           (-> (expect (refused? (tool "edit" {:file_path "apps.py"}))) (.toBe true))
              ;; Traversal out of the spec dir resolves before it is compared.
                                           (-> (expect (refused? (tool "write"
                                                                       {:path ".specify/specs/auth/../../../apps.py"})))
                                               (.toBe true))

              ;; The phase's OWN work is what it is for.
                                           (-> (expect (tool "write" {:path ".specify/specs/auth/tasks.md"}))
                                               (.toBeFalsy))
                                           (-> (expect (tool "read" {:path "apps.py"})) (.toBeFalsy))

              ;; The refusal has to explain itself: `skip` still fires
              ;; tool_result, and an unexplained one just gets retried.
                                           (let [r (tool "write" {:path "apps.py"})]
                                             (-> (expect (.includes (str (.-result r)) "plan")) (.toBe true)))
                                           (when off (off)))))))

                               (it "stops gating once the loop reaches execute"
                                   (fn []
                                     (with-tmp
                                       (fn [_tmp _d]
                                         (let [{:keys [run end tool off]} (harness)]
                                           (run "start" "auth" "--force")
                                           (run "phase" "plan")
                                           (run "run")
                                           (end)                                   ; plan → execute
                                           (-> (expect (tool "write" {:path "apps.py"})) (.toBeFalsy))
                                           (-> (expect (tool "bash" {:command "pytest"})) (.toBeFalsy))
                                           (when off (off)))))))

                               (it "sends the phase's own prompt, not the execute one, while planning"
                                   (fn []
        ;; Telling a plan-phase turn to "do the NEXT unchecked task" is how a
        ;; scaffold tasks.md got implemented instead of replaced.
                                     (with-tmp
                                       (fn [_tmp d]
                                         (let [{:keys [run end off]} (harness)]
                                           (fs/writeFileSync (path/join d "tasks.md")
                                                             "# Tasks\n- [ ] T001 Replace this template\n")
                                           (fs/writeFileSync (path/join d "spec.md")
                                                             "# Spec\n[NEEDS CLARIFICATION: which store?]\n")
                                           (run "start" "auth" "--force")
                                           (run "phase" "plan")
                                           (run "run")
              ;; Held in plan by the open marker, so the next prompt is plan's.
                                           (let [r (end)]
                                             (-> (expect (:sent r)) (.toBe 0)))
                                           (-> (expect (.includes (spec/prompt-for-phase "plan") "NEEDS CLARIFICATION"))
                                               (.toBe true))
                                           (-> (expect (spec/prompt-for-phase "execute"))
                                               (.toBe spec/continue-prompt))
              ;; Anything unrecognised falls back to the loop's hot-path prompt,
              ;; whose bytes must not move — the provider cache depends on it.
                                           (-> (expect (spec/prompt-for-phase "nonsense"))
                                               (.toBe spec/continue-prompt))
                                           (when off (off)))))))))
