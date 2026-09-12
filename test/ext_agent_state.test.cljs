(ns ext-agent-state.test
  "Reporting nyma's lifecycle to a supervisor that is running it.

   herdr — a terminal multiplexer that runs several coding agents at once —
   asks agents to report `idle` / `working` / `blocked` by running a CLI
   command, and explicitly supports agents it has never heard of
   (`--source custom:<name>`). An agent that reports becomes a 'lifecycle
   authority' and herdr stops guessing its state from the screen.

   Written as a generic reporter, so the tests cover the argv template as a
   primitive and herdr as one detected default.

   The case that matters most is the one where nothing is configured: every
   user who has never heard of herdr runs that path, and it must subscribe
   nothing and spawn nothing."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.agent-state.index :as ast
             :refer [expand detect-herdr resolve-command]]))

;;; ─── argv template ──────────────────────────────────────────

(describe "expand"
          (fn []
            (it "substitutes every placeholder"
                (fn []
                  (-> (expect (expand ["x" "--state" "{state}" "--seq" "{seq}"]
                                      {:state "working" :seq 3}))
                      (.toEqual #js ["x" "--state" "working" "--seq" "3"]))))

            (it "drops a flag whose value resolves to nothing"
                (fn []
                  ;; A missing --message must not become an empty argument the
                  ;; receiving CLI has to reject.
                  (-> (expect (expand ["x" "--state" "{state}" "--message" "{message}"]
                                      {:state "idle" :message nil}))
                      (.toEqual #js ["x" "--state" "idle"]))))

            (it "keeps a message when there is one"
                (fn []
                  (-> (expect (expand ["x" "--message" "{message}"] {:message "approve edit?"}))
                      (.toEqual #js ["x" "--message" "approve edit?"]))))

            (it "leaves literal arguments alone"
                (fn []
                  (-> (expect (expand ["herdr" "pane" "report-agent" "p1"] {:state "idle"}))
                      (.toEqual #js ["herdr" "pane" "report-agent" "p1"]))))))

;;; ─── detection ──────────────────────────────────────────────

(defn- with-env [pairs f]
  (let [saved (mapv (fn [[k _]] [k (aget js/process.env k)]) pairs)]
    (doseq [[k v] pairs]
      (if (nil? v) (js-delete js/process.env k) (aset js/process.env k v)))
    (try (f)
         (finally
           (doseq [[k v] saved]
             (if (undefined? v) (js-delete js/process.env k) (aset js/process.env k v)))))))

(describe "detect-herdr"
          (fn []
            (it "builds the herdr argv when both env vars are present"
                (fn []
                  (with-env [["HERDR_BIN_PATH" "/usr/bin/herdr"] ["HERDR_PANE_ID" "pane-7"]]
                    (fn []
                      (let [t (detect-herdr "nyma")]
                        (-> (expect (vec (:report t)))
                            (.toEqual #js ["/usr/bin/herdr" "pane" "report-agent" "pane-7"
                                           "--source" "custom:nyma" "--agent" "nyma"
                                           "--state" "{state}" "--seq" "{seq}"]))
                        (-> (expect (vec (take 3 (:release t))))
                            (.toEqual #js ["/usr/bin/herdr" "pane" "release-agent"])))))))

            (it "is nil when either variable is missing"
                (fn []
                  ;; One without the other is not a herdr session, and guessing
                  ;; would mean spawning a command that does not exist.
                  (with-env [["HERDR_BIN_PATH" "/usr/bin/herdr"] ["HERDR_PANE_ID" nil]]
                    (fn [] (-> (expect (detect-herdr "nyma")) (.toBeNil))))
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" "pane-7"]]
                    (fn [] (-> (expect (detect-herdr "nyma")) (.toBeNil))))))))

(describe "resolve-command"
          (fn []
            (it "prefers an explicitly configured command"
                (fn []
                  (with-env [["HERDR_BIN_PATH" "/usr/bin/herdr"] ["HERDR_PANE_ID" "p"]]
                    (fn []
                      (-> (expect (vec (:report (resolve-command
                                                 {:command ["mine" "{state}"]} "nyma"))))
                          (.toEqual #js ["mine" "{state}"]))))))

            (it "is nil with nothing configured and nothing detected"
                (fn []
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" nil]]
                    (fn [] (-> (expect (resolve-command {} "nyma")) (.toBeNil))))))

            (it "is nil when explicitly disabled, even inside herdr"
                (fn []
                  (with-env [["HERDR_BIN_PATH" "/usr/bin/herdr"] ["HERDR_PANE_ID" "p"]]
                    (fn [] (-> (expect (resolve-command {:enabled false} "nyma"))
                               (.toBeNil))))))))

;;; ─── activation ─────────────────────────────────────────────

(defn- fake-api [settings]
  (let [handlers (atom {}) spawned (atom [])]
    {:api #js {:on          (fn [e h] (swap! handlers update e (fnil conj []) h) nil)
               :off         (fn [e h] (swap! handlers update e (fn [hs] (vec (remove #(= % h) hs)))) nil)
               :getSettings (fn [] (clj->js settings))
               :spawn       (fn [cmd args _] (swap! spawned conj (into [cmd] (vec args))) nil)}
     :handlers handlers
     :spawned  spawned
     :fire (fn [e data] (mapv (fn [h] (h data)) (get @handlers e)))}))

(describe "agent-state activation"
          (fn []
            (it "subscribes nothing and spawns nothing when unconfigured"
                (fn []
                  ;; The default path for everyone not running under a supervisor.
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" nil]]
                    (fn []
                      (let [{:keys [api handlers spawned]} (fake-api {})
                            stop (ast/activate api)]
                        (-> (expect (count @handlers)) (.toBe 0))
                        (-> (expect (count @spawned)) (.toBe 0))
                        (stop))))))

            (it "reports working, blocked and idle across a turn"
                (fn []
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" nil]]
                    (fn []
                      (let [{:keys [api spawned fire]}
                            (fake-api {"agent-state"
                                       {"command" ["sup" "--state" "{state}"
                                                   "--message" "{message}"]}})
                            stop (ast/activate api)]
                                ;; Activation reports idle before the first prompt.
                        (-> (expect (vec (first @spawned)))
                            (.toEqual #js ["sup" "--state" "idle"]))
                        (fire "agent_start" #js {})
                        (fire "permission_request" #js {:toolName "bash"})
                        (fire "agent_end" #js {})
                        (let [got (mapv (fn [a] (nth a 2)) @spawned)]
                          (-> (expect got) (.toEqual #js ["idle" "working" "blocked" "idle"])))
                                ;; The blocked report says what it is blocked on.
                        (-> (expect (vec (nth @spawned 2)))
                            (.toContain "bash"))
                        (stop))))))

            (it "releases on shutdown and unsubscribes"
                (fn []
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" nil]]
                    (fn []
                      (let [{:keys [api handlers spawned]}
                            (fake-api {"agent-state" {"command" ["sup" "{state}"]}})
                            stop (ast/activate api)]
                        (-> (expect (count (get @handlers "agent_start"))) (.toBe 1))
                        (stop)
                        (-> (expect (count (get @handlers "agent_start"))) (.toBe 0))
                                ;; Teardown reports too — a pane whose agent vanished
                                ;; without releasing stays marked busy forever.
                        (-> (expect (count @spawned)) (.toBeGreaterThan 1)))))))

            (it "reports release once, however many teardown events fire"
                (fn []
                  ;; session_shutdown, session_end and the deactivate thunk all
                  ;; fire on a normal exit. Measured before the latch: three
                  ;; release spawns for one teardown.
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" nil]]
                    (fn []
                      (let [{:keys [api spawned fire]}
                            (fake-api {"agent-state" {"command" ["sup" "{state}"]}})
                            stop (ast/activate api)
                            before (count @spawned)]
                        (fire "session_shutdown" #js {})
                        (fire "session_end" #js {})
                        (stop)
                        (-> (expect (- (count @spawned) before)) (.toBe 1)))))))

            (it "a spawn that throws does not escape the handler"
                (fn []
                  ;; A supervisor that is gone or broken must never fail a turn.
                  (with-env [["HERDR_BIN_PATH" nil] ["HERDR_PANE_ID" nil]]
                    (fn []
                      (let [handlers (atom {})
                            api #js {:on  (fn [e h] (swap! handlers update e (fnil conj []) h) nil)
                                     :off (fn [_ _] nil)
                                     :getSettings (fn [] (clj->js {"agent-state" {"command" ["boom"]}}))
                                     :spawn (fn [& _] (throw (js/Error. "ENOENT")))}
                            stop (ast/activate api)]
                        (-> (expect (fn [] ((first (get @handlers "agent_start")) #js {})))
                            (.not.toThrow))
                        (stop))))))))
