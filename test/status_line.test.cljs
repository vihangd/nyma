(ns status-line.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.status-line-segments
             :refer [context-usage-level
                     register-segment
                     get-segment
                     segment-registry
                     builtin-segments
                     render-segments
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
