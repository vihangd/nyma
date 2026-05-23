(ns interactive-alt-screen.test
  "Unit tests for the experimental NYMA_ALT_SCREEN env-var path in
   src/agent/modes/interactive.cljs.

   The env var enables Ink 7's `alternateScreen` mode (vim/htop/less
   style). The wiring also force-disables scrollback-mode so the
   commit-sweep doesn't write past turns into the alt-screen buffer
   (which has no shared terminal scrollback by design).

   Tests here pin the env-parsing predicate and the implication that
   alt-screen and scrollback-mode are mutually exclusive."
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/modes/interactive.mjs" :refer [alt_screen_enabled_QMARK_
                                                     effective_scrollback_on_QMARK_
                                                     pager_mode_enabled_QMARK_]]))

(describe "alt-screen-enabled?: NYMA_ALT_SCREEN env parsing"
          (fn []

            (it "returns false when env var is unset (nil)"
                (fn []
                  (-> (expect (alt_screen_enabled_QMARK_ nil)) (.toBe false))
                  (-> (expect (alt_screen_enabled_QMARK_ js/undefined)) (.toBe false))))

            (it "returns false for empty string"
                (fn []
                  (-> (expect (alt_screen_enabled_QMARK_ "")) (.toBe false))))

            (it "returns false for explicit falsy strings"
                ;; The user might `export NYMA_ALT_SCREEN=0` to leave
                ;; the var defined but disabled. Honor that.
                (fn []
                  (-> (expect (alt_screen_enabled_QMARK_ "0")) (.toBe false))
                  (-> (expect (alt_screen_enabled_QMARK_ "false")) (.toBe false))))

            (it "returns true for \"1\" and \"true\""
                (fn []
                  (-> (expect (alt_screen_enabled_QMARK_ "1")) (.toBe true))
                  (-> (expect (alt_screen_enabled_QMARK_ "true")) (.toBe true))))

            (it "returns true for any other non-empty string (be permissive)"
                ;; We accept anything truthy-looking; users may set
                ;; NYMA_ALT_SCREEN=yes or =on without surprises.
                (fn []
                  (-> (expect (alt_screen_enabled_QMARK_ "yes")) (.toBe true))
                  (-> (expect (alt_screen_enabled_QMARK_ "on")) (.toBe true))
                  (-> (expect (alt_screen_enabled_QMARK_ "anything")) (.toBe true))))))

(describe "effective-scrollback-on?: alt-screen and scrollback-mode are mutually exclusive"
          (fn []

            (it "alt-screen ON forces scrollback-mode OFF, regardless of setting"
                ;; The whole point of the gate: writing past turns to
                ;; scrollback inside an alt-screen buffer pollutes the
                ;; alt-screen and leaves nothing in real scrollback.
                ;; alt-screen wins the conflict.
                (fn []
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? true :scrollback-mode-setting true}))
                      (.toBe false))
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? true :scrollback-mode-setting false}))
                      (.toBe false))
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? true :scrollback-mode-setting nil}))
                      (.toBe false))))

            (it "alt-screen OFF: honors the setting verbatim"
                (fn []
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting true}))
                      (.toBe true))
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting false}))
                      (.toBe false))))

            (it "alt-screen OFF: setting=nil defaults to true (matches the project-wide default)"
                ;; scrollback-mode default flipped to true in d49e4a4.
                ;; The helper preserves that default so first-run users
                ;; (no setting persisted) get the same behavior.
                (fn []
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting nil}))
                      (.toBe true))))

            (it "setting=\"pager\" forces scrollback-on? false (pager owns in-flight)"
                ;; Pager mode manages its own windowed render; the
                ;; writeToStdout commit loop is irrelevant there.
                (fn []
                  (-> (expect (effective_scrollback_on_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting "pager"}))
                      (.toBe false))))))

(describe "pager-mode-enabled?: \"pager\" setting vs other values"
          (fn []

            (it "setting=\"pager\" with alt-screen OFF returns true"
                (fn []
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting "pager"}))
                      (.toBe true))))

            (it "alt-screen ON suppresses pager even if setting=\"pager\""
                ;; alt-screen and pager are orthogonal — forcing one at
                ;; a time keeps the interactions predictable.
                (fn []
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? true :scrollback-mode-setting "pager"}))
                      (.toBe false))))

            (it "setting=true (default) is NOT pager mode"
                (fn []
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting true}))
                      (.toBe false))))

            (it "setting=false (Static mode) is NOT pager mode"
                (fn []
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting false}))
                      (.toBe false))))

            (it "setting=nil (missing) is NOT pager mode"
                (fn []
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting nil}))
                      (.toBe false))))

            (it "unrecognized string value is NOT pager mode (strict match)"
                ;; Only the exact literal "pager" enables the mode.
                (fn []
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting "PAGER"}))
                      (.toBe false))
                  (-> (expect (pager_mode_enabled_QMARK_
                               #js {:alt-screen? false :scrollback-mode-setting "page"}))
                      (.toBe false))))))
