(ns ext-mcp-config-errors.test
  "A broken MCP config must say so, and /mcp must say which files it looked in.

   Both failures were silent: mcp-client caught every settings.json parse error
   with `(catch :default _e defaults)`, and mcp-discovery warned about a
   malformed .mcp.json to debug.log — a file nobody running nyma normally ever
   opens. In both cases the user saw zero MCP servers and no reason."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.agent-shell.features.mcp-discovery :as disco]
            [agent.extensions.mcp-client.index :as mcp]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))
(def orig-cwd (atom nil))

(beforeEach (fn []
              (reset! orig-cwd (js/process.cwd))
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-mcpcfg-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-mcpcfg-h-")))))

(afterEach (fn []
             (js/process.chdir @orig-cwd)
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

(defn- write! [p content]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p content))

;;; ─── a malformed .mcp.json is reported, not swallowed ─────────────────────

(describe "mcp-discovery/a malformed .mcp.json is reported with its path"
          (fn []
            (it "calls back with a message naming the exact file"
                (fn []
                  (let [bad  (path/join @tmp-root ".mcp.json")
                        errs (atom [])]
                    (write! bad "{ not json at all ")
                    (disco/scan-mcp-servers @tmp-root @tmp-home
                                            (fn [m] (swap! errs conj m)))
                    (-> (expect (count @errs)) (.toBe 1))
                    (-> (expect (.includes (first @errs) bad)) (.toBe true))
                    (-> (expect (.includes (first @errs) "will NOT load")) (.toBe true)))))

            (it "still returns the servers from the files that did parse"
                (fn []
                  (write! (path/join @tmp-root ".mcp.json") "{ broken")
                  (write! (path/join @tmp-root ".nyma" "mcp.json")
                          (js/JSON.stringify
                           (clj->js {:mcpServers {:good {:command "echo"}}})))
                  (let [servers (disco/scan-mcp-servers @tmp-root @tmp-home (fn [_] nil))]
                    (-> (expect (count servers)) (.toBe 1))
                    (-> (expect (:name (first servers))) (.toBe "good")))))

            (it "says nothing when every config parses"
                (fn []
                  (let [errs (atom [])]
                    (disco/scan-mcp-servers @tmp-root @tmp-home
                                            (fn [m] (swap! errs conj m)))
                    (-> (expect (count @errs)) (.toBe 0)))))))

;;; ─── /mcp and /mcp-status name the files they consulted ───────────────────

(describe "mcp-discovery/the candidate config files are printed"
          (fn []
            (it "lists all four paths in precedence order"
                (fn []
                  (let [ps (disco/candidate-paths @tmp-root @tmp-home)]
                    (-> (expect (count ps)) (.toBe 4))
                    (-> (expect (nth ps 0)) (.toBe (path/join @tmp-home ".nyma" "mcp.json")))
                    (-> (expect (nth ps 1)) (.toBe (path/join @tmp-root ".nyma" "mcp.json")))
                    (-> (expect (nth ps 2)) (.toBe (path/join @tmp-root ".cursor" "mcp.json")))
                    (-> (expect (nth ps 3)) (.toBe (path/join @tmp-root ".mcp.json"))))))

            (it "marks which of them exist and which do not"
                (fn []
                  (write! (path/join @tmp-root ".mcp.json")
                          (js/JSON.stringify (clj->js {:mcpServers {}})))
                  (let [report (disco/candidate-report @tmp-root @tmp-home)]
                    ;; every candidate is named
                    (doseq [p (disco/candidate-paths @tmp-root @tmp-home)]
                      (-> (expect (.includes report p)) (.toBe true)))
                    (-> (expect (.includes report (str "✓ " (path/join @tmp-root ".mcp.json"))))
                        (.toBe true))
                    (-> (expect (.includes report "(not found)")) (.toBe true)))))

            (it "formats entries without touching the filesystem"
                (fn []
                  (let [s (disco/format-candidates [{:file "/a/mcp.json" :exists? true}
                                                    {:file "/b/mcp.json" :exists? false}])]
                    (-> (expect (.includes s "✓ /a/mcp.json"))          (.toBe true))
                    (-> (expect (.includes s "/b/mcp.json (not found)")) (.toBe true)))))))

;;; ─── a malformed settings.json#mcp is reported, not swallowed ─────────────

(describe "mcp-client/a malformed settings.json#mcp is reported with file and key"
          (fn []
            (it "calls back naming the file and the mcp key"
                (fn []
                  (let [cfg (path/join @tmp-root ".nyma" "settings.json")]
                    (write! cfg "{ \"mcp\": { oops }")
                    (js/process.chdir @tmp-root)
                    (let [errs     (atom [])
                          settings (mcp/load-settings (fn [m] (swap! errs conj m)))]
                      (-> (expect (count @errs)) (.toBe 1))
                      ;; macOS resolves /var → /private/var, so compare on the
                      ;; tail rather than the absolute prefix.
                      (-> (expect (.includes (first @errs) "settings.json")) (.toBe true))
                      (-> (expect (.includes (first @errs) "mcp"))           (.toBe true))
                      ;; and it still falls back to working defaults
                      (-> (expect (number? (:max-restarts settings))) (.toBe true))))))

            (it "says nothing when there is no settings file at all"
                (fn []
                  (js/process.chdir @tmp-root)
                  (let [errs (atom [])]
                    (mcp/load-settings (fn [m] (swap! errs conj m)))
                    (-> (expect (count @errs)) (.toBe 0)))))

            (it "builds a message carrying both the path and the key"
                (fn []
                  (let [m (mcp/malformed-settings-message "/p/.nyma/settings.json" "Unexpected token")]
                    (-> (expect (.includes m "/p/.nyma/settings.json")) (.toBe true))
                    (-> (expect (.includes m "mcp"))                    (.toBe true))
                    (-> (expect (.includes m "Unexpected token"))       (.toBe true)))))))
