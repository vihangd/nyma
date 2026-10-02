(ns startup-mode-test
  "`settings.startup` — card (default), header, or silent.

   The project card printed unconditionally. `NYMA_QUIET` gates only the debug
   logger's stderr mirror and `tool-display` is about tool results, so there
   was no way to turn startup output down.

   The one behaviour worth pinning beyond the obvious: an unknown value falls
   back to the CARD, not to silence. A typo must not be the reason a session
   opens with no context — that would be a settings bug presenting as a
   missing feature, which is the shape of several entries in this repo's
   roadmap."
  (:require ["bun:test" :refer [test expect describe]]
            [agent.ui.project-card :as pc]
            [agent.settings.manager :as sm]))

(describe "startup-mode"
          (fn []
            (test "the three modes"
                  (fn []
                    (-> (expect (pc/startup-mode "card")) (.toBe :card))
                    (-> (expect (pc/startup-mode "header")) (.toBe :header))
                    (-> (expect (pc/startup-mode "silent")) (.toBe :silent))))

            (test "absent or blank means card — what nyma has always done"
                  (fn []
                    (doseq [v [nil "" "   "]]
                      (-> (expect (pc/startup-mode v)) (.toBe :card)))))

            (test "case and surrounding space do not matter"
                  (fn []
                    (-> (expect (pc/startup-mode "  HEADER ")) (.toBe :header))
                    (-> (expect (pc/startup-mode "Silent")) (.toBe :silent))))

            (test "an unknown value is nil, so the caller can warn — and it must not read as silent"
                  (fn []
                    (-> (expect (pc/startup-mode "quiet")) (.toBeNil))
                    (-> (expect (pc/startup-mode "header-only")) (.toBeNil))
        ;; The distinction that matters: nil is "say something is wrong", not
        ;; "print nothing".
                    (-> (expect (= (pc/startup-mode "quiet") :silent)) (.toBe false))))

            (test "every mode it returns is one startup-modes declares"
                  (fn []
                    (doseq [v ["card" "header" "silent" nil]]
                      (-> (expect (contains? pc/startup-modes (pc/startup-mode v))) (.toBe true)))))))

(describe "the setting"
          (fn []
            (test "defaults to card"
                  (fn []
                    (-> (expect (:startup sm/defaults)) (.toBe "card"))))

            (test "the default is a value startup-mode accepts"
                  (fn []
        ;; Guards the pairing: a default the reader rejects would warn on every
        ;; launch of an unconfigured nyma.
                    (-> (expect (pc/startup-mode (:startup sm/defaults))) (.toBe :card))))))

(describe "header"
          (fn []
            (test "is the card's first line"
                  (fn []
                    (let [h (pc/header {:version "9.9.9" :cwd "/tmp/x"})]
                      (-> (expect (.startsWith h "nyma 9.9.9")) (.toBe true))
                      (-> (expect h) (.toContain "/tmp/x"))
          ;; One line, not a block: that is the whole point of header mode.
                      (-> (expect (.includes h "\n")) (.toBe false)))))

            (test "head-line abbreviates the home prefix like the card does"
                  (fn []
                    (-> (expect (pc/head-line {:version "1" :cwd "/Users/me/p" :home-dir "/Users/me"}))
                        (.toContain "~/p"))))))
