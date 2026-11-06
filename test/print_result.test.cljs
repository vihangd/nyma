(ns print-result.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.extensions.budget.shared :as budget]
            [agent.modes.print :refer [result-object last-assistant-text start-result]]))

(defn- fake-agent [state-map session]
  {:state (atom state-map) :session (atom session)})

(describe "print:result-object (claude-style)"
          (fn []
            (it "emits a claude-style result with text + usage + cost"
                (fn []
                  (let [a (fake-agent
                           {:messages [{:role "user" :content "q"}
                                       {:role "assistant" :content "the answer"}]
                            :total-input-tokens 100 :total-output-tokens 50 :total-cost 0.0123}
                           nil)
                        r (result-object a 42 nil)]
                    (-> (expect (.-type r)) (.toBe "result"))
                    (-> (expect (.-is_error r)) (.toBe false))
                    (-> (expect (.-result r)) (.toBe "the answer"))
                    (-> (expect (.-total_cost_usd r)) (.toBe 0.0123))
                    (-> (expect (.. r -usage -input_tokens)) (.toBe 100))
                    (-> (expect (.. r -usage -output_tokens)) (.toBe 50))
                    (-> (expect (.. r -usage -total_tokens)) (.toBe 150))
                    (-> (expect (.-duration_ms r)) (.toBe 42))
                    (-> (expect (string? (.-session_id r))) (.toBe true))
                    (-> (expect (pos? (count (.-session_id r)))) (.toBe true)))))

            (it "extracts text from array content (last assistant)"
                (fn []
                  (let [a (fake-agent
                           {:messages [{:role "assistant"
                                        :content [#js {:type "text" :text "part A"}
                                                  #js {:type "text" :text "part B"}]}
                                       {:role "tool" :content "ignored tool output"}]}
                           nil)
                        r (result-object a 1 nil)]
                    (-> (expect (.-result r)) (.toContain "part A"))
                    (-> (expect (.-result r)) (.toContain "part B"))
                    (-> (expect (.-result r)) (.not.toContain "ignored")))))

            (it "derives session_id from the session file path"
                (fn []
                  (let [a (fake-agent
                           {:messages [{:role "assistant" :content "x"}]}
                           {:get-file-path (fn [] "/home/u/.nyma/sessions/2026/abc123.jsonl")})
                        r (result-object a 1 nil)]
                    (-> (expect (.-session_id r)) (.toBe "abc123")))))

            (it "defaults token counts to 0 when absent"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "y"}]} nil)
                        r (result-object a 0 nil)]
                    (-> (expect (.. r -usage -input_tokens)) (.toBe 0))
                    (-> (expect (.. r -usage -total_tokens)) (.toBe 0)))))

            (it "reports cost as null when the model has no known rates"
                (fn []
                  ;; 0 says "this was free". An unpriced model — every local one —
                  ;; is not free, it is unmeasured, and a machine consumer of
                  ;; `-p --output-format json` could not tell the two apart.
                  (let [a (fake-agent {:messages [{:role "assistant" :content "y"}]} nil)
                        r (result-object a 0 nil)]
                    (-> (expect (.-total_cost_usd r)) (.toBeNull))))) 

            (it "reports null when a priced session's spend went to an unpriced model"
                (fn []
                  (let [a (assoc (fake-agent {:messages [{:role "assistant" :content "y"}]
                                              :cost-unknown? true} nil)
                                 :config #js {:model #js {:modelId "claude-sonnet-5"}})
                        r (result-object a 0 nil)]
                    (-> (expect (.-total_cost_usd r)) (.toBeNull))
                    (-> (expect (.-total_cost_partial r)) (.toBe false)))))

            (it "marks a total that leaves out unpriced spend as partial"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "y"}]
                                       :total-cost 0.0123 :cost-unknown? true} nil)
                        r (result-object a 0 nil)]
                    (-> (expect (.-total_cost_usd r)) (.toBe 0.0123))
                    (-> (expect (.-total_cost_partial r)) (.toBe true)))))

            (it "reports 0 for a PRICED model that genuinely spent nothing"
                (fn []
                  ;; the other half of the contract: null must mean "unknown",
                  ;; not "zero", or it is the same conflation the other way round
                  (let [a (assoc (fake-agent {:messages [{:role "assistant" :content "y"}]} nil)
                                 :config #js {:model #js {:modelId "claude-sonnet-5"}})
                        r (result-object a 0 nil)]
                    (-> (expect (.-total_cost_usd r)) (.toBe 0)))))

            (it "a real cost is reported whether or not the model is priced"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "y"}]
                                       :total-cost 0.0123} nil)
                        r (result-object a 0 nil)]
                    (-> (expect (.-total_cost_usd r)) (.toBe 0.0123)))))))

;; is_error so orchestrators (cw) can fall back on flaky/empty small-model replies
(describe "print:result-object is_error"
          (fn []
            (it "flags is_error when run threw"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "partial"}]} nil)
                        r (result-object a 5 "No model configured")]
                    (-> (expect (.-is_error r)) (.toBe true))
                    (-> (expect (.-result r)) (.toContain "No model configured")))))

            (it "flags is_error when the assistant produced no text (empty reply)"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "   "}]} nil)
                        r (result-object a 5 nil)]
                    (-> (expect (.-is_error r)) (.toBe true))
                    (-> (expect (.-result r)) (.toContain "empty")))))

            (it "flags is_error when there is no assistant message at all"
                (fn []
                  (let [a (fake-agent {:messages [{:role "user" :content "q"}]} nil)
                        r (result-object a 5 nil)]
                    (-> (expect (.-is_error r)) (.toBe true)))))))

;;; ─── reasoning in one-shot output ───────────────────────────
;;; A reasoning model hands back <think>…</think> around its answer. Printing
;;; that as `result` gives an orchestrator the deliberation instead of the
;;; answer — but blanking a reply that is ONLY reasoning would be worse, so
;;; that case passes through intact.

(describe "one-shot output strips reasoning"
          (fn []
            (it "keeps the answer and drops the think block"
                (fn []
                  (let [agent (fake-agent {:messages [{:role "user" :content "hi"}
                                                      {:role "assistant"
                                                       :content "<think>let me see</think>\nThe answer is 42."}]} nil)
                        r     (result-object agent 10 nil)]
                    (-> (expect (.-result r)) (.toBe "The answer is 42."))
                    (-> (expect (.-is_error r)) (.toBe false)))))

            (it "passes a reasoning-only reply through rather than blanking it"
                (fn []
                  (let [agent (fake-agent {:messages [{:role "user" :content "hi"}
                                                      {:role "assistant" :content "<think>ok then</think>\n\n"}]} nil)
                        r     (result-object agent 10 nil)]
                    (-> (expect (.-result r)) (.toContain "ok then")))))

            (it "leaves a plain answer untouched"
                (fn []
                  (let [agent (fake-agent {:messages [{:role "user" :content "hi"}
                                                      {:role "assistant" :content "ok"}]} nil)]
                    (-> (expect (.-result (result-object agent 10 nil))) (.toBe "ok")))))))

;;; ─── `-p` text mode prints the same answer as `--output-format json` ───
;;; Text mode printed `(:content (last messages))`: whatever message happened to
;;; be last — a tool_result, a user echo — with `<think>` blocks intact. The two
;;; one-shot paths disagreed about what the answer even was.

(describe "-p text mode"
          (fn []
            (it "prints the last assistant message, not the last message"
                (fn []
                  (let [msgs [{:role "user" :content "q"}
                              {:role "assistant" :content "the answer"}
                              {:role "tool_result" :content "grep: 14 matches"}]]
                    (-> (expect (last-assistant-text msgs)) (.toBe "the answer")))))

            (it "strips <think> blocks from the printed text"
                (fn []
                  (let [msgs [{:role "assistant"
                               :content "<think>weighing options</think>\nThe answer is 42."}]]
                    (-> (expect (last-assistant-text msgs)) (.toBe "The answer is 42.")))))

            (it "prints exactly what --output-format json puts in `result`"
                (fn []
                  (let [msgs [{:role "user" :content "q"}
                              {:role "assistant" :content "<think>hm</think>\nfinal"}
                              {:role "tool_result" :content "noise"}]
                        a    (fake-agent {:messages msgs} nil)]
                    (-> (expect (last-assistant-text msgs))
                        (.toBe (.-result (result-object a 1 nil)))))))))

;;; ─── exit code ──────────────────────────────────────────────
;;; Text mode has always exited 1 on failure; JSON mode exited 0 on every
;;; outcome, so `nyma -p --output-format json && deploy` deployed on a failed
;;; run. A shell's only error channel is the exit code.

(defn ^:async test-json-mode-exit-1 []
  (let [orig-code (.-exitCode js/process)
        orig-log  js/console.log
        lines     (atom [])]
    (set! (.-exitCode js/process) 0)
    (set! js/console.log (fn [line] (swap! lines conj (str line))))
    (try
      ;; A bare map is not a runnable agent: `run` throws, which is the same
      ;; path a provider error takes.
      (js-await (start-result (fake-agent {:messages []} nil) "hi"))
      (finally
        (set! js/console.log orig-log)))
    (let [code (.-exitCode js/process)
          out  (js/JSON.parse (first @lines))]
      ;; Restore before asserting — a failed expect must not leak exitCode 1
      ;; into bun's own status and redden the whole suite.
      (set! (.-exitCode js/process) (or orig-code 0))
      (-> (expect (.-is_error out)) (.toBe true))
      (-> (expect code) (.toBe 1))
      ;; The JSON still reaches stdout intact — the exit code is additive.
      (-> (expect (.-type out)) (.toBe "result")))))

(describe "--output-format json exit code"
          (fn []
            (it "exits 1 when is_error is true" test-json-mode-exit-1)))

;; ── a stopped run says so ──
(def ^:private usage-part
  #js {:inputTokens #js {:total 10} :outputTokens #js {:total 5}})

(def ^:private step-one
  #js [#js {:type "text-start" :id "t"}
       #js {:type "text-delta" :id "t" :delta "partial"}
       #js {:type "text-end" :id "t"}
       #js {:type "tool-call" :toolCallId "c1" :toolName "think" :input "{\"thought\":\"x\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool_use"} :usage usage-part}])

(defn- model-of [calls]
  (new MockLanguageModelV4
       ;; The SDK still calls after an abort, with the signal already aborted:
       ;; a real fetch rejects at once. Only a live call costs anything.
       #js {:doStream (fn [o]
                        (when-not (.. o -abortSignal -aborted) (swap! calls inc))
                        (js/Promise.resolve #js {:stream (convertArrayToReadableStream step-one)}))}))

(defn ^:async stopped-result [reason]
  (let [calls (atom 0)
        agent (create-agent {:model (model-of calls) :system-prompt "x" :max-steps 5})]
    ((:on (:events agent)) "turn_end"
                           (fn [_] (.abort @(:abort-controller agent) reason)))
    (js-await (run agent "go"))
    [(result-object agent 1 nil) @calls]))

(defn ^:async test-cap-stop []
  (let [[r calls] (js-await (stopped-result (budget/stop-reason "error_max_budget_tokens" "turn cap")))]
    (-> (expect calls) (.toBe 1))
    (-> (expect (.-is_error r)) (.toBe true))
    (-> (expect (.-subtype r)) (.toBe "error_max_budget_tokens"))))

(defn ^:async test-bare-abort []
  (let [[r _] (js-await (stopped-result js/undefined))]
    (-> (expect (.-is_error r)) (.toBe true))
    (-> (expect (.-subtype r)) (.toBe "error_during_execution"))))

(describe "print:stopped run"
          (fn []
            (it "a cap reports its subtype and is an error" test-cap-stop)
            (it "a bare abort is an execution error" test-bare-abort)
            (it "a finished run is a success"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "done"}]} nil)]
                    (-> (expect (.-subtype (result-object a 1 nil))) (.toBe "success")))))
            (it "with no text the reason is the result"
                (fn []
                  (let [c (js/AbortController.)
                        a (assoc (fake-agent {:messages []} nil) :abort-controller (atom c))]
                    (.abort c (budget/stop-reason "error_max_wall_time" "wall-clock budget exceeded (5s)"))
                    (let [r (result-object a 1 nil)]
                      (-> (expect (.-result r)) (.toBe "Error: wall-clock budget exceeded (5s)"))
                      (-> (expect (.-subtype r)) (.toBe "error_max_wall_time"))))))))
