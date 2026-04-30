(ns claude-hook-bridge-diagnostics.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.claude-hook-bridge.diagnostics :as diag]))

(beforeEach (fn [] (diag/reset!)))

;;; ─── configured-pairs ────────────────────────────────────────────────────

(describe "diagnostics/configured-pairs" (fn []
                                           (it "extracts every (event, matcher) pair"
                                               (fn []
                                                 (let [hooks {"PreToolUse"
                                                              [#js {:matcher "Bash" :hooks #js []}
                                                               #js {:matcher "Edit|Write" :hooks #js []}]
                                                              "PostToolUse"
                                                              [#js {:matcher "Read" :hooks #js []}]}
                                                       pairs (diag/configured-pairs* hooks)]
                                                   (-> (expect (count pairs)) (.toBe 3))
                                                   (-> (expect (boolean (some #(= % ["PreToolUse" "Bash"]) pairs))) (.toBe true))
                                                   (-> (expect (boolean (some #(= % ["PreToolUse" "Edit|Write"]) pairs))) (.toBe true))
                                                   (-> (expect (boolean (some #(= % ["PostToolUse" "Read"]) pairs))) (.toBe true)))))

                                           (it "skips wildcard matchers (* / empty / nil)"
                                               (fn []
                                                 (let [hooks {"Stop"
                                                              [#js {:matcher "" :hooks #js []}
                                                               #js {:matcher "*" :hooks #js []}
                                                               #js {:matcher nil :hooks #js []}]}
                                                       pairs (diag/configured-pairs* hooks)]
                                                   ;; wildcards never report unseen — they always match
                                                   (-> (expect (count pairs)) (.toBe 0)))))))

;;; ─── record-match! / report-unseen ───────────────────────────────────────

(describe "diagnostics/report-unseen" (fn []
                                        (it "no unseen when all configured matchers fired"
                                            (fn []
                                              (let [hooks {"PreToolUse" [#js {:matcher "Bash" :hooks #js []}]}]
                                                (diag/record-match! "PreToolUse" "Bash")
                                                (-> (expect (count (diag/report-unseen hooks))) (.toBe 0)))))

                                        (it "reports unseen matchers"
                                            (fn []
                                              (let [hooks {"PreToolUse"
                                                           [#js {:matcher "Bash" :hooks #js []}
                                                            #js {:matcher "Edit" :hooks #js []}]}]
                                                ;; Only Bash fired, Edit didn't
                                                (diag/record-match! "PreToolUse" "Bash")
                                                (let [unseen (diag/report-unseen hooks)]
                                                  (-> (expect (count unseen)) (.toBe 1))
                                                  (-> (expect (first (first unseen))) (.toBe "PreToolUse"))
                                                  (-> (expect (second (first unseen))) (.toBe "Edit"))))))

                                        (it "wildcard matchers never appear unseen"
                                            (fn []
                                              (let [hooks {"Stop" [#js {:matcher "*" :hooks #js []}]}]
                                                ;; Don't record any match
                                                (-> (expect (count (diag/report-unseen hooks))) (.toBe 0)))))

                                        (it "reset! clears the seen set"
                                            (fn []
                                              (diag/record-match! "PreToolUse" "Bash")
                                              (diag/reset!)
                                              (let [hooks {"PreToolUse" [#js {:matcher "Bash" :hooks #js []}]}]
                                                ;; After reset!, Bash is unseen again
                                                (-> (expect (count (diag/report-unseen hooks))) (.toBe 1)))))))

;;; ─── did-you-mean ────────────────────────────────────────────────────────

(describe "diagnostics/did-you-mean" (fn []
                                       (it "suggests Bash for typo bahs"
                                           (fn []
                                             (-> (expect (diag/did-you-mean* "bahs")) (.toBe "Bash"))))

                                       (it "suggests Edit for typo Edot"
                                           (fn []
                                             (-> (expect (diag/did-you-mean* "Edot")) (.toBe "Edit"))))

                                       (it "returns nil when no candidate is close"
                                           (fn []
                                             (-> (expect (diag/did-you-mean* "completely-different-name")) (.toBeNil))))

                                       (it "returns nil when matcher exactly matches a tool"
                                           (fn []
                                             ;; \"Bash\" is identity (distance 0), so no suggestion
                                             (-> (expect (diag/did-you-mean* "Bash")) (.toBeNil))))))
