(ns status-bar.test
  "Tests for create-status-bar — covers role display (regression: name-not-defined)
   and basic render contract."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.status-bar :refer [create-status-bar]]))

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
