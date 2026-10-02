(ns session-fork-test
  "`/fork` has to actually diverge.

   It used to read the CURRENT leaf and hand it to `branch-fn`, which is
   `reset!` of `leaf-id` to the value it already held. So it emitted
   `session_before_fork`, emitted `session_start`, printed \"Session forked\" —
   and the next message appended to exactly the same branch. Every observable
   signal said it worked.

   That is why the assertions here are about the TREE and not about the events:
   the events were never the thing that was broken. A test that checked them
   would have passed throughout."
  (:require ["bun:test" :refer [test expect describe beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.sessions.manager :as sm]))

(def tmp (atom nil))

(defn- mk-session []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-fork-"))]
    (reset! tmp dir)
    (sm/create-session-manager (path/join dir "session.jsonl"))))

(describe "fork-candidates"
          (fn []
            (test "offers the earlier user messages, oldest first"
                  (fn []
                    (let [s (mk-session)]
                      ((:append s) {:role "user" :content "first question"})
                      ((:append s) {:role "assistant" :content "an answer"})
                      ((:append s) {:role "user" :content "second question"})
                      ((:append s) {:role "assistant" :content "another answer"})
                      ((:append s) {:role "user" :content "third question"})
                      (let [cs ((:fork-candidates s))]
                        (-> (expect (mapv :label cs))
                            (.toEqual (clj->js ["first question" "second question"])))))))

            (test "excludes the current leaf — branching at the tip is a no-op"
                  (fn []
                    (let [s (mk-session)]
                      ((:append s) {:role "user" :content "only message"})
          ;; The tip IS this message, so there is nothing to branch from.
                      (-> (expect (count ((:fork-candidates s)))) (.toBe 0)))))

            (test "skips assistant and tool entries"
                  (fn []
                    (let [s (mk-session)]
                      ((:append s) {:role "user" :content "u1"})
                      ((:append s) {:role "assistant" :content "a1"})
                      ((:append s) {:role "tool_result" :content "t1"})
                      ((:append s) {:role "user" :content "u2"})
                      (-> (expect (mapv :role ((:fork-candidates s)))) (.toEqual (clj->js [])))
                      (-> (expect (mapv :label ((:fork-candidates s)))) (.toEqual (clj->js ["u1"]))))))

            (test "collapses whitespace and truncates a long label to one line"
                  (fn []
                    (let [s (mk-session)]
                      ((:append s) {:role "user" :content (str "a\n\n   b" (.repeat "x" 200))})
                      ((:append s) {:role "user" :content "tip"})
                      (let [l (:label (first ((:fork-candidates s))))]
                        (-> (expect (.includes l "\n")) (.toBe false))
                        (-> (expect (count l)) (.toBeLessThanOrEqual 72))
                        (-> (expect (.startsWith l "a b")) (.toBe true))))))))

(describe "branching diverges"
          (fn []
            (test "a message after the branch does NOT extend the abandoned branch"
                  (fn []
                    (let [s (mk-session)]
                      ((:append s) {:role "user" :content "shared"})
                      ((:append s) {:role "assistant" :content "reply A"})
                      ((:append s) {:role "user" :content "follow A"})
                      (let [tip-a ((:leaf-id s))
                            fork-point (:id (first ((:fork-candidates s))))]
            ;; Branch back to "shared", then say something else.
                        ((:branch s) fork-point)
                        ((:append s) {:role "assistant" :content "reply B"})
                        (let [tip-b ((:leaf-id s))
                              entries ((:get-entries s))
                              by-id (into {} (map (juxt :id identity) entries))]
              ;; THE assertion the old /fork could never have passed: the new
              ;; entry's parent is the fork point, not the old tip.
                          (-> (expect (:parent-id (get by-id tip-b))) (.toBe fork-point))
                          (-> (expect tip-b) (.not.toBe tip-a))
              ;; Both branches survive — nothing was erased.
                          (-> (expect (some? (get by-id tip-a))) (.toBe true))
              ;; And the active context is the new branch, not the old one.
                          (let [ctx (map :content ((:get-branch s)))]
                            (-> (expect (clj->js (vec ctx))) (.toContain "reply B"))
                            (-> (expect (clj->js (vec ctx))) (.not.toContain "follow A"))))))))

            (test "branching at the leaf changes nothing — which is why it is excluded"
                  (fn []
                    (let [s (mk-session)]
                      ((:append s) {:role "user" :content "u1"})
                      ((:append s) {:role "assistant" :content "a1"})
                      (let [leaf ((:leaf-id s))]
            ;; Exactly what the old /fork did.
                        ((:branch s) leaf)
                        ((:append s) {:role "user" :content "u2"})
                        (let [by-id (into {} (map (juxt :id identity) ((:get-entries s))))]
              ;; The new entry hangs off the same old tip: no divergence.
                          (-> (expect (:parent-id (get by-id ((:leaf-id s))))) (.toBe leaf)))))))))

(afterEach
 (fn []
   (when-let [d @tmp]
     (try (fs/rmSync d #js {:recursive true :force true}) (catch :default _e nil))
     (reset! tmp nil))))
