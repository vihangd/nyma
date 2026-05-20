(ns ext-agent-shell-api.test
  "Unit tests for agent_shell.api/run-prompt and the composite-key pool.

   We don't spawn real ACP processes here — those are exercised by the e2e
   suite. Instead we register a fake agent that uses `:create-fn` to bypass
   subprocess spawning and produce a fully-mocked connection in memory."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.agent-shell.api :as as-api]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]))

(defn- fake-conn
  "Hand-build a conn map that looks like one create-connection would return,
   minus the actual subprocess. Resolves the run-prompt request promise to a
   recorded outcome after firing the supplied on-stream callback (if any)."
  [agent-key cwd outcome-atom stream-chunks]
  (let [p-key  (shared/pool-key agent-key cwd)
        callbacks (atom nil)]
    {:proc         #js {}
     :stdin        nil
     :stdout       nil
     :stderr       nil
     :project-root cwd
     :pool-key     p-key
     :agent-key    agent-key
     :state        (atom {:pending {} :terminals {}})
     :prompt-state (atom {:text "" :tool-calls []})
     :callbacks    callbacks
     :id-counter   (atom 0)
     :session-id   (atom "fake-session")
     ;; Override send-prompt-via-client by exposing a sdk-query short-circuit.
     ;; Pool's get-or-create doesn't care; client/send-prompt does — see
     ;; in-process branch at acp/client.cljs send-prompt.
     :in-process?  true
     :sdk-query    (fn [conn prompt-text]
                     ;; Fire stream chunks via the per-conn callback
                     (doseq [c stream-chunks]
                       (when-let [cb (and @(:callbacks conn) (:on-stream @(:callbacks conn)))]
                         (cb c)))
                     (reset! outcome-atom {:prompt prompt-text
                                           :cwd    cwd
                                           :agent  agent-key})
                     (js/Promise.resolve {:text        (apply str stream-chunks)
                                          :stop-reason "end_turn"
                                          :usage       {:input-tokens 1 :output-tokens 1}
                                          :tool-calls  []}))}))

(defn- register-fake-agent!
  "Register an `:in-process?` agent whose :create-fn returns a fake conn."
  [agent-key outcome-atom stream-chunks]
  (registry/register-agent!
   agent-key
   {:command     "fake"
    :in-process? true
    :create-fn   (fn [k _def _api cwd]
                   (js/Promise.resolve (fake-conn k cwd outcome-atom stream-chunks)))}))

(beforeEach
 (fn []
   (reset! shared/connections {})
   (registry/reset-dynamic!)))

;;; ─── pool composite key ──────────────────────────────────────

(describe "shared/pool-key"
          (fn []
            (it "composes agent-key and resolved cwd into a stable string"
                (fn []
                  (let [k (shared/pool-key "claude" "/tmp/a")]
                    (-> (expect (.startsWith k "claude@/")) (.toBe true))
                    (-> (expect (.endsWith k "/tmp/a")) (.toBe true)))))

            (it "different cwds produce different keys"
                (fn []
                  (let [k1 (shared/pool-key "claude" "/tmp/a")
                        k2 (shared/pool-key "claude" "/tmp/b")]
                    (-> (expect (= k1 k2)) (.toBe false)))))))

(describe "shared/find-conn-by-agent"
          (fn []
            (it "returns nil when no connection exists for the agent"
                (fn []
                  (reset! shared/connections {})
                  (-> (expect (some? (shared/find-conn-by-agent "claude")))
                      (.toBe false))))

            (it "returns any conn matching the agent prefix (cwd-agnostic)"
                (fn []
                  (let [c1 {:dummy :one}
                        c2 {:dummy :two}]
                    (reset! shared/connections
                            {(shared/pool-key "claude" "/tmp/a") c1
                             (shared/pool-key "gemini" "/tmp/a") c2})
                    (let [hit (shared/find-conn-by-agent "claude")]
                      (-> (expect (some? hit)) (.toBe true))
                      (-> (expect (= hit c1)) (.toBe true))))))))

;;; ─── api/run-prompt ──────────────────────────────────────────

(describe "agent-shell.api/run-prompt"
          (fn []
            (it "rejects unknown agents"
                (fn []
                  (-> (as-api/run-prompt #js {}
                                         {:agent "no-such-agent" :prompt "hi"})
                      (.then (fn [_] (throw (js/Error. "should have rejected")))
                             (fn [e]
                               (-> (expect (.includes (.-message e) "Unknown agent"))
                                   (.toBe true)))))))

            (it "rejects blank prompts"
                (fn []
                  (register-fake-agent! :fake (atom nil) [])
                  (-> (as-api/run-prompt #js {} {:agent "fake" :prompt ""})
                      (.then (fn [_] (throw (js/Error. "should have rejected")))
                             (fn [e]
                               (-> (expect (.includes (.-message e) "prompt is required"))
                                   (.toBe true)))))))

            (it "spawns one pool entry per (agent, cwd) pair"
                (fn []
                  (let [outcome (atom nil)]
                    (register-fake-agent! :fake outcome ["done"])
                    (-> (js/Promise.all
                         #js [(as-api/run-prompt #js {} {:agent "fake" :cwd "/tmp/a"
                                                         :prompt "first"})
                              (as-api/run-prompt #js {} {:agent "fake" :cwd "/tmp/b"
                                                         :prompt "second"})])
                        (.then (fn [_]
                       ;; Two distinct entries in the pool
                                 (-> (expect (count @shared/connections)) (.toBe 2))
                                 (-> (expect (some? (get @shared/connections
                                                         (shared/pool-key "fake" "/tmp/a"))))
                                     (.toBe true))
                                 (-> (expect (some? (get @shared/connections
                                                         (shared/pool-key "fake" "/tmp/b"))))
                                     (.toBe true))))))))

            (it "reuses the same connection for repeated (agent, cwd) hits"
                (fn []
                  (let [outcome (atom nil)]
                    (register-fake-agent! :fake outcome ["ok"])
                    (-> (as-api/run-prompt #js {} {:agent "fake" :cwd "/tmp/x"
                                                   :prompt "first"})
                        (.then (fn [_]
                                 (as-api/run-prompt #js {} {:agent "fake" :cwd "/tmp/x"
                                                            :prompt "second"})))
                        (.then (fn [_]
                                 (-> (expect (count @shared/connections)) (.toBe 1))))))))

            (it "serializes concurrent calls on the same (agent, cwd) so callbacks don't race"
                (fn []
        ;; Two concurrent run-prompt calls on the same (agent, cwd) must not
        ;; clobber each other's :on-stream — ACP sessions support only one
        ;; in-flight prompt anyway, but more importantly the per-conn
        ;; :callbacks atom is single-valued. Without serialization the second
        ;; call's reset! erases the first's callback before the first finishes.
                  (let [outcome (atom nil)
                        a-chunks (atom [])
                        b-chunks (atom [])
              ;; The fake :sdk-query fires its chunks synchronously; to actually
              ;; *prove* serialization we need a fake whose chunks fire after a
              ;; small async delay. Register a fake agent that delays before
              ;; emitting and resolving.
                        _ (registry/register-agent!
                           :slow
                           {:command "fake" :in-process? true
                            :create-fn
                            (fn [k _def _api cwd]
                              (let [callbacks (atom nil)
                                    p-key     (shared/pool-key k cwd)]
                                (js/Promise.resolve
                                 {:proc #js {} :stdin nil :stdout nil :stderr nil
                                  :project-root cwd :pool-key p-key :agent-key k
                                  :state (atom {:pending {} :terminals {}})
                                  :prompt-state (atom {:text "" :tool-calls []})
                                  :callbacks callbacks
                                  :id-counter (atom 0)
                                  :session-id (atom "fake")
                                  :in-process? true
                                  :sdk-query
                                  (fn [conn prompt-text]
                                    (js/Promise.
                                     (fn [resolve _]
                                       (js/setTimeout
                                        (fn []
                                    ;; Read the callback AT EMIT TIME — not at
                                    ;; entry. If a second call clobbered the
                                    ;; callbacks atom before this resolves, the
                                    ;; first prompt's chunk would route to the
                                    ;; wrong sink.
                                          (when-let [cb (and @(:callbacks conn)
                                                             (:on-stream @(:callbacks conn)))]
                                            (cb (str "chunk-from-" prompt-text)))
                                          (resolve {:text (str "done-" prompt-text)
                                                    :stop-reason "end_turn"
                                                    :usage {} :tool-calls []}))
                                        50))))})))})
                        p-a (as-api/run-prompt #js {}
                                               {:agent     "slow" :cwd "/tmp/race"
                                                :prompt    "A"
                                                :on-stream (fn [t] (swap! a-chunks conj t))})
              ;; Issue B immediately — without serialization, B's reset! would
              ;; happen before A's setTimeout fires, and A's chunk would land
              ;; in b-chunks. With the lane in place, B's callbacks aren't
              ;; set until A resolves.
                        p-b (as-api/run-prompt #js {}
                                               {:agent     "slow" :cwd "/tmp/race"
                                                :prompt    "B"
                                                :on-stream (fn [t] (swap! b-chunks conj t))})]
                    (-> (js/Promise.all #js [p-a p-b])
                        (.then (fn [_]
                                 (-> (expect (count @a-chunks)) (.toBe 1))
                                 (-> (expect (count @b-chunks)) (.toBe 1))
                                 (-> (expect (first @a-chunks)) (.toBe "chunk-from-A"))
                                 (-> (expect (first @b-chunks)) (.toBe "chunk-from-B"))))))))

            (it "invokes the supplied :on-stream callback with chunks"
                (fn []
                  (let [outcome (atom nil)
                        chunks  (atom [])]
                    (register-fake-agent! :fake outcome ["alpha" "beta"])
                    (-> (as-api/run-prompt #js {}
                                           {:agent     "fake"
                                            :cwd       "/tmp/c"
                                            :prompt    "go"
                                            :on-stream (fn [t] (swap! chunks conj t))})
                        (.then (fn [result]
                                 (-> (expect (count @chunks)) (.toBe 2))
                                 (-> (expect (first @chunks)) (.toBe "alpha"))
                                 (-> (expect (last  @chunks)) (.toBe "beta"))
                                 (-> (expect (:text result))  (.toBe "alphabeta"))))))))))

;;; ─── api/list-pool ───────────────────────────────────────────

(describe "agent-shell.api/list-pool"
          (fn []
            (it "decomposes live pool keys into {:agent :cwd :session-id} entries"
                (fn []
                  (let [outcome (atom nil)]
                    (register-fake-agent! :fake outcome ["x"])
                    (-> (as-api/run-prompt #js {} {:agent "fake" :cwd "/tmp/zz"
                                                   :prompt "hi"})
                        (.then (fn [_]
                                 (let [entries (as-api/list-pool)]
                                   (-> (expect (count entries)) (.toBe 1))
                                   (let [e (first entries)]
                                     (-> (expect (:agent e)) (.toBe "fake"))
                                     (-> (expect (.endsWith (:cwd e) "/tmp/zz"))
                                         (.toBe true))))))))))))
