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
        hooks  (atom {})
        nudges (atom [])
        ;; the real state — :all-tool-sigs must start as a SET or `contains?`
        ;; silently never matches and the detector is inert
        state  (shared/make-state)
        api #js {:addMiddleware (fn [m] (reset! mw m))
                 :on            (fn [ev f] (swap! hooks assoc ev f) nil)
                 :off           (fn [& _] nil)
                 ;; PRODUCTION SHAPE: extensions.cljs returns (clj->js (keys …)),
                 ;; i.e. a JS ARRAY of names — not an object. Mocking an object
                 ;; here is what let the hallucination bug through: Object.keys
                 ;; on an array yields ["0" "1" "2"], so every real tool name
                 ;; failed the membership check and every result was destroyed.
                 :getAllTools   (fn [] #js ["bash" "read" "write"])
                 :emitGlobal    (fn [ev data] (swap! signals conj [ev (.-reason data)]))
                 :sendUserMessage (fn [msg & _] (swap! nudges conj (str msg)) nil)}]
    (qm/activate api {:quality-monitor {:enabled true}} state)
    {:mw mw :signals signals :state state :hooks hooks :nudges nudges}))

(defn- turn!
  "Run one turn through the hooks: text pieces streamed, then N tool calls."
  [h pieces tool-calls]
  (let [hk @(:hooks h)]
    ((get hk "turn_start") #js {} nil)
    (doseq [p pieces] ((get hk "stream_filter") #js {:chunk p :delta p} nil))
    ((get hk "turn_finalize") #js {:toolCalls tool-calls :error false} nil)))

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

(describe "small-model/quality-monitor tool-name recognition"
  (fn []
    (it "recognises a real tool from the array getAllTools actually returns"
        ;; the whole extension scored 10% against an 80% baseline because this
        ;; check inverted: every call looked like a hallucinated tool name
        (fn []
          (let [h (harness)
                r (call! h "bash" {:command "ls"} "a.txt")]
            (-> (expect r) (.toContain "a.txt"))
            (-> (expect (str r)) (.not.toMatch #"(?i)doesn't exist|available tools")))))

    (it "still catches a genuinely unknown tool name"
        (fn []
          (let [h (harness)
                r (call! h "definitely_not_a_tool" {} "")]
            (-> (expect (str r)) (.toContain "definitely_not_a_tool")))))

    (it "tolerates an object-shaped tool map too"
        ;; getActiveTools/getAllTools have differed before; accept both rather
        ;; than depend on which one the host hands over
        (fn []
          (let [mw (atom nil)
                st (shared/make-state)
                api #js {:addMiddleware (fn [m] (reset! mw m))
                         :on (fn [& _] nil) :off (fn [& _] nil)
                         :getAllTools (fn [] #js {:bash #js {} :read #js {}})
                         :emitGlobal (fn [& _] nil) :sendUserMessage (fn [& _] nil)}
                _ (qm/activate api {:quality-monitor {:enabled true}} st)
                ctx (js-obj "tool-name" "bash" "args" #js {} "result" "real output")]
            ((.-leave @mw) ctx)
            (-> (expect (.-result ctx)) (.toContain "real output")))))))


;; ── the abort that killed runs ───────────────────────────────────
;; `stream_filter` fires only on TEXT deltas, so it cannot see tool calls. The
;; empty-turn check aborted mid-stream on a blank delta, which is exactly what a
;; turn that goes straight to a tool call looks like. Two aborts exhausted the
;; loop's retry budget and ended the run with finishReason
;; "stream-filter-aborted" — measured on python/bottle-song as 4 turns and 66k
;; tokens with the stub never touched, a task that passes with this module off.
;; This was the SECOND false positive from the same heuristic, and the first fix
;; shipped without a test pinning the tool-call case.
(describe "small-model/quality-monitor empty-turn detection"
          (fn []
            (it "does not nudge a silent turn that called a tool"
        ;; the behaviour you WANT from a small model: no preamble, just act
                (fn []
                  (let [h (harness)]
                    (turn! h [""] 1)
                    (-> (expect (count @(:nudges h))) (.toBe 0)))))

            (it "does not nudge a turn that produced real text"
                (fn []
                  (let [h (harness)]
                    (turn! h ["Let me read the file"] 0)
                    (-> (expect (count @(:nudges h))) (.toBe 0)))))

            (it "nudges a turn that produced neither text nor a tool call"
                (fn []
                  (let [h (harness)]
                    (turn! h ["" "  "] 0)
                    (-> (expect (count @(:nudges h))) (.toBe 1)))))

            (it "escalates the wording, and re-arms after a productive turn"
                (fn []
                  (let [h (harness)]
                    (turn! h [""] 0)
                    (turn! h [""] 0)
                    (-> (expect (second @(:nudges h))) (.toContain "Empty response again"))
                    (turn! h [""] 1)          ;; productive: resets the counter
                    (turn! h [""] 0)
                    (-> (expect (last @(:nudges h))) (.toContain "empty or only whitespace")))))))
