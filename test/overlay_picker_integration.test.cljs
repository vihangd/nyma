(ns overlay-picker-integration.test
  "Integration tests for the CustomComponentAdapter → picker onInput
   call path.

   The existing custom_ui.test.cljs mounts components but never actually
   dispatches keys through the adapter, which is why a protocol mismatch
   (the adapter passed a single pi-mono-style key string while every real
   picker in the codebase expects Ink's 2-arg `(input, key)` convention)
   shipped without a failing test. These tests use
   ink-testing-library's stdin.write to simulate real keystrokes against
   a real picker mounted through Overlay."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/overlay.jsx" :refer [Overlay]]
            ["./agent/ui/mention_picker.mjs" :as mention-picker]
            ["./agent/ui/skill_picker.mjs" :as skill-picker]
            ["./agent/ui/tree_viewer.mjs" :as tree-viewer]
            ["./agent/extensions/agent_shell/ui/model_picker.mjs" :as model-picker]))

(afterEach (fn [] (cleanup)))

(def ESC "\u001b")
(def ENTER "\r")
(def DOWN "\u001b[B")
(def UP   "\u001b[A")
;; On macOS the physical backspace key sends \x7f, which ink v6 parses as
;; key.delete (not key.backspace). Pickers must handle both.
(def BACKSPACE "\u007f")
;; Ctrl+letter control codes. ink parses \x01-\x1a as {ctrl: true, name:
;; letter, input: letter}. Pickers use these for vi-style Ctrl+N/Ctrl+P
;; navigation — if ctrl detection breaks, these bindings silently die.
(def CTRL-N "\u000e")
(def CTRL-P "\u0010")

(defn- wait-ms [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

;;; ─── mention_picker ─────────────────────────────────────────
;;; Reproduces the `/` and `@` autocomplete picker path: app.cljs opens
;;; this picker when the editor matches a trigger, and the user's
;;; keystrokes are forwarded through Overlay → CustomComponentAdapter →
;;; picker.onInput. The adapter used to pass a single pi-mono key-string;
;;; the picker expects Ink's (input, key), so every keystroke threw
;;; TypeError (silently swallowed by the adapter's try/catch).

(describe "mention picker through Overlay"
          (fn []
            (it "closes with resolve(nil) when user hits Esc"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "a.txt" :value "a.txt"}
                                       #js {:label "b.txt" :value "b.txt"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (-> (expect (lastFrame)) (.toContain "Select file"))
                    (.write stdin ESC)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBeNull))))))))

            (it "resolves with the selected item on Enter"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "first.txt"  :value "first.txt"}
                                       #js {:label "second.txt" :value "second.txt"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (some? @resolved)) (.toBe true))
                                 (-> (expect (.-value @resolved)) (.toBe "first.txt"))))))))

            (it "moves selection with down arrow and Enter returns the second item"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "one"   :value "one"}
                                       #js {:label "two"   :value "two"}
                                       #js {:label "three" :value "three"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin DOWN)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.-value @resolved)) (.toBe "two"))))))))

            (it "filters by typed character"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "apple"  :value "apple"}
                                       #js {:label "banana" :value "banana"}
                                       #js {:label "cherry" :value "cherry"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin "b")
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "banana"))
                                 (-> (expect (.includes (lastFrame) "apple")) (.toBe false))))))))

            (it "backspace (\\x7f / key.delete) removes last filter character"
                (fn []
                  (let [picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "apple"  :value "apple"}
                                       #js {:label "banana" :value "banana"}]
                                  ""
                                  (fn [_]))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
          ;; Type "b" → only banana visible
                    (.write stdin "b")
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "banana"))
                                 (-> (expect (.includes (lastFrame) "apple")) (.toBe false))
                       ;; Now backspace → filter cleared → both items visible again
                                 (.write stdin BACKSPACE)))
                        (.then (fn [_] (wait-ms 30)))
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "apple"))
                                 (-> (expect (lastFrame)) (.toContain "banana"))))))))

            (it "backspace on empty filter does not crash"
                (fn []
                  (let [picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "a.txt" :value "a.txt"}]
                                  ""
                                  (fn [_]))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin BACKSPACE)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "a.txt"))))))))))

;;; ─── skill_picker ───────────────────────────────────────────

(describe "skill picker through Overlay"
          (fn []
            (it "Esc resolves with nil"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git" :desc "git helper" :active false}
                                       #js {:name "web" :desc "web helper" :active false}]
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin ESC)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBeNull))))))))

            (it "Enter resolves with selected skill name"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git" :desc "git helper" :active false}
                                       #js {:name "web" :desc "web helper" :active false}]
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "git"))))))))

            (it "backspace (\\x7f / key.delete) removes last filter character"
                (fn []
                  (let [picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git"  :desc "" :active false}
                                       #js {:name "web"  :desc "" :active false}
                                       #js {:name "grep" :desc "" :active false}]
                                  (fn [_]))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
          ;; "g" → git and grep visible, web hidden
                    (.write stdin "g")
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "git"))
                                 (-> (expect (.includes (lastFrame) "web")) (.toBe false))
                                 (.write stdin BACKSPACE)))
                        (.then (fn [_] (wait-ms 30)))
                        (.then (fn [_]
                       ;; filter cleared — all three items visible again
                                 (-> (expect (lastFrame)) (.toContain "web"))))))))))

;;; ─── model_picker ───────────────────────────────────────────
;;; model_picker calls on-resolve directly (no {close:true} return value),
;;; so we just wait for the atom to be updated.

(def test-models
  #js [#js {:id "opus"   :display "Claude Opus 4.6"}
       #js {:id "sonnet" :display "Claude Sonnet 4.6"}
       #js {:id "haiku"  :display "Claude Haiku 4.5"}])

(describe "model picker through Overlay"
          (fn []
            (it "Esc resolves with nil"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin ESC)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBeNull))))))))

            (it "Enter resolves with the first model id"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "opus"))))))))

            (it "Down + Enter resolves with the second model id"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin DOWN)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "sonnet"))))))))

            (it "typing filters the visible list"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin "h")
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "Haiku"))
                                 (-> (expect (.includes (lastFrame) "Opus")) (.toBe false))))))))

            (it "backspace (\\x7f / key.delete) clears the filter"
                (fn []
                  (let [picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [_]))
                        {:keys [stdin lastFrame]}
                        (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin "h")
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.includes (lastFrame) "Opus")) (.toBe false))
                                 (.write stdin BACKSPACE)))
                        (.then (fn [_] (wait-ms 30)))
                        (.then (fn [_]
                                 (-> (expect (lastFrame)) (.toContain "Opus"))))))))))

;;; ─── tree_viewer ────────────────────────────────────────────
;;; tree_viewer uses the same pi-mono component contract. Verify it
;;; also flows through the adapter correctly.

(describe "tree viewer through Overlay"
          (fn []
            (it "returns {close: true} from onInput on Esc"
                (fn []
                  (let [session {:get-tree
                                 (fn []
                                   [{:id "1" :role "user" :content "hi"
                                     :parent-id nil :depth 0}])}
                        tv       ((.-create_tree_viewer tree-viewer) session)
              ;; Directly invoke onInput the way the adapter now does
                        result   (.onInput tv nil #js {:escape true})]
                    (-> (expect (.-close result)) (.toBe true)))))))

;;; ─── Picker navigation: Ctrl+N/Ctrl+P ───────────────────────
;;; vi-style shortcuts. Every picker's cond includes
;;;   (or (.-upArrow key) (and (.-ctrl key) (= input "p")))
;;; but no test ever exercised the Ctrl branch — identical failure
;;; mode to the key.delete vs key.backspace bug. ink parses \x0e as
;;; {ctrl: true, name: "n", input: "n"} and \x10 as Ctrl+P.

(describe "mention picker Ctrl+N/Ctrl+P navigation"
          (fn []
            (it "Ctrl+N moves selection down (equivalent to DownArrow)"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "one"   :value "one"}
                                       #js {:label "two"   :value "two"}
                                       #js {:label "three" :value "three"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin CTRL-N)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.-value @resolved)) (.toBe "two"))))))))

            (it "Ctrl+P moves selection up (equivalent to UpArrow)"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "one"   :value "one"}
                                       #js {:label "two"   :value "two"}
                                       #js {:label "three" :value "three"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
          ;; Down twice → "three" selected; Ctrl+P → back to "two"
                    (.write stdin CTRL-N)
                    (.write stdin CTRL-N)
                    (.write stdin CTRL-P)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.-value @resolved)) (.toBe "two"))))))))))

(describe "skill picker Ctrl+N/Ctrl+P navigation"
          (fn []
            (it "Ctrl+N moves selection down"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git"  :desc "" :active false}
                                       #js {:name "web"  :desc "" :active false}
                                       #js {:name "grep" :desc "" :active false}]
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin CTRL-N)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "web"))))))))

            (it "Ctrl+P moves selection up"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git"  :desc "" :active false}
                                       #js {:name "web"  :desc "" :active false}
                                       #js {:name "grep" :desc "" :active false}]
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin CTRL-N)
                    (.write stdin CTRL-N)
                    (.write stdin CTRL-P)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "web"))))))))))

(describe "model picker Ctrl+N/Ctrl+P navigation"
          (fn []
            (it "Ctrl+N moves selection down"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin CTRL-N)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "sonnet"))))))))

            (it "Ctrl+P moves selection up"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker model-picker)
                                  test-models
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin CTRL-N)
                    (.write stdin CTRL-N)
                    (.write stdin CTRL-P)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "sonnet"))))))))))

;;; ─── Picker navigation: boundary clamps ─────────────────────
;;; Off-by-one risk in (min (dec (count filtered)) (inc %)) and
;;; (max 0 (dec %)). Clamp behavior was never tested; a regression
;;; that lets selection go negative or past the last item would
;;; silently display the wrong highlighted row.

(describe "mention picker boundary clamps"
          (fn []
            (it "up arrow at index 0 stays at 0 (Enter selects first item)"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "first"  :value "first"}
                                       #js {:label "second" :value "second"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin UP)
                    (.write stdin UP)
                    (.write stdin UP)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.-value @resolved)) (.toBe "first"))))))))

            (it "down arrow past last item clamps to last (Enter selects last)"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "first"  :value "first"}
                                       #js {:label "second" :value "second"}
                                       #js {:label "third"  :value "third"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.-value @resolved)) (.toBe "third"))))))))

            (it "single-item list: down arrow stays on the only item"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker mention-picker)
                                  #js [#js {:label "only" :value "only"}]
                                  ""
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect (.-value @resolved)) (.toBe "only"))))))))))

(describe "skill picker boundary clamps"
          (fn []
            (it "up arrow at index 0 stays at 0"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git"  :desc "" :active false}
                                       #js {:name "web"  :desc "" :active false}]
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin UP)
                    (.write stdin UP)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "git"))))))))

            (it "down past last stays on last"
                (fn []
                  (let [resolved (atom :pending)
                        picker   ((.-create_picker skill-picker)
                                  #js [#js {:name "git"  :desc "" :active false}
                                       #js {:name "web"  :desc "" :active false}
                                       #js {:name "grep" :desc "" :active false}]
                                  (fn [v] (reset! resolved v)))
                        {:keys [stdin]} (render #jsx [Overlay {:onClose (fn [])} picker])]
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin DOWN)
                    (.write stdin ENTER)
                    (-> (wait-ms 30)
                        (.then (fn [_]
                                 (-> (expect @resolved) (.toBe "grep"))))))))))

;;; ─── Tree viewer: navigation coverage ──────────────────────
;;; The only existing tree_viewer test is an Esc-closes check.
;;; Pre-existing onInput branches for up/down/return were untested,
;;; which means a navigation regression would only be noticed by
;;; a human running the session tree overlay.

(describe "tree viewer navigation"
          (fn []
            (it "upArrow does not throw and returns a non-close result"
                (fn []
                  (let [session {:get-tree
                                 (fn []
                                   [{:id "1" :role "user" :content "a"
                                     :parent-id nil :depth 0}
                                    {:id "2" :role "assistant" :content "b"
                                     :parent-id "1" :depth 1}])}
                        tv     ((.-create_tree_viewer tree-viewer) session)
                        result (.onInput tv nil #js {:upArrow true})]
          ;; Should not return {close: true} — navigation never closes.
                    (-> (expect (or (nil? result) (not (.-close result))))
                        (.toBe true)))))

            (it "downArrow does not throw and returns a non-close result"
                (fn []
                  (let [session {:get-tree
                                 (fn []
                                   [{:id "1" :role "user" :content "a"
                                     :parent-id nil :depth 0}
                                    {:id "2" :role "assistant" :content "b"
                                     :parent-id "1" :depth 1}])}
                        tv     ((.-create_tree_viewer tree-viewer) session)
                        result (.onInput tv nil #js {:downArrow true})]
                    (-> (expect (or (nil? result) (not (.-close result))))
                        (.toBe true)))))

            (it "return (Enter) does not throw"
                (fn []
                  (let [session {:get-tree
                                 (fn []
                                   [{:id "1" :role "user" :content "a"
                                     :parent-id nil :depth 0}])}
                        tv     ((.-create_tree_viewer tree-viewer) session)]
          ;; Return might close or might toggle — just verify no throw.
                    (-> (expect (fn [] (.onInput tv nil #js {:return true})))
                        (.not.toThrow)))))))
