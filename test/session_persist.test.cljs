(ns session-persist.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.cli :refer [pick-session]]
            [agent.state :refer [create-agent-store]]
            [agent.sessions.manager :refer [create-session-manager attach-session-persistence!]]))

(def ^:private test-dir (atom nil))

(beforeEach
 (fn []
   (let [dir (path/join (os/tmpdir) (str "nyma-test-persist-" (js/Date.now)))]
     (fs/mkdirSync dir #js {:recursive true})
     (reset! test-dir dir))))

(afterEach
 (fn []
   (when @test-dir
     (try (fs/rmSync @test-dir #js {:recursive true :force true})
          (catch :default _)))))

(defn- read-lines [fp]
  (->> (.split (fs/readFileSync fp "utf8") "\n")
       (filter seq)
       (mapv #(js/JSON.parse %))))

(describe "attach-session-persistence!" (fn []

                                          (it "appends user and assistant turns added to the store"
                                              (fn []
                                                (let [fp    (path/join @test-dir "s.jsonl")
                                                      _     (fs/writeFileSync fp "")
                                                      mgr   (create-session-manager fp)
                                                      store (create-agent-store {:messages []})
                                                      agent {:store store}]
                                                  (attach-session-persistence! agent mgr)
                                                  ((:dispatch! store) :message-added {:message {:role "user" :content "q1"}})
                                                  ((:dispatch! store) :message-added {:message {:role "assistant" :content "a1"}})
                                                  (let [lines (read-lines fp)]
                                                    (-> (expect (count lines)) (.toBe 2))
                                                    (-> (expect (.-role (first lines))) (.toBe "user"))
                                                    (-> (expect (.-content (nth lines 1))) (.toBe "a1"))))))

                                          (it "does not append non user/assistant roles"
                                              (fn []
                                                (let [fp    (path/join @test-dir "s.jsonl")
                                                      _     (fs/writeFileSync fp "")
                                                      mgr   (create-session-manager fp)
                                                      store (create-agent-store {:messages []})]
                                                  (attach-session-persistence! {:store store} mgr)
                                                  ((:dispatch! store) :message-added {:message {:role "system" :content "x"}})
                                                  (-> (expect (count (read-lines fp))) (.toBe 0)))))

                                          (it "is a no-op for an ephemeral (nil path) session"
                                              (fn []
      ;; A nil-path manager: get-file-path returns nil → subscriber never attaches.
                                                (let [mgr   (create-session-manager nil)
                                                      store (create-agent-store {:messages []})]
        ;; Should not throw; nothing to assert on disk.
                                                  (attach-session-persistence! {:store store} mgr)
                                                  ((:dispatch! store) :message-added {:message {:role "user" :content "q"}})
                                                  (-> (expect true) (.toBe true)))))

                                          (it "does NOT re-append while :replaying-session? is set (/resume, /import)"
                                              (fn []
                                                (let [fp    (path/join @test-dir "s.jsonl")
                                                      _     (fs/writeFileSync fp "")
                                                      mgr   (create-session-manager fp)
                                                      store (create-agent-store {:messages []})]
                                                  (attach-session-persistence! {:store store} mgr)
        ;; Simulate a replay: flag set, then messages dispatched.
                                                  ((:swap store) #(assoc % :replaying-session? true))
                                                  ((:dispatch! store) :message-added {:message {:role "user" :content "old1"}})
                                                  ((:dispatch! store) :message-added {:message {:role "assistant" :content "old2"}})
                                                  (-> (expect (count (read-lines fp))) (.toBe 0))
        ;; Flag cleared → live turns persist again.
                                                  ((:swap store) #(assoc % :replaying-session? false))
                                                  ((:dispatch! store) :message-added {:message {:role "user" :content "new"}})
                                                  (-> (expect (count (read-lines fp))) (.toBe 1)))))))

(describe "pick-session" (fn []

                           (let [sessions [{:path "/a/new.jsonl"  :name "new"}
                                           {:path "/a/mid.jsonl"  :name "mid"}
                                           {:path "/a/old.jsonl"  :name "old"}]]

                             (it "blank input picks the most recent (first)"
                                 (fn []
                                   (-> (expect (:path (pick-session sessions ""))) (.toBe "/a/new.jsonl"))
                                   (-> (expect (:path (pick-session sessions nil))) (.toBe "/a/new.jsonl"))))

                             (it "1-based number selects that session"
                                 (fn []
                                   (-> (expect (:path (pick-session sessions "2"))) (.toBe "/a/mid.jsonl"))
                                   (-> (expect (:path (pick-session sessions "3"))) (.toBe "/a/old.jsonl"))))

                             (it "out-of-range or non-numeric returns nil"
                                 (fn []
                                   (-> (expect (pick-session sessions "0")) (.toBeNil))
                                   (-> (expect (pick-session sessions "9")) (.toBeNil))
                                   (-> (expect (pick-session sessions "abc")) (.toBeNil))))

                             (it "empty session list returns nil"
                                 (fn []
                                   (-> (expect (pick-session [] "")) (.toBeNil)))))))
