(ns claude-hook-bridge-hooks-command.test
  "A user whose hooks do not fire has no way to ask why.

   Two silences, one cause. `safe-read-json` swallowed every JSON error into
   nil — a settings file with a trailing comma produced zero hooks, no
   warning, and not even a debug.log line; it was indistinguishable from
   having configured no hooks at all. And nothing anywhere printed which of
   the eight candidate files nyma had actually read, so `.claude/settings.json`
   sitting there unconsulted because `hooks-compat` was off looked exactly
   like a broken bridge.

   `/hooks` answers both, and a malformed file now says so out loud at load."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.claude-hook-bridge.config :as config]
            [agent.extensions.claude-hook-bridge.index :as index]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))

(beforeEach (fn []
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-hookcmd-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-hookcmd-h-")))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

(defn- write! [p content]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p content))

(defn- load! [& [compat]]
  (config/load-merged-hooks @tmp-root
                            (or compat {:claude false :agents false})
                            @tmp-home))

;;; ─── /hooks lists every resolved hook and where it came from ──────────────

(describe "/hooks lists every resolved hook with its source file"
          (fn []
            (it "names the event, the matcher, the handler and the file"
                (fn []
                  (let [p (path/join @tmp-root ".nyma" "settings.json")]
                    (write! p (js/JSON.stringify
                               (clj->js {:hooks {:PreToolUse
                                                 [{:matcher "Bash"
                                                   :hooks [{:type "command"
                                                            :command "rtk hook claude"}]}]}})))
                    (let [loaded (load!)
                          report (config/format-report loaded)]
                      (-> (expect (count (:index loaded))) (.toBe 1))
                      (-> (expect (:source (first (:index loaded)))) (.toBe p))
                      (-> (expect (.includes report "PreToolUse")) (.toBe true))
                      (-> (expect (.includes report "[Bash]")) (.toBe true))
                      (-> (expect (.includes report "command: rtk hook claude")) (.toBe true))
                      (-> (expect (.includes report p)) (.toBe true))))))

            (it "labels http, prompt and mcp_tool handlers by their own shape"
                (fn []
                  (-> (expect (config/handler-label #js {:type "http" :url "http://x/y"}))
                      (.toBe "http: http://x/y"))
                  (-> (expect (config/handler-label #js {:type "mcp_tool" :server "memory" :tool "read"}))
                      (.toBe "mcp_tool: memory/read"))
                  (-> (expect (config/handler-label #js {:type "command" :command "echo hi"}))
                      (.toBe "command: echo hi"))))

            (it "keeps the provenance out of the hooks map itself"
                (fn []
                  ;; getHookConfig, the matcher and the diagnostics all walk the
                  ;; user's own entry objects. Stamping a source onto them would
                  ;; show up in every one of those.
                  (write! (path/join @tmp-root ".nyma" "settings.json")
                          (js/JSON.stringify
                           (clj->js {:hooks {:PreToolUse
                                             [{:matcher "Bash"
                                               :hooks [{:command "x"}]}]}})))
                  (let [entry (first (get (:hooks (load!)) "PreToolUse"))]
                    (-> (expect (js/JSON.stringify (js/Object.keys entry)))
                        (.toBe (js/JSON.stringify #js ["matcher" "hooks"]))))))))

;;; ─── …and which of the eight files exist and were read ────────────────────

(describe "/hooks shows which candidate files exist and which were read"
          (fn []
            (it "reports all eight candidates even when compat is off"
                (fn []
                  (-> (expect (count (:candidates (load!)))) (.toBe 8))))

            (it "marks a read file as read and a missing one as not found"
                (fn []
                  (write! (path/join @tmp-root ".nyma" "settings.json") "{}")
                  (let [report (config/format-report (load!))]
                    (-> (expect (.includes report (str (path/join @tmp-root ".nyma" "settings.json") " (read)")))
                        (.toBe true))
                    (-> (expect (.includes report (str (path/join @tmp-root ".nyma" "settings.local.json") " (not found)")))
                        (.toBe true)))))

            (it "says a .claude file was skipped because hooks-compat is off"
                (fn []
                  (write! (path/join @tmp-root ".claude" "settings.json") "{}")
                  (let [report (config/format-report (load!))]
                    (-> (expect (.includes report "not consulted; enable via hooks-compat"))
                        (.toBe true)))
                  ;; …and reads it once compat is on.
                  (let [report (config/format-report (load! {:claude true :agents false}))]
                    (-> (expect (.includes report (str (path/join @tmp-root ".claude" "settings.json") " (read)")))
                        (.toBe true)))))

            (it "names the source that set disableAllHooks"
                (fn []
                  (let [p (path/join @tmp-root ".nyma" "settings.json")]
                    (write! p (js/JSON.stringify (clj->js {:disableAllHooks true})))
                    (-> (expect (.includes (config/format-report (load!)) "disableAllHooks: true in "))
                        (.toBe true)))))))

;;; ─── a file that will not parse is named, with its error ──────────────────

(describe "a malformed hooks file is named in /hooks with its parse error"
          (fn []
            (it "collects the path and the error instead of swallowing them"
                (fn []
                  (let [bad (path/join @tmp-root ".nyma" "settings.json")]
                    (write! bad "{ not json at all ")
                    (let [loaded (load!)]
                      (-> (expect (count (:errors loaded))) (.toBe 1))
                      (-> (expect (:path (first (:errors loaded)))) (.toBe bad))
                      (-> (expect (> (count (:message (first (:errors loaded)))) 0)) (.toBe true))
                      (let [report (config/format-report loaded)]
                        (-> (expect (.includes report "Parse failures (1)")) (.toBe true))
                        (-> (expect (.includes report bad)) (.toBe true)))))))

            (it "still loads the sources below the broken one"
                (fn []
                  (write! (path/join @tmp-root ".nyma" "settings.json") "{ broken")
                  (write! (path/join @tmp-root ".nyma" "settings.local.json")
                          (js/JSON.stringify
                           (clj->js {:hooks {:Stop [{:hooks [{:command "ok"}]}]}})))
                  (let [loaded (load!)]
                    (-> (expect (count (:errors loaded))) (.toBe 1))
                    (-> (expect (count (get (:hooks loaded) "Stop"))) (.toBe 1)))))

            (it "reports nothing when every file parses"
                (fn []
                  (write! (path/join @tmp-root ".nyma" "settings.json") "{}")
                  (-> (expect (count (:errors (load!)))) (.toBe 0))))))

;;; ─── …and is announced at load, not only in debug.log ─────────────────────

(describe "a malformed hooks file notifies an error at load"
          (fn []
            (it "notifies once per broken file, naming the file"
                (fn []
                  (let [seen (atom [])]
                    (index/notify-parse-errors!
                     [{:path "/tmp/x/.nyma/settings.json" :message "Unexpected token"}]
                     (fn [m lvl] (swap! seen conj [m lvl])))
                    (-> (expect (count @seen)) (.toBe 1))
                    (-> (expect (nth (first @seen) 1)) (.toBe "error"))
                    (-> (expect (.includes (nth (first @seen) 0) "/tmp/x/.nyma/settings.json"))
                        (.toBe true))
                    (-> (expect (.includes (nth (first @seen) 0) "will NOT load")) (.toBe true)))))

            (it "says nothing when there are no errors"
                (fn []
                  (let [seen (atom [])]
                    (index/notify-parse-errors! [] (fn [m lvl] (swap! seen conj [m lvl])))
                    (-> (expect (count @seen)) (.toBe 0)))))))
