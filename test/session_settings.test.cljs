(ns session-settings.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.sessions.settings :as ss]))

(def ^:private test-dir (atom nil))

(beforeEach
 (fn []
   (let [dir (path/join (os/tmpdir) (str "nyma-test-settings-" (js/Date.now) "-" (rand-int 1e6)))]
     (fs/mkdirSync dir #js {:recursive true})
     (reset! test-dir dir))))

(afterEach
 (fn []
   (when @test-dir
     (try (fs/rmSync @test-dir #js {:recursive true :force true}) (catch :default _)))))

(defn- mock-agent [file]
  (let [cfg #js {:model #js {:modelId "claude-x"}}
        mgr (create-session-manager file)]
    (aset cfg "active-provider-name" "anthropic")
    ((:load mgr))
    {:config         cfg
     :state          (atom {:permission-mode "default" :active-role "default"})
     :thinking-level (atom "high")
     :session        (atom mgr)}))

(defn- saved-entries [agent]
  (filter #(= "session-settings" (:role %)) ((:get-entries @(:session agent)))))

(describe "session settings: record" (fn []
                                       (it "records the setup once, and again only when it changes"
                                           (fn []
                                             (let [f (path/join @test-dir "s.jsonl")
                                                   a (mock-agent f)]
                                               (ss/record! a)
                                               (ss/record! a)
                                               (-> (expect (count (saved-entries a))) (.toBe 1))
                                               (-> (expect (:model (ss/last-saved @(:session a)))) (.toBe "anthropic/claude-x"))
                                               (reset! (:thinking-level a) "off")
                                               (swap! (:state a) assoc :permission-mode "accept-edits")
                                               (ss/record! a)
                                               (-> (expect (count (saved-entries a))) (.toBe 2))
                                               (let [s (ss/last-saved @(:session a))]
                                                 (-> (expect (:thinking s)) (.toBe "off"))
                                                 (-> (expect (:permission-mode s)) (.toBe "accept-edits"))))))

                                       (it "survives a reload: the last entry wins"
                                           (fn []
                                             (let [f (path/join @test-dir "s.jsonl")
                                                   a (mock-agent f)]
                                               (ss/record! a)
                                               (reset! (:thinking-level a) "low")
                                               (ss/record! a)
                                               (let [m (create-session-manager f)]
                                                 ((:load m))
                                                 (-> (expect (:thinking (ss/last-saved m))) (.toBe "low"))
          ;; inert for the model
                                                 (-> (expect (count ((:build-context m)))) (.toBe 0))))))

                                       (it "records nothing while a temporary model is on"
                                           (fn []
                                             (let [a (mock-agent (path/join @test-dir "s.jsonl"))]
                                               (swap! (:state a) assoc :plan-mode true)
                                               (ss/record! a)
                                               (swap! (:state a) assoc :plan-mode false :escalated-to "anthropic/big")
                                               (ss/record! a)
                                               (-> (expect (count (saved-entries a))) (.toBe 0)))))))
