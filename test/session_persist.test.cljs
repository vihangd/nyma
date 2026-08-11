(ns session-persist.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.cli :refer [pick-session]]
            [agent.state :refer [create-agent-store]]
            [agent.sessions.manager :refer [create-session-manager attach-session-persistence!]]
            [agent.sessions.partial :refer [read-partial]]
            [agent.events :refer [create-event-bus]]
            [agent.modes.interactive :refer [pane-messages]]))

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

;;; ─── streamed text durability ────────────────────────────────────────────
;;; Every other message reaches disk synchronously as it is added. The
;;; assistant's own text did not: it is dispatched to the store only when the
;;; turn COMPLETES, so a crash mid-stream lost the entire response. The
;;; checkpoint closes that, and this asserts it is actually wired into
;;; attach-session-persistence! rather than merely available.

(describe "attach-session-persistence! checkpoints the streaming response"
          (fn []
            (it "writes the partial response to a sidecar as deltas arrive"
                (fn []
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)
                        agent  {:store store :events events}]
                    (attach-session-persistence! agent mgr)
                    ((:dispatch! store) :message-added {:message {:role "user" :content "q"}})
                    ;; The stream begins. Nothing has completed.
                    ((:emit events) "message_update" #js {:textDelta "Once upon "})
                    ((:emit events) "message_update" #js {:textDelta "a time"})
                    ;; This is the crash: the turn never completes.
                    ;;
                    ;; Asserted as a PREFIX, not the full text. Writes are
                    ;; throttled — a response streams hundreds of deltas and a
                    ;; synchronous write per delta would slow every turn to
                    ;; protect text about to be superseded. So the guarantee
                    ;; is "the response so far, minus at most the last flush
                    ;; interval", against a previous guarantee of nothing.
                    (let [recovered (read-partial fp)]
                      (-> (expect recovered) (.toBeTruthy))
                      (-> (expect (.startsWith "Once upon a time" recovered)) (.toBe true)))
                    ;; And the JSONL is untouched by it — only the user turn.
                    (-> (expect (count (read-lines fp))) (.toBe 1)))))

            (it "drops the sidecar once the real assistant entry is stored"
                (fn []
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)
                        agent  {:store store :events events}]
                    (attach-session-persistence! agent mgr)
                    ((:emit events) "message_update" #js {:textDelta "hello"})
                    (-> (expect (read-partial fp)) (.toBe "hello"))
                    ((:dispatch! store) :message-added {:message {:role "assistant" :content "hello world"}})
                    ;; The checkpoint has nothing left to protect.
                    (-> (expect (read-partial fp)) (.toBeNil))
                    (-> (expect (.-content (first (read-lines fp)))) (.toBe "hello world")))))

            (it "still works for a session with no event bus"
                (fn []
                  ;; sdk/gateway callers pass an agent without :events.
                  (let [fp    (path/join @test-dir "s.jsonl")
                        _     (fs/writeFileSync fp "")
                        mgr   (create-session-manager fp)
                        store (create-agent-store {:messages []})]
                    (attach-session-persistence! {:store store} mgr)
                    ((:dispatch! store) :message-added {:message {:role "user" :content "q"}})
                    (-> (expect (count (read-lines fp))) (.toBe 1)))))))

(describe "checkpoint turn boundaries"
          (fn []
            (it "does not clear mid-turn when a STEP finishes"
                (fn []
                  ;; "turn_end" comes from the SDK's :onStepFinish, so it fires
                  ;; once per step — several times inside any tool-using
                  ;; response. Clearing on it would delete the sidecar exactly
                  ;; when it is protecting something.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:emit events) "message_update" #js {:textDelta "thinking about it"})
                    ((:emit events) "turn_end" #js {:step 1})
                    ((:emit events) "turn_end" #js {:step 2})
                    ;; Still there — the response has not been stored yet.
                    (-> (expect (read-partial fp)) (.toBeTruthy)))))

            (it "starts clean on the next user message"
                (fn []
                  ;; Otherwise an unfinished turn's text gets prefixed onto the
                  ;; next response, and a later crash resurrects text the user
                  ;; already moved on from.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)
                        clock  (atom 0)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:emit events) "message_update" #js {:textDelta "ABANDONED"})
                    (-> (expect (read-partial fp)) (.toBeTruthy))
                    ;; The turn never completed; the user types again.
                    ((:dispatch! store) :message-added {:message {:role "user" :content "never mind"}})
                    (-> (expect (read-partial fp)) (.toBeNil))
                    ;; And the new response carries none of the old text.
                    ((:emit events) "message_update" #js {:textDelta "FRESH"})
                    (let [recovered (str (read-partial fp))]
                      (-> (expect (.includes recovered "FRESH")) (.toBe true))
                      (-> (expect (.includes recovered "ABANDONED")) (.toBe false))))))))

(describe "a /resume replay must not write anything"
          (fn []
            (it "neither re-appends nor drops a live checkpoint"
                (fn []
                  ;; /resume sets :replaying-session? then dispatches
                  ;; :message-added for every seeded message, so persistence
                  ;; must sit the whole thing out. The append guard has always
                  ;; been there; the checkpoint branches added later sit inside
                  ;; the same `when`, and if they ever escaped it a replayed
                  ;; user message would unlink a sidecar holding a live partial
                  ;; response — silent data loss.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ;; A response is mid-flight and checkpointed.
                    ((:emit events) "message_update" #js {:textDelta "in flight"})
                    (-> (expect (read-partial fp)) (.toBeTruthy))
                    (let [before (str (fs/readFileSync fp "utf8"))]
                      ;; The replay, exactly as /resume performs it.
                      ((:swap store) (fn [st] (assoc st :replaying-session? true)))
                      ((:dispatch! store) :message-added
                       {:message {:role "user" :content "replayed"}})
                      ((:dispatch! store) :message-added
                       {:message {:role "assistant" :content "replayed too"}})
                      ((:swap store) (fn [st] (assoc st :replaying-session? false)))
                      ;; Nothing appended…
                      (-> (expect (str (fs/readFileSync fp "utf8"))) (.toBe before))
                      ;; …and the in-flight response is still protected.
                      (-> (expect (read-partial fp)) (.toBeTruthy))))))))

;;; ─── a turn that ends without storing a response ─────────────────────────
;;; The assistant message is dispatched at exactly one place in the loop (the
;;; final-text path). A provider error or a user interrupt throws out of the
;;; stream into the loop's catch, and retry-exhaustion emits agent_end carrying
;;; the accumulated text and drops it — so a response the user WATCHED ARRIVE
;;; was never recorded. Measured on real sessions before this: seven user
;;; messages, two assistant messages.

(describe "an interrupted turn still records its response"
          (fn []
            (it "writes the streamed text when nothing else stored it"
                (fn []
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:dispatch! store) :message-added {:message {:role "user" :content "q"}})
                    ((:emit events) "message_update" #js {:textDelta "partial answer"})
                    ;; The turn dies here — no assistant :message-added ever comes.
                    ((:emit events) "turn_finalize" #js {:error true})
                    (let [lines (read-lines fp)]
                      (-> (expect (count lines)) (.toBe 2))
                      (-> (expect (.-role (nth lines 1))) (.toBe "assistant"))
                      (-> (expect (.includes (.-content (nth lines 1)) "partial answer"))
                          (.toBe true))))))

            (it "marks it as cut off"
                (fn []
                  ;; Unmarked, the model resumes from a sentence it believes it
                  ;; finished.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:emit events) "message_update" #js {:textDelta "half a thought"})
                    ((:emit events) "turn_finalize" #js {:error true})
                    (-> (expect (.includes (.-content (first (read-lines fp))) "cut off"))
                        (.toBe true)))))

            (it "does NOT double-write when the turn completed normally"
                (fn []
                  ;; The ordering this design rests on: the assistant
                  ;; :message-added fires BEFORE turn_finalize and resets the
                  ;; accumulator, so a non-empty accumulator at finalize means
                  ;; nothing was stored. If that order ever reversed, every
                  ;; normal turn would be written twice — which is why this
                  ;; test exists rather than a comment.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:emit events) "message_update" #js {:textDelta "hello "})
                    ((:emit events) "message_update" #js {:textDelta "world"})
                    ((:dispatch! store) :message-added
                     {:message {:role "assistant" :content "hello world"}})
                    ((:emit events) "turn_finalize" #js {:error false})
                    (let [lines (read-lines fp)]
                      (-> (expect (count lines)) (.toBe 1))
                      (-> (expect (.-content (first lines))) (.toBe "hello world"))))))

            (it "writes nothing for a turn that produced no text"
                (fn []
                  ;; A tool-only step must not leave an empty assistant entry.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:emit events) "turn_finalize" #js {:error false})
                    ((:emit events) "message_update" #js {:textDelta "   "})
                    ((:emit events) "turn_finalize" #js {:error false})
                    (-> (expect (count (read-lines fp))) (.toBe 0)))))

            (it "clears the sidecar once the interrupted text is on disk"
                (fn []
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:emit events) "message_update" #js {:textDelta "streamed"})
                    (-> (expect (read-partial fp)) (.toBeTruthy))
                    ((:emit events) "turn_finalize" #js {:error true})
                    ;; The real entry exists now, so the checkpoint is spent.
                    (-> (expect (read-partial fp)) (.toBeNil)))))

            (it "shows up in the resumed transcript"
                (fn []
                  ;; The user-visible claim: interrupt a turn, resume, see the
                  ;; response.
                  (let [fp     (path/join @test-dir "s.jsonl")
                        _      (fs/writeFileSync fp "")
                        mgr    (create-session-manager fp)
                        store  (create-agent-store {:messages []})
                        events (create-event-bus)]
                    (attach-session-persistence! {:store store :events events} mgr)
                    ((:dispatch! store) :message-added {:message {:role "user" :content "ask"}})
                    ((:emit events) "message_update" #js {:textDelta "the answer so far"})
                    ((:emit events) "turn_finalize" #js {:error true})
                    ;; Reload from scratch, exactly as a resume does.
                    (let [fresh (create-session-manager fp)
                          _     ((:load fresh))
                          shown (pane-messages fresh)]
                      (-> (expect (count shown)) (.toBe 2))
                      (-> (expect (:role (last shown))) (.toBe "assistant"))
                      (-> (expect (.includes (:content (last shown)) "the answer so far"))
                          (.toBe true))))))))
