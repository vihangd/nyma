(ns overlay-host.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/overlay_host.mjs"
             :refer [printable-char data->key should-dismiss? close-signal?
                     adapt-component make-select-picker make-input-picker
                     make-text-overlay]]))

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
            (defn- row-count [rows]
              (let [items (mapv (fn [i] #js {:value (str "v" i) :label (str "model-" i)})
                                (range 30))]
                (count (.split ((.-render (make-select-picker "Pick" items (fn [_] nil)))
                                100 rows)
                               "\n"))))

            ;; The overlay box is maxHeight 70%; a fixed row count overflowed
            ;; short terminals and pi-tui wraps over-tall content.
            (it "keeps the picker inside 70% of terminal height"
                (fn []
                  (doseq [rows [40 24 20 15 10]]
                    (-> (expect (row-count rows))
                        (.toBeLessThanOrEqual (js/Math.floor (* 0.7 rows)))))))

            (it "still shows a usable number of rows on a tall terminal"
                (fn []
                  (-> (expect (row-count 40)) (.toBeGreaterThan 5))))

            (it "caps row width on a narrow terminal"
                (fn []
                  (let [items #js [#js {:value "x"
                                        :label "anthropic/claude-haiku-4-5-20251001-long"
                                        :description "200.0k · $1/$5"}]
                        out   ((.-render (make-select-picker "Pick" items (fn [_] nil))) 40 24)
                        widest (reduce max 0 (map count (.split out "\n")))]
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
