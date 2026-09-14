(ns session-manager.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager parse-lines]]
            [agent.events :refer [create-event-bus]]))

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

(defn- on! [bus event-type handler]
  ((:on bus) event-type handler))

(describe "agent.sessions.manager — session switch events"
          (fn []
            (it "fires session_before_switch with old and new paths"
                (fn []
                  (let [bus   (create-event-bus)
                        sm    (create-session-manager nil {:events bus})
                        fired (atom nil)]
                    (on! bus "session_before_switch" (fn [data] (reset! fired data)))
                    ((:switch-file sm) "/tmp/new-session.jsonl")
                    (-> (expect (some? @fired)) (.toBe true))
                    (-> (expect (:new-path @fired)) (.toBe "/tmp/new-session.jsonl"))
                    ;; old-path was nil (no initial file)
                    (-> (expect (nil? (:old-path @fired))) (.toBe true)))))

            (it "fires session_switch after load with entry-count"
                (fn []
                  (let [bus   (create-event-bus)
                        sm    (create-session-manager nil {:events bus})
                        fired (atom nil)]
                    (on! bus "session_switch" (fn [data] (reset! fired data)))
                    ((:switch-file sm) "/tmp/new2-session.jsonl")
                    (-> (expect (some? @fired)) (.toBe true))
                    (-> (expect (:new-path @fired)) (.toBe "/tmp/new2-session.jsonl"))
                    (-> (expect (number? (:entry-count @fired))) (.toBe true)))))

            (it "session_before_switch fires before session_switch"
                (fn []
                  (let [bus   (create-event-bus)
                        sm    (create-session-manager nil {:events bus})
                        order (atom [])]
                    (on! bus "session_before_switch" (fn [_] (swap! order conj "before")))
                    (on! bus "session_switch" (fn [_] (swap! order conj "after")))
                    ((:switch-file sm) "/tmp/order-test.jsonl")
                    (-> (expect (first @order)) (.toBe "before"))
                    (-> (expect (second @order)) (.toBe "after")))))

            (it "does not fire switch events when no events bus is provided"
                (fn []
                  (let [sm    (create-session-manager nil)
                        calls (atom 0)]
                    ;; No event bus — switch-file must complete without error
                    ((:switch-file sm) "/tmp/no-events.jsonl")
                    (-> (expect @calls) (.toBe 0)))))))

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
                    (.rmSync fs tmp-dir #js {:recursive true}))))

            ;; /name used to set an in-memory atom only, so the name died with
            ;; the process and listing/explicit-name — its only reader — never
            ;; had an entry to find.
            (it "session name survives a reload"
                (fn []
                  (let [tmp-dir   (.mkdtempSync fs (str (.tmpdir os) "/nyma-test-"))
                        tmp-file  (.join path tmp-dir "session.jsonl")
                        sm1       (create-session-manager tmp-file)]
                    ((:append sm1) {:role "user" :content "hi"})
                    ((:set-session-name sm1) "refactor pass")
                    (let [sm2 (create-session-manager tmp-file)]
                      ((:load sm2))
                      (-> (expect ((:get-session-name sm2))) (.toBe "refactor pass"))
                      ;; Inert for the model: the name is not a conversation turn.
                      (-> (expect (count ((:build-context sm2)))) (.toBe 1)))
                    (.rmSync fs tmp-dir #js {:recursive true}))))))

;;; ─── a half-written last line ───────────────────────────────
;;; A session file's final line is truncated whenever the previous run was
;;; killed mid-append — Ctrl-C during a stream, a crash, an OOM. `mapv
;;; JSON.parse` threw on it, so `-c` and `-r` died on exactly the sessions a
;;; user most wants back. `sessions/listing.cljs` has always skipped bad lines,
;;; which is why the picker LISTED a session that then refused to open.

(defn- write-truncated! [lines trailing]
  (let [dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-trunc-"))
        file (.join path dir "session.jsonl")]
    (fs/writeFileSync file (str (str/join "\n" (map #(js/JSON.stringify (clj->js %)) lines))
                                "\n" trailing))
    [dir file]))

(describe "resuming a session whose last line is half-written"
          (fn []
            (it "loads the good turns instead of throwing"
                (fn []
                  (let [[dir file] (write-truncated!
                                    [{:id "a" :parent-id nil :role "user" :content "hi"}
                                     {:id "b" :parent-id "a" :role "assistant" :content "hello"}]
                                    "{\"id\":\"c\",\"role\":\"assis")
                        sm (create-session-manager file)]
                    ((:load sm))
                    (-> (expect (count ((:get-tree sm)))) (.toBe 2))
                    (-> (expect (count ((:build-context sm)))) (.toBe 2))
                    (.rmSync fs dir #js {:recursive true}))))

            (it "leaves the leaf on the last GOOD entry, so the next turn chains to it"
                (fn []
                  (let [[dir file] (write-truncated!
                                    [{:id "a" :parent-id nil :role "user" :content "hi"}
                                     {:id "b" :parent-id "a" :role "assistant" :content "hello"}]
                                    "{\"id\":\"c\"")
                        sm (create-session-manager file)]
                    ((:load sm))
                    (-> (expect ((:leaf-id sm))) (.toBe "b"))
                    (.rmSync fs dir #js {:recursive true}))))

            (it "counts every skipped line, not just the last"
                (fn []
                  (-> (expect (:skipped (parse-lines "{\"a\":1}\nnot json\n{\"b\":2}\n{\"c\":")))
                      (.toBe 2))
                  (-> (expect (count (:entries (parse-lines "{\"a\":1}\nnot json\n{\"b\":2}"))))
                      (.toBe 2))))

            (it "is a no-op for a clean file"
                (fn []
                  (-> (expect (:skipped (parse-lines "{\"a\":1}\n{\"b\":2}\n"))) (.toBe 0))))))
