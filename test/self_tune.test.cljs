(ns self-tune.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/extensions/small_model/self_tune.mjs"
             :refer [append-lesson clean-lesson render-playbook activate]]))

;; Stub api: records subscriptions; advisor.execute returns "" (empty lesson →
;; no PLAYBOOK.md write, so the test never touches the filesystem) and bumps a
;; call counter so reflection gating is observable.
(defn- make-api [subs calls]
  #js {:on      (fn [ev h] (swap! subs update ev (fn [v] (conj (or v []) h))))
       :off     (fn [_ _] nil)
       :getTool (fn [_] #js {:execute (fn [_] (swap! calls inc) "")})})

(defn- fire [subs ev]
  (doseq [h (get @subs ev)] (h #js {:reason "hallucinated tool: frobnicate"} nil)))

(describe "self-tune/append-lesson (ACE delta: dedup + cap)"
          (fn []
            (it "appends a fresh lesson"
                (fn []
                  (let [out (append-lesson #js ["a"] "b" 20)]
                    (-> (expect (count out)) (.toBe 2))
                    (-> (expect (aget out 1)) (.toBe "b")))))

            (it "drops an exact duplicate"
                (fn []
                  (let [out (append-lesson #js ["always run tests"] "always run tests" 20)]
                    (-> (expect (count out)) (.toBe 1)))))

            (it "drops a near-duplicate (case/whitespace-insensitive containment)"
                (fn []
                  (let [out (append-lesson #js ["Always run the tests before committing"]
                                           "always run   the   TESTS before committing" 20)]
                    (-> (expect (count out)) (.toBe 1)))))

            (it "drops a blank candidate"
                (fn []
                  (let [out (append-lesson #js ["a"] "   " 20)]
                    (-> (expect (count out)) (.toBe 1)))))

            (it "caps to max-lessons, evicting the oldest"
                (fn []
                  (let [out (append-lesson #js ["one" "two" "three"] "four" 3)]
                    (-> (expect (count out)) (.toBe 3))
                    ;; "one" evicted; newest kept
                    (-> (expect (aget out 0)) (.toBe "two"))
                    (-> (expect (aget out 2)) (.toBe "four")))))))

(describe "self-tune/clean-lesson"
          (fn []
            (it "strips leading bullet/number preamble"
                (fn []
                  (-> (expect (clean-lesson "1. Never call a tool that isn't listed."))
                      (.toBe "Never call a tool that isn't listed."))))

            (it "collapses to at most two non-blank lines"
                (fn []
                  (let [out (clean-lesson "line one\n\nline two\nline three")]
                    (-> (expect out) (.toBe "line one line two")))))

            (it "empty input yields empty string"
                (fn []
                  (-> (expect (clean-lesson "")) (.toBe ""))))

            ;; call-advisor-tool never throws — it RETURNS these strings. They
            ;; must never reach PLAYBOOK.md as "rules".
            (it "rejects advisor-failure fallback strings"
                (fn []
                  (-> (expect (clean-lesson "Supervisor: advisor call failed — timeout"))
                      (.toBe ""))
                  (-> (expect (clean-lesson "Supervisor: advisor tool not available. Focus: x"))
                      (.toBe ""))))

            (it "does not mangle a rule starting with a number-word"
                (fn []
                  (-> (expect (clean-lesson "3-way merges must be resolved manually."))
                      (.toBe "3-way merges must be resolved manually."))))

            (it "still strips a real numbered/bulleted marker"
                (fn []
                  (-> (expect (clean-lesson "- Never guess a tool name.")) (.toBe "Never guess a tool name."))
                  (-> (expect (clean-lesson "2) Never guess a tool name.")) (.toBe "Never guess a tool name."))))))

(describe "self-tune/activate — bus wiring + gating"
          (fn []
            (it "subscribes both failure signals + before_agent_start"
                (fn []
                  (let [subs (atom {}) calls (atom 0)
                        cfg  {:self-tune {:enabled true :min-failures 2 :max-reflections 3
                                          :max-lessons 20
                                          :reflect-on ["quality-signal" "verify-fail"]}}]
                    (activate (make-api subs calls) cfg)
                    (-> (expect (boolean (get @subs "small-model/quality-signal"))) (.toBe true))
                    (-> (expect (boolean (get @subs "small-model/verify-fail"))) (.toBe true))
                    (-> (expect (boolean (get @subs "before_agent_start"))) (.toBe true)))))

            (it "reflect-on filters which signals are wired"
                (fn []
                  (let [subs (atom {}) calls (atom 0)
                        cfg  {:self-tune {:enabled true :reflect-on ["quality-signal"]}}]
                    (activate (make-api subs calls) cfg)
                    (-> (expect (boolean (get @subs "small-model/quality-signal"))) (.toBe true))
                    (-> (expect (get @subs "small-model/verify-fail")) (.toBeUndefined)))))

            (it "reflects only after min-failures, capped at max-reflections"
                (^:async fn []
                  (let [subs (atom {}) calls (atom 0)
                        cfg  {:self-tune {:enabled true :min-failures 2 :max-reflections 1
                                          :max-lessons 20 :reflect-on ["quality-signal"]}}
                        flush (fn [] (js/Promise. (fn [res] (js/setTimeout res 10))))]
                    (activate (make-api subs calls) cfg)
                    (fire subs "small-model/quality-signal")   ; failures=1 → no reflect
                    (js-await (flush))
                    (-> (expect @calls) (.toBe 0))
                    (fire subs "small-model/quality-signal")   ; failures=2 → reflect #1
                    (fire subs "small-model/quality-signal")
                    (fire subs "small-model/quality-signal")   ; would be #2 but cap=1
                    (js-await (flush))
                    (-> (expect @calls) (.toBe 1)))))

            ;; squint compiles `or` to JS `||`, where 0 is falsey — 0 must mean
            ;; "no reflections", not the 3 default.
            (it "max-reflections 0 disables reflection (0 is not falsey-defaulted)"
                (^:async fn []
                  (let [subs (atom {}) calls (atom 0)
                        cfg  {:self-tune {:enabled true :min-failures 1 :max-reflections 0
                                          :max-lessons 20 :reflect-on ["quality-signal"]}}
                        flush (fn [] (js/Promise. (fn [res] (js/setTimeout res 10))))]
                    (activate (make-api subs calls) cfg)
                    (fire subs "small-model/quality-signal")
                    (fire subs "small-model/quality-signal")
                    (js-await (flush))
                    (-> (expect @calls) (.toBe 0)))))))

(describe "self-tune/render-playbook"
          (fn []
            (it "renders lessons as a bullet list under a header"
                (fn []
                  (let [out (render-playbook #js ["do x" "do y"])]
                    (-> (expect out) (.toContain "# Playbook"))
                    (-> (expect out) (.toContain "- do x"))
                    (-> (expect out) (.toContain "- do y")))))))
