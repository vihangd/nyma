(ns provider-fallback.test
  "A fallback chain, and the line it draws.

   Three model outages in one working session prompted this: a local MLX server
   answering 401 until its key was passed, a DGX box that went unreachable
   mid-run and stayed wedged for hours, and a model the same server listed but
   could not load. Each stopped work dead.

   The distinction that matters is availability versus request. Unreachable,
   overloaded, missing, failed-to-load → try the next model. Rejected key,
   forbidden, prompt too long → do not, because a second model fails the same way
   or the real fix is compaction, and falling back would bury a setup error behind
   a model nobody chose."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.providers.fallback :as fb]))

;;; ─── when to fall back ─────────────────────────────────────────

(describe "should-fall-back?: availability failures" (fn []

  (it "an unreachable host — the DGX case"
      (fn []
        (doseq [m ["fetch failed" "connect ECONNREFUSED 192.168.14.15:8000"
                   "getaddrinfo ENOTFOUND spark.local" "socket hang up"
                   "The operation timed out"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m true])))))

  (it "an overloaded or broken server"
      (fn []
        (doseq [m ["503 Service Unavailable" "502 Bad Gateway"
                   "Overloaded" "this group's upstream load is saturated"
                   "500 Internal Server Error" "no healthy upstream"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m true])))))

  (it "a model the server lists but cannot load — the Ternary-Bonsai case"
      (fn []
        ;; verbatim shape of the real failure
        (doseq [m ["Model 'Ternary-Bonsai-2-27B-mlx-2bit' failed to load: VLM load failed"
                   "Model type prism_hadamard_qwen35 not supported"
                   "model_not_found" "unknown model" "The model does not exist"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m true])))))))

(describe "should-fall-back?: request failures stay put" (fn []

  (it "a rejected key — a second model would fail the same way"
      (fn []
        ;; and falling back would hide the thing that needs fixing
        (doseq [m ["401 Unauthorized" "invalid api key"
                   "authentication_error" "403 Forbidden" "permission denied"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m false])))))

  (it "a context overflow — compaction is the fix, not another model"
      (fn []
        (doseq [m ["prompt is too long: 250000 tokens > 200000"
                   "context_length_exceeded" "maximum context length"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m false])))))

  (it "a request failure wins over an availability word in the same message"
      (fn []
        ;; Anthropic returns an overflow as invalid_request_error, and providers
        ;; mix vocabularies — so the restrictive branch is checked first
        (-> (expect (fb/should-fall-back? "invalid_request_error: context too long (503-style body)"))
            (.toBe false))))

  (it "nothing and an unrecognised message do not fall back"
      (fn []
        ;; silence is not a licence to start swapping models
        (-> (expect (fb/should-fall-back? nil)) (.toBe false))
        (-> (expect (fb/should-fall-back? "")) (.toBe false))
        (-> (expect (fb/should-fall-back? "something went wrong")) (.toBe false))))))

;;; ─── which model ───────────────────────────────────────────────

(describe "chain" (fn []

  (it "normalises keyword and string keys"
      (fn []
        (-> (expect (clj->js (fb/chain {:fallback [{:provider "zai" :model "glm-5.3-flash"}]})))
            (.toEqual (clj->js [{:provider "zai" :model "glm-5.3-flash"}])))
        (-> (expect (clj->js (fb/chain {"fallback" [{"provider" "zai" "model" "glm"}]})))
            (.toEqual (clj->js [{:provider "zai" :model "glm"}])))))

  (it "drops a half-specified entry rather than guessing"
      (fn []
        ;; {provider} with no model is a typo, not "keep the current model"
        (-> (expect (count (fb/chain {:fallback [{:provider "zai"}
                                                 {:model "glm"}
                                                 {:provider "a" :model "b"}]})))
            (.toBe 1))))

  (it "no chain is an empty chain, not nil"
      (fn []
        (-> (expect (clj->js (fb/chain {}))) (.toEqual (clj->js [])))
        (-> (expect (clj->js (fb/chain nil))) (.toEqual (clj->js [])))))))

(describe "next-entry" (fn []

  (it "takes them in order"
      (fn []
        (let [c (fb/chain {:fallback [{:provider "a" :model "1"}
                                      {:provider "b" :model "2"}]})]
          (-> (expect (fb/spec-of (fb/next-entry c #{}))) (.toBe "a/1"))
          (-> (expect (fb/spec-of (fb/next-entry c #{"a/1"}))) (.toBe "b/2")))))

  (it "never returns the model that just failed"
      (fn []
        ;; a chain naming the primary again must not retry it — this is why
        ;; `attempted` has to include the primary
        (let [c (fb/chain {:fallback [{:provider "omlx" :model "m"}
                                      {:provider "zai" :model "g"}]})]
          (-> (expect (fb/spec-of (fb/next-entry c #{"omlx/m"}))) (.toBe "zai/g")))))

  (it "an exhausted chain is nil, so the error surfaces"
      (fn []
        (let [c (fb/chain {:fallback [{:provider "a" :model "1"}]})]
          (-> (expect (fb/next-entry c #{"a/1"})) (.toBeNil))
          (-> (expect (fb/next-entry [] #{})) (.toBeNil)))))))

(describe "announcement" (fn []

  (it "names both models, because a silent swap is worse than a failure"
      (fn []
        (let [s (fb/announcement "omlx/qwen3.6-35b" "zai/glm-5.3-flash" "fetch failed")]
          (-> (expect s) (.toContain "omlx/qwen3.6-35b"))
          (-> (expect s) (.toContain "zai/glm-5.3-flash"))
          (-> (expect s) (.toContain "fetch failed")))))

  (it "works without a reason"
      (fn []
        (-> (expect (fb/announcement "a" "b" nil)) (.toContain "falling back to b"))))))

(describe "should-fall-back?: status codes stand alone" (fn []
  ;; "500" as a bare substring matched any number containing it.
  (it "a number that merely contains 500/502 is not a server error"
      (fn []
        (doseq [m ["429 rate limit exceeded, retry after 5000ms"
                   "Rate limited (request id req_5021abc)"
                   "used 1500 of 1000 requests"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m false])))))
  (it "a real status still counts"
      (fn []
        (doseq [m ["HTTP 503: try later" "status 502" "500 Internal"]]
          (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m true])))))))

(describe "retarget!" (fn []
  (it "drops what was built for the failed model"
      (fn []
        (let [cfg #js {:model "old" :providerOptions #js {:anthropic #js {}} :maxOutputTokens 128000}]
          (fb/retarget! cfg "new" "gpt-4")
          (-> (expect (.-model cfg)) (.toBe "new"))
          (-> (expect (.-anthropic (.-providerOptions cfg))) (.toBeUndefined))
          (-> (expect (< (.-maxOutputTokens cfg) 128000)) (.toBe true)))))))
