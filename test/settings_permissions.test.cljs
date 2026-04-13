(ns settings-permissions.test
  "Tests for three-way approval persistence in settings manager and
   permission-check interceptor. Covers:
     - append-allow-tool! writes atomically to temp project dir
     - Idempotency: duplicate appends don't create duplicate entries
     - tool-allowed? unions project + global allow-lists
     - Permission check skips emit when tool is pre-allowed
     - allow_always_project decision persists
     - allow_once and deny decisions do not persist
     - Existing :decision 'allow' still works (back-compat)
     - Malformed settings JSON is handled gracefully"
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.middleware :refer [create-pipeline permission-check-enter]]
            [agent.events :refer [create-event-bus]]))

;;; ─── temp-dir fixture ────────────────────────────────────────

(def ^:private tmp-root (atom nil))

(defn- make-tmp-dir []
  (let [base (path/join (os/tmpdir) "nyma-perm-test-")]
    (fs/mkdtempSync base)))

(defn- rm-rf [p]
  (when (fs/existsSync p)
    (fs/rmSync p #js {:recursive true :force true})))

(defn- tmp-project-path []
  (path/join @tmp-root "project" "settings.json"))

(defn- tmp-global-path []
  (path/join @tmp-root "global" "settings.json"))

(defn- make-manager
  ([] (make-manager nil nil))
  ([project-json] (make-manager project-json nil))
  ([project-json global-json]
   ;; Write initial files if provided
   (when project-json
     (let [dir (path/dirname (tmp-project-path))]
       (when-not (fs/existsSync dir) (fs/mkdirSync dir #js {:recursive true}))
       (fs/writeFileSync (tmp-project-path) (js/JSON.stringify (clj->js project-json)))))
   (when global-json
     (let [dir (path/dirname (tmp-global-path))]
       (when-not (fs/existsSync dir) (fs/mkdirSync dir #js {:recursive true}))
       (fs/writeFileSync (tmp-global-path) (js/JSON.stringify (clj->js global-json)))))
   (create-settings-manager {:project-path (tmp-project-path)
                             :global-path  (tmp-global-path)})))

(defn- read-project-json []
  (when (fs/existsSync (tmp-project-path))
    (js/JSON.parse (fs/readFileSync (tmp-project-path) "utf8"))))

;;; ─── setup/teardown ─────────────────────────────────────────

(beforeEach (fn [] (reset! tmp-root (make-tmp-dir))))
(afterEach  (fn [] (rm-rf @tmp-root) (reset! tmp-root nil)))

;;; ─── append-allow-tool! ──────────────────────────────────────

(describe "append-allow-tool!"
          (fn []
            (it "writes tool name to project permissions.allow"
                (fn []
                  (let [mgr (make-manager)]
                    ((:append-allow-tool! mgr) "bash")
                    (let [data (read-project-json)]
                      (-> (expect (some? data)) (.toBe true))
                      (-> (expect (some #(= % "bash")
                                        (js/Array.from (.. data -permissions -allow))))
                          (.toBe true))))))

            (it "is idempotent — calling twice does not duplicate"
                (fn []
                  (let [mgr (make-manager)]
                    ((:append-allow-tool! mgr) "bash")
                    ((:append-allow-tool! mgr) "bash")
                    (let [data  (read-project-json)
                          allow (vec (js/Array.from (.. data -permissions -allow)))]
                      (-> (expect (count (filter #(= % "bash") allow))) (.toBe 1))))))

            (it "preserves existing allow entries"
                (fn []
                  (let [mgr (make-manager {:permissions {:allow ["read"]}})]
                    ((:append-allow-tool! mgr) "write")
                    (let [data  (read-project-json)
                          allow (vec (js/Array.from (.. data -permissions -allow)))]
                      (-> (expect (some #(= % "read") allow)) (.toBe true))
                      (-> (expect (some #(= % "write") allow)) (.toBe true))))))

            (it "creates settings file when none exists"
                (fn []
                  (let [mgr (make-manager)]
                    (-> (expect (fs/existsSync (tmp-project-path))) (.toBe false))
                    ((:append-allow-tool! mgr) "bash")
                    (-> (expect (fs/existsSync (tmp-project-path))) (.toBe true)))))

            (it "preserves other project settings when appending"
                (fn []
                  (let [mgr (make-manager {:model "claude-opus-4-20250514"})]
                    ((:append-allow-tool! mgr) "bash")
                    (let [data (read-project-json)]
                      (-> (expect (.-model data)) (.toBe "claude-opus-4-20250514"))))))))

;;; ─── tool-allowed? ───────────────────────────────────────────

(describe "tool-allowed?"
          (fn []
            (it "returns false when allow-list is empty"
                (fn []
                  (let [mgr (make-manager)]
                    (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe false)))))

            (it "returns true when tool is in project allow-list"
                (fn []
                  (let [mgr (make-manager {:permissions {:allow ["bash" "write"]}})]
                    (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe true))
                    (-> (expect ((:tool-allowed? mgr) "write")) (.toBe true))
                    (-> (expect ((:tool-allowed? mgr) "read")) (.toBe false)))))

            (it "returns true when tool is in global allow-list"
                (fn []
                  (let [mgr (make-manager nil {:permissions {:allow ["read"]}})]
                    (-> (expect ((:tool-allowed? mgr) "read")) (.toBe true))
                    (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe false)))))

            (it "unions project and global allow-lists"
                (fn []
                  (let [mgr (make-manager {:permissions {:allow ["bash"]}}
                                          {:permissions {:allow ["read"]}})]
                    (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe true))
                    (-> (expect ((:tool-allowed? mgr) "read")) (.toBe true))
                    (-> (expect ((:tool-allowed? mgr) "write")) (.toBe false)))))

            (it "reflects appended tools immediately"
                (fn []
                  (let [mgr (make-manager)]
                    (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe false))
                    ((:append-allow-tool! mgr) "bash")
                    (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe true)))))))

;;; ─── permission-check integration ───────────────────────────

(describe "permission-check — allow-list skip"
          (fn []
            (it "skips permission_request emit when tool is pre-allowed"
                (fn []
                  (let [events       (create-event-bus)
                        perm-fired   (atom false)
                        _            ((:on events) "permission_request"
                                                   (fn [_] (reset! perm-fired true)))
                        mgr          (make-manager {:permissions {:allow ["bash"]}})
                        pipeline     (create-pipeline events nil nil mgr)
                        tool         #js {:execute (fn [_] "ok")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [ctx]
                                 (-> (expect @perm-fired) (.toBe false))
                                 (-> (expect (:result ctx)) (.toBe "ok"))))))))

            (it "emits permission_request for non-allowed tools"
                (fn []
                  (let [events       (create-event-bus)
                        perm-fired   (atom false)
                        _            ((:on events) "permission_request"
                                                   (fn [_] (reset! perm-fired true) #js {"decision" "allow"}))
                        mgr          (make-manager) ;; empty allow-list
                        pipeline     (create-pipeline events nil nil mgr)
                        tool         #js {:execute (fn [_] "ok")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [_ctx]
                                 (-> (expect @perm-fired) (.toBe true))))))))))

;;; ─── allow_always_project persistence ───────────────────────

(describe "permission-check — allow_always_project"
          (fn []
            (it "allow_always_project decision persists tool to settings"
                (fn []
                  (let [events   (create-event-bus)
                        _        ((:on events) "permission_request"
                                               (fn [_] #js {"decision" "allow_always_project"}))
                        mgr      (make-manager)
                        pipeline (create-pipeline events nil nil mgr)
                        tool     #js {:execute (fn [_] "result")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [ctx]
                       ;; Tool should not be cancelled
                                 (-> (expect (:cancelled ctx)) (.toBeFalsy))
                       ;; Tool should be in allow-list now
                                 (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe true))
                       ;; Settings file should exist with the tool listed
                                 (let [data (read-project-json)]
                                   (-> (expect (some? data)) (.toBe true))
                                   (-> (expect (some #(= % "bash")
                                                     (js/Array.from (.. data -permissions -allow))))
                                       (.toBe true)))))))))

            (it "second call skips prompt because tool is now pre-allowed"
                (fn []
                  (let [events     (create-event-bus)
                        call-count (atom 0)
                        _          ((:on events) "permission_request"
                                                 (fn [_]
                                                   (swap! call-count inc)
                                                   #js {"decision" "allow_always_project"}))
                        mgr        (make-manager)
                        pipeline   (create-pipeline events nil nil mgr)
                        tool       #js {:execute (fn [_] "ok")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [_]
                       ;; First call prompted once
                                 (-> (expect @call-count) (.toBe 1))
                       ;; Second call should skip the prompt
                                 ((:execute pipeline) "bash" tool {})))
                        (.then (fn [_]
                                 (-> (expect @call-count) (.toBe 1))))))))))

;;; ─── allow_once and deny do not persist ─────────────────────

(describe "permission-check — non-persisting decisions"
          (fn []
            (it "allow_once decision does not add to allow-list"
                (fn []
                  (let [events   (create-event-bus)
                        _        ((:on events) "permission_request"
                                               (fn [_] #js {"decision" "allow"}))
                        mgr      (make-manager)
                        pipeline (create-pipeline events nil nil mgr)
                        tool     #js {:execute (fn [_] "ok")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [_]
                                 (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe false))))))))

            (it "deny decision does not add to allow-list"
                (fn []
                  (let [events   (create-event-bus)
                        _        ((:on events) "permission_request"
                                               (fn [_] #js {"decision" "deny"}))
                        mgr      (make-manager)
                        pipeline (create-pipeline events nil nil mgr)
                        tool     #js {:execute (fn [_] "ok")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [ctx]
                                 (-> (expect (:cancelled ctx)) (.toBe true))
                                 (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe false))))))))))

;;; ─── back-compat: existing 'allow' decision ─────────────────

(describe "permission-check — back-compat"
          (fn []
            (it "allow decision still allows tool execution"
                (fn []
                  (let [events   (create-event-bus)
                        _        ((:on events) "permission_request"
                                               (fn [_] #js {"decision" "allow"}))
                        pipeline (create-pipeline events)
                        tool     #js {:execute (fn [_] "ran")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [ctx]
                                 (-> (expect (:cancelled ctx)) (.toBeFalsy))
                                 (-> (expect (:result ctx)) (.toBe "ran"))))))))

            (it "no permission handlers — tool runs without prompt"
                (fn []
                  (let [events   (create-event-bus)
                        pipeline (create-pipeline events)
                        tool     #js {:execute (fn [_] "free")}]
                    (-> ((:execute pipeline) "read" tool {})
                        (.then (fn [ctx]
                                 (-> (expect (:result ctx)) (.toBe "free"))))))))

            (it "nil settings — no allow-list check, prompt still fires"
                (fn []
                  (let [events     (create-event-bus)
                        perm-fired (atom false)
                        _          ((:on events) "permission_request"
                                                 (fn [_] (reset! perm-fired true) #js {"decision" "allow"}))
                        pipeline   (create-pipeline events nil nil nil) ;; nil settings
                        tool       #js {:execute (fn [_] "ok")}]
                    (-> ((:execute pipeline) "bash" tool {})
                        (.then (fn [_]
                                 (-> (expect @perm-fired) (.toBe true))))))))))

;;; ─── malformed settings ──────────────────────────────────────

(describe "settings — malformed JSON"
          (fn []
            (it "malformed project settings.json falls back safely"
                (fn []
                  (let [dir (path/dirname (tmp-project-path))]
                    (when-not (fs/existsSync dir) (fs/mkdirSync dir #js {:recursive true}))
                    (fs/writeFileSync (tmp-project-path) "{ invalid json !!!")
          ;; Should not throw; returns settings without project overrides
                    (let [mgr (create-settings-manager {:project-path (tmp-project-path)
                                                        :global-path  (tmp-global-path)})]
            ;; tool-allowed? should not throw
                      (-> (expect (fn [] ((:tool-allowed? mgr) "bash"))) (.not.toThrow))
                      (-> (expect ((:tool-allowed? mgr) "bash")) (.toBe false))))))))
