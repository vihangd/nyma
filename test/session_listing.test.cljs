(ns session-listing.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.sessions.listing :refer [list-sessions]]))

(def ^:private test-dir (atom nil))

(beforeEach
  (fn []
    (let [dir (path/join (os/tmpdir) (str "nyma-test-sessions-" (js/Date.now)))]
      (fs/mkdirSync dir #js {:recursive true})
      (reset! test-dir dir))))

(afterEach
  (fn []
    (when @test-dir
      (try (fs/rmSync @test-dir #js {:recursive true :force true})
           (catch :default _)))))

(describe "list-sessions" (fn []
  (it "returns empty vec for nonexistent directory"
    (fn []
      (let [result (list-sessions "/nonexistent/path/12345")]
        (-> (expect (count result)) (.toBe 0)))))

  (it "returns empty vec for nil directory"
    (fn []
      (let [result (list-sessions nil)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "returns empty vec for empty directory"
    (fn []
      (let [result (list-sessions @test-dir)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "finds .jsonl files and returns metadata"
    (fn []
      (let [dir @test-dir
            f1  (path/join dir "session1.jsonl")
            f2  (path/join dir "session2.jsonl")]
        (fs/writeFileSync f1 "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hello\"}\n")
        (fs/writeFileSync f2 "{\"id\":\"b\",\"role\":\"user\",\"content\":\"world\"}\n{\"id\":\"c\",\"role\":\"assistant\",\"content\":\"hi\"}\n")
        (let [result (list-sessions dir)]
          (-> (expect (count result)) (.toBe 2))
          ;; Both have paths and entry counts
          (-> (expect (:entry-count (first result))) (.toBeGreaterThan 0))
          (-> (expect (:path (first result))) (.toBeTruthy))))))

  (it "extracts session name from session-name entry"
    (fn []
      (let [dir @test-dir
            f   (path/join dir "named.jsonl")]
        (fs/writeFileSync f
          (str "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hello\"}\n"
               "{\"id\":\"b\",\"role\":\"session-name\",\"content\":\"My Project\"}\n"))
        (let [result (list-sessions dir)
              sess   (first result)]
          (-> (expect (:name sess)) (.toBe "My Project"))))))

  (it "uses filename as fallback name"
    (fn []
      (let [dir @test-dir
            f   (path/join dir "fallback.jsonl")]
        (fs/writeFileSync f "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hi\"}\n")
        (let [result (list-sessions dir)
              sess   (first result)]
          (-> (expect (:name sess)) (.toBe "fallback"))))))

  (it "sorts by modified time descending"
    (fn []
      (let [dir @test-dir
            f1  (path/join dir "old.jsonl")
            f2  (path/join dir "new.jsonl")]
        ;; Write old first, then set its mtime to the past
        (fs/writeFileSync f1 "{\"id\":\"a\",\"role\":\"user\",\"content\":\"old\"}\n")
        (let [past (/ (- (js/Date.now) 60000) 1000)]
          (fs/utimesSync f1 past past))
        ;; Write new — will have current mtime (more recent)
        (fs/writeFileSync f2 "{\"id\":\"b\",\"role\":\"user\",\"content\":\"new\"}\n")
        (let [result (list-sessions dir)]
          ;; Most recently modified should be first
          (-> (expect (:file (first result))) (.toBe "new.jsonl"))))))

  (it "ignores non-jsonl files"
    (fn []
      (let [dir @test-dir]
        (fs/writeFileSync (path/join dir "notes.txt") "not a session")
        (fs/writeFileSync (path/join dir "real.jsonl") "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hi\"}\n")
        (let [result (list-sessions dir)]
          (-> (expect (count result)) (.toBe 1))
          (-> (expect (:file (first result))) (.toBe "real.jsonl"))))))))
