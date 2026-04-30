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

;;; ─── role display (regression: name-not-defined) ─────────────────────────

(describe "status-bar/role" (fn []
                              (it "hides role when default"
                                  (fn []
                                    (let [bar (create-status-bar theme)]
                                      (.setState bar {:role "default"})
                                      (-> (expect (.includes (render-bar bar 80) "[default]")) (.toBe false)))))

                              (it "hides role when nil"
                                  (fn []
                                    (let [bar (create-status-bar theme)]
                                      (.setState bar {:role nil})
                                      (let [text (render-bar bar 80)]
                                        (-> (expect (.includes text "[")) (.toBe false))))))

                              (it "shows non-default role in brackets"
                                  (fn []
                                    (let [bar (create-status-bar theme)]
                                      (.setState bar {:role "plan"})
                                      (-> (expect (.includes (render-bar bar 80) "[plan]")) (.toBe true)))))

                              (it "shows build role"
                                  (fn []
                                    (let [bar (create-status-bar theme)]
                                      (.setState bar {:role "build"})
                                      (-> (expect (.includes (render-bar bar 80) "[build]")) (.toBe true)))))

                              (it "shows fast role"
                                  (fn []
                                    (let [bar (create-status-bar theme)]
                                      (.setState bar {:role "fast"})
                                      (-> (expect (.includes (render-bar bar 80) "[fast]")) (.toBe true)))))))

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
