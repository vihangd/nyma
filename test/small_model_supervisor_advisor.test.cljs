(ns small-model-supervisor-advisor.test
  "Whatever `call-advisor-tool` returns is injected into the worker's context as
   guidance. It used to return its own error text on failure:

     🧭 Supervisor guidance:

     Advisor: call failed — Failed after 3 attempts. Last error:
     AI_APICallError: The model service is temporarily unavailable.

   Seen twice on one benchmark task, after which the worker stopped solving the
   exercise and went reading nyma's own settings.json looking for the problem.
   It timed out. A supervisor with nothing to say must say nothing."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.debug :as d]
            [agent.extensions.small-model.supervisor :as sv]
            [agent.extensions.small-model.shared :as shared]))

(afterEach (fn [] (d/reset-logger!) nil))

(defn- api-with [advisor-tool steers]
  #js {:getTool (fn [n] (when (= n "advisor__advisor") advisor-tool))
       :sendUserMessage (fn [msg & _] (swap! steers conj (str msg)) nil)})

(defn- run [advisor-tool]
  (let [steers (atom []) state (shared/make-state)]
    (-> (sv/do-intervention (api-with advisor-tool steers) state "why is this failing")
        (.then (fn [advice] {:steers @steers :state @state :advice advice})))))

(describe "small-model/supervisor: a failed advisor injects nothing" (fn []

  (it "an advisor that throws steers the worker with nothing"
      (fn []
        (let [boom #js {:execute (fn [_] (throw (js/Error. "AI_APICallError: temporarily unavailable")))}]
          (-> (run boom)
              (.then (fn [r]
                       (-> (expect (count (:steers r))) (.toBe 0))))))))

  (it "an advisor that is not registered steers the worker with nothing"
      (fn []
        (-> (run nil)
            (.then (fn [r] (-> (expect (count (:steers r))) (.toBe 0)))))))

  (it "an advisor that answers blank is not dressed up as guidance"
      (fn []
        (-> (run #js {:execute (fn [_] "   ")})
            (.then (fn [r] (-> (expect (count (:steers r))) (.toBe 0)))))))

  (it "a failed intervention still spends its budget"
      (fn []
        ;; otherwise a permanently unreachable advisor retries on every
        ;; every-N-turns tick for the whole run
        (-> (run nil)
            (.then (fn [r] (-> (expect (:interventions (:state r))) (.toBe 1)))))))

  (it "real advice still reaches the worker"
      (fn []
        (-> (run #js {:execute (fn [_] "Check the loop bound on line 12.")})
            (.then (fn [r]
                     (-> (expect (count (:steers r))) (.toBe 1))
                     (-> (expect (first (:steers r))) (.toContain "line 12"))
                     (-> (expect (first (:steers r))) (.toContain "Supervisor guidance")))))))

  (it "the failure is logged rather than swallowed"
      (fn []
        (let [lines (atom [])]
          (d/configure-logger! (fn [l] (swap! lines conj (str l))))
          (-> (run nil)
              (.then (fn [_]
                       (-> (expect (some #(.includes % "advisor") @lines)) (.toBeTruthy))))))))))

;;; ─── the advisor reports failure as a SUCCESSFUL result ────────

(describe "small-model/supervisor: an advisor error payload is not advice" (fn []

  (it "the exact string a benchmark task was steered with three times"
      (^:async fn []
        ;; The advisor tool always returns a string — deliberately, so the
        ;; executor model can react to a refusal — so it reports its own
        ;; failures as a successful call. Guarding only against a throw missed
        ;; every one of them, and the earlier test here mocked a throw, which is
        ;; why this shipped.
        (let [boom #js {:execute
                        (fn [_] (str "Advisor: call failed — Failed after 3 attempts. "
                                     "Last error: AI_APICallError: The model service is "
                                     "temporarily unavailable. Please try again later."))}]
          (-> (run boom)
              (.then (fn [r] (-> (expect (count (:steers r))) (.toBe 0))))))))

  (it "a refusal about transcript size is not advice either"
      (^:async fn []
        (-> (run #js {:execute (fn [_] "Advisor: refused — transcript is 240000 tokens, over the cap")})
            (.then (fn [r] (-> (expect (count (:steers r))) (.toBe 0)))))))

  (it "no model resolved is not advice"
      (^:async fn []
        (-> (run #js {:execute (fn [_] "Advisor: no model resolved. Set settings.roles.advisor to a {provider, model} pair.")})
            (.then (fn [r] (-> (expect (count (:steers r))) (.toBe 0)))))))

  (it "advice that merely MENTIONS the advisor still reaches the worker"
      (^:async fn []
        ;; the check is a prefix, not a substring — otherwise real advice
        ;; discussing the advisor tool would be silently dropped
        (-> (run #js {:execute (fn [_] "Call the Advisor: no. Fix the loop bound on line 12.")})
            (.then (fn [r]
                     (-> (expect (count (:steers r))) (.toBe 1))
                     (-> (expect (first (:steers r))) (.toContain "line 12")))))))))
