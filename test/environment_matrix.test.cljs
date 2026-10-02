(ns environment-matrix-test
  "The degraded-terminal paths, which are the ones nobody runs.

   `ansi.cljs` collapsed every semantic colour to bold-white or white below
   256 colours, so on a 16-colour terminal error, warning and success printed
   identically — colour stopped carrying the only thing it carries in this UI,
   in the one environment with no other signal. That shipped because the
   branch is never exercised: a developer's terminal is truecolor.

   `themes.cljs` has the same shape for glyphs (`NYMA_ASCII`, non-UTF-8
   locale). Both are pure over an injected env, so the matrix costs nothing to
   assert — the reason it was missing is that nobody thought to vary the
   environment, not that it was hard."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.ansi :as ansi]
            [agent.ui.themes :as themes]))

(def ^:private depths
  {"NO_COLOR"  {:env #js {"NO_COLOR" "1"}                  :want 0}
   "TERM=dumb" {:env #js {"TERM" "dumb"}                    :want 0}
   "16-colour" {:env #js {"TERM" "xterm-color"}             :want 16}
   "256"       {:env #js {"TERM" "xterm-256color"}          :want 256}
   "truecolor" {:env #js {"COLORTERM" "truecolor"}          :want 16777216}
   ;; FORCE_COLOR alone forces truecolor...
   "FORCE"     {:env #js {"FORCE_COLOR" "1"}                :want 16777216}
   ;; ...but TERM=dumb is checked FIRST and wins. Pinned as-is rather than
   ;; "fixed": the de-facto convention (chalk, supports-color, node) is that
   ;; FORCE_COLOR overrides every heuristic except NO_COLOR, so this is a
   ;; deviation — but changing what colour a terminal gets is not this
   ;; change's business. Recorded so the next person sees it is deliberate.
   "FORCE+dumb" {:env #js {"FORCE_COLOR" "1" "TERM" "dumb"} :want 0}
   "no TERM"   {:env #js {}                                 :want 16777216}})

(describe "colour depth detection"
  (fn []
    (it "reads the environment the way each terminal advertises itself"
      (fn []
        (doseq [[label {:keys [env want]}] depths]
          (-> (expect #js [label (ansi/color-depth env)]) (.toEqual #js [label want])))))

    (it "NO_COLOR wins over everything, including FORCE_COLOR"
      (fn []
        ;; NO_COLOR is the documented opt-out; a tool that lets another
        ;; variable override it is not honouring it.
        (-> (expect (ansi/color-depth #js {"NO_COLOR" "1" "FORCE_COLOR" "1"
                                           "COLORTERM" "truecolor"}))
            (.toBe 0))))

    (it "an absent TERM is no terminal, not a 16-colour one"
      (fn []
        ;; CI, a pipe, a test runner. Guessing 16 here would degrade output
        ;; everywhere it is captured rather than displayed.
        (-> (expect (ansi/color-depth #js {})) (.toBe 16777216))))))

(describe "every depth keeps the semantic colours apart"
  (fn []
    (it "error, warning and success are distinguishable at 16, 256 and truecolor"
      (fn []
        ;; THE regression. At depth 16 all three were ESC[97m.
        (doseq [depth [16 256 16777216]]
          (doseq [theme [themes/default-dark themes/default-light]]
            (let [cols  (:colors theme)
                  codes (map (fn [k] (ansi/fg (get cols k) depth))
                             [:error :warning :success])]
              (-> (expect #js [depth (count (set codes))]) (.toEqual #js [depth 3])))))))

    (it "depth 0 emits nothing at all, for every colour"
      (fn []
        ;; Not "the same thing three times" — the empty string, so callers can
        ;; concatenate unconditionally.
        (doseq [theme [themes/default-dark themes/default-light]]
          (doseq [[_ hex] (:colors theme)]
            (when (string? hex)
              (-> (expect (ansi/fg hex 0)) (.toBe "")))))))

    (it "no depth ever emits a malformed escape"
      (fn []
        (doseq [depth [0 16 256 16777216]]
          (doseq [hex ["#000000" "#ffffff" "#f7768e" "#9ece6a"]]
            (let [s (ansi/fg hex depth)]
              (-> (expect #js [depth hex (or (= s "") (.endsWith s "m"))])
                  (.toEqual #js [depth hex true])))))))))

(describe "glyph fallback"
  (fn []
    (it "unicode when the locale can show it"
      (fn []
        (-> (expect (themes/unicode-ok? #js {"LANG" "en_US.UTF-8"})) (.toBe true))))

    (it "ASCII under NYMA_ASCII"
      (fn []
        (-> (expect (themes/unicode-ok? #js {"NYMA_ASCII" "1" "LANG" "en_US.UTF-8"}))
            (.toBe false))))

    (it "ASCII under a non-UTF-8 locale"
      (fn []
        (-> (expect (themes/unicode-ok? #js {"LANG" "C"})) (.toBe false))
        (-> (expect (themes/unicode-ok? #js {"LC_ALL" "POSIX"})) (.toBe false))))

    (it "every icon has an ASCII stand-in, so none can render as tofu"
      (fn []
        ;; The reason the fallback exists: the renderers hardcoded emoji and a
        ;; terminal without them showed tofu. A missing entry reintroduces it.
        (doseq [k (keys themes/unicode-icons)]
          (-> (expect #js [k (string? (get themes/ascii-icons k))])
              (.toEqual #js [k true])))))

    (it "icon never returns an empty string"
      (fn []
        (doseq [k (keys themes/unicode-icons)]
          (-> (expect #js [k (pos? (count (themes/icon themes/default-dark k)))])
              (.toEqual #js [k true])))))))
