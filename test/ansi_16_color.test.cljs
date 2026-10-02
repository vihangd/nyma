(ns ansi-16-color-test
  "A 16-colour terminal has to keep error, warning and success apart.

   `fg` used to decide the sub-256 case with a luminance test returning 97 or
   37 — bright white or white — for every colour. So on a 16-colour terminal
   every semantic colour in the theme printed identically, and colour stopped
   carrying the only thing it carries in this UI: which line is a problem.
   That is also the terminal where there is no other signal to fall back on.

   Two things this file pins, both learned by getting them wrong first:

   - **Nearest-RGB is not good enough.** `#f7768e`, the dark theme's error red,
     is 34,152 away from pure red and 14,706 from mid-grey, so a distance
     metric prints a salmon error message grey — and success and muted land on
     that same grey. Every desaturated colour collapses to the centre.
   - **The yellow/green boundary is not the midpoint.** `#9ece6a`, an
     unmistakably green success colour, computes to hue 88.8°; with a boundary
     at 90 it printed as yellow, the same shade as warning."
  (:require ["bun:test" :refer [test expect describe]]
            [agent.utils.ansi :as ansi]
            [agent.ui.themes :as themes]))

(defn- code
  "The SGR number `fg` emits at depth 16. Goes through `fg` rather than calling
   `rgb->16` directly, because `hex->rgb` is private and because the pairing of
   the two is what callers actually depend on."
  [hex]
  (js/parseInt (.replace (.replace (ansi/fg hex 16) (js/RegExp. "^\\u001b\\[") "") "m" "") 10))

(describe "rgb->16 hues"
          (fn []
            (test "the six primaries map to their own colour"
                  (fn []
                    (-> (expect (code "#ff0000")) (.toBe 91))
                    (-> (expect (code "#ffff00")) (.toBe 93))
                    (-> (expect (code "#00ff00")) (.toBe 92))
                    (-> (expect (code "#00ffff")) (.toBe 96))
                    (-> (expect (code "#0000ff")) (.toBe 94))
                    (-> (expect (code "#ff00ff")) (.toBe 95))))

            (test "dark colours use the normal set, light ones the bright set"
                  (fn []
        ;; 31 vs 91 is the only brightness distinction 16 colours offer.
                    (-> (expect (code "#800000")) (.toBe 31))
                    (-> (expect (code "#ff0000")) (.toBe 91))))

            (test "greys go to the greyscale ramp, not to a hue"
                  (fn []
                    (-> (expect (code "#000000")) (.toBe 30))
                    (-> (expect (code "#555555")) (.toBe 90))
                    (-> (expect (code "#cccccc")) (.toBe 37))
                    (-> (expect (code "#ffffff")) (.toBe 97))))

            (test "a desaturated red is still red, not grey"
                  (fn []
        ;; The nearest-RGB failure, pinned.
                    (-> (expect (code "#f7768e")) (.toBe 91))))

            (test "a yellow-leaning green is still green, not yellow"
                  (fn []
        ;; hue 88.8° — the midpoint-boundary failure, pinned.
                    (-> (expect (code "#9ece6a")) (.toBe 92))
        ;; and actual yellow stays yellow
                    (-> (expect (code "#e0af68")) (.toBe 93))))))

(describe "fg at depth 16"
          (fn []
            (test "emits a 16-colour SGR, not a 256 or truecolor one"
                  (fn []
                    (-> (expect (ansi/fg "#f7768e" 16)) (.toBe (str (js/String.fromCharCode 27) "[91m")))))

            (test "the other depths are unchanged"
                  (fn []
                    (-> (expect (ansi/fg "#f7768e" 0)) (.toBe ""))
                    (-> (expect (ansi/fg "#ff0000" 256)) (.toContain "38;5;"))
                    (-> (expect (ansi/fg "#ff0000" 16777216)) (.toBe (str (js/String.fromCharCode 27) "[38;2;255;0;0m")))))

            (test "a malformed hex still yields the empty string"
                  (fn []
                    (doseq [h [nil "" "red" "#fff" "#gggggg"]]
                      (-> (expect (ansi/fg h 16)) (.toBe "")))))))

(describe "the shipped themes stay legible"
          (fn []
            (test "error, warning and success are three different colours in both themes"
                  (fn []
        ;; THE regression this guards. Before, all three were ESC[97m.
                    (doseq [theme [themes/default-dark themes/default-light]]
                      (let [cols (:colors theme)
                            codes (map (fn [k] (ansi/fg (get cols k) 16))
                                       [:error :warning :success])]
                        (-> (expect (count (set codes))) (.toBe 3))))))))
