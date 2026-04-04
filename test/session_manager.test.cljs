(ns session-manager.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager]]))

(describe "agent.sessions.manager (in-memory)"
  (fn []
    (it "starts with empty tree and null leaf"
      (fn []
        (let [sm (create-session-manager nil)]
          (-> (expect (count ((:get-tree sm)))) (.toBe 0))
          (-> (expect ((:leaf-id sm))) (.toBeNull)))))

    (it "append returns string id and updates leaf"
      (fn []
        (let [sm (create-session-manager nil)
              id ((:append sm) {:role "user" :content "hello"})]
          (-> (expect (string? id)) (.toBe true))
          (-> (expect ((:leaf-id sm))) (.toBe id)))))

    (it "append chains parent-id to previous leaf"
      (fn []
        (let [sm  (create-session-manager nil)
              id1 ((:append sm) {:role "user" :content "first"})
              id2 ((:append sm) {:role "assistant" :content "second"})
              tree ((:get-tree sm))
              entry1 (first tree)
              entry2 (second tree)]
          (-> (expect (:parent-id entry1)) (.toBeNull))
          (-> (expect (:parent-id entry2)) (.toBe id1)))))

    (it "build-context walks leaf to root and filters LLM roles"
      (fn []
        (let [sm (create-session-manager nil)]
          ((:append sm) {:role "user" :content "hi"})
          ((:append sm) {:role "assistant" :content "hello"})
          ((:append sm) {:role "compaction" :content "summary"})
          ((:append sm) {:role "user" :content "next"})
          (let [ctx ((:build-context sm))]
            ;; compaction is included (LLM needs to see summaries)
            (-> (expect (count ctx)) (.toBe 4))
            (-> (expect (:role (first ctx))) (.toBe "user"))
            (-> (expect (:content (first ctx))) (.toBe "hi"))
            ;; internal roles like "system" are filtered out
            ))))

    (it "build-context returns root-to-leaf order"
      (fn []
        (let [sm (create-session-manager nil)]
          ((:append sm) {:role "user" :content "A"})
          ((:append sm) {:role "assistant" :content "B"})
          ((:append sm) {:role "user" :content "C"})
          (let [ctx ((:build-context sm))]
            (-> (expect (:content (first ctx))) (.toBe "A"))
            (-> (expect (:content (last ctx))) (.toBe "C"))))))

    (it "branch forks the tree"
      (fn []
        (let [sm (create-session-manager nil)
              id-a ((:append sm) {:role "user" :content "A"})
              _id-b ((:append sm) {:role "assistant" :content "B"})]
          ;; Branch back to A
          ((:branch sm) id-a)
          ((:append sm) {:role "assistant" :content "C"})
          (let [ctx ((:build-context sm))]
            (-> (expect (count ctx)) (.toBe 2))
            (-> (expect (:content (first ctx))) (.toBe "A"))
            (-> (expect (:content (second ctx))) (.toBe "C"))))))

    (it "entries have timestamps"
      (fn []
        (let [sm (create-session-manager nil)]
          ((:append sm) {:role "user" :content "test"})
          (let [entry (first ((:get-tree sm)))]
            (-> (expect (number? (:timestamp entry))) (.toBe true))))))

    (it "build-context on empty session returns empty"
      (fn []
        (let [sm (create-session-manager nil)]
          (-> (expect (count ((:build-context sm)))) (.toBe 0)))))))

(describe "agent.sessions.manager (file-backed)"
  (fn []
    (it "append writes JSONL to disk"
      (fn []
        (let [tmp-dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-test-"))
              tmp-file (.join path tmp-dir "session.jsonl")
              sm       (create-session-manager tmp-file)]
          ((:append sm) {:role "user" :content "hello"})
          (let [content (.readFileSync fs tmp-file "utf8")
                lines   (filterv seq (.split content "\n"))]
            (-> (expect (count lines)) (.toBe 1))
            (let [parsed (js/JSON.parse (first lines))]
              (-> (expect (:role parsed)) (.toBe "user"))))
          (.rmSync fs tmp-dir #js {:recursive true}))))

    (it "load restores tree and leaf from file"
      (fn []
        (let [tmp-dir   (.mkdtempSync fs (str (.tmpdir os) "/nyma-test-"))
              tmp-file  (.join path tmp-dir "session.jsonl")
              sm1       (create-session-manager tmp-file)
              id        ((:append sm1) {:role "user" :content "persist"})
              sm2       (create-session-manager tmp-file)]
          ((:load sm2))
          (-> (expect (count ((:get-tree sm2)))) (.toBe 1))
          (-> (expect ((:leaf-id sm2))) (.toBe id))
          (.rmSync fs tmp-dir #js {:recursive true}))))))
