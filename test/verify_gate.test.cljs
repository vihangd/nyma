(ns verify-gate.test
  (:require ["bun:test" :refer [describe it expect]]
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
