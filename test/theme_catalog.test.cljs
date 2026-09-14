(ns theme-catalog.test
  (:require [agent.ui.themes :refer [icon unicode-ok? ascii-icons]]
            ["bun:test" :refer [describe it expect]]
            [agent.ui.theme-catalog :as tc]))

(describe "base16 → nyma theme conversion" (fn []

                                             (it "maps base16 slots to nyma's schema (golden: gruvbox)"
                                                 (fn []
                                                   (let [t (tc/base16->theme #js {"base02" "#504945" "base03" "#665c54"
                                                                                  "base08" "#fb4934" "base0A" "#fabd2f"
                                                                                  "base0B" "#b8bb26" "base0D" "#83a598" "base0E" "#d3869b"})]
                                                     (-> (expect (get-in t [:colors :primary])) (.toBe "#83a598"))   ; base0D blue
                                                     (-> (expect (get-in t [:colors :error])) (.toBe "#fb4934"))     ; base08 red
                                                     (-> (expect (get-in t [:colors :warning])) (.toBe "#fabd2f"))   ; base0A yellow
                                                     (-> (expect (get-in t [:colors :secondary])) (.toBe "#b8bb26")); base0B green
                                                     (-> (expect (get-in t [:colors :muted])) (.toBe "#665c54"))     ; base03
                                                     (-> (expect (get-in t [:colors :context-purple])) (.toBe "#d3869b")) ; base0E
                                                     (-> (expect (get-in t [:colors :editor-border :high])) (.toBe "#fb4934")))))

                                             (it "produces the icons block"
                                                 (fn []
                                                   (-> (expect (get-in (tc/base16->theme #js {}) [:icons :assistant])) (.toBe "●"))))))

(describe "theme catalog" (fn []

                            (it "has multiple bundled themes"
                                (fn []
                                  (-> (expect (>= (count (tc/theme-names)) 8)) (.toBe true))
                                  (-> (expect (contains? (set (tc/theme-names)) "gruvbox-dark")) (.toBe true))))

                            (it "every bundled theme has the required color slots"
                                (fn []
                                  (doseq [[_ t] tc/catalog]
                                    (-> (expect (string? (get-in t [:colors :primary]))) (.toBe true))
                                    (-> (expect (string? (get-in t [:colors :error]))) (.toBe true))
                                    (-> (expect (map? (get-in t [:colors :editor-border]))) (.toBe true)))))

                            (it "pick-theme resolves by name, falls back on unknown"
                                (fn []
                                  (let [dflt {:default true}]
                                    (-> (expect (map? (tc/pick-theme "nord" nil dflt))) (.toBe true))
                                    (-> (expect (tc/pick-theme "no-such-theme" nil dflt)) (.toEqual (clj->js dflt)))
                                    (-> (expect (tc/pick-theme nil nil dflt)) (.toEqual (clj->js dflt))))))

                            (it "user themes merge with the catalog"
                                (fn []
                                  (-> (expect (:mine (tc/pick-theme "custom" {"custom" {:mine 1}} {}))) (.toBe 1))))))

(describe "icons" (fn []
  (it "fall back to ASCII when the locale is not UTF-8 or NYMA_ASCII is set"
      (fn []
        (-> (expect (unicode-ok? #js {"LANG" "C"})) (.toBe false))
        (-> (expect (unicode-ok? #js {"LANG" "en_US.UTF-8"})) (.toBe true))
        (-> (expect (unicode-ok? #js {"LANG" "en_US.UTF-8" "NYMA_ASCII" "1"})) (.toBe false))
        (-> (expect (get ascii-icons :error)) (.toBe "x"))))
  (it "a theme's own icon wins when unicode is fine"
      (fn []
        (when (unicode-ok?)
          (-> (expect (icon {:icons {:user "»"}} :user)) (.toBe "»"))
          (-> (expect (icon {} :tool-done)) (.toBe "✓")))))))
