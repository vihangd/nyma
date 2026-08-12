(ns session-partial.test
  "Everything in a session already survives a hard kill: appends are
   synchronous, one per message. The one exception was the assistant's own
   text, dispatched to the store only when the turn COMPLETES — so a crash
   mid-stream lost the whole response however long it had been running.

   These tests pin the checkpoint policy, the recovery, and the property that
   makes the sidecar safe: it never touches the append-only JSONL tree."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.sessions.partial :as p]
            [agent.sessions.manager :refer [create-session-manager session->seed-messages]]))

;;; ─── fakes ───────────────────────────────────────────────────────────────

(defn- fake-fs []
  (let [files (atom {})]
    {:files files
     :write-fn  (fn [pth s] (swap! files assoc pth s) nil)
     :remove-fn (fn [pth] (swap! files dissoc pth) nil)
     :read-fn   (fn [pth] (get @files pth))
     :exists-fn (fn [pth] (contains? @files pth))}))

(describe "sidecar-path"
          (fn []
            (it "sits next to the session file"
                (fn []
                  (-> (expect (p/sidecar-path "/x/1.jsonl")) (.toBe "/x/1.jsonl.partial"))))

            (it "is nil for an ephemeral session"
                (fn []
                  ;; A --no-session / print-mode run has nothing to protect.
                  (-> (expect (p/sidecar-path nil)) (.toBeNil))
                  (-> (expect (p/sidecar-path "")) (.toBeNil))))))

(describe "create-checkpoint"
          (fn []
            (it "writes the first delta immediately"
                (fn []
                  ;; A crash one delta into a turn should still leave that
                  ;; delta behind.
                  (let [{:keys [files write-fn remove-fn]} (fake-fs)
                        c (p/create-checkpoint "/s.jsonl"
                                               {:now-fn (fn [] 10000)
                                                :write-fn write-fn :remove-fn remove-fn})]
                    ((:note! c) "Hel")
                    (-> (expect (get @files "/s.jsonl.partial")) (.toBe "Hel")))))

            (it "throttles the writes in between"
                (fn []
                  ;; Hundreds of deltas per response; one sync write each would
                  ;; make every turn slower to protect text about to be
                  ;; superseded.
                  (let [{:keys [files write-fn remove-fn]} (fake-fs)
                        clock (atom 10000)
                        c (p/create-checkpoint "/s.jsonl"
                                               {:now-fn (fn [] @clock)
                                                :write-fn write-fn :remove-fn remove-fn})]
                    ((:note! c) "a")
                    (reset! clock 10100) ((:note! c) "ab")
                    (reset! clock 10200) ((:note! c) "abc")
                    (-> (expect (get @files "/s.jsonl.partial")) (.toBe "a"))
                    ;; …until the interval elapses.
                    (reset! clock 10500) ((:note! c) "abcd")
                    (-> (expect (get @files "/s.jsonl.partial")) (.toBe "abcd")))))

            (it "flush! forces whatever is pending out"
                (fn []
                  (let [{:keys [files write-fn remove-fn]} (fake-fs)
                        clock (atom 10000)
                        c (p/create-checkpoint "/s.jsonl"
                                               {:now-fn (fn [] @clock)
                                                :write-fn write-fn :remove-fn remove-fn})]
                    ((:note! c) "a")
                    (reset! clock 10050) ((:note! c) "ab")
                    ((:flush! c))
                    (-> (expect (get @files "/s.jsonl.partial")) (.toBe "ab")))))

            (it "commit! drops the sidecar once the real entry is stored"
                (fn []
                  (let [{:keys [files write-fn remove-fn]} (fake-fs)
                        c (p/create-checkpoint "/s.jsonl"
                                               {:now-fn (fn [] 10000)
                                                :write-fn write-fn :remove-fn remove-fn})]
                    ((:note! c) "text")
                    ((:commit! c))
                    (-> (expect (contains? @files "/s.jsonl.partial")) (.toBe false)))))

            (it "survives a write failure without taking the turn with it"
                (fn []
                  ;; A full disk must not cost the user the response the
                  ;; checkpoint exists to protect.
                  (let [c (p/create-checkpoint "/s.jsonl"
                                               {:now-fn (fn [] 10000)
                                                :write-fn (fn [_ _] (throw (js/Error. "ENOSPC")))
                                                :remove-fn (fn [_] nil)})]
                    ((:note! c) "text")
                    (-> (expect true) (.toBe true)))))

            (it "is inert for an ephemeral session"
                (fn []
                  (let [wrote (atom 0)
                        c (p/create-checkpoint nil
                                               {:now-fn (fn [] 10000)
                                                :write-fn (fn [_ _] (swap! wrote inc))
                                                :remove-fn (fn [_] nil)})]
                    ((:note! c) "text") ((:flush! c)) ((:commit! c))
                    (-> (expect @wrote) (.toBe 0)))))))

(describe "read-partial"
          (fn []
            (it "returns the text a crashed run left behind"
                (fn []
                  (let [{:keys [files read-fn exists-fn]} (fake-fs)]
                    (swap! files assoc "/s.jsonl.partial" "half an answer")
                    (-> (expect (p/read-partial "/s.jsonl" {:read-fn read-fn :exists-fn exists-fn}))
                        (.toBe "half an answer")))))

            (it "is nil when there is nothing to recover"
                (fn []
                  (let [{:keys [files read-fn exists-fn]} (fake-fs)
                        opts {:read-fn read-fn :exists-fn exists-fn}]
                    ;; No sidecar — the normal case, every clean exit.
                    (-> (expect (p/read-partial "/s.jsonl" opts)) (.toBeNil))
                    ;; Whitespace only is noise, not a lost turn.
                    (swap! files assoc "/s.jsonl.partial" "  \n ")
                    (-> (expect (p/read-partial "/s.jsonl" opts)) (.toBeNil)))))

            (it "is nil rather than throwing when the sidecar is unreadable"
                (fn []
                  (-> (expect (p/read-partial "/s.jsonl"
                                              {:exists-fn (fn [_] true)
                                               :read-fn (fn [_] (throw (js/Error. "EACCES")))}))
                      (.toBeNil))))))

(describe "append-partial"
          (fn []
            (it "adds the recovered response and marks it as cut off"
                (fn []
                  ;; Unmarked, the model would continue from a sentence it
                  ;; believes it finished.
                  (let [out (p/append-partial [{:role "user" :content "hi"}] "partial answer")]
                    (-> (expect (count out)) (.toBe 2))
                    (-> (expect (:role (last out))) (.toBe "assistant"))
                    (-> (expect (.includes (:content (last out)) "partial answer")) (.toBe true))
                    (-> (expect (.includes (:content (last out)) "cut off")) (.toBe true)))))

            (it "merges into a trailing assistant message instead of adding a second"
                (fn []
                  ;; Two assistant turns in a row is not a shape every provider
                  ;; accepts.
                  ;;
                  ;; The earlier version of this test asserted only that the
                  ;; partial was present, and passed against an implementation
                  ;; that REPLACED the existing content — silently discarding a
                  ;; completed response. Assert both halves survive.
                  (let [out (p/append-partial [{:role "user" :content "hi"}
                                               {:role "assistant" :content "COMPLETED"}]
                                              "more")
                        tail (:content (last out))]
                    (-> (expect (count out)) (.toBe 2))
                    (-> (expect (.includes tail "COMPLETED")) (.toBe true))
                    (-> (expect (.includes tail "more")) (.toBe true))
                    ;; …and in that order.
                    (-> (expect (< (.indexOf tail "COMPLETED") (.indexOf tail "more")))
                        (.toBe true)))))

            (it "leaves the conversation untouched with nothing to recover"
                (fn []
                  (let [msgs [{:role "user" :content "hi"}]]
                    (-> (expect (p/append-partial msgs nil)) (.toEqual msgs))
                    (-> (expect (p/append-partial msgs "")) (.toEqual msgs)))))))

;;; ─── against a real filesystem ───────────────────────────────────────────

(def ^:private tmp-files (atom []))

(defn- tmp-session []
  (let [pth (path/join (os/tmpdir)
                       (str "nyma-partial-test-"
                            (.slice (.toString (js/Math.random) 36) 2) ".jsonl"))]
    (swap! tmp-files conj pth)
    pth))

(afterEach (fn []
             (doseq [f @tmp-files]
               (try (fs/unlinkSync f) (catch :default _ nil))
               (try (fs/unlinkSync (str f ".partial")) (catch :default _ nil)))
             (reset! tmp-files [])))

(describe "round trip on a real filesystem"
          (fn []
            (it "a crashed stream is readable back, and a committed one is gone"
                (fn []
                  (let [sp (tmp-session)
                        c  (p/create-checkpoint sp)]
                    ((:note! c) "streaming text 漢字 🎉")
                    ((:flush! c))
                    ;; This is the crash: nothing else runs.
                    (-> (expect (p/read-partial sp)) (.toBe "streaming text 漢字 🎉"))
                    ;; A committed turn leaves nothing behind.
                    ((:commit! c))
                    (-> (expect (p/read-partial sp)) (.toBeNil)))))

            (it "never writes into the session JSONL itself"
                (fn []
                  ;; The JSONL is an append-only tree linked by :parent-id and
                  ;; walked leaf→root. A partial entry written into it would
                  ;; corrupt the chain every reader depends on — which is the
                  ;; whole reason this is a sidecar.
                  (let [sp (tmp-session)
                        c  (p/create-checkpoint sp)]
                    (fs/writeFileSync sp "{\"role\":\"user\",\"id\":\"a\"}\n" "utf8")
                    ((:note! c) "streaming")
                    ((:flush! c))
                    (-> (expect (str (fs/readFileSync sp "utf8")))
                        (.toBe "{\"role\":\"user\",\"id\":\"a\"}\n"))
                    (-> (expect (fs/existsSync (str sp ".partial"))) (.toBe true)))))

            (it "clear-partial! removes it after it has been folded in"
                (fn []
                  ;; Otherwise a resume that folds it in and then exits without
                  ;; a turn would fold the same text again next time.
                  (let [sp (tmp-session)
                        c  (p/create-checkpoint sp)]
                    ((:note! c) "x") ((:flush! c))
                    (p/clear-partial! sp)
                    (-> (expect (p/read-partial sp)) (.toBeNil)))))))

(describe "flush-all!"
          (fn []
            (it "writes out text the throttle was still holding"
                (fn []
                  ;; The exit paths call this. Without it, the last flush
                  ;; interval of a response is discarded on the way out for no
                  ;; benefit — the process is ending anyway.
                  (let [sp (tmp-session)
                        c  (p/create-checkpoint sp)]
                    ((:note! c) "first")          ;; written immediately
                    ((:note! c) "first second")   ;; throttled, still pending
                    (-> (expect (p/read-partial sp)) (.toBe "first"))
                    (p/flush-all!)
                    (-> (expect (p/read-partial sp)) (.toBe "first second")))))

            (it "is safe with nothing pending"
                (fn []
                  (p/flush-all!)
                  (-> (expect true) (.toBe true))))))

;;; ─── recovery must be durable ────────────────────────────────────────────
;;; The resume path folded the sidecar into `:messages` and deleted it in the
;;; same expression. Seeding uses `swap!` (deliberately, so nothing
;;; re-appends), so the recovered turn lived only in that session's memory: the
;;; next user message linked to the pre-crash leaf, and every later resume
;;; silently dropped the response. Recovery has to reach disk.

(describe "a recovered partial survives the next resume"
          (fn []
            (it "is on disk after being folded in"
                (fn []
                  (let [sp (tmp-session)
                        mgr (create-session-manager sp)]
                    ((:append mgr) {:role "user" :content "do the thing"})
                    ;; crash mid-stream
                    (let [c (p/create-checkpoint sp)]
                      ((:note! c) "half an answer")
                      ((:flush! c)))
                    ;; what the resume path does
                    (let [recovered (p/read-partial sp)]
                      (-> (expect recovered) (.toBeTruthy))
                      ((:append mgr) {:role "assistant" :content (p/mark-cutoff recovered)})
                      (p/clear-partial! sp))
                    ;; a LATER resume, fresh manager, sidecar long gone
                    (let [again (create-session-manager sp)
                          _     ((:load again))
                          ctx   (session->seed-messages ((:build-context again)))
                          text  (.join (to-array (mapv (fn [m] (str (:content m))) ctx)) " ")]
                      (-> (expect (count ctx)) (.toBe 2))
                      (-> (expect (:role (last ctx))) (.toBe "assistant"))
                      (-> (expect (.includes text "half an answer")) (.toBe true))
                      (-> (expect (.includes text "cut off")) (.toBe true))))))))
