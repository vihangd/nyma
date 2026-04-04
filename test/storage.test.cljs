(ns storage.test
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.sessions.storage :refer [create-sqlite-store]]))

(def ^:private test-db-path ":memory:")

(defn make-store []
  (let [store (create-sqlite-store test-db-path)]
    ((:init-schema store))
    store))

(describe "create-sqlite-store" (fn []

  (it "creates schema without error"
    (fn []
      (let [store (make-store)]
        ;; If we get here, schema creation succeeded
        (-> (expect store) (.toBeDefined))
        ((:close store)))))

  (it "upserts and retrieves entry via branch path"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "root" :parent-id "" :role "user"
                                 :content "hello" :timestamp 1000 :session-file "test"})
        ((:upsert-entry store) {:id "child" :parent-id "root" :role "assistant"
                                 :content "hi there" :timestamp 1001 :session-file "test"})
        (let [path ((:query-branch-path store) "child")]
          (-> (expect (count path)) (.toBe 2))
          (-> (expect (:id (first path))) (.toBe "root"))
          (-> (expect (:id (second path))) (.toBe "child")))
        ((:close store)))))

  (it "branch path with deep chain"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "a" :parent-id "" :role "user" :content "1" :timestamp 1 :session-file "t"})
        ((:upsert-entry store) {:id "b" :parent-id "a" :role "assistant" :content "2" :timestamp 2 :session-file "t"})
        ((:upsert-entry store) {:id "c" :parent-id "b" :role "user" :content "3" :timestamp 3 :session-file "t"})
        ((:upsert-entry store) {:id "d" :parent-id "c" :role "assistant" :content "4" :timestamp 4 :session-file "t"})
        (let [path ((:query-branch-path store) "d")]
          (-> (expect (count path)) (.toBe 4))
          (-> (expect (:id (first path))) (.toBe "a"))
          (-> (expect (:id (last path))) (.toBe "d")))
        ((:close store)))))

  (it "branch path for single entry"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "only" :parent-id "" :role "user" :content "solo" :timestamp 1 :session-file "t"})
        (let [path ((:query-branch-path store) "only")]
          (-> (expect (count path)) (.toBe 1))
          (-> (expect (:content (first path))) (.toBe "solo")))
        ((:close store)))))

  (it "branch path for nonexistent id returns empty"
    (fn []
      (let [store (make-store)]
        (let [path ((:query-branch-path store) "nope")]
          (-> (expect (count path)) (.toBe 0)))
        ((:close store)))))

  (it "search-content finds matching entries"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "1" :parent-id "" :role "user" :content "fix the bug in auth" :timestamp 1 :session-file "t"})
        ((:upsert-entry store) {:id "2" :parent-id "1" :role "assistant" :content "looking at the code" :timestamp 2 :session-file "t"})
        ((:upsert-entry store) {:id "3" :parent-id "2" :role "user" :content "now fix the UI" :timestamp 3 :session-file "t"})
        (let [results ((:search-content store) "fix")]
          (-> (expect (count results)) (.toBe 2)))
        ((:close store)))))

  (it "search-content returns empty for no match"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "1" :parent-id "" :role "user" :content "hello" :timestamp 1 :session-file "t"})
        (let [results ((:search-content store) "zzzzzzz")]
          (-> (expect (count results)) (.toBe 0)))
        ((:close store)))))

  (it "upsert-usage and get-session-usage"
    (fn []
      (let [store (make-store)]
        ((:upsert-usage store) {:session-file "s1" :turn-id "t1" :model "claude"
                                 :input-tokens 100 :output-tokens 50 :cost-usd 0.01})
        ((:upsert-usage store) {:session-file "s1" :turn-id "t2" :model "claude"
                                 :input-tokens 200 :output-tokens 100 :cost-usd 0.02})
        (let [usage ((:get-session-usage store) "s1")]
          (-> (expect (:total-input-tokens usage)) (.toBe 300))
          (-> (expect (:total-output-tokens usage)) (.toBe 150))
          (-> (expect (:total-cost usage)) (.toBeCloseTo 0.03 6))
          (-> (expect (:turn-count usage)) (.toBe 2)))
        ((:close store)))))

  (it "get-session-usage for unknown session returns zeros"
    (fn []
      (let [store (make-store)]
        (let [usage ((:get-session-usage store) "unknown")]
          (-> (expect (:total-input-tokens usage)) (.toBe 0))
          (-> (expect (:turn-count usage)) (.toBe 0)))
        ((:close store)))))

  (it "upsert-entry preserves metadata as JSON"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "m1" :parent-id "" :role "tool_call"
                                 :content "result" :metadata {:tool-name "read" :args {:path "/foo"}}
                                 :timestamp 1 :session-file "t"})
        (let [path ((:query-branch-path store) "m1")
              entry (first path)]
          (-> (expect (:tool-name (:metadata entry))) (.toBe "read"))
          (-> (expect (get-in (:metadata entry) [:args :path])) (.toBe "/foo")))
        ((:close store)))))

  (it "upsert-branch-summary and retrieve"
    (fn []
      (let [store (make-store)]
        ((:upsert-branch-summary store)
          {:id "bs1" :branch-leaf-id "leaf42" :session-file "s1"
           :summary "Did some auth work" :files-read ["src/auth.cljs"]
           :files-modified ["src/login.cljs"]})
        (let [summaries ((:get-branch-summaries store) "s1")]
          (-> (expect (count summaries)) (.toBe 1))
          (-> (expect (:summary (first summaries))) (.toBe "Did some auth work"))
          (-> (expect (first (:files-read (first summaries)))) (.toBe "src/auth.cljs")))
        ((:close store)))))

  (it "upsert-entry is idempotent (INSERT OR REPLACE)"
    (fn []
      (let [store (make-store)]
        ((:upsert-entry store) {:id "x" :parent-id "" :role "user" :content "v1" :timestamp 1 :session-file "t"})
        ((:upsert-entry store) {:id "x" :parent-id "" :role "user" :content "v2" :timestamp 2 :session-file "t"})
        (let [path ((:query-branch-path store) "x")]
          (-> (expect (count path)) (.toBe 1))
          (-> (expect (:content (first path))) (.toBe "v2")))
        ((:close store)))))))
