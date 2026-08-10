(ns tool-output-accounting.test
  "Tool output is what fills a context window — 92% of message bytes across
   sampled sessions, dominated by `read`. Any change aimed at that (compressed
   tools, subagent delegation, tighter guards) is unfalsifiable without a number
   to compare, so the number has to be trustworthy first.

   Two figures, measured either side of the per-tool result policy:
     raw-bytes   — what the tool produced
     model-bytes — what actually entered the context

   The gap between them is how much the existing caps already save, which is
   the baseline any new mechanism has to beat."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [create-pipeline]]
            ["./agent/extensions/stats_dashboard/index.mjs" :refer [format-tool-report]]))

(defn- mock-tool [execute-fn]
  #js {:execute execute-fn :description "mock"})

(defn ^:async run-tool
  "Execute `tool-name` through the real pipeline and return the
   tool_execution_end payload."
  [tool-name output]
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        seen     (atom nil)]
    ((:on events) "tool_execution_end" (fn [d] (reset! seen d) nil))
    (js-await ((:execute pipeline) tool-name (mock-tool (fn [_] output)) {}))
    @seen))

;; ── The measurement point ────────────────────────────────

(defn ^:async test-reports-exact-bytes []
  (let [out (apply str (repeat 500 "x"))
        ev  (js-await (run-tool "think" out))]
    ;; `think` caps at 4000, so 500 chars passes through untouched and both
    ;; figures must equal the real length — an accounting error would show here
    ;; before any policy interaction muddies it.
    (-> (expect (.-rawBytes ev)) (.toBe 500))
    (-> (expect (.-modelBytes ev)) (.toBe 500))))

(defn ^:async test-measures-across-the-policy []
  (let [out (apply str (repeat 9000 "y"))
        ev  (js-await (run-tool "think" out))]
    ;; think caps at 4000 chars. raw must be the full output and model must be
    ;; the capped size — measuring both sides is the entire point, since one
    ;; number alone cannot show what the caps already save.
    (-> (expect (.-rawBytes ev)) (.toBe 9000))
    (-> (expect (.-modelBytes ev)) (.toBeLessThan 9000))
    (-> (expect (.-modelBytes ev)) (.toBeGreaterThan 0))))

(defn ^:async test-not-measured-from-the-display-string []
  ;; `result` is for display: ANSI-wrapped to terminal width, then capped at
  ;; 500 LINES. Its length is wrong in BOTH directions — wrapping inflates a
  ;; long single line, the line cap collapses a tall one — so a consumer
  ;; measuring it would report neither figure. Both shown here.
  (let [wide   (js-await (run-tool "think" (apply str (repeat 3000 "z"))))
        tall-s (str/join "\n" (repeat 600 "line"))
        tall   (js-await (run-tool "think" tall-s))]
    ;; Wrapping made the display string LONGER than the actual output.
    (-> (expect (count (str (.-result wide)))) (.toBeGreaterThan (.-rawBytes wide)))
    (-> (expect (.-rawBytes wide)) (.toBe 3000))
    ;; The line cap made it far SHORTER than the actual output.
    (-> (expect (count (str (.-result tall)))) (.toBeLessThan (.-rawBytes tall)))
    (-> (expect (.-rawBytes tall)) (.toBe (count tall-s)))))

(defn ^:async test-empty-result []
  (let [ev (js-await (run-tool "think" ""))]
    (-> (expect (.-rawBytes ev)) (.toBe 0))))

(describe "tool output accounting"
          (fn []
            (it "reports exact byte counts when nothing is capped" test-reports-exact-bytes)
            (it "reports raw and model bytes either side of the policy"
                test-measures-across-the-policy)
            (it "is not derived from the display-truncated result"
                test-not-measured-from-the-display-string)
            (it "handles an empty result" test-empty-result)))

;; ── The report ───────────────────────────────────────────

(def ^:private fixture
  {"read" {:calls 25 :total-ms 500 :errors 0 :raw-bytes 88000 :model-bytes 80000}
   "bash" {:calls 3  :total-ms 300 :errors 1 :raw-bytes 14000 :model-bytes 14000}
   "glob" {:calls 1  :total-ms 10  :errors 0 :raw-bytes 300   :model-bytes 300}})

(describe "format-tool-report"
          (fn []
            (it "attributes context share to the dominant tool"
                (fn []
                  (let [out (format-tool-report fixture)]
                    ;; read is 80000 of 94300 model bytes ~ 85%.
                    (-> (expect out) (.toContain "read"))
                    (-> (expect out) (.toContain "85% of tool output")))))

            (it "shows what the policy already saved"
                (fn []
                  ;; read was capped 88000 -> 80000; without this the baseline
                  ;; is invisible and a new mechanism looks better than it is.
                  (-> (expect (format-tool-report fixture)) (.toContain "capped from"))))

            (it "reports a session total"
                (fn []
                  (-> (expect (format-tool-report fixture))
                      (.toContain "Tool output entering context"))))

            (it "still reports timings for tools that produced no bytes"
                (fn []
                  (let [out (format-tool-report {"x" {:calls 2 :total-ms 40 :errors 0}})]
                    (-> (expect out) (.toContain "2 calls"))
                    (-> (expect out) (.not.toContain "% of tool output")))))

            (it "does not divide by zero on an all-empty session"
                (fn []
                  (-> (expect (format-tool-report {"x" {:calls 1 :total-ms 1 :errors 0
                                                        :raw-bytes 0 :model-bytes 0}}))
                      (.toContain "1 calls"))))))
