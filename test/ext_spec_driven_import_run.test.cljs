(ns ext-spec-driven-import-run.test
  "`/spec import <name> --run` — the seam between a captured plan and the loop.

   A real run got this far and then stopped, silently. `/plan-capture` wrote
   `.nyma/plans/plan-….md`, `/spec import … --run` scaffolded the spec and
   reported \"Decomposition queued\" — and the spec's three files were still
   the untouched scaffold twenty minutes later. `tasks.md` said `First task`.

   Nothing in the existing tests could have caught it: they cover
   `compose-import-seed` and `extract-toc` as pure functions, so the seed's
   TEXT was verified while the question of whether anything ever RAN it was
   untested. These drive the real command handler against a real agent and
   assert on the follow-queue and on disk.

   Deliberately no scripted model. Proving the queued turn writes a good
   `tasks.md` would need an SSE fixture emitting tool calls, and it would only
   ever prove that a model whose output the test author wrote does what the
   test author wrote. The seam worth locking is queue → drain."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.loop :refer [run follow-up]]
            [agent.extensions.spec-driven.phases :as phases]
            ["./agent/extensions/spec_driven/index.mjs" :as spec-driven]
            [test-util.agent-harness :refer [make-test-agent block-provider!]]))

;;; ─── Harness ───────────────────────────────────────────────

(defn- activate!
  "The extension entry point is a default export, so it is reached through the
   module object rather than as a named var."
  [api]
  ((.-default spec-driven) api))

(def ^:private plan-fixture
  "Shaped like a real `/plan-capture` artifact: provenance frontmatter, then
   numbered steps naming absolute paths."
  (str "---\n"
       "source: agent-shell/claude\n"
       "mode: default\n"
       "captured: 2026-09-04T09:23:31.516Z\n"
       "request: add a token store\n"
       "---\n\n"
       "**Plan**\n\n"
       "1. Create `src/auth/store.ts` exporting `TokenStore`.\n"
       "2. Wire it into `src/auth/routes.ts` at the callback handler.\n"
       "3. Add `test/auth.test.ts` covering expiry.\n"
       "4. Check: `bun test test/auth.test.ts` exits 0.\n"))

(defn- with-tmp
  "Run body in a fresh cwd. spec_driven resolves everything from
   `process.cwd()`, so the temp dir has to be the working directory, not just
   an argument."
  [body]
  (let [tmp  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-specrun-"))
        prev (js/process.cwd)]
    (try (.chdir js/process tmp) (body tmp)
         (finally (.chdir js/process prev)
                  (try (fs/rmSync tmp #js {:recursive true :force true})
                       (catch :default _ nil))))))

(defn- write-plan!
  "Write the artifact where `/spec import` with no path looks for it."
  [tmp]
  (let [dir (path/join tmp ".nyma" "plans")]
    (fs/mkdirSync dir #js {:recursive true})
    (let [f (path/join dir "plan-2026-09-04T09-23-31-516Z.md")]
      (fs/writeFileSync f plan-fixture "utf8")
      f)))

(defn- harness
  "A mock extension API carrying the pieces spec_driven actually touches.
   `__state_atom` is the same atom `getState` reads, because the pending flag
   is written through one and read through the other."
  []
  (let [st    (atom {})
        notes (atom [])
        cmds  (atom {})]
    {:api #js {:ui                #js {:available true
                                       :notify (fn [m & _] (swap! notes conj m))}
               :__state_atom      st
               :getState          (fn [] @st)
               :registerCommand   (fn [n c] (swap! cmds assoc n c))
               :unregisterCommand (fn [n] (swap! cmds dissoc n))
               :registerStatusSegment (fn [_ _] nil)
               :unregisterStatusSegment (fn [_] nil)
               :on                (fn [_e _h & _] nil)
               :off               (fn [_e _h] nil)
               :sendUserMessage   (fn [_t _o] nil)
               :getSettings       (fn [] #js {})}
     :state st :notes notes :cmds cmds}))

(defn- spec-cmd!
  "Activate spec_driven and invoke /spec with `args`, passing a ctx that
   carries the real agent — `(aget ctx \"agent\")` is the seam import needs
   and the one place a nil would degrade the whole flow to a warning."
  [{:keys [api cmds]} agent args]
  (activate! api)
  (let [handler (.-handler (get @cmds "spec"))]
    (handler (clj->js args) #js {:agent agent :ui (.-ui api)})))

(defn- tasks-progress
  "Parse the spec's tasks.md the way the loop does — `progress` takes the
   already-parsed list plus the raw text, so it can tell an empty file from
   one whose every box is ticked."
  [tmp name]
  (let [raw (fs/readFileSync (path/join tmp ".specify" "specs" name "tasks.md") "utf8")]
    (phases/progress ((.-parse_tasks spec-driven) raw) raw)))

;;; ─── Import queues the decomposition ───────────────────────

(defn test-import-run-queues-a-turn []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            h     (harness)]
        (spec-cmd! h agent ["import" "token-store" "--run"])
        ;; The spec exists and its tasks are still the scaffold — that is the
        ;; expected state at this instant, and exactly the state the real run
        ;; never left.
        (-> (expect (fs/existsSync (path/join tmp ".specify" "specs" "token-store" "tasks.md")))
            (.toBe true))
        (-> (expect (phases/template-tasks? (tasks-progress tmp "token-store")))
            (.toBe true))
        ;; The assertion the old tests were missing: something is queued to
        ;; act on it. A passing `compose-import-seed` test plus an empty queue
        ;; is precisely the bug that shipped.
        (-> (expect (count @(:follow-queue agent))) (.toBe 1))
        (let [seed (str (:content (first @(:follow-queue agent))))]
          (-> (expect (.includes seed "token-store")) (.toBe true))
          (-> (expect (.includes seed ".nyma/plans/")) (.toBe true)))))))

(defn test-import-run-marks-pending []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [state] :as h} (harness)]
        (spec-cmd! h agent ["import" "token-store" "--run"])
        ;; Written with `(.-__state-atom api)` and read back through
        ;; `getState` — squint munges the hyphen, so this locks that the two
        ;; halves meet.
        (-> (expect (boolean (or (:spec-loop-pending @state)
                                 (get @state "spec-loop-pending"))))
            (.toBe true))
        (-> (expect (or (:active-spec @state) (get @state "active-spec")))
            (.toBe "token-store"))))))

(defn test-import-without-run-queues-but-stays-inactive []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [state] :as h} (harness)]
        (spec-cmd! h agent ["import" "token-store"])
        ;; Decomposition is queued either way — `--run` only decides whether
        ;; the spec activates and the loop arms behind it.
        (-> (expect (count @(:follow-queue agent))) (.toBe 1))
        (-> (expect (boolean (or (:spec-loop-pending @state)
                                 (get @state "spec-loop-pending"))))
            (.toBe false))))))

(defn test-import-without-agent-does-not-claim-success []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [{:keys [cmds api notes state]} (harness)]
        (activate! api)
        ;; No :agent on ctx — the branch that cannot queue anything. It must
        ;; say so rather than print "Decomposition queued", and must not
        ;; activate a spec that nothing is going to fill in.
        ((.-handler (get @cmds "spec")) #js ["import" "token-store" "--run"]
                                        #js {:ui (.-ui api)})
        (let [all (apply str @notes)]
          (-> (expect (.includes all "no agent")) (.toBe true))
          (-> (expect (.includes all "Decomposition queued")) (.toBe false)))
        (-> (expect (or (:active-spec @state) (get @state "active-spec")))
            (.toBeUndefined))))))

;;; ─── The queue actually drains ─────────────────────────────

(defn ^:async test-queued-follow-up-runs-at-turn-end []
  (let [agent (make-test-agent)]
    (block-provider! agent)
    (follow-up agent {:role "user" :content "DECOMPOSE-ME"})
    (js-await (run agent "go"))
    ;; The half that failed in the real run: the seed only becomes a turn when
    ;; some other turn ends. Both must be in the transcript, in order.
    (let [contents (mapv #(str (:content %)) (:messages @(:state agent)))]
      (-> (expect (.indexOf (clj->js contents) "go")) (.toBeGreaterThanOrEqual 0))
      (-> (expect (some #(= "DECOMPOSE-ME" %) contents)) (.toBe true))
      (-> (expect (count @(:follow-queue agent))) (.toBe 0)))))

(defn ^:async test-queue-survives-a-failed-turn []
  (let [agent (make-test-agent)]
    (follow-up agent {:role "user" :content "DECOMPOSE-ME"})
    ;; A provider error propagates out of `run` and the drain is skipped —
    ;; the throw precedes it. So the item is DEFERRED, not lost: it waits for
    ;; the next turn that ends cleanly. Worth pinning because the plausible
    ;; regression is the opposite, dropping queued work on a transient 429.
    ((:on (:events agent)) "before_agent_start"
                           (fn [_] (throw (js/Error. "provider exploded"))))
    (let [threw (atom false)]
      (js-await (-> (run agent "go")
                    (.catch (fn [_] (reset! threw true)))))
      (-> (expect @threw) (.toBe true))
      ;; Still queued, not lost — the work is deferred to the next good turn
      ;; rather than dropped.
      (-> (expect (count @(:follow-queue agent))) (.toBe 1)))))

;;; ─── Registration ──────────────────────────────────────────

(describe "/spec import --run queues a decomposition"
          (fn []
            (it "queues exactly one follow-up naming the spec and the artifact"
                test-import-run-queues-a-turn)
            (it "activates the spec and marks the loop pending"
                test-import-run-marks-pending)
            (it "queues without --run, but leaves the spec inactive"
                test-import-without-run-queues-but-stays-inactive)
            (it "refuses to claim success when there is no agent to queue onto"
                test-import-without-agent-does-not-claim-success)))

(describe "the follow-queue is what makes the decomposition run"
          (fn []
            (it "drains a queued follow-up at the end of the next turn"
                test-queued-follow-up-runs-at-turn-end)
            (it "keeps queued work when a turn fails rather than dropping it"
                test-queue-survives-a-failed-turn)))

;;; ─── Pending survives a restart, honestly ──────────────────

;; `--run` persisted :active-spec but kept :spec-loop-pending in the state
;; atom alone. A restart therefore resumed with the spec active and injected
;; into every turn, no pending flag, and — the follow-queue being in memory —
;; no queued decomposition either. The loop could never arm and nothing said
;; so; the observed session sat on `First task` indefinitely.

(defn- ext-state-file [tmp]
  (path/join tmp ".nyma" "ext-state" "spec-driven.json"))

(defn- read-ext-state
  "The raw JS object — `js->clj` is not available here, and the store writes
   plain JSON anyway."
  [tmp]
  (if (fs/existsSync (ext-state-file tmp))
    (js/JSON.parse (fs/readFileSync (ext-state-file tmp) "utf8"))
    #js {}))

(defn- write-ext-state! [tmp obj]
  (fs/mkdirSync (path/dirname (ext-state-file tmp)) #js {:recursive true})
  (fs/writeFileSync (ext-state-file tmp) (js/JSON.stringify obj) "utf8"))

(defn- persistent-state
  "A `state` capability backed by the same file the real store uses, so the
   test observes what a restart would actually read."
  [tmp]
  #js {:get    (fn [k] (aget (read-ext-state tmp) (str k)))
       :set    (fn [k v] (let [o (read-ext-state tmp)]
                           (aset o (str k) v)
                           (write-ext-state! tmp o)))
       :delete (fn [k] (let [o (read-ext-state tmp)]
                         (js-delete o (str k))
                         (write-ext-state! tmp o)))})

(defn test-run-persists-pending []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            h     (harness)]
        (aset (:api h) "state" (persistent-state tmp))
        (spec-cmd! h agent ["import" "token-store" "--run"])
        ;; Both, or a restart resumes half-configured.
        (-> (expect (aget (read-ext-state tmp) "active-spec")) (.toBe "token-store"))
        (-> (expect (aget (read-ext-state tmp) "spec-loop-pending")) (.toBe true))))))

(defn test-restart-clears-pending-and-says-so []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            h1    (harness)]
        (aset (:api h1) "state" (persistent-state tmp))
        (spec-cmd! h1 agent ["import" "token-store" "--run"])
        ;; Second process: same disk, fresh atom, and crucially a fresh agent
        ;; whose follow-queue is empty — the seed is gone.
        (let [{:keys [api notes state]} (harness)]
          (aset api "state" (persistent-state tmp))
          (activate! api)
          (-> (expect (or (:active-spec @state) (get @state "active-spec")))
              (.toBe "token-store"))
          ;; The flag must NOT come back: nothing is queued to satisfy it, so
          ;; a restored `pending` would leave the loop waiting forever.
          (-> (expect (boolean (or (:spec-loop-pending @state)
                                   (get @state "spec-loop-pending"))))
              (.toBe false))
          (-> (expect (aget (read-ext-state tmp) "spec-loop-pending")) (.toBeUndefined))
          ;; And it has to say what to do, since the user's only other signal
          ;; is a tasks.md that never changes.
          (let [all (apply str @notes)]
            (-> (expect (.includes all "did not survive")) (.toBe true))
            (-> (expect (.includes all "--force --run")) (.toBe true))))))))

(defn test-restart-without-pending-is-silent []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            h1    (harness)]
        (aset (:api h1) "state" (persistent-state tmp))
        ;; No --run: nothing pending, so a restart must not nag.
        (spec-cmd! h1 agent ["import" "token-store"])
        (let [{:keys [api notes]} (harness)]
          (aset api "state" (persistent-state tmp))
          (activate! api)
          (-> (expect (.includes (apply str @notes) "did not survive")) (.toBe false)))))))

(describe "a pending decomposition is persisted, and recoverable"
          (fn []
            (it "--run writes both active-spec and the pending flag to disk"
                test-run-persists-pending)
            (it "clears a restored pending flag and says how to recover"
                test-restart-clears-pending-and-says-so)
            (it "stays quiet on restart when nothing was pending"
                test-restart-without-pending-is-silent)))

;;; ─── Re-import over an existing spec ───────────────────────

;; The restart notice and the documented refine-and-retry loop both say to run
;; `/spec import <name> --force --run`. `--force` did not exist on import — it
;; was parsed by `/spec start` only — so create-spec! answered "Spec already
;; exists" and the recovery path dead-ended on advice that could not work.

(defn test-reimport-without-force-refuses []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [notes] :as h} (harness)]
        (spec-cmd! h agent ["import" "token-store" "--run"])
        (reset! notes [])
        (spec-cmd! h agent ["import" "token-store" "--run"])
        (-> (expect (.includes (apply str @notes) "already exists")) (.toBe true))))))

(defn test-reimport-with-force-replaces []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [notes] :as h} (harness)]
        (spec-cmd! h agent ["import" "token-store" "--run"])
        ;; Stand in for a spec that has been worked on: real tasks, one ticked.
        (fs/writeFileSync (path/join tmp ".specify" "specs" "token-store" "tasks.md")
                          "# token-store — Tasks\n\n- [x] Build the store\n- [ ] Wire the route\n"
                          "utf8")
        (reset! notes [])
        (spec-cmd! h agent ["import" "token-store" "--force" "--run"])
        (let [all (apply str @notes)]
          (-> (expect (.includes all "already exists")) (.toBe false))
          ;; Replacing real progress must be visible, not silent.
          (-> (expect (.includes all "replaced the previous spec")) (.toBe true))
          (-> (expect (.includes all "1 of 2 tasks were already ticked")) (.toBe true)))
        ;; Back to scaffold, and a fresh decomposition queued for it.
        (-> (expect (phases/template-tasks? (tasks-progress tmp "token-store"))) (.toBe true))
        ;; Two seeds, because the first was never consumed — re-importing
        ;; before sending any message stacks them. Left alone deliberately:
        ;; both decompose the same spec from the same artifact, so the second
        ;; simply redoes the first, costing one turn and converging on the
        ;; same tasks.md. Draining the queue selectively would mean an
        ;; extension reaching into the agent's internals to undo work it did
        ;; not queue.
        (-> (expect (count @(:follow-queue agent))) (.toBe 2))))))

(defn test-force-on-a-new-spec-is-a-no-op []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [notes] :as h} (harness)]
        ;; Nothing to replace — it must not claim it replaced anything.
        (spec-cmd! h agent ["import" "token-store" "--force" "--run"])
        (let [all (apply str @notes)]
          (-> (expect (.includes all "✓ Imported")) (.toBe true))
          (-> (expect (.includes all "replaced the previous spec")) (.toBe false)))))))

(defn test-force-is-not-mistaken-for-a-path []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [notes] :as h} (harness)]
        ;; Flags are stripped before positional parsing; `--force` sitting where
        ;; the path goes must still resolve the newest artifact.
        (spec-cmd! h agent ["import" "token-store" "--force"])
        (-> (expect (.includes (apply str @notes) "✓ Imported")) (.toBe true))
        (-> (expect (count @(:follow-queue agent))) (.toBe 1))))))

(defn test-force-applies-to-directory-mode []
  (with-tmp
    (fn [tmp]
      (write-plan! tmp)
      (let [agent (make-test-agent)
            {:keys [notes] :as h} (harness)]
        (spec-cmd! h agent ["import" "token-store" "--run"])
        ;; A source DIRECTORY takes the other import branch — which also goes
        ;; through create-spec!, so it refused on an existing name while the
        ;; help text promised --force would replace it.
        (let [src (path/join tmp "incoming")]
          (fs/mkdirSync src #js {:recursive true})
          (fs/writeFileSync (path/join src "spec.md") "# imported\n\nreal content\n" "utf8")
          (reset! notes [])
          (spec-cmd! h agent ["import" "token-store" src "--force"])
          (let [all (apply str @notes)]
            (-> (expect (.includes all "already exists")) (.toBe false))
            (-> (expect (.includes all "replaced the previous spec")) (.toBe true)))
          (-> (expect (.includes (fs/readFileSync
                                  (path/join tmp ".specify" "specs" "token-store" "spec.md")
                                  "utf8")
                                 "real content"))
              (.toBe true)))))))

(describe "/spec import --force is the recovery path"
          (fn []
            (it "refuses a re-import without it" test-reimport-without-force-refuses)
            (it "replaces the spec and reports what was discarded"
                test-reimport-with-force-replaces)
            (it "says nothing about replacing when there was nothing there"
                test-force-on-a-new-spec-is-a-no-op)
            (it "is stripped before the path argument is read"
                test-force-is-not-mistaken-for-a-path)
            (it "applies to directory mode too, not just file mode"
                test-force-applies-to-directory-mode)))
