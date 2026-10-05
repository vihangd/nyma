(ns width-parity-test
  "Where `Bun.stringWidth` and pi-tui's `visibleWidth` agree, and where they
   do not.

   This matters because pi-tui's crash check — the throw that kills a session
   when a rendered line exceeds the terminal — measures with ITS OWN
   `visibleWidth`, while nyma wraps and measures most text with Bun's. The
   width guard deliberately clamps with pi-tui's functions, so a disagreement
   costs truncated text rather than a crash. But that only holds while the
   disagreements stay in one direction, and nothing tested it: the only
   comparison was `render_width_safety`, on ASCII.

   Measured on Bun 1.4.2 / pi-tui 1.0: three inputs disagree, and in every one
   Bun gives the SMALLER number — so Bun can say a line fits when pi-tui says it
   does not. If a future version flips a direction, or adds a case, this fails
   and says which."
  (:require ["bun:test" :refer [describe it expect]]
            ["@earendil-works/pi-tui" :refer [visibleWidth]]))

(def ^:private agree
  "Inputs on which the two must agree. The hard cases for a width function —
   CJK, ZWJ sequences, flags, variation selectors, keycaps, skin tones,
   ambiguous-width symbols, Thai, and SGR/OSC escapes — all do."
  {"ascii"      "hello"
   "CJK"        "漢字"
   "ZWJ family" "👨‍👩‍👧"
   "flag"       "🇯🇵"
   "VS16 heart" "❤️"
   "keycap"     "1️⃣"
   "skin tone"  "👍🏽"
   "ambiguous"  "±×→…①Ω"
   "thai"       "สวัสดี"
   "SGR"        "\u001b[31mred\u001b[0m"
   "OSC8 link"  "\u001b]8;;https://x\u001b\\link\u001b]8;;\u001b\\"})

(def ^:private known-differ
  "Inputs on which they DIFFER today, with the measured values. Pinned exactly,
   so a change in either direction is visible."
  {"lone regional indicator" ["🇯" 1 2]
   "devanagari conjunct"     ["क्षि" 2 3]
   "tab"                     ["a\tb" 2 5]})

(describe "Bun.stringWidth vs pi-tui visibleWidth"
  (fn []
    (it "agree on every hard case except the recorded ones"
      (fn []
        (doseq [[label s] agree]
          (-> (expect #js [label (js/Bun.stringWidth s) (visibleWidth s)])
              (.toEqual #js [label (visibleWidth s) (visibleWidth s)])))))

    (it "differ exactly as recorded"
      (fn []
        (doseq [[label [s bun pi]] known-differ]
          (-> (expect #js [label (js/Bun.stringWidth s) (visibleWidth s)])
              (.toEqual #js [label bun pi])))))

    (it "every disagreement has Bun LOWER — which decides what it costs"
      (fn []
        ;; The guard is crash-safe either way, because it clamps with pi-tui's
        ;; own functions. Direction decides the cost. Bun LOWER (today): nyma
        ;; wraps a line pi-tui considers too wide, and the guard cuts the
        ;; overflow — lost characters. Bun HIGHER would instead wrap too early —
        ;; wasted columns, nothing lost. A flip would change which of those
        ;; users see, so it should be a decision, not a surprise.
        (doseq [[label [s _ _]] known-differ]
          (-> (expect #js [label (< (js/Bun.stringWidth s) (visibleWidth s))])
              (.toEqual #js [label true])))))))
