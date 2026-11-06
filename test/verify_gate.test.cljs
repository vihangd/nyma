(ns verify-gate.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [agent.extensions.verify-gate.shared :as shared]
            [agent.extensions.verify-gate.index :as vg]))

(defn- make-api [cmd sent]
  (let [handlers (atom {})
        ;; Emits get their own sink: the existing tests count `sent` to assert
        ;; how many follow-ups were injected, so mixing bus traffic in there
        ;; would silently change what those assertions mean.
        emits    (atom [])]
    {:api #js {:getSettings     (fn [] (if cmd #js {:verify #js {:cmd cmd :max-attempts 2}} #js {}))
               :settings (fn [sec] (let [all (if cmd #js {:verify #js {:cmd cmd :max-attempts 2}} #js {})] (if sec (or (get all sec) {}) (or all {}))))
               :on              (fn [evt h] (swap! handlers assoc evt h))
               :off             (fn [evt _] (swap! handlers dissoc evt))
               :sendMessage     (fn [_ _])
               :sendUserMessage (fn [text opts] (swap! sent conj {:text text :opts opts}))
               :emitGlobal      (fn [evt data] (swap! emits conj {:emit evt :data data}))}
     :handlers handlers
     :emits    emits}))

(defn- fire [handlers evt data]
  (when-let [h (get @handlers evt)] (h data nil)))

(describe "verify-gate shared" (fn []
                                 (it "config defaults + settings override"
                                     (fn []
                                       (-> (expect (:cmd (shared/config nil))) (.toBeFalsy))
                                       (let [c (shared/config #js {:verify #js {:cmd "bun test" :max-attempts 3}})]
                                         (-> (expect (:cmd c)) (.toBe "bun test"))
                                         (-> (expect (:max-attempts c)) (.toBe 3))
                                         (-> (expect (:timeout-ms c)) (.toBe 120000)))))

                                 (it "edit-tool? matches only editing tools"
                                     (fn []
                                       (-> (expect (shared/edit-tool? "edit")) (.toBe true))
                                       (-> (expect (shared/edit-tool? "write")) (.toBe true))
                                       (-> (expect (shared/edit-tool? "read")) (.toBe false))
                                       (-> (expect (shared/edit-tool? nil)) (.toBe false))))

                                 (it "tampered-paths flags test files and settings, not source"
                                     (fn []
                                       (let [flagged (shared/tampered-paths
                                                      #{"src/agent/tools.cljs"
                                                        "test/tools.test.cljs"
                                                        "spec/foo.spec.ts"
                                                        ".nyma/settings.json"})]
                                         (-> (expect (count flagged)) (.toBe 3))
                                         (-> (expect (.includes (clj->js flagged) "src/agent/tools.cljs")) (.toBe false)))))

                                 (it "failure-message tails long output"
                                     (fn []
                                       (let [long-out (.join (.map (js/Array.from #js {:length 100} (fn [_ i] i))
                                                                   (fn [i] (str "line" i)))
                                                             "\n")
                                             msg      (shared/failure-message "bun test" 1 long-out 1 2)]
                                         (-> (expect msg) (.toContain "line99"))
                                         (-> (expect msg) (.-not) (.toContain "line10\n"))
                                         (-> (expect msg) (.toContain "attempt 1/2")))))))

(describe "verify-gate loop" (fn []
                               (it "does nothing when no cmd configured"
                                   (fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api nil sent)]
                                       (vg/activate api)
                                       (-> (expect (count @handlers)) (.toBe 0)))))

                               (it "injects follow-up when the gate fails after an edit"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "echo FAILWHALE; exit 1" sent)]
                                       (vg/activate api)
                                       (fire handlers "tool_complete" #js {:toolName "edit" :isError false})
                                       (js-await (fire handlers "turn_finalize" #js {:error false}))
                                       (-> (expect (count @sent)) (.toBe 1))
                                       (-> (expect (:text (first @sent))) (.toContain "FAILWHALE"))
                                       (-> (expect (aget (:opts (first @sent)) "deliverAs")) (.toBe "followUp")))))

                               (it "stays quiet when the gate passes"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "exit 0" sent)]
                                       (vg/activate api)
                                       (fire handlers "tool_complete" #js {:toolName "write" :isError false})
                                       (js-await (fire handlers "turn_finalize" #js {:error false}))
                                       (-> (expect (count @sent)) (.toBe 0)))))

                               (it "stays quiet when nothing was edited"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "exit 1" sent)]
                                       (vg/activate api)
                                       (fire handlers "tool_complete" #js {:toolName "read" :isError false})
                                       (js-await (fire handlers "turn_finalize" #js {:error false}))
                                       (-> (expect (count @sent)) (.toBe 0)))))

                               (it "verifies the final fix and reports instead of looping at the cap"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "echo STILLRED; exit 1" sent)]
                                       (vg/activate api)
                                       (dotimes [_ 3]
                                         (fire handlers "tool_complete" #js {:toolName "edit" :isError false})
                                         (js-await (fire handlers "turn_finalize" #js {:error false})))
                                       ;; max-attempts 2 → 2 fix follow-ups, then the capped turn STILL
                                       ;; runs the gate and sends a report-only follow-up.
                                       (-> (expect (count @sent)) (.toBe 3))
                                       (-> (expect (:text (nth @sent 2))) (.toContain "Do NOT edit further"))
                                       (-> (expect (:text (nth @sent 2))) (.toContain "STILLRED")))))))

;;; ─── what the failure signal carries ─────────────────────────────────────
;;; self-tune asks the advisor to write a rule that prevents this CLASS of
;;; failure. It used to be handed only "verify command failed (exit 1)" — a
;;; rule written against an exit number is a guess. The gate already has the
;;; output in hand and already sends it to the model; it now puts it on the bus
;;; too.

(describe "what the gate tells the user"
          (fn []
            (it "a failure offers a way out besides changing the test"
                (fn []
                  (-> (expect (shared/failure-message "t" 1 "x" 1 2)) (.toContain "stop and say so"))))

            (it "a green gate after a test edit in the fix loop reaches the UI"
                (^:async
                 fn []
                 (let [sent   (atom [])
                       notes  (atom [])
                       marker (path/join (os/tmpdir) (str "nyma-green-" (js/Date.now)))
                       {:keys [api handlers]} (make-api (str "test -f " marker) sent)]
                   (aset api "ui" #js {:available true :notify (fn [m _] (swap! notes conj m))})
                   (vg/activate api)
                   (fire handlers "tool_complete" #js {:toolName "edit" :isError false
                                                       :args #js {:path "src/a.cljs"}})
                   (js-await (fire handlers "turn_finalize" #js {:error false}))
                   (fs/writeFileSync marker "")
                   (fire handlers "tool_complete" #js {:toolName "edit" :isError false
                                                       :args #js {:path "test/a.test.cljs"}})
                   (js-await (fire handlers "turn_finalize" #js {:error false}))
                   (fs/rmSync marker)
                   (-> (expect (first @notes)) (.toContain "test/a.test.cljs")))))))

(describe "verify-fail signal"
          (fn []
            (it "carries the failure output, not just the exit code"
                (^:async
                 fn []
                 (let [sent (atom [])
                       {:keys [api handlers emits]} (make-api "echo ASSERTION_XYZ; exit 1" sent)]
                   (vg/activate api)
                   (fire handlers "tool_complete" #js {:toolName "edit" :isError false})
                   (js-await (fire handlers "turn_finalize" #js {:error false}))
                   (let [signal (first @emits)]
                     (-> (expect signal) (.toBeTruthy))
                     (-> (expect (:emit signal)) (.toBe "small-model/verify-fail"))
                     (-> (expect (.-reason (:data signal))) (.toContain "exit 1"))
                     ;; The part that was missing.
                     (-> (expect (.-output (:data signal))) (.toContain "ASSERTION_XYZ"))))))

            (it "tails the output rather than shipping the whole log"
                (^:async
                 fn []
                 (let [sent (atom [])
                       cmd  "for i in $(seq 1 100); do echo line$i; done; exit 1"
                       {:keys [api handlers emits]} (make-api cmd sent)]
                   (vg/activate api)
                   (fire handlers "tool_complete" #js {:toolName "edit" :isError false})
                   (js-await (fire handlers "turn_finalize" #js {:error false}))
                   (let [out (str (.-output (:data (first @emits))))]
                     (-> (expect out) (.toContain "line100"))
                     (-> (expect (.includes out "line1\n")) (.toBe false))))))))

;;; ─── critic ──────────────────────────────────────────────────────────────

(defn- git! [dir & args]
  (cp/execFileSync "git" (clj->js args) #js {:cwd dir :encoding "utf8"}))

(defn- repo []
  (let [d (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "nyma-critic-")))]
    (git! d "init" "-q")
    (git! d "config" "user.email" "t@t") (git! d "config" "user.name" "t")
    (fs/writeFileSync (path/join d "a.txt") "one\n")
    (fs/writeFileSync (path/join d "mine.txt") "one\n")
    (git! d "add" ".") (git! d "commit" "-qm" "init")
    (fs/writeFileSync (path/join d "a.txt") "two\n")
    (fs/writeFileSync (path/join d "mine.txt") "users own edit\n")
    d))

(defn- critic-harness
  "A gate with a critic, its child stubbed to answer `reply`."
  [cmd reply]
  (let [sent  (atom [])
        calls (atom [])
        notes (atom [])
        all   #js {:verify #js {:cmd cmd :critic "reviewer"}}
        {:keys [api handlers]} (make-api cmd sent)]
    (aset api "settings" (fn [sec] (if sec (or (get all sec) {}) all)))
    (aset api "ui" #js {:available true :notify (fn [m _] (swap! notes conj m))})
    (aset api "getState" (fn [] {:messages [{:role "user" :content "fix the thing"}]}))
    (aset api "getTool" (fn [_] #js {:execute (fn [args _]
                                                (swap! calls conj args)
                                                (js/Promise.resolve reply))}))
    (vg/activate api)
    (fire handlers "agent_start" #js {})
    {:sent sent :calls calls :notes notes :handlers handlers}))

(defn- edit! [handlers p]
  (fire handlers "tool_complete" #js {:toolName "edit" :isError false :args #js {:path p}}))

(defn ^:async in-repo [f]
  (let [d (repo) was (js/process.cwd)]
    (js/process.chdir d)
    (try (js-await (f))
         (finally (js/process.chdir was)
                  (fs/rmSync d #js {:recursive true})))))

(def finding {:ok true :status "done" :text "a.txt:1 — wrong — input x"})

(describe "critic"
  (fn []
    (it "runs once per prompt, sees only this run's paths, and its findings go back"
        (fn []
          (in-repo
           ^:async (fn []
             (let [{:keys [sent calls handlers]} (critic-harness "exit 0" finding)]
               (edit! handlers "a.txt")
               (js-await (fire handlers "turn_finalize" #js {:error false}))
               (-> (expect (count @calls)) (.toBe 1))
               (let [task (.-task (first @calls))]
                 (-> (expect task) (.toContain "+two"))
                 (-> (expect task) (.toContain "fix the thing"))
                 (-> (expect task) .-not (.toContain "users own edit")))
               (-> (expect (:text (first @sent))) (.toContain "a.txt:1 — wrong"))
               ;; the fix is checked by cmd, and not reviewed again
               (edit! handlers "a.txt")
               (js-await (fire handlers "turn_finalize" #js {:error false}))
               (-> (expect (count @calls)) (.toBe 1))
               ;; the next prompt is a new run
               (fire handlers "agent_start" #js {})
               (edit! handlers "a.txt")
               (js-await (fire handlers "turn_finalize" #js {:error false}))
               (-> (expect (count @calls)) (.toBe 2)))))))

    (it "says nothing on No findings"
        (fn []
          (in-repo
           ^:async (fn []
             (let [{:keys [sent calls handlers]} (critic-harness "exit 0" {:ok true :text "No findings."})]
               (edit! handlers "a.txt")
               (js-await (fire handlers "turn_finalize" #js {:error false}))
               (-> (expect (count @calls)) (.toBe 1))
               (-> (expect (count @sent)) (.toBe 0)))))))

    (it "waits for green, and skips a turn that is not the end of the work"
        (fn []
          (in-repo
           ^:async (fn []
             (let [red (critic-harness "exit 1" finding)]
               (edit! (:handlers red) "a.txt")
               (js-await (fire (:handlers red) "turn_finalize" #js {:error false}))
               (-> (expect (count @(:calls red))) (.toBe 0)))
             (doseq [ev [#js {:error true} #js {:error false :stepCapped true} #js {:error false :nudged true}]]
               (let [{:keys [calls handlers]} (critic-harness "exit 0" finding)]
                 (edit! handlers "a.txt")
                 (js-await (fire handlers "turn_finalize" ev))
                 (-> (expect (count @calls)) (.toBe 0))))
             (let [{:keys [calls handlers]} (critic-harness "exit 0" finding)]
               (edit! handlers "a.txt")
               (js-await ((get @handlers "turn_finalize") #js {:error false}
                                                          #js {:hasPendingMessages (fn [] true)}))
               (-> (expect (count @calls)) (.toBe 0)))))))

    (it "a failed critic warns and never blocks"
        (fn []
          (in-repo
           ^:async (fn []
             (let [{:keys [sent notes handlers]} (critic-harness "exit 0" {:ok false :text "no model"})]
               (edit! handlers "a.txt")
               (js-await (fire handlers "turn_finalize" #js {:error false}))
               (-> (expect (count @sent)) (.toBe 0))
               (-> (expect (last @notes)) (.toContain "no model")))))))

    (it "takes at most three findings, and a path cannot reach a shell"
        (fn []
          (-> (expect (count (shared/critic-findings "a\nb\n\nc\nd"))) (.toBe 3))
          (-> (expect (:diff (shared/run-change ["$(touch /tmp/nyma-pwned)"] (js/process.cwd)))) (.toBe ""))
          (-> (expect (fs/existsSync "/tmp/nyma-pwned")) (.toBe false))))))
