(ns ui-layout.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Box Text]]
            ["./agent/ui/editor.jsx" :refer [Editor editor-prefix]]
            ["./agent/ui/header.jsx" :refer [Header]]
            ["./agent/ui/footer.jsx" :refer [Footer]]
            ["./agent/ui/chat_view.jsx" :refer [ChatView compute_turn_split]]))

(def test-theme
  {:colors {:primary "#7aa2f7" :secondary "#9ece6a" :error "#f7768e"
            :warning "#e0af68" :success "#9ece6a" :muted "#565f89"
            :border "#3b4261"}})

(afterEach (fn [] (cleanup)))

;;; ─── Editor component ─────────────────────────────────────────

(describe "Editor - layout" (fn []
                              (it "renders input box when not hidden"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Editor {:onSubmit       (fn [_])
                                                                             :editorValue    ""
                                                                             :setEditorValue (fn [_])
                                                                             :hidden         false
                                                                             :overlay        false
                                                                             :streaming      false
                                                                             :steerAcked     false
                                                                             :theme          test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "❯")))))

                              (it "returns nil when hidden"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Box {}
                                                                     [Editor {:onSubmit       (fn [_])
                                                                              :editorValue    ""
                                                                              :setEditorValue (fn [_])
                                                                              :hidden         true
                                                                              :overlay        false
                                                                              :streaming      false
                                                                              :steerAcked     false
                                                                              :theme          test-theme}]])]
        ;; When hidden, editor returns nil — should not contain the prompt marker
                                      (-> (expect (.includes (lastFrame) "❯")) (.toBe false)))))

                              (it "shows streaming prefix when streaming"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Editor {:onSubmit       (fn [_])
                                                                             :editorValue    ""
                                                                             :setEditorValue (fn [_])
                                                                             :hidden         false
                                                                             :overlay        false
                                                                             :streaming      true
                                                                             :steerAcked     false
                                                                             :theme          test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "steer❯")))))

                              (it "shows steer-acked prefix when acknowledged"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Editor {:onSubmit       (fn [_])
                                                                             :editorValue    ""
                                                                             :setEditorValue (fn [_])
                                                                             :hidden         false
                                                                             :overlay        false
                                                                             :streaming      false
                                                                             :steerAcked     true
                                                                             :theme          test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "↳ queued")))))))

;;; ─── editor-prefix pure function ───────────────────────────────

(describe "editor-prefix" (fn []
                            (it "returns normal prefix when idle"
                                (fn []
                                  (-> (expect (editor-prefix false false)) (.toBe "❯ "))))

                            (it "returns steer prefix when streaming"
                                (fn []
                                  (-> (expect (editor-prefix true false)) (.toBe "steer❯ "))))

                            (it "returns queued prefix when steer-acked (takes priority over streaming)"
                                (fn []
                                  (-> (expect (editor-prefix true true)) (.toBe "↳ queued "))))))

;;; ─── Header component ─────────────────────────────────────────

(describe "Header - layout" (fn []
                              (it "renders with title and model"
                                  (fn []
                                    (let [agent {:config {:model #js {:modelId "test-model"}}}
                                          {:keys [lastFrame]} (render
                                                               #jsx [Header {:agent agent
                                                                             :resources {}
                                                                             :theme test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "nyma"))
                                      (-> (expect (lastFrame)) (.toContain "test-model")))))))

;;; ─── Footer component ─────────────────────────────────────────

(describe "Footer - layout" (fn []
                              (it "renders help text and version"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Footer {:agent {} :theme test-theme :statuses {}}])]
                                      (-> (expect (lastFrame)) (.toContain "nyma v0.1.0"))
        ;; Footer hints come from the keybinding registry (or the nil-agent
        ;; fallback in footer.cljs). Both emit a visible "help" label.
                                      (-> (expect (lastFrame)) (.toContain "help")))))

                              (it "renders status items"
                                  (fn []
                                    (let [{:keys [lastFrame]} (render
                                                               #jsx [Footer {:agent {} :theme test-theme
                                                                             :statuses {"s1" "Active"}}])]
                                      (-> (expect (lastFrame)) (.toContain "Active")))))))

;;; ─── ChatView component ────────────────────────────────────────

(describe "ChatView - layout" (fn []
                                (it "renders messages"
                                    (fn []
                                      (let [messages [{:role "user" :content "Hello"}
                                                      {:role "assistant" :content "Hi there"}]
                                            {:keys [lastFrame]} (render
                                                                 #jsx [ChatView {:messages messages :theme test-theme}])]
                                        (-> (expect (lastFrame)) (.toContain "Hello"))
                                        (-> (expect (lastFrame)) (.toContain "Hi there")))))

                                (it "renders empty state with no messages"
                                    (fn []
                                      (let [{:keys [lastFrame]} (render
                                                                 #jsx [ChatView {:messages [] :theme test-theme}])]
        ;; Should not crash
                                        (-> (expect (lastFrame)) (.toBeDefined)))))

                                (it "streaming=true marks last message as live (no crash with :streaming prop)"
                                    (fn []
                                      (let [messages [{:role "user"      :content "q"}
                                                      {:role "assistant" :content "answer in progress"}]
                                            {:keys [lastFrame]} (render
                                                                 #jsx [ChatView {:messages  messages
                                                                                 :theme     test-theme
                                                                                 :streaming true}])]
                                        (-> (expect (lastFrame)) (.toContain "answer in progress")))))

                                (it "multiple turns all appear in frame history"
    ;; Encodes the scrollback-preservation contract.
    ;; Currently all turns are in lastFrame (no Static yet).
    ;; After Static (Step 7) earlier turns move to scrollback frames only.
    ;; Either way they must appear *somewhere* in the frame log.
                                    (fn []
                                      (let [t1 [{:role "user"      :content "TURN1_Q"}
                                                {:role "assistant" :content "TURN1_A"}]
                                            t2 (into t1 [{:role "user"      :content "TURN2_Q"}
                                                         {:role "assistant" :content "TURN2_A"}])
                                            t3 (into t2 [{:role "user"      :content "TURN3_Q"}
                                                         {:role "assistant" :content "TURN3_A"}])
                                            {:keys [frames rerender]}
                                            (render #jsx [ChatView {:messages t1 :theme test-theme}])]
                                        (rerender #jsx [ChatView {:messages t2 :theme test-theme}])
                                        (rerender #jsx [ChatView {:messages t3 :theme test-theme}])
        ;; Every turn's answer must appear at least once across all frames
                                        (-> (expect (boolean (.some frames #(.includes % "TURN1_A")))) (.toBe true))
                                        (-> (expect (boolean (.some frames #(.includes % "TURN2_A")))) (.toBe true))
                                        (-> (expect (boolean (.some frames #(.includes % "TURN3_A")))) (.toBe true)))))))

;;; ─── No-jump invariant (regression for editor-bar jump bug) ────
;;;
;;; The editor bar used to jump up visually when the second question was
;;; submitted. Root cause: the dynamic region briefly held ALL messages
;;; (Q1, A1, Q2) before the commit sweep fired and removed Q1+A1.
;;;
;;; Fix: ChatView's in-flight = live (current turn only). The dynamic
;;; region NEVER holds past-turn messages — it only holds the slice from
;;; the last user message onwards. No commit-sweep timing involved.
;;;
;;; These pure-function tests pin compute-turn-split's `live` slice so
;;; the invariant can't regress silently.

(describe "compute-turn-split: no-jump invariant"
          (fn []

            (it "single turn: live = all messages (user + ongoing response)"
                ;; During turn 1, live covers everything (turn-idx=0 → from
                ;; index 0 to end). in-flight shows the whole conversation.
                (fn []
                  (let [u1  #js {:role "user"      :content "q1" :id "u1"}
                        a1  #js {:role "assistant" :content "a1" :id "a1"}
                        {:keys [live]} (compute_turn_split #js [u1 a1])]
                    (-> (expect (.-length live)) (.toBe 2)))))

            (it "second turn arrives: live = [Q2] only, NOT [Q1 A1 Q2]"
                ;; THE REGRESSION TEST. Before the fix, in-flight used
                ;; filterv(not committed, all-msgs). Committed was set async
                ;; (via useEffect). For one React render frame, all three
                ;; messages were in-flight. When useEffect fired and committed
                ;; Q1+A1, the region shrank from 3 items to 1 — editor jump.
                ;;
                ;; With in-flight = live, the dynamic region shows only [Q2]
                ;; from the very first render where Q2 is added. No shrink,
                ;; no jump, even before the commit sweep fires.
                (fn []
                  (let [u1  #js {:role "user"      :content "q1" :id "u1"}
                        a1  #js {:role "assistant" :content "a1" :id "a1"}
                        u2  #js {:role "user"      :content "q2" :id "u2"}
                        {:keys [live]} (compute_turn_split #js [u1 a1 u2])]
                    (-> (expect (.-length live)) (.toBe 1))
                    (-> (expect (.-id (aget live 0))) (.toBe "u2")))))

            (it "third turn: live = [Q3] only"
                (fn []
                  (let [u1  #js {:role "user"      :content "q1" :id "u1"}
                        a1  #js {:role "assistant" :content "a1" :id "a1"}
                        u2  #js {:role "user"      :content "q2" :id "u2"}
                        a2  #js {:role "assistant" :content "a2" :id "a2"}
                        u3  #js {:role "user"      :content "q3" :id "u3"}
                        {:keys [live]} (compute_turn_split #js [u1 a1 u2 a2 u3])]
                    (-> (expect (.-length live)) (.toBe 1))
                    (-> (expect (.-id (aget live 0))) (.toBe "u3")))))

            (it "streaming turn: live includes user + partial response"
                ;; While streaming, live = [u2, a2-partial]. Both shown in
                ;; the dynamic region. No past-turn content, so no jump risk.
                (fn []
                  (let [u1  #js {:role "user"      :content "q1"          :id "u1"}
                        a1  #js {:role "assistant" :content "a1"          :id "a1"}
                        u2  #js {:role "user"      :content "q2"          :id "u2"}
                        a2  #js {:role "assistant" :content "streaming..." :id "a2"}
                        {:keys [live]} (compute_turn_split #js [u1 a1 u2 a2])]
                    (-> (expect (.-length live)) (.toBe 2))
                    (-> (expect (.-id (aget live 0))) (.toBe "u2"))
                    (-> (expect (.-id (aget live 1))) (.toBe "a2")))))

            (it "frame-by-frame simulation: live slice is bounded at every step"
                ;; REGRESSION GUARD FOR TEST-GAP-1. The existing tests call
                ;; compute_turn_split once with a fixed array. Real runtime
                ;; appends messages one at a time through a streaming turn.
                ;; This test walks through a realistic sequence: submit Q1,
                ;; stream A1 chunks, submit Q2, stream A2. At every frame
                ;; the live slice must stay bounded to AT MOST the current
                ;; turn — if it ever held past-turn content, the no-jump
                ;; invariant is broken.
                (fn []
                  (let [u1    #js {:role "user"      :content "q1" :id "u1"}
                        a1-c1 #js {:role "assistant" :content "h" :id "a1"}
                        a1-c2 #js {:role "assistant" :content "hi " :id "a1"}
                        a1-c3 #js {:role "assistant" :content "hi there" :id "a1"}
                        u2    #js {:role "user"      :content "q2" :id "u2"}
                        a2-c1 #js {:role "assistant" :content "w" :id "a2"}
                        frames (atom [])
                        record (fn [msgs]
                                 (swap! frames conj
                                        (:live (compute_turn_split msgs))))]
                    ;; Frame 1: Q1 just submitted, no chunks yet.
                    (record #js [u1])
                    ;; Frames 2-4: A1 streams, messages grow.
                    (record #js [u1 a1-c1])
                    (record #js [u1 a1-c2])
                    (record #js [u1 a1-c3])
                    ;; Frame 5: Q2 submitted. Turn boundary shifts — live
                    ;; must immediately drop to [Q2] only. Before the
                    ;; no-jump fix this was where duplication snuck in.
                    (record #js [u1 a1-c3 u2])
                    ;; Frame 6: A2 chunk arrives.
                    (record #js [u1 a1-c3 u2 a2-c1])

                    ;; Turn 1 frames (0..3): live covers [Q1] → [Q1, A1].
                    (-> (expect (.-length (nth @frames 0))) (.toBe 1))
                    (-> (expect (.-length (nth @frames 1))) (.toBe 2))
                    (-> (expect (.-length (nth @frames 2))) (.toBe 2))
                    (-> (expect (.-length (nth @frames 3))) (.toBe 2))
                    ;; Frame 4: Q2 submitted → live = [Q2] ONLY.
                    ;; This is the regression test — the original bug was
                    ;; that live briefly held [Q1, A1, Q2] here.
                    (-> (expect (.-length (nth @frames 4))) (.toBe 1))
                    (-> (expect (.-id (aget (nth @frames 4) 0))) (.toBe "u2"))
                    ;; Frame 5: A2 starts streaming → live = [Q2, A2].
                    (-> (expect (.-length (nth @frames 5))) (.toBe 2))
                    (-> (expect (.-id (aget (nth @frames 5) 0))) (.toBe "u2"))
                    (-> (expect (.-id (aget (nth @frames 5) 1))) (.toBe "a2"))

                    ;; INVARIANT: across ALL frames, live never contains
                    ;; past-turn content. Since turn 1 has ids u1/a1 and
                    ;; turn 2 has u2/a2, once Q2 arrives no frame should
                    ;; ever include u1 or a1 in live.
                    (doseq [frame (drop 4 @frames)]
                      (doseq [i (range (.-length frame))]
                        (let [id (.-id (aget frame i))]
                          (-> (expect (or (= id "u2") (= id "a2")))
                              (.toBe true))))))))))
