(ns session-settings.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.sessions.settings :as ss]
            [agent.sessions.storage :refer [create-sqlite-store]]))

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

                                       (it "reads the active branch: a fork back past a change is not resumed on it"
                                           (fn []
                                             (let [a (mock-agent (path/join @test-dir "s.jsonl"))
                                                   s @(:session a)]
                                               (ss/record! a)
                                               (let [before ((:get-leaf-id s))]
                                                 (reset! (:thinking-level a) "low")
                                                 (ss/record! a)
                                                 ((:branch s) before)
                                                 (-> (expect (:thinking (ss/last-saved s))) (.toBe "high"))))))

                                       (it "records nothing while a temporary model is on"
                                           (fn []
                                             (let [a (mock-agent (path/join @test-dir "s.jsonl"))]
                                               (swap! (:state a) assoc :plan-mode true)
                                               (ss/record! a)
                                               (swap! (:state a) assoc :plan-mode false :escalated-to "anthropic/big")
                                               (ss/record! a)
                                               (-> (expect (count (saved-entries a))) (.toBe 0)))))))

(defn- restore-rig
  "A mock agent plus spies: models set, role_change emits, /mode calls."
  [file]
  (let [a      (mock-agent file)
        models (atom [])
        roles  (atom [])
        modes  (atom [])
        api    #js {:setModel (fn [m] (swap! models conj m))
                    :ui #js {}}]
    (let [r (assoc a
           :events {:emit (fn [ev d] (when (= ev "role_change") (swap! roles conj (:role d))))}
           :commands (atom {"mode" {:handler (fn [args _] (swap! modes conj (first args)) (swap! (:state a) assoc :permission-mode (first args)))}})
           :provider-registry {:get (fn [p] (contains? #{"anthropic" "zai"} p))}
           :spies {:models models :roles roles :modes modes})]
      ;; the same property cli sets (and every reader uses)
      (set! (.-extension-api r) api)
      r)))

(describe "session settings: restore" (fn []
  (it "applies role, model, thinking and mode that differ"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))
            parts (ss/restore! a {:model "zai/glm-5" :thinking "low"
                                  :permission-mode "accept-edits" :role "reviewer"} {})]
        (-> (expect (count parts)) (.toBe 4))
        (-> (expect (clj->js @(:models (:spies a)))) (.toEqual #js ["zai/glm-5"]))
        (-> (expect (clj->js @(:roles (:spies a)))) (.toEqual #js ["reviewer"]))
        (-> (expect @(:thinking-level a)) (.toBe "low"))
        (-> (expect (clj->js @(:modes (:spies a)))) (.toEqual #js ["accept-edits"])))))

  (it "leaves explicit fields alone, and the role too when -m was given"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))]
        (ss/restore! a {:model "zai/glm-5" :thinking "low" :permission-mode "plan" :role "reviewer"}
                     {:explicit #{:model :thinking :permission-mode}})
        (-> (expect (count @(:models (:spies a)))) (.toBe 0))
        (-> (expect (count @(:roles (:spies a)))) (.toBe 0))
        (-> (expect (count @(:modes (:spies a)))) (.toBe 0))
        (-> (expect @(:thinking-level a)) (.toBe "high")))))

  (it "a role that carries a model does not stop the saved model coming back"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))
            a (assoc a :events {:emit (fn [ev _]
                                        (when (= ev "role_change")
                                          (set! (.-model (:config a)) "anthropic/role-model")))})]
        (ss/restore! a {:role "deep" :model "anthropic/claude-x"} {})
        (-> (expect (clj->js @(:models (:spies a)))) (.toEqual #js ["anthropic/claude-x"])))))

  (it "full-auto comes back as accept-edits; headless skips the mode"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))]
        (ss/restore! a {:permission-mode "full-auto"} {})
        (-> (expect (clj->js @(:modes (:spies a)))) (.toEqual #js ["accept-edits"])))
      (let [a (restore-rig (path/join @test-dir "t.jsonl"))]
        (ss/restore! a {:permission-mode "plan"} {:headless? true})
        (-> (expect (count @(:modes (:spies a)))) (.toBe 0)))))

  (it "a model whose provider is gone is skipped, not thrown"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))
            parts (ss/restore! a {:model "gone/m1"} {})]
        (-> (expect (count @(:models (:spies a)))) (.toBe 0))
        (-> (expect (first parts)) (.toContain "unavailable")))))

  (it "the setup it was left in is a no-op"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))]
        (ss/record! a)
        (-> (expect (count (ss/restore-session! a {}))) (.toBe 0)))))))

(describe "session settings: sessions from before" (fn []
  (it "the usage table's last turn model, skipping extension spend"
    (fn []
      (let [db (create-sqlite-store ":memory:")
            rec (:record-usage db)]
        (rec {:session-file "a.jsonl" :turn-id "t1" :model "zai/glm-5" :input-tokens 10})
        (rec {:session-file "a.jsonl" :turn-id "t2" :model "anthropic/claude-x" :input-tokens 10})
        (rec {:session-file "a.jsonl" :turn-id "extension" :model "anthropic/haiku" :input-tokens 10})
        (rec {:session-file "b.jsonl" :turn-id "t1" :model "zai/glm-9" :input-tokens 10})
        (-> (expect ((:last-model db) "a.jsonl")) (.toBe "anthropic/claude-x"))
        (-> (expect (nil? ((:last-model db) "none.jsonl"))) (.toBe true)))))

  (it "restore-session! falls back to it only when nothing was recorded"
    (fn []
      (let [a (restore-rig (path/join @test-dir "s.jsonl"))]
        (ss/restore-session! a {:last-model (fn [_] "zai/glm-5")})
        (-> (expect (clj->js @(:models (:spies a)))) (.toEqual #js ["zai/glm-5"])))
      (let [a (restore-rig (path/join @test-dir "t.jsonl"))]
        (ss/record! a)
        (ss/restore-session! a {:last-model (fn [_] "zai/glm-5")})
        (-> (expect (count @(:models (:spies a)))) (.toBe 0)))))))
