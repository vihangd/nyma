(ns interactive-helpers.test
  "Tests for the pure helper functions in agent.modes.interactive."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.interactive :refer [alt-screen-enabled?
                                             pager-mode-enabled?
                                             effective-scrollback-on?]]))

;;; ─── alt-screen-enabled? ──────────────────────────────────────────────────

(describe "alt-screen-enabled?" (fn []
                                  (it "returns true for '1'"
                                      (fn [] (-> (expect (alt-screen-enabled? "1")) (.toBe true))))

                                  (it "returns true for 'true'"
                                      (fn [] (-> (expect (alt-screen-enabled? "true")) (.toBe true))))

                                  (it "returns true for any non-empty non-zero string"
                                      (fn [] (-> (expect (alt-screen-enabled? "yes")) (.toBe true))))

                                  (it "returns false for '0'"
                                      (fn [] (-> (expect (alt-screen-enabled? "0")) (.toBe false))))

                                  (it "returns false for 'false'"
                                      (fn [] (-> (expect (alt-screen-enabled? "false")) (.toBe false))))

                                  (it "returns false for empty string"
                                      (fn [] (-> (expect (alt-screen-enabled? "")) (.toBe false))))

                                  (it "returns false for nil"
                                      (fn [] (-> (expect (alt-screen-enabled? nil)) (.toBe false))))))

;;; ─── pager-mode-enabled? ──────────────────────────────────────────────────

(describe "pager-mode-enabled?" (fn []
                                  (it "returns true when not alt-screen and setting is 'pager'"
                                      (fn []
                                        (-> (expect (pager-mode-enabled? {:alt-screen? false :scrollback-mode-setting "pager"}))
                                            (.toBe true))))

                                  (it "returns false when alt-screen is true even with pager setting"
                                      (fn []
                                        (-> (expect (pager-mode-enabled? {:alt-screen? true :scrollback-mode-setting "pager"}))
                                            (.toBe false))))

                                  (it "returns false when setting is not 'pager'"
                                      (fn []
                                        (-> (expect (pager-mode-enabled? {:alt-screen? false :scrollback-mode-setting "on"}))
                                            (.toBe false))))

                                  (it "returns false when setting is nil"
                                      (fn []
                                        (-> (expect (pager-mode-enabled? {:alt-screen? false :scrollback-mode-setting nil}))
                                            (.toBe false))))))

;;; ─── effective-scrollback-on? ─────────────────────────────────────────────

(describe "effective-scrollback-on?" (fn []
                                       (it "returns true by default (nil setting, no alt-screen)"
                                           (fn []
                                             (-> (expect (effective-scrollback-on? {:alt-screen? false :scrollback-mode-setting nil}))
                                                 (.toBe true))))

                                       (it "returns false when alt-screen is active"
                                           (fn []
                                             (-> (expect (effective-scrollback-on? {:alt-screen? true :scrollback-mode-setting nil}))
                                                 (.toBe false))))

                                       (it "returns false when pager mode is active"
                                           (fn []
                                             (-> (expect (effective-scrollback-on? {:alt-screen? false :scrollback-mode-setting "pager"}))
                                                 (.toBe false))))

                                       (it "returns true when setting is truthy non-pager value"
                                           (fn []
                                             (-> (expect (effective-scrollback-on? {:alt-screen? false :scrollback-mode-setting true}))
                                                 (.toBe true))))

                                       (it "returns false when setting is false"
                                           (fn []
                                             (-> (expect (effective-scrollback-on? {:alt-screen? false :scrollback-mode-setting false}))
                                                 (.toBe false))))))
