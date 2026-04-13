(ns picker-input.test
  "Unit tests for the shared picker input dispatcher. Every cond
   branch gets a focused test hitting it with a fake ink key object,
   so any regression that breaks one branch lands loudly. The shape
   of the fake key object mirrors mk-key from the keybinding tests."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.picker-input :refer [dispatch-input]]))

;;; ─── Fake ink-key helper ──────────────────────────────

(defn- k [opts]
  #js {:ctrl       (or (:ctrl opts) false)
       :meta       (or (:meta opts) false)
       :shift      (or (:shift opts) false)
       :escape     (or (:escape opts) false)
       :return     (or (:return opts) false)
       :tab        (or (:tab opts) false)
       :upArrow    (or (:up opts) false)
       :downArrow  (or (:down opts) false)
       :leftArrow  (or (:left opts) false)
       :rightArrow (or (:right opts) false)
       :backspace  (or (:backspace opts) false)
       :delete     (or (:delete opts) false)})

;;; ─── Test harness ─────────────────────────────────────

(defn- make-harness
  "Build a handler + the atoms + call log it dispatches against.
   Returns a map you can poke at during assertions."
  ([] (make-harness ["a" "b" "c"]))
  ([items]
   (let [filter-text  (atom "")
         selected-idx (atom 0)
         items-atom   (atom items)
         selected     (atom :none)
         cancelled    (atom false)
         handler      (dispatch-input
                       {:filter-text-atom  filter-text
                        :selected-idx-atom selected-idx
                        :filtered-fn       (fn [] @items-atom)
                        :on-select         (fn [item] (reset! selected item))
                        :on-cancel         (fn [] (reset! cancelled true))})]
     {:handler handler
      :filter-text filter-text
      :selected-idx selected-idx
      :items items-atom
      :selected selected
      :cancelled cancelled})))

;;; ─── Esc / Enter ──────────────────────────────────────

(describe "dispatch-input: Esc / Enter" (fn []
                                          (it "Escape triggers on-cancel"
                                              (fn []
                                                (let [{:keys [handler cancelled]} (make-harness)]
                                                  (handler "" (k {:escape true}))
                                                  (-> (expect @cancelled) (.toBe true)))))

                                          (it "Enter selects the currently-focused item"
                                              (fn []
                                                (let [{:keys [handler selected selected-idx]} (make-harness ["a" "b" "c"])]
                                                  (reset! selected-idx 1)
                                                  (handler "" (k {:return true}))
                                                  (-> (expect @selected) (.toBe "b")))))

                                          (it "Enter on an empty list does NOT call on-select"
                                              (fn []
                                                (let [{:keys [handler selected]} (make-harness [])]
                                                  (handler "" (k {:return true}))
                                                  (-> (expect (= @selected :none)) (.toBe true)))))

                                          (it "Enter with an out-of-range selected-idx clamps into the list"
                                              (fn []
      ;; Regression: the old per-picker copies used
      ;; `(min @idx (max 0 (dec count)))` — we now rely on
      ;; safe-index, which must handle the '7 → clamp to 2' case.
                                                (let [{:keys [handler selected selected-idx]} (make-harness ["a" "b" "c"])]
                                                  (reset! selected-idx 99)
                                                  (handler "" (k {:return true}))
                                                  (-> (expect @selected) (.toBe "c")))))))

;;; ─── Navigation ───────────────────────────────────────

(describe "dispatch-input: navigation" (fn []
                                         (it "upArrow steps selected-idx up"
                                             (fn []
                                               (let [{:keys [handler selected-idx]} (make-harness ["a" "b" "c"])]
                                                 (reset! selected-idx 2)
                                                 (handler "" (k {:up true}))
                                                 (-> (expect @selected-idx) (.toBe 1)))))

                                         (it "upArrow at 0 clamps to 0"
                                             (fn []
                                               (let [{:keys [handler selected-idx]} (make-harness ["a" "b"])]
                                                 (handler "" (k {:up true}))
                                                 (-> (expect @selected-idx) (.toBe 0)))))

                                         (it "downArrow steps selected-idx down"
                                             (fn []
                                               (let [{:keys [handler selected-idx]} (make-harness ["a" "b" "c"])]
                                                 (handler "" (k {:down true}))
                                                 (-> (expect @selected-idx) (.toBe 1)))))

                                         (it "downArrow past last clamps at count - 1"
                                             (fn []
                                               (let [{:keys [handler selected-idx]} (make-harness ["a" "b" "c"])]
                                                 (reset! selected-idx 2)
                                                 (handler "" (k {:down true}))
                                                 (-> (expect @selected-idx) (.toBe 2)))))

                                         (it "downArrow on empty list stays at 0 — NOT -1"
                                             (fn []
      ;; Regression for the old (min (dec 0) (inc 0)) → -1 bug.
                                               (let [{:keys [handler selected-idx]} (make-harness [])]
                                                 (handler "" (k {:down true}))
                                                 (-> (expect @selected-idx) (.toBe 0)))))

                                         (it "Ctrl+P is equivalent to upArrow"
                                             (fn []
                                               (let [{:keys [handler selected-idx]} (make-harness ["a" "b" "c"])]
                                                 (reset! selected-idx 2)
                                                 (handler "p" (k {:ctrl true}))
                                                 (-> (expect @selected-idx) (.toBe 1)))))

                                         (it "Ctrl+N is equivalent to downArrow"
                                             (fn []
                                               (let [{:keys [handler selected-idx]} (make-harness ["a" "b" "c"])]
                                                 (handler "n" (k {:ctrl true}))
                                                 (-> (expect @selected-idx) (.toBe 1)))))))

;;; ─── Backspace / key.delete ──────────────────────────

(describe "dispatch-input: backspace / delete" (fn []
                                                 (it "backspace strips the last char of filter-text"
                                                     (fn []
                                                       (let [{:keys [handler filter-text]} (make-harness)]
                                                         (reset! filter-text "abc")
                                                         (handler "" (k {:backspace true}))
                                                         (-> (expect @filter-text) (.toBe "ab")))))

  ;; THE macOS REGRESSION: the physical backspace key on macOS sends
  ;; \u007f, which ink v6 parses as key.delete — not key.backspace.
  ;; dispatch-input MUST treat both the same way. If a future edit
  ;; drops the `or` in the branch, this test fires.
                                                 (it "key.delete (macOS \\u007f backspace) also strips the last char"
                                                     (fn []
                                                       (let [{:keys [handler filter-text]} (make-harness)]
                                                         (reset! filter-text "xyz")
                                                         (handler "" (k {:delete true}))
                                                         (-> (expect @filter-text) (.toBe "xy")))))

                                                 (it "backspace on an empty filter does nothing (no crash)"
                                                     (fn []
                                                       (let [{:keys [handler filter-text]} (make-harness)]
                                                         (handler "" (k {:backspace true}))
                                                         (-> (expect @filter-text) (.toBe "")))))

                                                 (it "backspace resets selected-idx to 0"
                                                     (fn []
                                                       (let [{:keys [handler filter-text selected-idx]} (make-harness)]
                                                         (reset! filter-text "abc")
                                                         (reset! selected-idx 5)
                                                         (handler "" (k {:backspace true}))
                                                         (-> (expect @selected-idx) (.toBe 0)))))))

;;; ─── Character input ─────────────────────────────────

(describe "dispatch-input: character input" (fn []
                                              (it "a single printable char appends to filter-text"
                                                  (fn []
                                                    (let [{:keys [handler filter-text]} (make-harness)]
                                                      (handler "a" (k {}))
                                                      (-> (expect @filter-text) (.toBe "a")))))

                                              (it "multiple chars build up the filter"
                                                  (fn []
                                                    (let [{:keys [handler filter-text]} (make-harness)]
                                                      (handler "h" (k {}))
                                                      (handler "i" (k {}))
                                                      (-> (expect @filter-text) (.toBe "hi")))))

                                              (it "typing a char resets selected-idx to 0"
                                                  (fn []
                                                    (let [{:keys [handler filter-text selected-idx]} (make-harness)]
                                                      (reset! selected-idx 3)
                                                      (handler "z" (k {}))
                                                      (-> (expect @selected-idx) (.toBe 0)))))

                                              (it "empty input string is ignored"
                                                  (fn []
                                                    (let [{:keys [handler filter-text]} (make-harness)]
                                                      (handler "" (k {}))
                                                      (-> (expect @filter-text) (.toBe "")))))

                                              (it "multi-char input (paste) is ignored"
                                                  (fn []
      ;; The dispatcher only handles single-char input. Paste
      ;; would need its own path (bracketed-paste handler).
                                                    (let [{:keys [handler filter-text]} (make-harness)]
                                                      (handler "hello" (k {}))
                                                      (-> (expect @filter-text) (.toBe "")))))))

;;; ─── Tab is swallowed ────────────────────────────────

(describe "dispatch-input: Tab" (fn []
                                  (it "Tab does not throw and does not modify state"
                                      (fn []
                                        (let [{:keys [handler filter-text selected-idx]} (make-harness)]
                                          (reset! filter-text "abc")
                                          (handler "" (k {:tab true}))
                                          (-> (expect @filter-text) (.toBe "abc"))
                                          (-> (expect @selected-idx) (.toBe 0)))))))
