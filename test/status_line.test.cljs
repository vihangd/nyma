(ns status-line.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.status-line-segments
             :refer [context-usage-level
                     register-segment
                     unregister-segment
                     get-segment
                     segment-registry
                     builtin-segments
                     render-segments
                     auto-append-ids
                     token-rate-per-sec]]
            [agent.ui.status-line-presets :refer [get-preset]]
            [agent.ui.status-line-separators :refer [get-separator]]))

(def test-theme
  {:colors {:primary         "#7aa2f7"
            :secondary       "#9ece6a"
            :muted           "#565f89"
            :warning         "#e0af68"
            :context-ok      "#9ece6a"
            :context-warning "#e0af68"
            :context-purple  "#bb9af7"
            :context-error   "#f7768e"}})

;;; ─── context-usage-level ───────────────────────────────

(describe "context-usage-level" (fn []
                                  (it "returns :ok for zero usage"
                                      (fn []
                                        (-> (expect (context-usage-level 0 200000)) (.toBe "ok"))))

                                  (it "returns :warning at 50% of window"
                                      (fn []
                                        (-> (expect (context-usage-level 100000 200000)) (.toBe "warning"))))

                                  (it "returns :purple at 70%"
                                      (fn []
                                        (-> (expect (context-usage-level 140000 200000)) (.toBe "purple"))))

                                  (it "returns :error at 90%"
                                      (fn []
                                        (-> (expect (context-usage-level 180000 200000)) (.toBe "error"))))

                                  (it "triggers :error on absolute token count regardless of window"
                                      (fn []
                                        (-> (expect (context-usage-level 500001 5000000)) (.toBe "error"))))

                                  (it "triggers :warning on absolute token count with huge window"
                                      (fn []
                                        (-> (expect (context-usage-level 160000 10000000)) (.toBe "warning"))))

                                  (it "handles zero window without crashing"
                                      (fn []
                                        (-> (expect (context-usage-level 0 0)) (.toBe "ok"))))

                                  (it "handles nil window"
                                      (fn []
                                        (-> (expect (context-usage-level 0 nil)) (.toBe "ok"))))))

;;; ─── Segment registry ─────────────────────────────────

(describe "segment registry" (fn []
                               (it "all built-ins are registered on module load"
                                   (fn []
                                     (-> (expect (pos? (count (segment-registry)))) (.toBe true))
                                     (-> (expect (count builtin-segments)) (.toBe 18))))

                               (it "can retrieve a built-in by id"
                                   (fn []
                                     (-> (expect (:id (get-segment "model"))) (.toBe "model"))))

                               (it "custom registration round-trips"
                                   (fn []
                                     (register-segment "test.custom"
                                                       {:category :test
                                                        :render (fn [_] {:content "X" :color "#fff" :visible? true})})
                                     (-> (expect (:id (get-segment "test.custom"))) (.toBe "test.custom"))))))

;;; ─── auto-append-ids ──────────────────────────────────
;;; Regression for the ACP footer-vs-status-line migration: when an
;;; extension registers segments with :auto-append? true, StatusLine
;;; must include them alongside the user's preset ids so the segments
;;; actually get rendered. Before this fix the agent_shell extension
;;; registered acp.* segments that never appeared anywhere.

(describe "auto-append-ids" (fn []
                              (it "returns ids marked auto-append? for the requested position"
                                  (fn []
                                    (register-segment "test.auto.left"
                                                      {:auto-append? true
                                                       :position      :left
                                                       :render (fn [_] {:content "L" :color "#fff" :visible? true})})
                                    (register-segment "test.auto.right"
                                                      {:auto-append? true
                                                       :position      :right
                                                       :render (fn [_] {:content "R" :color "#fff" :visible? true})})
                                    (let [lefts  (set (auto-append-ids :left []))
                                          rights (set (auto-append-ids :right []))]
                                      (-> (expect (contains? lefts "test.auto.left")) (.toBe true))
                                      (-> (expect (contains? lefts "test.auto.right")) (.toBe false))
                                      (-> (expect (contains? rights "test.auto.right")) (.toBe true))
                                      (-> (expect (contains? rights "test.auto.left")) (.toBe false)))
                                    (unregister-segment "test.auto.left")
                                    (unregister-segment "test.auto.right")))

                              (it "does not return ids already in preset-ids (no duplicates)"
                                  (fn []
                                    (register-segment "test.dup"
                                                      {:auto-append? true
                                                       :position      :right
                                                       :render (fn [_] {:content "X" :color "#fff" :visible? true})})
                                    (let [out (auto-append-ids :right ["test.dup" "something-else"])]
                                      (-> (expect (.includes (clj->js out) "test.dup")) (.toBe false)))
                                    (unregister-segment "test.dup")))

                              (it "ignores segments without :auto-append?"
                                  (fn []
                                    (register-segment "test.manual"
                                                      {:position :right
                                                       :render (fn [_] {:content "X" :color "#fff" :visible? true})})
                                    (let [out (set (auto-append-ids :right []))]
                                      (-> (expect (contains? out "test.manual")) (.toBe false)))
                                    (unregister-segment "test.manual")))))

;;; ─── render-segments ──────────────────────────────────

(describe "render-segments" (fn []
                              (it "renders only visible segments"
                                  (fn []
                                    (let [ctx {:model-id "gpt-4" :theme test-theme}
                                          out (render-segments ["model" "git"] ctx)]
        ;; :git is hidden because no git-branch in ctx
                                      (-> (expect (count out)) (.toBe 1))
                                      (-> (expect (:id (first out))) (.toBe "model")))))

                              (it "skips unknown ids"
                                  (fn []
                                    (let [out (render-segments ["nonexistent" "model"]
                                                               {:model-id "x" :theme test-theme})]
                                      (-> (expect (count out)) (.toBe 1)))))

                              (it "context-pct segment shows percentage when window is set"
                                  (fn []
                                    (let [out (render-segments ["context-pct"]
                                                               {:ctx-used 50000 :ctx-window 200000
                                                                :theme test-theme})]
                                      (-> (expect (count out)) (.toBe 1))
                                      (-> (expect (.includes (:content (first out)) "25%"))
                                          (.toBe true)))))

                              (it "git segment flips to warning when dirty"
                                  (fn []
                                    (let [clean (render-segments ["git"]
                                                                 {:git-branch "main"
                                                                  :git-status {:staged 0 :unstaged 0 :untracked 0}
                                                                  :theme test-theme})
                                          dirty (render-segments ["git"]
                                                                 {:git-branch "main"
                                                                  :git-status {:unstaged 2}
                                                                  :theme test-theme})]
                                      (-> (expect (:color (first clean))) (.toBe "#9ece6a"))
                                      (-> (expect (:color (first dirty))) (.toBe "#e0af68"))
                                      (-> (expect (.endsWith (:content (first dirty)) "*"))
                                          (.toBe true)))))

                              (it "cost segment is always visible, even at $0"
                                  (fn []
                                    (let [out (render-segments ["cost"] {:cost-usd 0 :theme test-theme})]
                                      (-> (expect (count out)) (.toBe 1))
                                      (-> (expect (:content (first out))) (.toBe "$0.00")))))))

;;; ─── Individual built-in segments ──────────────────────
;;; Previously only 4 of the 18 built-in segments (model, git,
;;; context-pct, cost) had render tests. A theme-key typo or
;;; formatter change in any of the other 14 would silently turn
;;; the segment into `{:visible? false}` and nobody would notice
;;; until someone eyeballed their status line. Each segment gets a
;;; positive test (content appears) and a hidden-case test (absent
;;; context → not rendered).

(describe "built-in segments: path" (fn []
                                      (it "shows the basename of a short path"
                                          (fn []
                                            (let [out (render-segments ["path"] {:path "/tmp/proj" :theme test-theme})]
                                              (-> (expect (count out)) (.toBe 1))
                                              (-> (expect (.includes (:content (first out)) "proj")) (.toBe true)))))
                                      (it "collapses deep paths to .../parent/leaf"
                                          (fn []
                                            (let [out (render-segments ["path"] {:path "/a/b/c/deep" :theme test-theme})]
                                              (-> (expect (.includes (:content (first out)) "deep")) (.toBe true))
                                              (-> (expect (.startsWith (:content (first out)) ".../")) (.toBe true)))))
                                      (it "hidden when path is empty"
                                          (fn []
                                            (let [out (render-segments ["path"] {:theme test-theme})]
                                              (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: pr" (fn []
                                    (it "renders #number when pr-info has a number"
                                        (fn []
                                          (let [out (render-segments ["pr"] {:pr-info {:number 42} :theme test-theme})]
                                            (-> (expect (count out)) (.toBe 1))
                                            (-> (expect (:content (first out))) (.toBe "#42")))))
                                    (it "hidden when no pr-info"
                                        (fn []
                                          (let [out (render-segments ["pr"] {:theme test-theme})]
                                            (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: subagents" (fn []
                                           (it "renders sub:N when subagents is positive"
                                               (fn []
                                                 (let [out (render-segments ["subagents"] {:subagents 3 :theme test-theme})]
                                                   (-> (expect (:content (first out))) (.toBe "sub:3")))))
                                           (it "hidden when subagents is 0"
                                               (fn []
                                                 (let [out (render-segments ["subagents"] {:subagents 0 :theme test-theme})]
                                                   (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: token-in" (fn []
                                          (it "renders ↑ plus compact number"
                                              (fn []
                                                (let [out (render-segments ["token-in"] {:token-in 1234 :theme test-theme})]
                                                  (-> (expect (.startsWith (:content (first out)) "\u2191")) (.toBe true))
                                                  (-> (expect (.includes (:content (first out)) "1.2k")) (.toBe true)))))
                                          (it "hidden at 0"
                                              (fn []
                                                (let [out (render-segments ["token-in"] {:token-in 0 :theme test-theme})]
                                                  (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: token-out" (fn []
                                           (it "renders ↓ plus compact number"
                                               (fn []
                                                 (let [out (render-segments ["token-out"] {:token-out 5678 :theme test-theme})]
                                                   (-> (expect (.startsWith (:content (first out)) "\u2193")) (.toBe true))
                                                   (-> (expect (.includes (:content (first out)) "5.7k")) (.toBe true)))))
                                           (it "hidden at 0"
                                               (fn []
                                                 (let [out (render-segments ["token-out"] {:theme test-theme})]
                                                   (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: token-total" (fn []
                                             (it "renders tok: prefix with compact number"
                                                 (fn []
                                                   (let [out (render-segments ["token-total"] {:token-total 9999 :theme test-theme})]
                                                     (-> (expect (.startsWith (:content (first out)) "tok:")) (.toBe true))
                                                     (-> (expect (.includes (:content (first out)) "k")) (.toBe true)))))
                                             (it "hidden at 0"
                                                 (fn []
                                                   (let [out (render-segments ["token-total"] {:theme test-theme})]
                                                     (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: token-rate" (fn []
                                            (it "renders rounded t/s when rate > 0"
                                                (fn []
                                                  (let [out (render-segments ["token-rate"] {:token-rate 25.7 :theme test-theme})]
                                                    (-> (expect (.endsWith (:content (first out)) "t/s")) (.toBe true)))))
                                            (it "hidden at 0"
                                                (fn []
                                                  (let [out (render-segments ["token-rate"] {:token-rate 0 :theme test-theme})]
                                                    (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: context-total" (fn []
                                               (it "renders compact number of ctx-used"
                                                   (fn []
                                                     (let [out (render-segments ["context-total"] {:ctx-used 2500 :theme test-theme})]
                                                       (-> (expect (.includes (:content (first out)) "2.5k")) (.toBe true)))))
                                               (it "hidden when ctx-used is 0"
                                                   (fn []
                                                     (let [out (render-segments ["context-total"] {:theme test-theme})]
                                                       (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: time-spent" (fn []
                                            (it "renders m/s format for durations under 1 hour"
                                                (fn []
                                                  (let [out (render-segments ["time-spent"] {:time-spent-ms 90000 :theme test-theme})]
                                                    ;; 90 s → "1m30s"
                                                    (-> (expect (:content (first out))) (.toBe "1m30s")))))
                                            (it "renders h/m for durations ≥ 1 hour"
                                                (fn []
                                                  (let [out (render-segments ["time-spent"] {:time-spent-ms 7200000 :theme test-theme})]
                                                    ;; 7.2 M ms = 2h0m
                                                    (-> (expect (:content (first out))) (.toBe "2h0m")))))
                                            (it "hidden when time-spent-ms is 0"
                                                (fn []
                                                  (let [out (render-segments ["time-spent"] {:theme test-theme})]
                                                    (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: time" (fn []
                                      (it "always visible and renders HH:MM"
                                          (fn []
                                            (let [out (render-segments ["time"] {:theme test-theme})]
                                              (-> (expect (count out)) (.toBe 1))
                                              ;; Format is exactly 5 chars: HH:MM
                                              (-> (expect (count (:content (first out)))) (.toBe 5))
                                              (-> (expect (.charAt (:content (first out)) 2)) (.toBe ":")))))))

(describe "built-in segments: session" (fn []
                                         (it "renders ses: prefix with first 6 chars of session id"
                                             (fn []
                                               (let [out (render-segments ["session"] {:session-id "abc123def456" :theme test-theme})]
                                                 (-> (expect (:content (first out))) (.toBe "ses:abc123")))))
                                         (it "hidden when session-id is empty"
                                             (fn []
                                               (let [out (render-segments ["session"] {:theme test-theme})]
                                                 (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: hostname" (fn []
                                          (it "renders explicit hostname when provided"
                                              (fn []
                                                (let [out (render-segments ["hostname"] {:hostname "myhost" :theme test-theme})]
                                                  (-> (expect (:content (first out))) (.toBe "myhost")))))))

(describe "built-in segments: cache-read" (fn []
                                            (it "renders c↑ prefix with compact number"
                                                (fn []
                                                  (let [out (render-segments ["cache-read"] {:cache-read 1500 :theme test-theme})]
                                                    (-> (expect (.startsWith (:content (first out)) "c\u2191")) (.toBe true))
                                                    (-> (expect (.includes (:content (first out)) "1.5k")) (.toBe true)))))
                                            (it "hidden at 0"
                                                (fn []
                                                  (let [out (render-segments ["cache-read"] {:theme test-theme})]
                                                    (-> (expect (count out)) (.toBe 0)))))))

(describe "built-in segments: cache-write" (fn []
                                             (it "renders c↓ prefix with compact number"
                                                 (fn []
                                                   (let [out (render-segments ["cache-write"] {:cache-write 750 :theme test-theme})]
                                                     (-> (expect (.startsWith (:content (first out)) "c\u2193")) (.toBe true)))))
                                             (it "hidden at 0"
                                                 (fn []
                                                   (let [out (render-segments ["cache-write"] {:theme test-theme})]
                                                     (-> (expect (count out)) (.toBe 0)))))))

;;; ─── Presets ──────────────────────────────────────────

(describe "get-preset" (fn []
                         (it "returns the default preset"
                             (fn []
                               (let [p (get-preset "default")]
                                 (-> (expect (vector? (:left-segments p))) (.toBe true))
                                 (-> (expect (pos? (count (:left-segments p)))) (.toBe true)))))

                         (it "falls back to default for unknown name"
                             (fn []
                               (let [p (get-preset "no-such-preset")]
                                 (-> (expect (pos? (count (:left-segments p)))) (.toBe true)))))

                         (it "falls back to default for nil"
                             (fn []
                               (-> (expect (some? (get-preset nil))) (.toBe true))))

                         (it "minimal preset has fewer segments than default"
                             (fn []
                               (let [d (get-preset "default")
                                     m (get-preset "minimal")]
                                 (-> (expect (< (count (:left-segments m)) (count (:left-segments d))))
                                     (.toBe true)))))))

;;; ─── Separators ───────────────────────────────────────

(describe "get-separator" (fn []
                            (it "returns powerline-thin by default"
                                (fn []
                                  (let [s (get-separator nil)]
                                    (-> (expect (contains? s :middle)) (.toBe true)))))

                            (it "returns ascii separator with | glyph"
                                (fn []
                                  (let [s (get-separator "ascii")]
                                    (-> (expect (.includes (:middle s) "|")) (.toBe true)))))

                            (it "falls back for unknown style"
                                (fn []
                                  (-> (expect (some? (get-separator "weird"))) (.toBe true))))))

;;; ─── token-rate-per-sec ───────────────────────────────

(describe "token-rate-per-sec" (fn []
                                 (it "returns 0 for an empty sample list"
                                     (fn []
                                       (-> (expect (token-rate-per-sec [] 60000 10000)) (.toBe 0))))

                                 (it "returns 0 for a single sample"
                                     (fn []
                                       (-> (expect (token-rate-per-sec [{:ts 1000 :delta-tokens 100}]
                                                                       60000 10000))
                                           (.toBe 0))))

                                 (it "computes tokens/sec across a 1-second span"
                                     (fn []
      ;; Two samples 1000ms apart, second adds 500 tokens.
                                       (let [r (token-rate-per-sec
                                                [{:ts 1000 :delta-tokens 0}
                                                 {:ts 2000 :delta-tokens 500}]
                                                60000 3000)]
                                         (-> (expect r) (.toBe 500)))))

                                 (it "excludes samples older than the window"
                                     (fn []
      ;; Window is 60s, now is 120000 → cutoff 60000.
      ;; Sample at ts=10000 is outside, only 2 samples remain.
                                       (let [r (token-rate-per-sec
                                                [{:ts 10000  :delta-tokens 1000}   ;; outside
                                                 {:ts 110000 :delta-tokens 0}      ;; baseline inside
                                                 {:ts 120000 :delta-tokens 100}]   ;; inside
                                                60000 120000)]
                                         (-> (expect r) (.toBe 10)))))

                                 (it "returns 0 when span is zero"
                                     (fn []
                                       (let [r (token-rate-per-sec
                                                [{:ts 1000 :delta-tokens 50}
                                                 {:ts 1000 :delta-tokens 50}]
                                                60000 2000)]
                                         (-> (expect r) (.toBe 0)))))

                                 (it "accepts JS-style sample objects (deltaTokens)"
                                     (fn []
                                       (let [samples [#js {:ts 1000 :deltaTokens 0}
                                                      #js {:ts 2000 :deltaTokens 200}]
                                             r (token-rate-per-sec samples 60000 3000)]
                                         (-> (expect r) (.toBe 200)))))

                                 (it "handles out-of-order samples"
                                     (fn []
                                       (let [samples [{:ts 2000 :delta-tokens 100}
                                                      {:ts 1000 :delta-tokens 0}]
                                             r (token-rate-per-sec samples 60000 3000)]
                                         (-> (expect r) (.toBe 100)))))))
