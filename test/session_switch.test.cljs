(ns session-switch.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.sessions.manager :refer [create-session-manager]]))

(def ^:private test-dir (atom nil))

(beforeEach
  (fn []
    (let [dir (path/join (os/tmpdir) (str "nyma-test-switch-" (js/Date.now)))]
      (fs/mkdirSync dir #js {:recursive true})
      (reset! test-dir dir))))

(afterEach
  (fn []
    (when @test-dir
      (try (fs/rmSync @test-dir #js {:recursive true :force true})
           (catch :default _)))))

(describe "session manager :switch-file" (fn []
  (it "get-file-path returns current path"
    (fn []
      (let [f1  (path/join @test-dir "session1.jsonl")
            _   (fs/writeFileSync f1 "")
            mgr (create-session-manager f1)]
        (-> (expect ((:get-file-path mgr))) (.toBe f1)))))

  (it "switch-file changes file path and reloads"
    (fn []
      (let [f1  (path/join @test-dir "session1.jsonl")
            f2  (path/join @test-dir "session2.jsonl")
            _   (fs/writeFileSync f1 "{\"id\":\"a\",\"parentId\":null,\"role\":\"user\",\"content\":\"hello\"}\n")
            _   (fs/writeFileSync f2 "{\"id\":\"b\",\"parentId\":null,\"role\":\"user\",\"content\":\"world\"}\n{\"id\":\"c\",\"parentId\":\"b\",\"role\":\"assistant\",\"content\":\"hi\"}\n")
            mgr (create-session-manager f1)]
        ((:load mgr))
        ;; Initially has session1 data
        (-> (expect (count ((:get-tree mgr)))) (.toBe 1))
        (-> (expect ((:get-file-path mgr))) (.toBe f1))
        ;; Switch to session2
        ((:switch-file mgr) f2)
        (-> (expect ((:get-file-path mgr))) (.toBe f2))
        (-> (expect (count ((:get-tree mgr)))) (.toBe 2)))))

  (it "switch-file resets session name and labels"
    (fn []
      (let [f1  (path/join @test-dir "session1.jsonl")
            f2  (path/join @test-dir "session2.jsonl")
            _   (fs/writeFileSync f1 "")
            _   (fs/writeFileSync f2 "")
            mgr (create-session-manager f1)]
        ((:set-session-name mgr) "My Session")
        ((:set-label mgr) "entry-1" "Important")
        (-> (expect ((:get-session-name mgr))) (.toBe "My Session"))
        (-> (expect ((:get-label mgr) "entry-1")) (.toBe "Important"))
        ;; Switch resets metadata
        ((:switch-file mgr) f2)
        (-> (expect ((:get-session-name mgr))) (.toBeNull))
        (-> (expect ((:get-label mgr) "entry-1")) (.toBeUndefined)))))

  (it "switch-file returns new path"
    (fn []
      (let [f1  (path/join @test-dir "session1.jsonl")
            f2  (path/join @test-dir "session2.jsonl")
            _   (fs/writeFileSync f1 "")
            _   (fs/writeFileSync f2 "")
            mgr (create-session-manager f1)
            result ((:switch-file mgr) f2)]
        (-> (expect result) (.toBe f2)))))))
