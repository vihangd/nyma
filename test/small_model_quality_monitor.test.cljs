(ns small-model-quality-monitor.test
  "The repeat-call detector replaced a tool's REAL result with a scolding
   message. For a coding agent that is not a loop guard, it is blindness: the
   fix→verify cycle runs `bash python3 -m unittest ...` byte-identically every
   time, so the second run — the one that would tell the model whether its edit
   worked — returned \"You already ran this\" instead of the test output.

   Measured, not theorised: qwen3.6-35b-a3b scored 80% on ten tasks with this
   extension off and 10% with it on, and said so itself — \"my environment only
   has write and edit tools working\".

   The nudge also asserted \"The result hasn't changed\" without ever looking at
   the result."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.quality-monitor :as qm]
            [agent.extensions.small-model.shared :as shared]))

(defn- harness []
  (let [mw     (atom nil)
        signals (atom [])
        ;; the real state — :all-tool-sigs must start as a SET or `contains?`
        ;; silently never matches and the detector is inert
        state  (shared/make-state)
        api #js {:addMiddleware (fn [m] (reset! mw m))
                 :on            (fn [& _] nil)
                 :off           (fn [& _] nil)
                 :getAllTools   (fn [] #js {:bash #js {} :read #js {} :write #js {}})
                 :emitGlobal    (fn [ev data] (swap! signals conj [ev (.-reason data)]))
                 :sendUserMessage (fn [& _] nil)}]
    (qm/activate api {:quality-monitor {:enabled true}} state)
    {:mw mw :signals signals :state state}))

(defn- call! [h tool args result]
  ;; the middleware reads ctx["tool-name"], not ctx.toolName
  (let [ctx (js-obj "tool-name" tool "args" (clj->js args) "result" result)]
    ((.-leave @(:mw h)) ctx)
    (.-result ctx)))

(describe "small-model/quality-monitor repeat detection"
          (fn []
            (it "keeps the real result when the same command is run again"
        ;; the fix→verify cycle: run tests, edit, run the SAME tests again
                (fn []
                  (let [h (harness)
                        cmd {:command "python3 -m unittest discover"}
                        r1 (call! h "bash" cmd "FAILED (failures=25)")
                        r2 (call! h "bash" cmd "OK (25 tests)")]
                    (-> (expect r1) (.toContain "FAILED"))
            ;; the model must be able to see that its edit worked
                    (-> (expect r2) (.toContain "OK (25 tests)")))))

            (it "still flags a genuine loop — same call, same result"
                (fn []
                  (let [h (harness)
                        cmd {:command "ls"}
                        _  (call! h "bash" cmd "a.txt")
                        r2 (call! h "bash" cmd "a.txt")]
                    (-> (expect r2) (.toContain "a.txt"))
                    (-> (expect (str r2)) (.toMatch #"(?i)already ran|do not repeat|stop repeating"))
                    (-> (expect (mapv second @(:signals h))) (.toContain "repeat tool call: bash")))))

            (it "does not flag a repeat whose result changed"
                (fn []
                  (let [h (harness)
                        cmd {:command "cat log"}
                        _  (call! h "bash" cmd "line 1")
                        r2 (call! h "bash" cmd "line 1\nline 2")]
                    (-> (expect (str r2)) (.not.toMatch #"(?i)already ran|do not repeat")))))

            (it "still replaces the result for a hallucinated tool"
        ;; there is no real result to protect in that case
                (fn []
                  (let [h (harness)
                        r (call! h "nonexistent_tool" {} "")]
                    (-> (expect (str r)) (.toContain "nonexistent_tool")))))))
