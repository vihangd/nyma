(ns session-resume.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.sessions.manager :refer [create-session-manager session->seed-messages]]))

(def ^:private test-dir (atom nil))

(beforeEach
 (fn []
   (let [dir (path/join (os/tmpdir) (str "nyma-test-resume-" (js/Date.now)))]
     (fs/mkdirSync dir #js {:recursive true})
     (reset! test-dir dir))))

(afterEach
 (fn []
   (when @test-dir
     (try (fs/rmSync @test-dir #js {:recursive true :force true})
          (catch :default _)))))

(describe "session->seed-messages" (fn []

                                     (it "keeps user/assistant turns in order"
                                         (fn []
                                           (let [seeded (session->seed-messages
                                                         [{:role "user" :content "hi"}
                                                          {:role "assistant" :content "hello"}
                                                          {:role "user" :content "bye"}])]
                                             (-> (expect (count seeded)) (.toBe 3))
                                             (-> (expect (:role (first seeded))) (.toBe "user"))
                                             (-> (expect (:content (nth seeded 1))) (.toBe "hello")))))

                                     (it "drops tool_call / tool_result entries"
                                         (fn []
                                           (let [seeded (session->seed-messages
                                                         [{:role "user" :content "read it"}
                                                          {:role "tool_call" :content "file bytes"}
                                                          {:role "tool_result" :content "ok"}
                                                          {:role "assistant" :content "done"}])]
                                             (-> (expect (count seeded)) (.toBe 2))
                                             (-> (expect (mapv :role seeded)) (.toEqual #js ["user" "assistant"])))))

                                     (it "folds compaction/branch-summary into a synthetic user summary"
                                         (fn []
                                           (let [seeded (session->seed-messages
                                                         [{:role "compaction" :content "earlier we did X"}
                                                          {:role "user" :content "continue"}])]
                                             (-> (expect (count seeded)) (.toBe 2))
                                             (-> (expect (:role (first seeded))) (.toBe "user"))
                                             (-> (expect (.includes (:content (first seeded)) "earlier we did X")) (.toBe true)))))

                                     (it "compaction elides the messages it summarized (no re-expansion)"
                                         (fn []
                                           ;; 3 pre-compaction turns + summary + 1 post turn → only
                                           ;; [summary, post] survive (the 3 originals are dropped).
                                           (let [seeded (session->seed-messages
                                                         [{:role "user" :content "old1"}
                                                          {:role "assistant" :content "old2"}
                                                          {:role "user" :content "old3"}
                                                          {:role "compaction" :content "summary of old1-3"}
                                                          {:role "user" :content "new"}])]
                                             (-> (expect (count seeded)) (.toBe 2))
                                             (-> (expect (.includes (:content (first seeded)) "summary of old1-3")) (.toBe true))
                                             (-> (expect (:content (nth seeded 1))) (.toBe "new")))))

                                     (it "returns empty for an empty/zeroed transcript"
                                         (fn []
                                           (-> (expect (count (session->seed-messages []))) (.toBe 0))))))

(describe "resume round-trip (write half → load → seed)" (fn []

                                                           (it "persisted user/assistant turns reload and seed in order; tool entries excluded"
                                                               (fn []
                                                                 (let [fp  (path/join @test-dir "s.jsonl")
                                                                       _   (fs/writeFileSync fp "")
                                                                       mgr (create-session-manager fp)]
        ;; What attach-session-persistence! does per turn:
                                                                   ((:append mgr) {:role "user" :content "q1"})
                                                                   ((:append mgr) {:role "tool_call" :content "side effect"})
                                                                   ((:append mgr) {:role "assistant" :content "a1"})
                                                                   ((:append mgr) {:role "user" :content "q2"})
        ;; Fresh manager simulates a relaunch resuming the file.
                                                                   (let [mgr2   (create-session-manager fp)
                                                                         _      ((:load mgr2))
                                                                         seeded (session->seed-messages ((:build-context mgr2)))]
                                                                     (-> (expect (mapv :role seeded)) (.toEqual #js ["user" "assistant" "user"]))
                                                                     (-> (expect (mapv :content seeded)) (.toEqual #js ["q1" "a1" "q2"]))))))

                                                           (it "loading does not re-append (no growth loop)"
                                                               (fn []
                                                                 (let [fp  (path/join @test-dir "s.jsonl")
                                                                       _   (fs/writeFileSync fp "")
                                                                       mgr (create-session-manager fp)]
                                                                   ((:append mgr) {:role "user" :content "only"})
                                                                   (let [before (.-size (fs/statSync fp))
                                                                         mgr2   (create-session-manager fp)]
                                                                     ((:load mgr2))
                                                                     ((:build-context mgr2))
                                                                     (-> (expect (.-size (fs/statSync fp))) (.toBe before))))))))
