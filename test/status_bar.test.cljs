(ns status-bar.test
  "Tests for create-status-bar — covers role display (regression: name-not-defined),
   basic render contract, AND extension auto-append segment integration
   (regression: register-segment used to write to a registry no consumer read)."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.ui.status-bar :refer [create-status-bar]]
            [agent.ui.status-line-segments :as segs]))

(def ^:private theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"
            :warning   "#e0af68"}})

(defn- strip-ansi [s]
  (.replace s (js/RegExp. "\u001b\\[[0-9;]*m" "g") ""))

(defn- render-bar [bar width]
  (let [lines (.render bar width)]
    (strip-ansi (aget lines 0))))

;;; ─── creation ─────────────────────────────────────────────────────────────

(describe "status-bar/create" (fn []
                                (it "returns a component with render and invalidate"
                                    (fn []
                                      (let [bar (create-status-bar theme)]
                                        (-> (expect (fn? (.-render bar))) (.toBe true))
                                        (-> (expect (fn? (.-invalidate bar))) (.toBe true)))))

                                (it "render returns an array"
                                    (fn []
                                      (let [bar   (create-status-bar theme)
                                            lines (.render bar 80)]
                                        (-> (expect (js/Array.isArray lines)) (.toBe true)))))

                                (it "renders exactly one line"
                                    (fn []
                                      (let [bar   (create-status-bar theme)
                                            lines (.render bar 80)]
                                        (-> (expect (.-length lines)) (.toBe 1)))))))

;;; ─── default state ────────────────────────────────────────────────────────

(describe "status-bar/default-state" (fn []
                                       (it "shows nyma in the bar"
                                           (fn []
                                             (let [bar  (create-status-bar theme)
                                                   text (render-bar bar 80)]
                                               (-> (expect (.includes text "nyma")) (.toBe true)))))

                                       (it "shows ready when not streaming"
                                           (fn []
                                             (let [bar  (create-status-bar theme)
                                                   text (render-bar bar 80)]
                                               (-> (expect (.includes text "ready")) (.toBe true)))))

                                       (it "does not show turns when turn-count is 0"
                                           (fn []
                                             (let [bar  (create-status-bar theme)
                                                   text (render-bar bar 80)]
                                               (-> (expect (.includes text "turns")) (.toBe false)))))))

;;; ─── setState ─────────────────────────────────────────────────────────────

(describe "status-bar/setState" (fn []
                                  (it "updates model display"
                                      (fn []
                                        (let [bar (create-status-bar theme)]
                                          (.setState bar {:model "claude-sonnet-4-6"})
                                          (-> (expect (.includes (render-bar bar 80) "claude-sonnet-4-6")) (.toBe true)))))

                                  (it "shows streaming indicator"
                                      (fn []
                                        (let [bar (create-status-bar theme)]
                                          (.setState bar {:streaming true})
                                          (-> (expect (.includes (render-bar bar 80) "streaming")) (.toBe true)))))

                                  (it "hides streaming when false"
                                      (fn []
                                        (let [bar (create-status-bar theme)]
                                          (.setState bar {:streaming false})
                                          (let [text (render-bar bar 80)]
                                            (-> (expect (.includes text "streaming")) (.toBe false))
                                            (-> (expect (.includes text "ready")) (.toBe true))))))

                                  (it "shows turn count when positive"
                                      (fn []
                                        (let [bar (create-status-bar theme)]
                                          (.setState bar {:turn-count 5})
                                          (let [text (render-bar bar 80)]
                                            (-> (expect (.includes text "5")) (.toBe true))
                                            (-> (expect (.includes text "turns")) (.toBe true))))))

                                  (it "partial updates preserve other fields"
                                      (fn []
                                        (let [bar (create-status-bar theme)]
                                          (.setState bar {:model "gpt-4o" :turn-count 3})
                                          (.setState bar {:streaming true})
                                          (let [text (render-bar bar 80)]
                                            (-> (expect (.includes text "gpt-4o")) (.toBe true))
                                            (-> (expect (.includes text "streaming")) (.toBe true))
                                            (-> (expect (.includes text "3")) (.toBe true))))))

                                  (it "ignores nil setState"
                                      (fn []
                                        (let [bar (create-status-bar theme)]
                                          (.setState bar {:model "m1"})
                                          (.setState bar nil)
                                          (-> (expect (.includes (render-bar bar 80) "m1")) (.toBe true)))))))

;; Role/mode display moved OUT of the status-bar core into the model_roles
;; status SEGMENT (color-coded) — see model_roles_modes.test render-role tests.
;; The bar no longer renders an inline [role]; segments are appended by the
;; extension at runtime.

;;; ─── width handling ───────────────────────────────────────────────────────

(describe "status-bar/width" (fn []
                               (it "renders at narrow width without throwing"
                                   (fn []
                                     (let [bar   (create-status-bar theme)]
                                       (.setState bar {:model "minimax-m2.5-freeready" :role "plan" :turn-count 10})
                                       (let [lines (.render bar 40)]
                                         (-> (expect (.-length lines)) (.toBe 1))))))

                               (it "right side visible beats left truncation"
                                   (fn []
                                     (let [bar (create-status-bar theme)]
                                       (.setState bar {:model "a-very-long-model-name-that-wont-fit" :streaming false})
                                       (let [text (render-bar bar 30)]
                                         (-> (expect (.includes text "ready")) (.toBe true))))))))

;;; ─── extension auto-append segments ───────────────────────────────────────
;;; These regressions exist because for a long time register-segment
;;; wrote to a registry that NO status-line consumer actually read.
;;; Lock both directions: the bar reads the registry, hides invisible
;;; segments, places by :position, and a render error in one segment
;;; doesn't blank the bar.

(defn- cleanup-segs! []
  (doseq [id ["test.x" "test.y" "test.boom" "test.hidden"]]
    (try (segs/unregister-segment id) (catch :default _ nil))))

(afterEach cleanup-segs!)

(describe "status-bar/extension-segments"
          (fn []
            (it "renders an auto-append :left segment in the bar"
                (fn []
                  (segs/register-segment
                   "test.x"
                   {:auto-append? true
                    :position     :left
                    :render       (fn [_] {:visible? true
                                           :content  "MCP 1/1"
                                           :color    "#9ece6a"})})
                  (let [bar  (create-status-bar theme)
                        text (render-bar bar 200)]
                    (-> (expect (.includes text "MCP 1/1")) (.toBe true)))))

            (it "renders :right segments on the right side, before 'ready'"
                (fn []
                  (segs/register-segment
                   "test.y"
                   {:auto-append? true
                    :position     :right
                    :render       (fn [_] {:visible? true
                                           :content  "leanc ✓"})})
                  (let [bar  (create-status-bar theme)
                        text (render-bar bar 200)
                        idx-x (.indexOf text "leanc")
                        idx-r (.indexOf text "ready")]
                    (-> (expect (>= idx-x 0)) (.toBe true))
                    (-> (expect (>= idx-r 0)) (.toBe true))
                    ;; right segments precede the built-in 'ready'
                    (-> (expect (< idx-x idx-r)) (.toBe true)))))

            (it "skips segments whose render returns :visible? false"
                (fn []
                  (segs/register-segment
                   "test.hidden"
                   {:auto-append? true
                    :position     :left
                    :render       (fn [_] {:visible? false})})
                  (let [bar  (create-status-bar theme)
                        text (render-bar bar 200)]
                    (-> (expect (.includes text "test.hidden")) (.toBe false)))))

            (it "skips non-auto-append segments"
                (fn []
                  (segs/register-segment
                   "test.x"
                   {:auto-append? false  ;; opt-in only via preset
                    :position     :left
                    :render       (fn [_] {:visible? true :content "OPTIN"})})
                  (let [bar  (create-status-bar theme)
                        text (render-bar bar 200)]
                    (-> (expect (.includes text "OPTIN")) (.toBe false)))))

            (it "isolates errors in one segment — doesn't blank the bar"
                (fn []
                  (segs/register-segment
                   "test.boom"
                   {:auto-append? true
                    :position     :left
                    :render       (fn [_] (throw (js/Error. "kaboom")))})
                  (segs/register-segment
                   "test.x"
                   {:auto-append? true
                    :position     :left
                    :render       (fn [_] {:visible? true :content "OK1"})})
                  (let [bar  (create-status-bar theme)
                        text (render-bar bar 200)]
                    ;; Built-in content still renders despite the bad segment.
                    (-> (expect (.includes text "nyma")) (.toBe true))
                    ;; Other working segment still renders.
                    (-> (expect (.includes text "OK1")) (.toBe true)))))))

;;; ─── provider prefix ─────────────────────────────────────────────────────

(describe "status-bar/provider-prefix"
          (fn []
            (it "renders provider/model when a provider was set"
                (fn []
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "MiniMax-M2.5"
                                        :provider "minimax"})
                    (let [text (render-bar bar 200)]
                      (-> (expect (.includes text "minimax/MiniMax-M2.5")) (.toBe true))))))

            (it "renders just the model when no provider was set"
                (fn []
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "claude-sonnet-4-5"})
                    (let [text (render-bar bar 200)]
                      ;; No slash should appear in the model area
                      (-> (expect (.includes text "claude-sonnet-4-5")) (.toBe true))
                      (-> (expect (.includes text "/claude-sonnet-4-5")) (.toBe false))))))

            (it "renders just the model when provider is empty string"
                (fn []
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "claude-sonnet-4-5"
                                        :provider ""})
                    (let [text (render-bar bar 200)]
                      (-> (expect (.includes text "/claude-sonnet-4-5")) (.toBe false))))))

            (it "skips redundant prefix when provider == model"
                (fn []
                  ;; Edge case: bare-string model spec where setModel
                  ;; couldn't decompose provider/model. Don't render
                  ;; "x/x" in that case.
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "anthropic"
                                        :provider "anthropic"})
                    (let [text (render-bar bar 200)]
                      (-> (expect (.includes text "anthropic/anthropic")) (.toBe false))))))))

(describe "status-bar/provider-prefix edge cases"
          (fn []
            (it "model already prefixed with provider/ → strip duplicate, render once"
                (fn []
                  ;; Reproduces the registry-miss fallback where setModel left
                  ;; the model as a raw `provider/model` string. Rendering must
                  ;; collapse to `provider/model`, not `provider/provider/model`.
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "unknown/some-model"
                                        :provider "unknown"})
                    (let [text (render-bar bar 200)]
                      (-> (expect (.includes text "unknown/some-model")) (.toBe true))
                      (-> (expect (.includes text "unknown/unknown/")) (.toBe false))))))

            (it "ordinary clean case still renders provider/model"
                (fn []
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "MiniMax-M2.5"
                                        :provider "minimax"})
                    (let [text (render-bar bar 200)]
                      (-> (expect (.includes text "minimax/MiniMax-M2.5")) (.toBe true))
                      (-> (expect (.includes text "minimax/minimax/")) (.toBe false))))))

            (it "model that just happens to contain the provider name (no slash) is unaffected"
                (fn []
                  ;; e.g. provider="minimax", model="minimax-m2.5-free" —
                  ;; doesn't start with "minimax/" so leave it alone.
                  (let [bar (create-status-bar theme)]
                    (.setState bar #js {:model "minimax-m2.5-free"
                                        :provider "minimax"})
                    (let [text (render-bar bar 200)]
                      (-> (expect (.includes text "minimax/minimax-m2.5-free")) (.toBe true))))))))
