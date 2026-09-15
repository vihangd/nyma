(ns gateway-loop.test
  "gateway.loop: an inbound message becomes one run on its conversation's
   lane, the run's events reach the response context under the configured
   streaming policy, and the session pool keeps or drops the agent session
   per policy.

   Sessions are built by a fake create-session-fn around a scripted model
   driven through the real `run`, so the event names and payload fields the
   loop reads (`.text` on message_update, agent_end firing once) are the
   ones the loop actually emits."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.loop :refer [run]]
            [gateway.loop :as gloop]
            [gateway.session-pool :as sp]
            [gateway.pipelines :as pipelines]
            [gateway.protocols :as proto]
            [test-util.agent-harness :refer [make-test-agent]]
            [test-util.mock-model :refer [scripted-model]]))

(defn- session-factory
  "A create-session-fn that records its opts and hands back a session with
   the given model behind the real loop."
  [model calls]
  (fn [opts]
    (swap! calls conj opts)
    (let [agent (make-test-agent {:model model})]
      (js/Promise.resolve {:agent agent :send (partial run agent)}))))

(defn- recording-ctx
  "IResponseContext that logs every send!/stream!/meta! as {:op :arg}."
  [log & [caps]]
  (proto/make-response-context
   {:conversation-id "c1"
    :channel-name    "rec"
    :capabilities    (or caps #{:text})
    :send!   (fn [c] (swap! log conj {:op :send! :arg c}) (js/Promise.resolve nil))
    :stream! (fn [c] (swap! log conj {:op :stream! :arg c}) (js/Promise.resolve nil))
    :meta!   (fn [op args] (swap! log conj {:op op :arg args}) (js/Promise.resolve nil))}))

(defn- ops [log op] (filter #(= op (:op %)) @log))

(defn- ^:async run-with-policy
  "One inbound message through handle-message under `policy`; returns the
   response-ctx log."
  [policy]
  (let [{:keys [model]} (scripted-model ["hel" "lo" "!"])
        calls (atom [])
        log   (atom [])
        pool  (sp/create-session-pool)]
    (js-await (gloop/handle-message pool "c1" {:text "hi"} (recording-ctx log)
                                    {:create-session-fn (session-factory model calls)
                                     :agent-opts        {:model "m"}
                                     :streaming-policy  policy}))
    log))

(describe "streaming policies over a 3-chunk run" (fn []
                                                    (it ":immediate delivers each chunk as its own stream! call"
                                                        (^:async fn []
                                                          (let [log (js-await (run-with-policy :immediate))]
                                                            (-> (expect (mapv #(:text (:arg %)) (ops log :stream!)))
                                                                (.toEqual ["hel" "lo" "!"])))))

                                                    (it ":batch-on-end delivers the whole text once"
                                                        (^:async fn []
                                                          (let [log (js-await (run-with-policy :batch-on-end))]
                                                            (-> (expect (mapv #(:text (:arg %)) (ops log :stream!)))
                                                                (.toEqual ["hello!"])))))

                                                    (it ":debounce coalesces chunks that arrive faster than the delay"
                                                        (^:async fn []
                                                          (let [log (js-await (run-with-policy :debounce))]
                                                            (-> (expect (mapv #(:text (:arg %)) (ops log :stream!)))
                                                                (.toEqual ["hello!"])))))

                                                    (it ":throttle sends the first chunk at once and the rest at the end"
                                                        (^:async fn []
                                                          (let [log (js-await (run-with-policy :throttle))]
                                                            (-> (expect (mapv #(:text (:arg %)) (ops log :stream!)))
                                                                (.toEqual ["hel" "lo!"])))))

                                                    (it "signals :done exactly once per run"
                                                        (^:async fn []
                                                          (let [log (js-await (run-with-policy :batch-on-end))]
                                                            (-> (expect (count (ops log :done))) (.toBe 1)))))))

(describe "handle-message" (fn []
                             (it "sends typing-start only to a channel that advertises :typing, and points gateway tools at the ctx"
                                 (^:async fn []
                                   (let [{:keys [model]} (scripted-model ["ok"])
                                         calls (atom [])
                                         typing-log (atom [])
                                         plain-log  (atom [])
                                         pool  (sp/create-session-pool)
                                         opts  {:create-session-fn (session-factory model calls)
                                                :agent-opts {:model "m"} :streaming-policy :immediate}]
                                     (js-await (gloop/handle-message pool "typing" {:text "hi"}
                                                                     (recording-ctx typing-log #{:text :typing}) opts))
                                     (js-await (gloop/handle-message pool "plain" {:text "hi"}
                                                                     (recording-ctx plain-log) opts))
                                     (-> (expect (count (ops typing-log :typing-start))) (.toBe 1))
                                     (-> (expect (count (ops plain-log :typing-start))) (.toBe 0))
          ;; The session got the gateway's channel tools merged into :tools.
                                     (-> (expect (contains? (:tools (first @calls)) "send_message")) (.toBe true)))))

                             (it "leaves no event handlers on the agent once the run is over"
                                 (^:async fn []
                                   (let [{:keys [model]} (scripted-model ["ok"])
                                         calls (atom [])
                                         pool  (sp/create-session-pool)]
                                     (js-await (gloop/handle-message pool "c1" {:text "hi"} (recording-ctx (atom []))
                                                                     {:create-session-fn (session-factory model calls)
                                                                      :agent-opts {:model "m"} :streaming-policy :immediate}))
                                     (let [agent (:agent (:sdk-session (sp/get-data pool "c1" :session-bundle)))]
                                       (-> (expect ((:handler-total (:events agent)))) (.toBe 0))))))))

(describe "session pool policy" (fn []
                                  (it "persistent: one session per conversation, reused across messages"
                                      (^:async fn []
                                        (let [{:keys [model]} (scripted-model ["ok"])
                                              calls (atom [])
                                              pool  (sp/create-session-pool {:default-policy :persistent})
                                              opts  {:create-session-fn (session-factory model calls)
                                                     :agent-opts {:model "m"} :streaming-policy :immediate}]
                                          (js-await (gloop/handle-message pool "a" {:text "1"} (recording-ctx (atom [])) opts))
                                          (js-await (gloop/handle-message pool "a" {:text "2"} (recording-ctx (atom [])) opts))
                                          (js-await (gloop/handle-message pool "b" {:text "3"} (recording-ctx (atom [])) opts))
                                          (-> (expect (count @calls)) (.toBe 2))
          ;; The reused session carries the conversation: both user turns.
                                          (let [agent (:agent (:sdk-session (sp/get-data pool "a" :session-bundle)))
                                                users (filter #(= "user" (:role %)) (:messages @(:state agent)))]
                                            (-> (expect (count users)) (.toBe 2))))))

                                  (it "ephemeral: the session is dropped after every run, so each message starts fresh"
                                      (^:async fn []
                                        (let [{:keys [model]} (scripted-model ["ok"])
                                              calls (atom [])
                                              pool  (sp/create-session-pool {:default-policy :ephemeral})
                                              opts  {:create-session-fn (session-factory model calls)
                                                     :agent-opts {:model "m"} :streaming-policy :immediate}]
                                          (js-await (gloop/handle-message pool "a" {:text "1"} (recording-ctx (atom [])) opts))
                                          (-> (expect (sp/get-data pool "a" :session-bundle)) (.toBeNil))
                                          (js-await (gloop/handle-message pool "a" {:text "2"} (recording-ctx (atom [])) opts))
                                          (-> (expect (count @calls)) (.toBe 2)))))

                                  (it "ephemeral: dropping the session closes it, so its extension handlers do not accumulate"
                                      (^:async fn []
                                        ;; create-session returns :close (deactivates the extensions it
                                        ;; loaded); the pool never called it, so an ephemeral gateway grew
                                        ;; one set of handlers per message for the life of the process.
                                        (let [{:keys [model]} (scripted-model ["ok"])
                                              total (atom nil)
                                              baseline (atom nil)
                                              factory (fn [_opts]
                                                        (let [agent  (make-test-agent {:model model})
                                                              events (:events agent)
                                                              ext-h  (fn [_] nil)]
                                                          (reset! total (:handler-total events))
                                                          (reset! baseline (@total))
                                                          ((:on events) "agent_start" ext-h)
                                                          (js/Promise.resolve
                                                           {:agent agent
                                                            :send  (partial run agent)
                                                            :close (fn [] ((:off events) "agent_start" ext-h))})))
                                              pool  (sp/create-session-pool {:default-policy :ephemeral})
                                              opts  {:create-session-fn factory
                                                     :agent-opts {:model "m"} :streaming-policy :immediate}]
                                          (js-await (gloop/handle-message pool "a" {:text "1"} (recording-ctx (atom [])) opts))
                                          (-> (expect (@total)) (.toBe @baseline)))))))

(describe "wire-and-run" (fn []
                           (it "runs an allowed message on its lane, drops a denied one, and skips a replayed event id"
                               (^:async fn []
                                 (let [{:keys [model]} (scripted-model ["ok"])
                                       calls (atom [])
                                       pool  (sp/create-session-pool)
                                       auth  (pipelines/create-auth-pipeline)
                                       opts  {:create-session-fn (session-factory model calls)
                                              :agent-opts {:model "m"} :streaming-policy :immediate}
                                       orig-log (.-log js/console)]
                                   ((:add! auth) (fn [msg] (if (= "banned" (:user-id msg))
                                                             {:allow? false :reason "banned"}
                                                             {:allow? true})))
                                   (set! (.-log js/console) (fn [& _] nil))
                                   (try
                                     (js-await (gloop/wire-and-run pool auth {:conversation-id "a" :user-id "ok" :event-id "e1" :text "1"}
                                                                   (recording-ctx (atom [])) opts))
                                     (js-await (gloop/wire-and-run pool auth {:conversation-id "a" :user-id "ok" :event-id "e1" :text "again"}
                                                                   (recording-ctx (atom [])) opts))
                                     (js-await (gloop/wire-and-run pool auth {:conversation-id "z" :user-id "banned" :event-id "e2" :text "2"}
                                                                   (recording-ctx (atom [])) opts))
                                     (finally (set! (.-log js/console) orig-log)))
                                   (-> (expect (count @calls)) (.toBe 1))
                                   (-> (expect (some? (sp/get-entry pool "z"))) (.toBe false))
                                   (let [agent (:agent (:sdk-session (sp/get-data pool "a" :session-bundle)))
                                         users (filter #(= "user" (:role %)) (:messages @(:state agent)))]
                                     (-> (expect (count users)) (.toBe 1))))))))
