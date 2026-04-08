(ns ext-agent-shell-mcp.test
  "Tests for MCP server discovery and /mcp command."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.features.mcp-discovery :as mcp-discovery]))

;; ── Test fixtures ──────────────────────────────────────────────────────────

(def ^:private test-dir (atom nil))

(defn- make-temp-dir []
  (let [dir (path/join (os/tmpdir) (str "nyma-mcp-test-" (js/Date.now)))]
    (fs/mkdirSync dir #js {:recursive true})
    dir))

(defn- write-json [dir file-path content]
  (let [full-path (path/join dir file-path)
        parent    (path/dirname full-path)]
    (fs/mkdirSync parent #js {:recursive true})
    (fs/writeFileSync full-path (js/JSON.stringify (clj->js content) nil 2))))

(defn- cleanup-dir [dir]
  (when (and dir (fs/existsSync dir))
    (fs/rmSync dir #js {:recursive true :force true})))

;; ── Mock API ───────────────────────────────────────────────────────────────

(defn- make-mock-api []
  (let [registered-commands (atom {})
        notifications       (atom [])]
    #js {:ui          #js {:available  true
                           :notify     (fn [msg _level] (swap! notifications conj msg))}
         :registerCommand   (fn [name opts]
                              (swap! registered-commands assoc name opts))
         :unregisterCommand (fn [name]
                              (swap! registered-commands dissoc name))
         :_commands         registered-commands
         :_notifications    notifications}))

;; ── State reset ────────────────────────────────────────────────────────────

(beforeEach
  (fn []
    (reset! shared/mcp-servers [])
    (reset! test-dir (make-temp-dir))))

(afterEach
  (fn []
    (cleanup-dir @test-dir)
    (reset! test-dir nil)))

;;; ─── object->array ─────────────────────────────────────────────────────────

(describe "mcp-discovery:object->array" (fn []
  (it "converts a single server to array format"
    (fn []
      (let [obj #js {:my-db #js {:command "npx" :args #js ["-y" "@mcp/pg"]}}
            result (mcp-discovery/object->array obj)]
        (-> (expect (count result)) (.toBe 1))
        (-> (expect (:name (first result))) (.toBe "my-db"))
        (-> (expect (:command (first result))) (.toBe "npx"))
        (-> (expect (count (:args (first result)))) (.toBe 2)))))

  (it "converts multiple servers"
    (fn []
      (let [obj #js {:db #js {:command "pg-server"}
                     :fs #js {:command "fs-server"}}
            result (mcp-discovery/object->array obj)]
        (-> (expect (count result)) (.toBe 2)))))

  (it "returns empty array for nil input"
    (fn []
      (-> (expect (count (mcp-discovery/object->array nil))) (.toBe 0))))

  (it "returns empty array for empty object"
    (fn []
      (-> (expect (count (mcp-discovery/object->array #js {}))) (.toBe 0))))

  (it "defaults args to empty array when missing"
    (fn []
      (let [obj #js {:s #js {:command "cmd"}}
            result (mcp-discovery/object->array obj)]
        (-> (expect (count (:args (first result)))) (.toBe 0)))))))

;;; ─── scan-mcp-servers ──────────────────────────────────────────────────────

(describe "mcp-discovery:scan-mcp-servers" (fn []
  (it "returns empty when no config files exist"
    (fn []
      (let [result (mcp-discovery/scan-mcp-servers @test-dir)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "reads .mcp.json from project root"
    (fn []
      (write-json @test-dir ".mcp.json"
        {:mcpServers {:my-api {:command "node" :args ["server.js"]}}})
      (let [result (mcp-discovery/scan-mcp-servers @test-dir)]
        (-> (expect (count result)) (.toBe 1))
        (-> (expect (:name (first result))) (.toBe "my-api"))
        (-> (expect (:command (first result))) (.toBe "node")))))

  (it "reads .cursor/mcp.json"
    (fn []
      (write-json @test-dir ".cursor/mcp.json"
        {:mcpServers {:cursor-db {:command "pg-serve"}}})
      (let [result (mcp-discovery/scan-mcp-servers @test-dir)]
        (-> (expect (count result)) (.toBe 1))
        (-> (expect (:name (first result))) (.toBe "cursor-db")))))

  (it "merges both sources with .mcp.json winning"
    (fn []
      (write-json @test-dir ".cursor/mcp.json"
        {:mcpServers {:shared {:command "cursor-cmd"}
                      :cursor-only {:command "cursor-extra"}}})
      (write-json @test-dir ".mcp.json"
        {:mcpServers {:shared {:command "project-cmd"}}})
      (let [result   (mcp-discovery/scan-mcp-servers @test-dir)
            by-name  (into {} (map (fn [s] [(:name s) s]) result))]
        ;; Both servers present
        (-> (expect (count result)) (.toBe 2))
        ;; .mcp.json wins for "shared"
        (-> (expect (:command (get by-name "shared"))) (.toBe "project-cmd"))
        ;; cursor-only still present
        (-> (expect (some? (get by-name "cursor-only"))) (.toBe true)))))

  (it "handles malformed JSON gracefully"
    (fn []
      (fs/writeFileSync (path/join @test-dir ".mcp.json") "{ not valid json }")
      (let [result (mcp-discovery/scan-mcp-servers @test-dir)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "handles missing mcpServers key"
    (fn []
      (write-json @test-dir ".mcp.json" {:otherKey "value"})
      (let [result (mcp-discovery/scan-mcp-servers @test-dir)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "expands ${ENV_VAR} in env values"
    (fn []
      ;; Set a test env var
      (aset js/process.env "NYMA_TEST_MCP_KEY" "secret123")
      (write-json @test-dir ".mcp.json"
        {:mcpServers {:api {:command "serve"
                            :env {:API_KEY "${NYMA_TEST_MCP_KEY}"}}}})
      (let [result (mcp-discovery/scan-mcp-servers @test-dir)
            env    (:env (first result))]
        (-> (expect (aget env "API_KEY")) (.toBe "secret123")))
      ;; Cleanup env var
      (aset js/process.env "NYMA_TEST_MCP_KEY" js/undefined)))))

;;; ─── /mcp command ──────────────────────────────────────────────────────────

(describe "mcp-discovery:activate" (fn []
  (it "registers /mcp command on activate"
    (fn []
      (let [api (make-mock-api)
            _   (mcp-discovery/activate api)]
        (-> (expect (contains? @(.-_commands api) "mcp")) (.toBe true)))))

  (it "deactivator unregisters /mcp and clears servers"
    (fn []
      (reset! shared/mcp-servers [{:name "test" :command "x"}])
      (let [api   (make-mock-api)
            deact (mcp-discovery/activate api)]
        (deact)
        (-> (expect (contains? @(.-_commands api) "mcp")) (.toBe false))
        (-> (expect (count @shared/mcp-servers)) (.toBe 0)))))

  (it "/mcp list with no servers shows helpful message"
    (fn []
      (reset! shared/mcp-servers [])
      (let [api     (make-mock-api)
            _       (mcp-discovery/activate api)
            cmd     (get @(.-_commands api) "mcp")
            handler (.-handler cmd)]
        (handler #js [] nil)
        (-> (expect (some #(.includes % "No MCP") @(.-_notifications api)))
            (.toBe true)))))

  (it "/mcp list with servers shows server names"
    (fn []
      (let [api     (make-mock-api)
            _       (mcp-discovery/activate api)
            ;; Set servers AFTER activate (activate scans cwd which has no .mcp.json)
            _       (reset! shared/mcp-servers [{:name "my-db" :command "pg-serve" :args []}])
            cmd     (get @(.-_commands api) "mcp")
            handler (.-handler cmd)]
        (handler #js [] nil)
        (-> (expect (some #(.includes % "my-db") @(.-_notifications api)))
            (.toBe true)))))))
