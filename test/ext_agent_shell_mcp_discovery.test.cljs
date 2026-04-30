(ns ext-agent-shell-mcp-discovery.test
  "Tests for the source precedence chain in mcp_discovery/scan-mcp-servers.
   Uses isolated tmp dirs for both `project-root` and `home` so the
   user's real ~/.nyma/mcp.json doesn't leak into assertions."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.agent-shell.features.mcp-discovery :as disco]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))

(defn- write-mcp! [p obj]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p (js/JSON.stringify (clj->js obj))))

(defn- find-server [servers name]
  (some (fn [s] (when (= (:name s) name) s)) servers))

(beforeEach (fn []
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-mcp-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-mcp-h-")))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

;;; ─── Sources discovered ─────────────────────────────────────────

(describe "mcp-discovery/scan-mcp-servers/sources" (fn []
                                                     (it "returns empty when no source exists"
                                                         (fn []
                                                           (-> (expect (count (disco/scan-mcp-servers @tmp-root @tmp-home))) (.toBe 0))))

                                                     (it "reads ~/.nyma/mcp.json (user-global)"
                                                         (fn []
                                                           (write-mcp! (path/join @tmp-home ".nyma" "mcp.json")
                                                                       {:mcpServers {:global-srv {:command "echo" :args ["g"]}}})
                                                           (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)]
                                                             (-> (expect (count servers)) (.toBe 1))
                                                             (-> (expect (:name (first servers))) (.toBe "global-srv")))))

                                                     (it "reads <cwd>/.nyma/mcp.json (project)"
                                                         (fn []
                                                           (write-mcp! (path/join @tmp-root ".nyma" "mcp.json")
                                                                       {:mcpServers {:project-srv {:command "echo" :args ["p"]}}})
                                                           (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)]
                                                             (-> (expect (count servers)) (.toBe 1))
                                                             (-> (expect (:name (first servers))) (.toBe "project-srv")))))

                                                     (it "reads <cwd>/.mcp.json (CC convention)"
                                                         (fn []
                                                           (write-mcp! (path/join @tmp-root ".mcp.json")
                                                                       {:mcpServers {:cc-srv {:command "echo" :args ["c"]}}})
                                                           (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)]
                                                             (-> (expect (count servers)) (.toBe 1))
                                                             (-> (expect (:name (first servers))) (.toBe "cc-srv")))))

                                                     (it "reads <cwd>/.cursor/mcp.json (Cursor compat)"
                                                         (fn []
                                                           (write-mcp! (path/join @tmp-root ".cursor" "mcp.json")
                                                                       {:mcpServers {:cursor-srv {:command "echo" :args ["x"]}}})
                                                           (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)]
                                                             (-> (expect (count servers)) (.toBe 1))
                                                             (-> (expect (:name (first servers))) (.toBe "cursor-srv")))))))

;;; ─── Precedence (later overrides earlier on name collision) ─────

(describe "mcp-discovery/scan-mcp-servers/precedence" (fn []
                                                        (it "merges names from multiple sources"
                                                            (fn []
                                                              (write-mcp! (path/join @tmp-home ".nyma" "mcp.json")
                                                                          {:mcpServers {:global-only {:command "g"}}})
                                                              (write-mcp! (path/join @tmp-root ".mcp.json")
                                                                          {:mcpServers {:project-only {:command "p"}}})
                                                              (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)]
                                                                (-> (expect (count servers)) (.toBe 2))
                                                                (-> (expect (some? (find-server servers "global-only"))) (.toBe true))
                                                                (-> (expect (some? (find-server servers "project-only"))) (.toBe true)))))

                                                        (it "<cwd>/.mcp.json overrides ~/.nyma/mcp.json on name collision"
                                                            (fn []
                                                              (write-mcp! (path/join @tmp-home ".nyma" "mcp.json")
                                                                          {:mcpServers {:shared {:command "GLOBAL"}}})
                                                              (write-mcp! (path/join @tmp-root ".mcp.json")
                                                                          {:mcpServers {:shared {:command "PROJECT"}}})
                                                              (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)
                                                                    sh      (find-server servers "shared")]
                                                                (-> (expect (:command sh)) (.toBe "PROJECT")))))

                                                        (it "<cwd>/.mcp.json overrides <cwd>/.cursor/mcp.json on name collision"
                                                            (fn []
                                                              (write-mcp! (path/join @tmp-root ".cursor" "mcp.json")
                                                                          {:mcpServers {:dual {:command "CURSOR"}}})
                                                              (write-mcp! (path/join @tmp-root ".mcp.json")
                                                                          {:mcpServers {:dual {:command "MCP"}}})
                                                              (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)
                                                                    dual    (find-server servers "dual")]
                                                                (-> (expect (:command dual)) (.toBe "MCP")))))

                                                        (it "<cwd>/.nyma/mcp.json overrides ~/.nyma/mcp.json"
                                                            (fn []
                                                              (write-mcp! (path/join @tmp-home ".nyma" "mcp.json")
                                                                          {:mcpServers {:nm {:command "USER"}}})
                                                              (write-mcp! (path/join @tmp-root ".nyma" "mcp.json")
                                                                          {:mcpServers {:nm {:command "PROJECT"}}})
                                                              (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)
                                                                    nm      (find-server servers "nm")]
                                                                (-> (expect (:command nm)) (.toBe "PROJECT")))))

                                                        (it "all four sources visible together — distinct names persist"
                                                            (fn []
                                                              (write-mcp! (path/join @tmp-home ".nyma" "mcp.json")
                                                                          {:mcpServers {:a {:command "A"}}})
                                                              (write-mcp! (path/join @tmp-root ".nyma" "mcp.json")
                                                                          {:mcpServers {:b {:command "B"}}})
                                                              (write-mcp! (path/join @tmp-root ".cursor" "mcp.json")
                                                                          {:mcpServers {:c {:command "C"}}})
                                                              (write-mcp! (path/join @tmp-root ".mcp.json")
                                                                          {:mcpServers {:d {:command "D"}}})
                                                              (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home)]
                                                                (-> (expect (count servers)) (.toBe 4))
                                                                (doseq [n ["a" "b" "c" "d"]]
                                                                  (-> (expect (some? (find-server servers n))) (.toBe true))))))))
