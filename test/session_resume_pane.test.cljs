(ns session-resume-pane.test
  "`/resume` left an empty transcript every time.

   Three paths seeded the conversation and none shared code: startup filtered
   `build-context` itself into the pane's atom, cli built the model's context
   with `session->seed-messages`, and /resume dispatched to the STORE — which
   the pane does not subscribe to. Then /resume emitted `session_start`, whose
   handler was a bare `(reset! messages [])`. So it cleared the screen and put
   nothing back, regardless of what the session held.

   No test asserted that a resumed session renders, which is why this shipped."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.sessions.manager :refer [create-session-manager session->seed-messages]]
            [agent.modes.interactive :refer [pane-messages session-start-clears?
                                             make-session-start-handler]]))

(def ^:private tmp (atom []))

(defn- session-with [entries]
  (let [fp (path/join (os/tmpdir)
                      (str "nyma-resume-pane-"
                           (.slice (.toString (js/Math.random) 36) 2) ".jsonl"))]
    (swap! tmp conj fp)
    (fs/writeFileSync fp "" "utf8")
    (let [mgr (create-session-manager fp)]
      (doseq [e entries] ((:append mgr) e))
      ((:load mgr))
      mgr)))

(afterEach (fn []
             (doseq [f @tmp] (try (fs/unlinkSync f) (catch :default _ nil)))
             (reset! tmp [])))

(describe "pane-messages"
          (fn []
            (it "returns the conversation a resumed session should show"
                (fn []
                  (let [mgr  (session-with [{:role "user" :content "hello"}
                                            {:role "assistant" :content "hi there"}
                                            {:role "user" :content "again"}])
                        msgs (pane-messages mgr)]
                    (-> (expect (count msgs)) (.toBe 3))
                    (-> (expect (:role (first msgs))) (.toBe "user"))
                    (-> (expect (:content (second msgs))) (.toBe "hi there")))))

            (it "agrees exactly with the model's context"
                (fn []
                  ;; The transcript and the context are built by the same
                  ;; function now. They used to be built by two different
                  ;; filters, so they could disagree — and did, on any
                  ;; compacted session.
                  (let [mgr (session-with [{:role "user" :content "q"}
                                           {:role "assistant" :content "a"}])]
                    (-> (expect (vec (pane-messages mgr)))
                        (.toEqual (vec (session->seed-messages ((:build-context mgr)))))))))

            (it "keeps a compaction summary the old filter dropped"
                (fn []
                  ;; seed-messages folds compaction into a summary message and
                  ;; discards everything before it. The pane's own filter kept
                  ;; only raw user/assistant roles, so it dropped the summary
                  ;; outright — the screen showed less than the model saw.
                  (let [mgr  (session-with [{:role "user" :content "old"}
                                            {:role "assistant" :content "older"}
                                            {:role "compaction" :content "SUMMARY OF EARLIER"}
                                            {:role "user" :content "new question"}])
                        msgs (pane-messages mgr)
                        text (.join (to-array (mapv (fn [m] (str (:content m))) msgs)) "\n")]
                    (-> (expect (.includes text "SUMMARY OF EARLIER")) (.toBe true))
                    (-> (expect (.includes text "new question")) (.toBe true)))))

            (it "drops tool_call entries, which are not conversation"
                (fn []
                  (let [mgr (session-with [{:role "user" :content "q"}
                                           {:role "tool_call" :content "{}"}
                                           {:role "assistant" :content "a"}])]
                    (-> (expect (count (pane-messages mgr))) (.toBe 2)))))

            (it "is empty, not an error, with no session"
                (fn []
                  (-> (expect (vec (pane-messages nil))) (.toEqual []))
                  (-> (expect (vec (pane-messages #js {}))) (.toEqual []))))

            (it "is empty for a session with nothing in it"
                (fn []
                  (-> (expect (vec (pane-messages (session-with [])))) (.toEqual []))))))

(describe "session-start-clears?"
          (fn []
            (it "blanks only for /new"
                (fn []
                  ;; /new emits session_start without switching files, so
                  ;; re-seeding would repaint the conversation it just cleared.
                  (-> (expect (session-start-clears? "new")) (.toBe true))))

            (it "re-seeds for every emitter that changed the branch"
                (fn []
                  ;; These call switch-file (or re-point the leaf) BEFORE
                  ;; emitting, so build-context is already what the user asked
                  ;; for by the time the handler runs.
                  (doseq [r ["resume" "import" "fork" "default" "continue" nil ""]]
                    (-> (expect (session-start-clears? r)) (.toBe false)))))))

;;; ─── the handler itself ──────────────────────────────────────────────────
;;; Built from callbacks so the real thing can be driven without a TUI. An
;;; earlier version of this file asserted on compiled source instead and was
;;; theatre: reverting the handler to a bare clear left the helper functions
;;; still DEFINED in the module, so the assertion passed against the bug.

(describe "the session_start handler"
          (fn []
            (it "restores the transcript on /resume"
                (fn []
                  ;; The reported bug: this cleared and put nothing back.
                  (let [mgr    (session-with [{:role "user" :content "hello"}
                                              {:role "assistant" :content "hi there"}])
                        shown  (atom :never-called)
                        cleared (atom false)
                        h (make-session-start-handler
                           {:get-session (fn [] mgr)
                            :on-messages (fn [m] (reset! shown (vec m)))
                            :on-clear    (fn [] (reset! cleared true))})]
                    (h #js {:reason "resume"})
                    (-> (expect @cleared) (.toBe false))
                    (-> (expect (count @shown)) (.toBe 2))
                    (-> (expect (:content (second @shown))) (.toBe "hi there")))))

            (it "restores on /import and /fork too"
                (fn []
                  (doseq [reason ["import" "fork"]]
                    (let [mgr   (session-with [{:role "user" :content "q"}
                                               {:role "assistant" :content "a"}])
                          shown (atom [])
                          h (make-session-start-handler
                             {:get-session (fn [] mgr)
                              :on-messages (fn [m] (reset! shown (vec m)))
                              :on-clear    (fn [] (reset! shown :cleared))})]
                      (h #js {:reason reason})
                      (-> (expect (count @shown)) (.toBe 2))))))

            (it "still blanks on /new"
                (fn []
                  ;; /new emits session_start without switching files, so the
                  ;; session still holds the old conversation. Re-seeding here
                  ;; would repaint what the user just cleared.
                  (let [mgr     (session-with [{:role "user" :content "old"}
                                               {:role "assistant" :content "older"}])
                        shown   (atom :never-called)
                        cleared (atom false)
                        h (make-session-start-handler
                           {:get-session (fn [] mgr)
                            :on-messages (fn [m] (reset! shown (vec m)))
                            :on-clear    (fn [] (reset! cleared true))})]
                    (h #js {:reason "new"})
                    (-> (expect @cleared) (.toBe true))
                    (-> (expect @shown) (.toBe :never-called)))))

            (it "reads the session at call time, not at construction"
                (fn []
                  ;; /resume switches files BEFORE emitting, so a handler that
                  ;; captured the session when it was built would restore the
                  ;; wrong conversation.
                  (let [before (session-with [{:role "user" :content "BEFORE"}])
                        after  (session-with [{:role "user" :content "AFTER"}])
                        current (atom before)
                        shown  (atom [])
                        h (make-session-start-handler
                           {:get-session (fn [] @current)
                            :on-messages (fn [m] (reset! shown (vec m)))
                            :on-clear    (fn [] nil)})]
                    (reset! current after)
                    (h #js {:reason "resume"})
                    (-> (expect (:content (first @shown))) (.toBe "AFTER")))))

            (it "tolerates an event with no reason"
                (fn []
                  (let [mgr   (session-with [{:role "user" :content "q"}])
                        shown (atom [])
                        h (make-session-start-handler
                           {:get-session (fn [] mgr)
                            :on-messages (fn [m] (reset! shown (vec m)))
                            :on-clear    (fn [] (reset! shown :cleared))})]
                    (h #js {})
                    (h nil)
                    (-> (expect (count @shown)) (.toBe 1)))))))
