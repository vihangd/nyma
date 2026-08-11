(ns overlay-host.test
  (:require ["bun:test" :refer [describe it expect]]
            ["@mariozechner/pi-tui" :refer [visibleWidth]]
            ["./agent/ui/overlay_host.mjs"
             :refer [printable-char data->key should-dismiss? close-signal?
                     adapt-component make-select-picker make-input-picker
                     make-text-overlay resolve-content-width resolve-max-height
                     default-overlay-options paste-payload]]))

;; Worst-case specs actually present in the repo's provider lists.
(def ^:private real-specs
  ["openrouter/nvidia/nemotron-3-super-120b-a12b:free"
   "openrouter/meta-llama/llama-3.3-70b-instruct:free"
   "groq/meta-llama/llama-4-scout-17b-16e-instruct"
   "vllm/poolside/Laguna-S-2.1-NVFP4"
   "anthropic/claude-haiku-4-5-20251001"])

(defn- big-catalogue []
  (mapv (fn [i]
          (let [s (nth real-specs (mod i (count real-specs)))]
            #js {:value (str s i) :label s :description "200.0k · $2.5/$10"}))
        (range 95)))

(describe "overlay-host/resolve-content-width"
          (fn []
            ;; The old path went through overlay-max-width, which reserves 6
            ;; columns for an ink border+padding pi-tui never draws — at 80 cols
            ;; that left 42 columns for specs up to 49 characters.
            (it "resolves a percentage width against the terminal"
                (fn []
                  (-> (expect (resolve-content-width #js {:width "50%"} 80)) (.toBe 40))))

            (it "honours minWidth and never exceeds the terminal"
                (fn []
                  (-> (expect (resolve-content-width #js {:width "10%" :minWidth 30} 80)) (.toBe 30))
                  (-> (expect (resolve-content-width #js {:width "200%"} 80)) (.toBe 80))))

            (it "gives the /model picker room for a full spec at 80 cols"
                (fn []
                  (let [w (resolve-content-width default-overlay-options 80)]
                    (-> (expect w) (.toBeGreaterThanOrEqual 49)))))))

(describe "overlay-host/select picker at catalogue scale"
          (fn []
            ;; 95 entries, real specs, across terminal sizes: the picker must
            ;; always fit its box on BOTH axes, since pi-tui slices overflow
            ;; from the bottom (taking the selected row with it) and wraps
            ;; over-wide rows (breaking the differential renderer).
            (it "always fits the resolved box"
                (fn []
                  (let [items (big-catalogue)]
                    (doseq [cols [40 60 80 120]
                            rows [6 10 20 24 40]]
                      (let [bw    (resolve-content-width default-overlay-options cols)
                            bh    (resolve-max-height default-overlay-options rows)
                            out   ((.-render (make-select-picker "Model" items (fn [_] nil)
                                                                 (fn [] bw)))
                                   cols bh)
                            lines (.split out "\n")]
                        (-> (expect (.-length lines)) (.toBeLessThanOrEqual bh))
                        ;; COLUMNS, not characters. This corpus is ASCII, so
                        ;; the two agree here; the wide-glyph case below is
                        ;; what distinguishes them.
                        (doseq [l lines]
                          (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual bw))))))))

            (it "fits the box for non-ASCII labels too"
                (fn []
                  ;; The reachable crash: picker-frame measured rows with
                  ;; `count`, so a label of CJK or emoji occupied two to four
                  ;; columns per character it had budgeted one for. Measured
                  ;; through this exact production path — resolve-content-width
                  ;; + default-overlay-options — a catalogue with wide glyphs
                  ;; emitted 81 columns inside an 80-column box, at every
                  ;; terminal size tried. pi-tui throws on that from inside its
                  ;; own render timer after calling stop(), so it ends the
                  ;; session: /model, the skill picker and mention pickers all
                  ;; route through here.
                  (let [wide  ["\u6f22\u5b57\u30c6\u30b9\u30c8/\u30e2\u30c7\u30eb-\u5927"
                               "\ud83c\udf89 celebration/\ud83d\ude80-turbo-v2"
                               "\ud83c\uddef\ud83c\uddf5 jp/\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66-family-model"
                               "\u4e2d\u6587\u5b57\u7b26 \ud83c\udf89 mixed/ascii-tail"]
                        items (mapv (fn [i]
                                      #js {:value (str "m" i)
                                           :label (nth wide (mod i (count wide)))
                                           :description "200.0k \u00b7 $2.5/$10"})
                                    (range 40))]
                    (doseq [cols [40 60 80 120]
                            rows [6 10 24]]
                      (let [bw    (resolve-content-width default-overlay-options cols)
                            bh    (resolve-max-height default-overlay-options rows)
                            out   ((.-render (make-select-picker "Model" items (fn [_] nil)
                                                                 (fn [] bw)))
                                   cols bh)
                            lines (.split out "\n")]
                        (-> (expect (.-length lines)) (.toBeLessThanOrEqual bh))
                        (doseq [l lines]
                          (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual bw))))))))

            (it "shows the metadata column on a normal terminal"
                (fn []
                  (let [bw  (resolve-content-width default-overlay-options 80)
                        out ((.-render (make-select-picker "Model" (big-catalogue) (fn [_] nil)
                                                           (fn [] bw)))
                             80 12)]
                    (-> (expect out) (.toContain "200.0k")))))

            (it "keeps the distinguishing tail of a long spec"
                (fn []
                  (let [bw  (resolve-content-width default-overlay-options 60)
                        out ((.-render (make-select-picker "Model" (big-catalogue) (fn [_] nil)
                                                           (fn [] bw)))
                             60 12)]
                    (-> (expect out) (.toContain "a12b:free")))))

            ;; fuzzy-filter is multi-token, so provider + id can be combined.
            (it "filters across provider and id with a multi-token query"
                (fn []
                  (let [chosen (atom :unset)
                        picker (make-select-picker "Model" (big-catalogue)
                                                   (fn [it] (reset! chosen it)) (fn [] 72))
                        on-in  (.-onInput picker)]
                    (doseq [c "groq scout"] (on-in (str c) (data->key (str c))))
                    (on-in nil (data->key ENTER))
                    (-> (expect (.-label @chosen)) (.toContain "groq")))))))

;; Raw terminal byte sequences the TUI delivers to handleInput. Written as
;; explicit escapes — literal control bytes in source are invisible and easy
;; to drop silently when editing.
(def ESC    "")
(def ENTER  "\r")
(def UP     (str ESC "[A"))
(def DOWN   (str ESC "[B"))
(def RIGHT  (str ESC "[C"))
(def LEFT   (str ESC "[D"))
(def HOME   (str ESC "[H"))
(def END    (str ESC "[F"))
(def PGUP   (str ESC "[5~"))
(def CTRL-P "")
(def CTRL-N "")
(def BS     "")

(describe "overlay-host/printable-char"
          (fn []
            (it "passes through a printable character"
                (fn []
                  (-> (expect (printable-char "a")) (.toBe "a"))))

            ;; dispatch-input tests (and (.-ctrl key) (= input "p")), so the
            ;; ctrl chord must surface its bare letter as the "typed" char.
            (it "reports ctrl+p / ctrl+n as their bare letter"
                (fn []
                  (-> (expect (printable-char CTRL-P)) (.toBe "p"))
                  (-> (expect (printable-char CTRL-N)) (.toBe "n"))))

            (it "returns nil for control keys"
                (fn []
                  (-> (expect (printable-char ESC)) (.toBeNull))
                  (-> (expect (printable-char ENTER)) (.toBeNull))
                  (-> (expect (printable-char BS)) (.toBeNull))))))

(describe "overlay-host/data->key"
          (fn []
            (it "maps escape / enter"
                (fn []
                  (-> (expect (.-escape (data->key ESC))) (.toBe true))
                  (-> (expect (.-return (data->key ENTER))) (.toBe true))))

            (it "maps arrows"
                (fn []
                  (-> (expect (.-upArrow (data->key UP))) (.toBe true))
                  (-> (expect (.-downArrow (data->key DOWN))) (.toBe true))))

            ;; An unmapped key arrives as an all-false object — a silent no-op —
            ;; so multi-step components need the full navigation set.
            (it "maps left/right/home/end/page"
                (fn []
                  (-> (expect (.-leftArrow (data->key LEFT))) (.toBe true))
                  (-> (expect (.-rightArrow (data->key RIGHT))) (.toBe true))
                  (-> (expect (.-home (data->key HOME))) (.toBe true))
                  (-> (expect (.-end (data->key END))) (.toBe true))
                  (-> (expect (.-pageUp (data->key PGUP))) (.toBe true))))

            (it "maps backspace and ctrl chords"
                (fn []
                  (-> (expect (.-backspace (data->key BS))) (.toBe true))
                  (-> (expect (.-ctrl (data->key CTRL-P))) (.toBe true))))

            (it "a plain character sets no modifier flags"
                (fn []
                  (let [k (data->key "a")]
                    (-> (expect (.-escape k)) (.toBeFalsy))
                    (-> (expect (.-return k)) (.toBeFalsy))
                    (-> (expect (.-ctrl k)) (.toBeFalsy)))))))

(describe "overlay-host/should-dismiss?"
          (fn []
            ;; ui.custom is fire-and-forget, so the host decides teardown.
            (it "single-shot pickers close on enter and escape"
                (fn []
                  (-> (expect (should-dismiss? ENTER nil false)) (.toBe true))
                  (-> (expect (should-dismiss? ESC nil false)) (.toBe true))))

            (it "never closes on navigation or typing"
                (fn []
                  (-> (expect (should-dismiss? DOWN nil false)) (.toBeFalsy))
                  (-> (expect (should-dismiss? "a" nil false)) (.toBeFalsy))))

            ;; Regression: tree_viewer folds/unfolds with Enter. Treating Enter
            ;; as a dismissal closed /tree on the first keystroke.
            (it "keepOpen components do NOT close on enter"
                (fn []
                  (-> (expect (should-dismiss? ENTER nil true)) (.toBeFalsy))))

            (it "keepOpen components still close on escape"
                (fn []
                  (-> (expect (should-dismiss? ESC nil true)) (.toBe true))))

            ;; pi-mono's explicit signal, which tree_viewer returns on Esc.
            (it "an onInput result of {close:true} always closes"
                (fn []
                  (-> (expect (should-dismiss? DOWN #js {:close true} true)) (.toBe true))
                  (-> (expect (close-signal? #js {:close true})) (.toBe true))
                  (-> (expect (close-signal? nil)) (.toBe false))))))

(defn- mk-picker [on-resolve]
  (make-select-picker "Pick one"
                      [#js {:value "a" :label "alpha" :description "first"}
                       #js {:value "b" :label "beta"}
                       #js {:value "c" :label "gamma"}]
                      on-resolve))

(defn- mk-comp [picker]
  (adapt-component picker {:get-width (fn [] 100) :get-height (fn [] 30)}))

(defn- long-text []
  (.join (clj->js (mapv (fn [i] (str "line " (inc i))) (range 40))) "\n"))

(describe "overlay-host/adapt-component"
          (fn []
            (it "render returns an array of lines (pi-tui contract)"
                (fn []
                  (let [lines (.render (mk-comp (mk-picker (fn [_] nil))) 60)]
                    (-> (expect (js/Array.isArray lines)) (.toBe true))
                    (-> (expect (.-length lines)) (.toBeGreaterThan 1)))))

            (it "forwards keys so navigation + enter selects the right item"
                (fn []
                  (let [chosen (atom :unset)
                        comp   (mk-comp (mk-picker (fn [it] (reset! chosen it))))]
                    (.handleInput comp DOWN)
                    (.handleInput comp ENTER)
                    (-> (expect (.-value @chosen)) (.toBe "b")))))

            (it "escape resolves nil (cancel)"
                (fn []
                  (let [chosen (atom :unset)
                        comp   (mk-comp (mk-picker (fn [it] (reset! chosen it))))]
                    (.handleInput comp ESC)
                    (-> (expect @chosen) (.toBeNull)))))

            (it "typing filters the list"
                (fn []
                  (let [chosen (atom :unset)
                        comp   (mk-comp (mk-picker (fn [it] (reset! chosen it))))]
                    ;; "gam" leaves only gamma, so Enter picks it from index 0
                    (.handleInput comp "g")
                    (.handleInput comp "a")
                    (.handleInput comp "m")
                    (.handleInput comp ENTER)
                    (-> (expect (.-value @chosen)) (.toBe "c")))))

            (it "passes the raw data AND the onInput result to after-input"
                (fn []
                  (let [seen (atom [])
                        comp (adapt-component
                              #js {:render  (fn [_w _h] "x")
                                   :onInput (fn [_i _k] #js {:close true})}
                              {:get-width (fn [] 100)
                               :get-height (fn [] 30)
                               :after-input (fn [d r] (swap! seen conj [d (close-signal? r)]))})]
                    (.handleInput comp DOWN)
                    (-> (expect (count @seen)) (.toBe 1))
                    (-> (expect (first (first @seen))) (.toBe DOWN))
                    (-> (expect (second (first @seen))) (.toBe true)))))))

(describe "overlay-host/make-select-picker sizing"
          (fn []
            ;; `h` is the rows available INSIDE the overlay box (the host
            ;; resolves maxHeight first), so the picker must fit within it —
            ;; pi-tui slices overflow from the bottom, which is exactly where
            ;; render-frame's trailing window puts the selected row.
            (defn- row-count [box-rows]
              (let [items (mapv (fn [i] #js {:value (str "v" i) :label (str "model-" i)})
                                (range 30))]
                (count (.split ((.-render (make-select-picker "Pick" items (fn [_] nil)))
                                100 box-rows)
                               "\n"))))

            (it "never renders more lines than the box has rows"
                (fn []
                  (doseq [rows [28 16 12 10 7 4]]
                    (-> (expect (row-count rows)) (.toBeLessThanOrEqual rows)))))

            (it "still shows a usable number of rows in a tall box"
                (fn []
                  (-> (expect (row-count 28)) (.toBeGreaterThan 5))))

            (it "caps row width on a narrow terminal"
                (fn []
                  (let [items #js [#js {:value "x"
                                        :label "anthropic/claude-haiku-4-5-20251001-long"
                                        :description "200.0k · $1/$5"}]
                        out   ((.-render (make-select-picker "Pick" items (fn [_] nil))) 40 24)
                        ;; COLUMNS, not characters — the frame carries colour
                        ;; now and escapes are zero-width.
                        widest (reduce max 0 (map visibleWidth (.split out "\n")))]
                    (-> (expect widest) (.toBeLessThanOrEqual 40)))))))

(describe "overlay-host/make-input-picker"
          (fn []
            (it "accumulates typed text and resolves on Enter"
                (fn []
                  (let [got    (atom :unset)
                        picker (make-input-picker "Name" "placeholder"
                                                  (fn [v] (reset! got v)))
                        on-in  (.-onInput picker)]
                    (on-in "h" (data->key "h"))
                    (on-in "i" (data->key "i"))
                    (on-in nil (data->key ENTER))
                    (-> (expect @got) (.toBe "hi")))))

            (it "backspace removes the last character"
                (fn []
                  (let [got    (atom :unset)
                        picker (make-input-picker "Name" "" (fn [v] (reset! got v)))
                        on-in  (.-onInput picker)]
                    (on-in "a" (data->key "a"))
                    (on-in "b" (data->key "b"))
                    (on-in nil (data->key BS))
                    (on-in nil (data->key ENTER))
                    (-> (expect @got) (.toBe "a")))))

            (it "escape cancels with nil"
                (fn []
                  (let [got    (atom :unset)
                        picker (make-input-picker "Name" "" (fn [v] (reset! got v)))]
                    ((.-onInput picker) nil (data->key ESC))
                    (-> (expect @got) (.toBeNull)))))

            (it "shows the placeholder when empty"
                (fn []
                  (let [picker (make-input-picker "Name" "type here" (fn [_] nil))]
                    (-> (expect ((.-render picker) 100 30)) (.toContain "type here")))))))

(describe "overlay-host/make-text-overlay"
          (fn []
            ;; showOverlay's in-repo callers all pass a string (show-info,
            ;; stats_dashboard, tool_dsl), so text must render and scroll.
            (it "windows long content and reports the visible range"
                (fn []
                  (let [out ((.-render (make-text-overlay (long-text))) 100 20)]
                    ;; Header names the window so the user knows it scrolls.
                    (-> (expect out) (.toContain "of 40 lines"))
                    ;; Windowed, not the whole 40 lines.
                    (-> (expect (.-length (.split out "\n"))) (.toBeLessThan 40)))))

            (it "scrolls down with the arrow key"
                (fn []
                  (let [ov (make-text-overlay (long-text))
                        before ((.-render ov) 100 20)]
                    ((.-onInput ov) nil (data->key DOWN))
                    (-> (expect ((.-render ov) 100 20)) (.not.toBe before)))))

            (it "caps row width so the overlay box can't overflow"
                (fn []
                  (let [wide (.repeat "x" 400)
                        out  ((.-render (make-text-overlay wide)) 100 20)]
                    (-> (expect (.-length out)) (.toBeLessThan 100)))))))

;;; ─── Bracketed paste ────────────────────────────────────────────
;;; pi-tui delivers a paste as ONE chunk wrapped in bracketed-paste markers
;;; (terminal.js:106). The single-character test dropped it silently, which is
;;; what `/login` did with a pasted API key.

(def ^:private ESC-CHAR (js/String.fromCharCode 27))
(defn- wrap-paste [s] (str ESC-CHAR "[200~" s ESC-CHAR "[201~"))

(describe "overlay-host/paste"
  (fn []
    (it "unwraps a bracketed-paste chunk"
        (fn []
          (-> (expect (paste-payload (wrap-paste "sk-ant-api03-XYZ")))
              (.toBe "sk-ant-api03-XYZ"))))

    (it "returns nil for ordinary input"
        (fn []
          (-> (expect (paste-payload "a")) (.toBeNil))
          (-> (expect (paste-payload ESC-CHAR)) (.toBeNil))))

    (it "tolerates a chunk with no closing marker"
        (fn []
          (-> (expect (paste-payload (str ESC-CHAR "[200~tail-only")))
              (.toBe "tail-only"))))

    (it "feeds a pasted API key through as typed text"
        (fn []
          ;; The whole point: this returned nil before, so /login stayed empty.
          (-> (expect (printable-char (wrap-paste "sk-ant-api03-XYZ")))
              (.toBe "sk-ant-api03-XYZ"))))

    (it "strips control characters so a multi-line paste can't corrupt the field"
        (fn []
          (-> (expect (printable-char (wrap-paste "line1\nline2")))
              (.toBe "line1line2"))))

    (it "still passes single printable characters"
        (fn []
          (-> (expect (printable-char "a")) (.toBe "a"))))

    (it "still rejects bare control bytes and mouse reports"
        (fn []
          (-> (expect (printable-char ESC-CHAR)) (.toBeNil))
          (-> (expect (printable-char (str ESC-CHAR "[<0;10;5M"))) (.toBeNil))
          (-> (expect (printable-char (js/String.fromCharCode 3))) (.toBeNil))))))

(describe "overlay-host/input picker width safety" (fn []
  (it "keeps a CJK value inside the box while typing"
    (fn []
      ;; The tail-truncation dropped leading CHARACTERS against a COLUMN
      ;; budget, so every wide glyph the user typed pushed the row two
      ;; columns wider than budgeted. Measured through this production path
      ;; before the fix: 122 columns inside a 72-column box at an 80-column
      ;; terminal — enough to kill the session. Reachable by typing CJK into
      ;; any input picker.
      (doseq [cols [40 60 80 120]]
        (let [bw (resolve-content-width default-overlay-options cols)
              p  (make-input-picker "Name" "placeholder" (fn [_] nil) (fn [] bw))]
          (dotimes [_ 60] (.onInput p "\u6f22" #js {}))
          (doseq [l (.split (.render p cols 10) "\n")]
            (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual bw)))))))))
