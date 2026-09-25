(ns extension-model-spend.test
  "Some extensions call a model of their own: `advisor` (and through it
   small-model's supervisor and self-tune), `handoff`, `spec_driven`,
   `smart_compaction`. They import `generateText` from the AI SDK directly,
   resolve a model via `api.resolveModel`, and read only `.text`.

   Nothing counted any of it. The whole accounting chain is single-writer —
   `print.cljs` reads the agent's state atom, which only `:usage-updated`
   writes, dispatched from exactly one place in `loop.cljs` off the main loop's
   own streamText result. So a benchmark arm running six Opus-5 advisor calls
   reported the same `costUsd: 0` and the same token total as one running none.

   And dispatching was not enough on its own: the loop prices with the AGENT's
   model key, so a paid cloud advisor under a local worker would have been
   billed at the worker's rate, which for an unpriced local model is zero."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.state :as state]
            [agent.extensions.advisor.index :as advisor]
            [agent.pricing :as pricing]))

(beforeEach (fn [] (reset! pricing/unpriced-providers #{}) nil))

(defn- store [] (state/create-agent-store {}))

(defn- totals [s]
  (let [st ((:get-state s))]
    {:in (or (:total-input-tokens st) 0)
     :out (or (:total-output-tokens st) 0)
     :cost (or (:total-cost st) 0)
     :steps (or (:total-steps st) 0)
     :runs (or (:turn-count st) 0)}))

;;; ─── the tokens land ───────────────────────────────────────────

(describe "extension model spend: it is counted" (fn []

  (it "an extension call moves the session token totals"
      (fn []
        (let [s (store)]
          ((:dispatch! s) :extension-usage
           {:input-tokens 1200 :output-tokens 300 :cost 0.05})
          (let [t (totals s)]
            (-> (expect (:in t)) (.toBe 1200))
            (-> (expect (:out t)) (.toBe 300))
            (-> (expect (:cost t)) (.toBeCloseTo 0.05))))))

  (it "it is not a turn and not a step"
      (fn []
        ;; `num_turns` is the agent's own work. An advisor consult is spend, not
        ;; a turn the model took, and counting it would make a turn budget
        ;; calibrated against the wrong number.
        (let [s (store)]
          ((:dispatch! s) :extension-usage
           {:input-tokens 10 :output-tokens 5 :cost 0.001})
          (let [t (totals s)]
            (-> (expect (:steps t)) (.toBe 0))
            (-> (expect (:runs t)) (.toBe 0))))))

  (it "the cache split is carried like the loop's"
      (fn []
        (let [s (store)]
          ((:dispatch! s) :extension-usage
           {:input-tokens 1000 :output-tokens 10
            :cache-read-tokens 800 :cache-write-tokens 50 :cost 0.0})
          (let [st ((:get-state s))]
            (-> (expect (:total-cache-read-tokens st)) (.toBe 800))
            (-> (expect (:total-cache-write-tokens st)) (.toBe 50))))))

  (it "it accumulates alongside the agent's own usage"
      (fn []
        (let [s (store)]
          ((:dispatch! s) :usage-updated
           {:input-tokens 500 :output-tokens 100 :cost 0.01 :steps 3})
          ((:dispatch! s) :extension-usage
           {:input-tokens 2000 :output-tokens 400 :cost 0.20})
          (let [t (totals s)]
            (-> (expect (:in t)) (.toBe 2500))
            (-> (expect (:cost t)) (.toBeCloseTo 0.21))
            ;; the agent took 3 steps; the advisor took none
            (-> (expect (:steps t)) (.toBe 3))
            (-> (expect (:runs t)) (.toBe 1))))))

  (it "missing fields count as zero rather than NaN"
      (fn []
        (let [s (store)]
          ((:dispatch! s) :extension-usage {:input-tokens 10})
          (let [t (totals s)]
            (-> (expect (:out t)) (.toBe 0))
            (-> (expect (:cost t)) (.toBe 0))))))))

;;; ─── priced with ITS model, not the agent's ────────────────────

(describe "extension model spend: priced by the model that spent it" (fn []

  (it "a paid advisor under a free local worker is not free"
      (fn []
        ;; the whole point. The loop prices with (model-cost-key (:config agent)),
        ;; so routing an extension's usage through that path would bill an
        ;; Opus-5 consult at the local worker's rate — zero.
        (let [cost (pricing/calculate-turn-cost
                    "claude-opus-5"
                    {:input-tokens 100000 :output-tokens 2000})]
          (-> (expect (> cost 0)) (.toBe true)))))

  (it "the local worker's own rate stays zero"
      (fn []
        (reset! pricing/unpriced-providers #{"omlx"})
        (-> (expect (pricing/calculate-turn-cost
                     "omlx/Qwen3.6-35B-A3B-OptiQ-4bit"
                     {:input-tokens 100000 :output-tokens 2000}))
            (.toBe 0))))

  (it "an unpriced relay carrying a paid id still reports nothing"
      (fn []
        ;; lookup-cost refuses the bare-id fallback for a gateway, because the
        ;; user is not being charged the first-party rate
        (reset! pricing/unpriced-providers #{"openlux-claude"})
        (-> (expect (pricing/calculate-turn-cost
                     "openlux-claude/claude-opus-5"
                     {:input-tokens 100000 :output-tokens 2000}))
            (.toBe 0))))))

;;; ─── end to end through the advisor ────────────────────────────

(defn- advisor-api
  "The surface consult-advisor touches, with recordModelUsage wired to a real
   agent store so the totals are the ones `-p --output-format json` reports."
  [s]
  (let [recorded (atom [])]
    {:recorded recorded
     :api #js {:getState (fn [] #js {:messages #js [#js {:role "user" :content "fix the bug"}]})
               :resolveModel (fn [& _] #js {:modelId "claude-opus-5"})
               :recordModelUsage
               (fn [spec usage]
                 (swap! recorded conj [(str spec) usage])
                 ;; mirror the real implementation, nil-guard included
                 (let [u      (or usage #js {})
                       input  (or (.-inputTokens u) 0)
                       output (or (.-outputTokens u) 0)]
                   (when (pos? (+ input output))
                     ((:dispatch! s) :extension-usage
                      {:input-tokens input :output-tokens output
                       :cost (pricing/calculate-turn-cost (str spec)
                                                          {:input-tokens input
                                                           :output-tokens output})})))
                 nil)}}))

(defn- gen-stub [text usage]
  (fn [_cfg] (js/Promise.resolve #js {:text text :usage usage})))

(describe "extension model spend: an advisor consult shows up" (fn []

  (it "moves the session totals and is priced by the advisor's model"
      (^:async fn []
        (let [s (store)
              {:keys [api recorded]} (advisor-api s)
              out (js-await (advisor/consult-advisor
                             api
                             {:roles {:advisor {:provider "anthropic" :model "claude-opus-5"}}}
                             {:gen-fn (gen-stub "check the loop bound"
                                                #js {:inputTokens 120000 :outputTokens 3000})}))]
          (-> (expect (str out)) (.toContain "loop bound"))
          ;; attributed to the advisor's model, not the agent's
          (-> (expect (first (first @recorded))) (.toBe "anthropic/claude-opus-5"))
          (let [t (totals s)]
            (-> (expect (:in t)) (.toBe 120000))
            (-> (expect (:out t)) (.toBe 3000))
            ;; opus-5 is 5.0/25.0 per 1M → 0.6 + 0.075
            (-> (expect (:cost t)) (.toBeCloseTo 0.675 3))
            ;; and it is still not one of the agent's turns
            (-> (expect (:steps t)) (.toBe 0))))))

  (it "a provider that reports no usage records nothing, and the call still works"
      (^:async fn []
        ;; Asserting only "totals stayed 0" would pass if recording THREW and the
        ;; advisor's own catch swallowed it — the advice check is what rules that out.
        (let [s (store)
              {:keys [api]} (advisor-api s)
              out (js-await (advisor/consult-advisor
                             api
                             {:roles {:advisor {:provider "anthropic" :model "claude-opus-5"}}}
                             {:gen-fn (gen-stub "still advises" nil)}))]
          (-> (expect (str out)) (.toContain "still advises"))
          (-> (expect (str out)) (.not.toContain "call failed"))
          (-> (expect (:in (totals s))) (.toBe 0)))))))
