(ns overlay-host.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/overlay_host.mjs"
             :refer [printable-char data->key resolving-key? adapt-component
                     make-select-picker make-input-picker]]))

;; Raw terminal byte sequences the TUI actually delivers to handleInput.
(def ESC    "")
(def ENTER  "\r")
(def UP     "[A")
(def DOWN   "[B")
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

(describe "overlay-host/resolving-key?"
          (fn []
            ;; ui.custom is fire-and-forget, so the host dismisses on these.
            (it "true for enter and escape"
                (fn []
                  (-> (expect (resolving-key? ENTER)) (.toBe true))
                  (-> (expect (resolving-key? ESC)) (.toBe true))))

            (it "false for navigation and typing"
                (fn []
                  (-> (expect (resolving-key? DOWN)) (.toBeFalsy))
                  (-> (expect (resolving-key? "a")) (.toBeFalsy))))))

(defn- mk-picker [on-resolve]
  (make-select-picker "Pick one"
                      [#js {:value "a" :label "alpha" :description "first"}
                       #js {:value "b" :label "beta"}
                       #js {:value "c" :label "gamma"}]
                      on-resolve))

(describe "overlay-host/adapt-component"
          (fn []
            (it "render returns an array of lines (pi-tui contract)"
                (fn []
                  (let [comp (adapt-component (mk-picker (fn [_] nil))
                                              {:get-width (fn [] 100)
                                               :get-height (fn [] 30)})
                        lines (.render comp 60)]
                    (-> (expect (js/Array.isArray lines)) (.toBe true))
                    (-> (expect (.-length lines)) (.toBeGreaterThan 1)))))

            (it "forwards keys so navigation + enter selects the right item"
                (fn []
                  (let [chosen (atom :unset)
                        comp   (adapt-component (mk-picker (fn [it] (reset! chosen it)))
                                                {:get-width (fn [] 100)
                                                 :get-height (fn [] 30)})]
                    (.handleInput comp DOWN)
                    (.handleInput comp ENTER)
                    (-> (expect (.-value @chosen)) (.toBe "b")))))

            (it "escape resolves nil (cancel)"
                (fn []
                  (let [chosen (atom :unset)
                        comp   (adapt-component (mk-picker (fn [it] (reset! chosen it)))
                                                {:get-width (fn [] 100)
                                                 :get-height (fn [] 30)})]
                    (.handleInput comp ESC)
                    (-> (expect @chosen) (.toBeNull)))))

            (it "typing filters the list"
                (fn []
                  (let [chosen (atom :unset)
                        comp   (adapt-component (mk-picker (fn [it] (reset! chosen it)))
                                                {:get-width (fn [] 100)
                                                 :get-height (fn [] 30)})]
                    ;; "gam" leaves only gamma, so Enter picks it from index 0
                    (.handleInput comp "g")
                    (.handleInput comp "a")
                    (.handleInput comp "m")
                    (.handleInput comp ENTER)
                    (-> (expect (.-value @chosen)) (.toBe "c")))))

            (it "calls after-input with the raw data"
                (fn []
                  (let [seen (atom [])
                        comp (adapt-component (mk-picker (fn [_] nil))
                                              {:get-width (fn [] 100)
                                               :get-height (fn [] 30)
                                               :after-input (fn [d] (swap! seen conj d))})]
                    (.handleInput comp DOWN)
                    (-> (expect (count @seen)) (.toBe 1))
                    (-> (expect (first @seen)) (.toBe DOWN)))))))

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
                  (let [picker (make-input-picker "Name" "type here" (fn [_] nil))
                        out    ((.-render picker) 100 30)]
                    (-> (expect out) (.toContain "type here")))))))
