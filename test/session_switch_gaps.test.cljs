(ns session-switch-gaps.test
  "Switching what the session points at has to move everything that describes
   it. Four places it did not.

   The session identifier used for SQLite rows was captured once at startup
   while `:switch-file` reset a different atom, so after /resume every usage
   and entry row was filed under the session the process STARTED in. The RPC
   mode switched the file and nothing else. Branch navigation moved the leaf
   without reseeding what the model sees. And the compaction bookkeeping
   outlived the messages it described."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.state :as state]
            [agent.sessions.manager :refer [create-session-manager]]))

(def ^:private dirs (atom []))

(defn- tmp-session []
  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-switch-"))]
    (swap! dirs conj d)
    (path/join d "a.jsonl")))

(afterEach (fn []
             (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
             (reset! dirs [])
             nil))

(defn- test-session-file-follows-switch []
  (let [a  (tmp-session)
        b  (path/join (path/dirname a) "b.jsonl")
        sm (create-session-manager a)]
    (-> (expect ((:session-file sm))) (.toBe a))
    ((:switch-file sm) b)
    ;; this used to still answer `a`, so every usage row for session b was
    ;; filed under a and b's cost read zero forever
    (-> (expect ((:session-file sm))) (.toBe b))
    (-> (expect ((:get-file-path sm))) (.toBe b))))

(defn- test-explicit-override-still-wins []
  (let [a  (tmp-session)
        sm (create-session-manager a {:session-file "pinned"})]
    ((:switch-file sm) (path/join (path/dirname a) "b.jsonl"))
    (-> (expect ((:session-file sm))) (.toBe "pinned"))))

;;; ─── compaction bookkeeping ───────────────────────────────────

(defn- test-clear-resets-compaction-counter []
  ;; the gate is (count messages) - compacted-at-count >= 30, so a stale count
  ;; went negative after a clear and switched auto-compaction off entirely
  (let [reducer (:messages-cleared state/core-reducers)
        out     (reducer {:messages (vec (repeat 50 {:role "user"}))
                          :compacted-at-count 50
                          :last-input-tokens 120000}
                         {})]
    (-> (expect (:compacted-at-count out)) (.toBe 0))
    (-> (expect (:last-input-tokens out)) (.toBe 0))
    (-> (expect (count (:messages out))) (.toBe 0))))

(defn- test-replace-rebaselines-counter []
  (let [reducer (:messages-replaced state/core-reducers)
        out     (reducer {:messages (vec (repeat 50 {:role "user"}))
                          :compacted-at-count 50}
                         {:messages (vec (repeat 5 {:role "user"}))})]
    ;; never more than the list it describes
    (-> (expect (:compacted-at-count out)) (.toBe 5))))

(defn- test-replace-keeps-a-smaller-baseline []
  (let [reducer (:messages-replaced state/core-reducers)
        out     (reducer {:messages (vec (repeat 50 {:role "user"}))
                          :compacted-at-count 3}
                         {:messages (vec (repeat 40 {:role "user"}))})]
    (-> (expect (:compacted-at-count out)) (.toBe 3))))

(describe "the session identifier follows the session"
          (fn []
            (it "switch-file moves it" test-session-file-follows-switch)
            (it "an explicit override still wins" test-explicit-override-still-wins)))

(describe "compaction bookkeeping goes with its messages"
          (fn []
            (it "clearing resets the counter and the token count" test-clear-resets-compaction-counter)
            (it "replacing rebaselines the counter" test-replace-rebaselines-counter)
            (it "replacing never raises the counter" test-replace-keeps-a-smaller-baseline)))
