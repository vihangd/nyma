(ns editor.test
  "Coverage for src/agent/ui/editor.cljs — the main chat editor Box.

   This file fills a long-standing gap surfaced during the Static-refactor
   audit: the Editor component had no tests at all, only the pure helpers
   it delegates to (detect-mode, border-color-for-mode, mode-label — see
   test/editor_mode.test.cljs).

   The tests here cover:
     1. `editor-prefix` — pure function for the prompt glyph
     2. Single-shot render across each state (normal, streaming, queued,
        hidden, bash mode, bb mode, overlay)
     3. Rerender transitions — prop changes mid-life that the audit flagged
        as regression risk (streaming on→off, hidden on→off, mode changes
        as the user types, overlay toggle)."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/editor.jsx" :refer [Editor editor_prefix]]))

(def ^:private test-theme
  {:colors {:primary "#7aa2f7"
            :warning "#e0af68"
            :border  "#3b4261"
            :muted   "#565f89"}})

(defn- noop [& _])

(afterEach (fn [] (cleanup)))

;;; ─── editor-prefix: pure function ──────────────────────────────────

(describe "editor-prefix"
          (fn []

            (it "returns '❯ ' when not streaming and not queued"
                (fn []
                  (-> (expect (editor_prefix false false)) (.toBe "❯ "))))

            (it "returns 'steer❯ ' when streaming without steerAcked"
                (fn []
                  (-> (expect (editor_prefix true false)) (.toBe "steer❯ "))))

            (it "returns '↳ queued ' when steerAcked (takes priority over streaming)"
                ;; steerAcked is the state after the user has hit Enter to
                ;; queue a steer message; it must be shown regardless of
                ;; whether the underlying stream is still active.
                (fn []
                  (-> (expect (editor_prefix true true)) (.toBe "↳ queued "))
                  (-> (expect (editor_prefix false true)) (.toBe "↳ queued "))))))

;;; ─── Editor: single-shot rendering per state ───────────────────────

(describe "Editor: rendering states"
          (fn []

            (it "normal state shows the plain prompt glyph and no mode label"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "❯"))
                    ;; No mode label (bash / bb) should appear
                    (-> (expect (.includes (lastFrame) "bash")) (.toBe false))
                    (-> (expect (.includes (lastFrame) "bb")) (.toBe false))
                    ;; No "thinking..." placeholder — the TextInput is live
                    (-> (expect (.includes (lastFrame) "thinking")) (.toBe false)))))

            (it "streaming state shows 'steer❯' prefix"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      true
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "steer❯")))))

            (it "steerAcked state shows '↳ queued' prefix even while streaming"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      true
                                              :steerAcked     true
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "↳ queued"))
                    ;; steerAcked wins over streaming — no 'steer❯'
                    (-> (expect (.includes (lastFrame) "steer❯")) (.toBe false)))))

            (it "hidden state shows 'thinking...' and omits the TextInput"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "some draft"
                                              :setEditorValue noop
                                              :hidden         true
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "thinking"))
                    ;; When hidden, the draft content should NOT appear in
                    ;; the frame because the TextInput is not rendered.
                    (-> (expect (.includes (lastFrame) "some draft")) (.toBe false)))))

            (it "bash mode (editorValue starts with !) shows 'bash' label"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "!ls -la"
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "bash")))))

            (it "bb mode (editorValue starts with valid form) shows 'bb' label"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "$(+ 1 2)"
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "bb")))))

            (it "$PATH-lookalike does NOT trigger bb mode (form-start guard)"
                ;; Regression: $PATH, $HOME etc must not flash the bb label
                ;; since they fall through as normal LLM prompts.
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "$PATH"
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    ;; No 'bb' label should appear
                    (let [frame (lastFrame)]
                      ;; The label is " bb " (space-padded) — check for the exact label form
                      (-> (expect (.includes frame "bb ")) (.toBe false))))))

            (it "rendering with overlay=true still renders the editor"
                ;; Overlay only affects focus, not rendering — the box is
                ;; still drawn so the user can see what they were typing.
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "draft text"
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        true}])]
                    (-> (expect (lastFrame)) (.toContain "❯")))))))

;;; ─── Editor: rerender transitions ──────────────────────────────────

(describe "Editor: rerender transitions"
          (fn []

            (it "streaming toggles on then off: prefix changes accordingly"
                ;; When the stream ends, the editor must switch back to the
                ;; normal '❯' prefix. The old 'steer❯' must not persist.
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "❯"))
                    ;; Stream starts
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      true
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    ""
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "steer❯"))
                    ;; Stream ends
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    ""
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (.includes (lastFrame) "steer❯")) (.toBe false))
                    (-> (expect (lastFrame)) (.toContain "❯")))))

            (it "hidden toggles on then off: thinking label appears then disappears"
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "my question"
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (.includes (lastFrame) "thinking")) (.toBe false))
                    ;; Enter thinking state
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "my question"
                                            :setEditorValue noop
                                            :hidden         true
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "thinking"))
                    ;; Leave thinking state
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "my question"
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (.includes (lastFrame) "thinking")) (.toBe false)))))

            (it "editorValue transitions from normal to bash to bb to normal"
                ;; Simulates the user typing '!ls' then erasing and typing
                ;; '$(+ 1 2)' then erasing and typing 'hello'. The mode
                ;; label must update cleanly on every step — no stale labels.
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    ;; No label in normal state
                    (-> (expect (.includes (lastFrame) "bash")) (.toBe false))
                    (-> (expect (.includes (lastFrame) "bb ")) (.toBe false))
                    ;; Type '!'  → bash mode
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "!ls"
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "bash"))
                    ;; Erase and type '$' → bb mode
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "$(+ 1 2)"
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "bb"))
                    (-> (expect (.includes (lastFrame) "bash")) (.toBe false))
                    ;; Erase and type plain text → back to normal
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "hello"
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (.includes (lastFrame) "bash")) (.toBe false))
                    (-> (expect (.includes (lastFrame) "bb ")) (.toBe false)))))

            (it "editorValue transitions from bb-candidate ($PATH) to real bb ($(+))"
                ;; Regression for the form-start guard: typing '$P' must
                ;; NOT flash the bb label; only after the user types a
                ;; form-start char should the label appear.
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "$PATH"
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (.includes (lastFrame) "bb ")) (.toBe false))
                    ;; User backspaces to just '$' then types '('
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "$("
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "bb")))))

            (it "overlay toggles without crashing and prefix stays stable"
                ;; Overlay only flips TextInput focus; visible text is unchanged.
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "❯"))
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    ""
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        true}])
                    (-> (expect (lastFrame)) (.toContain "❯"))
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    ""
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "❯")))))

            (it "steerAcked toggles on then off: queued label appears then disappears"
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      true
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    ""
                                              :setEditorValue noop
                                              :hidden         false
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "steer❯"))
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      true
                                            :steerAcked     true
                                            :theme          test-theme
                                            :editorValue    ""
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "↳ queued"))
                    (-> (expect (.includes (lastFrame) "steer❯")) (.toBe false))
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      true
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    ""
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (lastFrame)) (.toContain "steer❯"))
                    (-> (expect (.includes (lastFrame) "↳ queued")) (.toBe false)))))

            (it "hidden-then-mode-change: thinking clears and new mode label appears"
                ;; Regression for the scenario where the draft survives a
                ;; hidden → unhidden transition (app.cljs keeps editorValue
                ;; in state while hidden is toggled during a stream).
                (fn []
                  (let [{:keys [lastFrame rerender]}
                        (render #jsx [Editor {:onSubmit       noop
                                              :streaming      false
                                              :steerAcked     false
                                              :theme          test-theme
                                              :editorValue    "!ls"
                                              :setEditorValue noop
                                              :hidden         true
                                              :overlay        false}])]
                    (-> (expect (lastFrame)) (.toContain "thinking"))
                    ;; When hidden, no mode label (the box is inert)
                    (rerender #jsx [Editor {:onSubmit       noop
                                            :streaming      false
                                            :steerAcked     false
                                            :theme          test-theme
                                            :editorValue    "!ls"
                                            :setEditorValue noop
                                            :hidden         false
                                            :overlay        false}])
                    (-> (expect (.includes (lastFrame) "thinking")) (.toBe false))
                    (-> (expect (lastFrame)) (.toContain "bash")))))))
