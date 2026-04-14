(ns gateway-core.test
  "Unit tests for gateway.core — channel registry, allow-list auth, and
   create-gateway validation. No network / IO."
  (:require ["bun:test" :refer [describe it expect]]
            [gateway.core :as core]))

;;; ─── channel type registry ───────────────────────────────────

(describe "gateway.core/register-channel-type! + build-channel" (fn []
                                                                  (it "build-channel returns nil and warns for unknown type"
                                                                      (fn []
        ;; Silence the console.warn for this one call
                                                                        (let [orig-warn (.-warn js/console)]
                                                                          (set! (.-warn js/console) (fn [& _] nil))
                                                                          (let [ch (core/build-channel {:type "nonexistent-xyz" :name "x"})]
                                                                            (-> (expect (nil? ch)) (.toBe true)))
                                                                          (set! (.-warn js/console) orig-warn))))

                                                                  (it "build-channel calls the registered factory with name and config"
                                                                      (fn []
                                                                        (let [captured (atom nil)
                                                                              factory  (fn [n cfg]
                                                                                         (reset! captured {:name n :config cfg})
                                                                                         #js {:name n :capabilities #{:text}
                                                                                              :start! (fn [_] (js/Promise.resolve))
                                                                                              :stop!  (fn [] (js/Promise.resolve))})]
                                                                          (core/register-channel-type! :unit-test-a factory)
                                                                          (let [ch (core/build-channel {:type "unit-test-a" :name "my-ch"
                                                                                                        :config {:k "v"}})]
                                                                            (-> (expect (some? ch)) (.toBe true))
                                                                            (-> (expect (:name @captured)) (.toBe "my-ch"))
                                                                            (-> (expect (:k (:config @captured))) (.toBe "v"))))))

                                                                  (it "build-channel works with a JS-shaped config entry"
                                                                      (fn []
                                                                        (let [factory (fn [n _] #js {:name n :start! (fn [_] nil) :stop! (fn [] nil)})]
                                                                          (core/register-channel-type! :unit-test-b factory)
          ;; Caller uses a kebab-case cljs map; build-channel reads :type/:name
                                                                          (let [ch (core/build-channel {:type "unit-test-b" :name "b-ch"})]
                                                                            (-> (expect (some? ch)) (.toBe true))))))))

;;; ─── create-gateway: validation and channel instantiation ────

(describe "gateway.core/create-gateway" (fn []
                                          (it "throws when config fails validation"
                                              (fn []
                                                (let [threw (atom false)]
                                                  (try
                                                    (core/create-gateway {} {:create-session-fn (fn [_] nil)})
                                                    (catch :default e
                                                      (reset! threw (some? (.-message e)))))
                                                  (-> (expect @threw) (.toBe true)))))

                                          (it "throws when no channels can be instantiated"
                                              (fn []
        ;; Silence warn for unknown type
                                                (let [orig-warn (.-warn js/console)]
                                                  (set! (.-warn js/console) (fn [& _] nil))
                                                  (let [threw (atom false)]
                                                    (try
                                                      (core/create-gateway
                                                       {:agent {:model "claude-test"}
                                                        :channels [{:type "this-type-never-exists" :name "x"}]}
                                                       {:create-session-fn (fn [_] nil)})
                                                      (catch :default e
                                                        (reset! threw (some #(.includes (.-message e) %)
                                                                            ["No channels" "channels"]))))
                                                    (-> (expect @threw) (.toBe true)))
                                                  (set! (.-warn js/console) orig-warn))))

                                          (it "channel-overrides bypass the registry"
                                              (fn []
                                                (let [fake-ch #js {:name "override-ch"
                                                                   :capabilities #{:text}
                                                                   :start! (fn [_] (js/Promise.resolve))
                                                                   :stop!  (fn [] (js/Promise.resolve))}
                                                      gw (core/create-gateway
                                                          {:agent {:model "claude-test"}
                                                           :channels [{:type "anything" :name "override-ch"}]}
                                                          {:create-session-fn (fn [_] nil)
                                                           :channel-overrides {"override-ch" fake-ch}})]
                                                  (-> (expect (count (:channels gw))) (.toBe 1))
                                                  (-> (expect (identical? (first (:channels gw)) fake-ch)) (.toBe true)))))

                                          (it "exposes state atom initialised to :created"
                                              (fn []
                                                (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
                                                      gw      (core/create-gateway
                                                               {:agent {:model "claude-test"}
                                                                :channels [{:type "x" :name "c1"}]}
                                                               {:create-session-fn (fn [_] nil)
                                                                :channel-overrides {"c1" fake-ch}})]
                                                  (-> (expect @(:state gw)) (.toBe :created)))))

                                          (it "wires auth pipeline with allow-list when config has :gateway.auth"
                                              (fn []
                                                (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
                                                      gw      (core/create-gateway
                                                               {:agent {:model "claude-test"}
                                                                :gateway {:auth {:allowed-user-ids ["U1" "U2"]}}
                                                                :channels [{:type "x" :name "c1"}]}
                                                               {:create-session-fn (fn [_] nil)
                                                                :channel-overrides {"c1" fake-ch}})]
                                                  (-> (expect (count @(:checks (:auth-pipeline gw)))) (.toBe 1)))))

                                          (it "auth pipeline is empty when no allow-list is configured"
                                              (fn []
                                                (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
                                                      gw      (core/create-gateway
                                                               {:agent {:model "claude-test"}
                                                                :channels [{:type "x" :name "c1"}]}
                                                               {:create-session-fn (fn [_] nil)
                                                                :channel-overrides {"c1" fake-ch}})]
                                                  (-> (expect (count @(:checks (:auth-pipeline gw)))) (.toBe 0)))))

                                          (it "agent-opts and streaming-policy flow through from config"
                                              (fn []
                                                (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
                                                      gw      (core/create-gateway
                                                               {:agent {:model "claude-test" :system-prompt "hi"}
                                                                :gateway {:streaming {:policy "immediate"}}
                                                                :channels [{:type "x" :name "c1"}]}
                                                               {:create-session-fn (fn [_] nil)
                                                                :channel-overrides {"c1" fake-ch}})]
                                                  (-> (expect (:model (:agent-opts gw))) (.toBe "claude-test"))
                                                  (-> (expect (:system-prompt (:agent-opts gw))) (.toBe "hi"))
                                                  (-> (expect (:streaming-policy gw)) (.toBe :immediate)))))))

;;; ─── allow-list auth check (integration via auth-pipeline) ───

(defn ^:async test-allowlist-denies-unknown-user []
  (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
        gw      (core/create-gateway
                 {:agent {:model "claude-test"}
                  :gateway {:auth {:allowed-user-ids ["U1"]}}
                  :channels [{:type "x" :name "c1"}]}
                 {:create-session-fn (fn [_] nil)
                  :channel-overrides {"c1" fake-ch}})
        r       (js-await ((:run! (:auth-pipeline gw))
                           {:user-id "U999" :channel-name "general" :text "hi"}))]
    (-> (expect (:allow? r)) (.toBe false))
    (-> (expect (.includes (:reason r) "U999")) (.toBe true))))

(defn ^:async test-allowlist-permits-listed-user []
  (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
        gw      (core/create-gateway
                 {:agent {:model "claude-test"}
                  :gateway {:auth {:allowed-user-ids ["U1"]}}
                  :channels [{:type "x" :name "c1"}]}
                 {:create-session-fn (fn [_] nil)
                  :channel-overrides {"c1" fake-ch}})
        r       (js-await ((:run! (:auth-pipeline gw))
                           {:user-id "U1" :channel-name "general" :text "hi"}))]
    (-> (expect (:allow? r)) (.toBe true))))

(defn ^:async test-allowlist-permits-when-user-id-absent []
  ;; When a request has no :user-id (e.g. HTTP webhook), the check allows it
  ;; through — the allow-list only constrains calls that DO have a user id.
  (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
        gw      (core/create-gateway
                 {:agent {:model "claude-test"}
                  :gateway {:auth {:allowed-user-ids ["U1"]}}
                  :channels [{:type "x" :name "c1"}]}
                 {:create-session-fn (fn [_] nil)
                  :channel-overrides {"c1" fake-ch}})
        r       (js-await ((:run! (:auth-pipeline gw))
                           {:channel-name "general" :text "hi"}))]
    (-> (expect (:allow? r)) (.toBe true))))

(describe "gateway.core/make-allowlist-check" (fn []
                                                (it "denies a user not on the allow-list"
                                                    test-allowlist-denies-unknown-user)
                                                (it "permits a user on the allow-list"
                                                    test-allowlist-permits-listed-user)
                                                (it "permits a request with no user-id (e.g. HTTP)"
                                                    test-allowlist-permits-when-user-id-absent)))

;;; ─── gateway-stats ───────────────────────────────────────────

(describe "gateway.core/gateway-stats" (fn []
                                         (it "reports channel names and state"
                                             (fn []
                                               (let [fake-ch #js {:name "c1" :start! (fn [_] nil) :stop! (fn [] nil)}
                                                     gw      (core/create-gateway
                                                              {:agent {:model "claude-test"}
                                                               :channels [{:type "x" :name "c1"}]}
                                                              {:create-session-fn (fn [_] nil)
                                                               :channel-overrides {"c1" fake-ch}})
                                                     stats   (core/gateway-stats gw)]
                                                 (-> (expect (:state stats)) (.toBe :created))
                                                 (-> (expect (count (:channels stats))) (.toBe 1))
                                                 (-> (expect (nth (:channels stats) 0)) (.toBe "c1")))))))
