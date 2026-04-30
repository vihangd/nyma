(ns claude-hook-bridge-config.test
  "Tests for the hook config loader. Uses a temp dir to write fixture
   settings.json files and verifies merged output."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.claude-hook-bridge.config :as config]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))

(defn- write-json! [p obj]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p (js/JSON.stringify (clj->js obj))))

(beforeEach (fn []
              (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bridge-test-"))
                    home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bridge-home-"))]
                (reset! tmp-root dir)
                (reset! tmp-home home))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (when d
                 (try (fs/rmSync d #js {:recursive true :force true})
                      (catch :default _e nil))))))

(defn- load
  "Helper: load-merged-hooks with the test's isolated fake home dir."
  [compat]
  (config/load-merged-hooks @tmp-root compat @tmp-home))

;;; ─── normalization ────────────────────────────────────────────────────────

(describe "config/load-merged-hooks/normalization" (fn []
                                                     (it "loads a project hooks block"
                                                         (fn []
                                                           (let [cwd @tmp-root]
                                                             (write-json! (path/join cwd ".nyma" "settings.json")
                                                                          {:hooks {:PreToolUse [{:matcher "Bash"
                                                                                                 :hooks   [{:type "command"
                                                                                                            :command "echo hi"}]}]}})
                                                             (let [{:keys [hooks]} (load {:claude false :agents false})
                                                                   entries (get hooks "PreToolUse")]
                                                               (-> (expect (count entries)) (.toBe 1))
                                                               (-> (expect (.-matcher (first entries))) (.toBe "Bash"))))))

                                                     (it "lifts a string command-shorthand into the canonical shape"
                                                         (fn []
                                                           (let [cwd @tmp-root]
                                                             (write-json! (path/join cwd ".nyma" "settings.json")
                                                                          {:hooks {:Stop "echo done"}})
                                                             (let [{:keys [hooks]} (load {:claude false :agents false})
                                                                   entries (get hooks "Stop")
                                                                   spec    (aget (.-hooks (first entries)) 0)]
                                                               (-> (expect (.-type spec)) (.toBe "command"))
                                                               (-> (expect (.-command spec)) (.toBe "echo done"))))))))

;;; ─── merge across sources ────────────────────────────────────────────────

(describe "config/load-merged-hooks/concat" (fn []
                                              (it "concatenates global and project arrays for the same event"
                                                  (fn []
                                                    (let [cwd @tmp-root
                                                          fake-home (path/join @tmp-root "fake-home")]
                                                      ;; We can't easily redirect HOME in this test runner; use the
                                                      ;; project file twice (settings.json + settings.local.json) to
                                                      ;; verify concat across two sources we DO control.
                                                      (write-json! (path/join cwd ".nyma" "settings.json")
                                                                   {:hooks {:PreToolUse [{:matcher "Bash"
                                                                                          :hooks   [{:type "command"
                                                                                                     :command "first"}]}]}})
                                                      (write-json! (path/join cwd ".nyma" "settings.local.json")
                                                                   {:hooks {:PreToolUse [{:matcher "Edit"
                                                                                          :hooks   [{:type "command"
                                                                                                     :command "second"}]}]}})
                                                      (let [{:keys [hooks]} (load {:claude false :agents false})
                                                            entries (get hooks "PreToolUse")]
                                                        (-> (expect (count entries)) (.toBe 2))
                                                        (-> (expect (.-matcher (first entries))) (.toBe "Bash"))
                                                        (-> (expect (.-matcher (second entries))) (.toBe "Edit"))))))))

;;; ─── disableAllHooks short-circuit ───────────────────────────────────────

(describe "config/load-merged-hooks/disable-all" (fn []
                                                   (it "stops accumulating below a disabling source"
                                                       (fn []
                                                         (let [cwd @tmp-root]
                                                           (write-json! (path/join cwd ".nyma" "settings.json")
                                                                        {:hooks {:PreToolUse [{:matcher "Bash"
                                                                                               :hooks   [{:type "command"
                                                                                                          :command "first"}]}]}
                                                                         :disableAllHooks true})
                                                           (write-json! (path/join cwd ".nyma" "settings.local.json")
                                                                        {:hooks {:PreToolUse [{:matcher "Edit"
                                                                                               :hooks   [{:type "command"
                                                                                                          :command "second"}]}]}})
                                                           (let [{:keys [hooks disable-all-source]}
                                                                 (load {:claude false :agents false})]
                                                             ;; settings.json fires, but settings.local.json should NOT
                                                             ;; (disable seen at settings.json level).
                                                             (-> (expect (count (get hooks "PreToolUse"))) (.toBe 1))
                                                             (-> (expect (.includes (str disable-all-source) "settings.json"))
                                                                 (.toBe true))))))))

;;; ─── opt-in compat sources ───────────────────────────────────────────────

(describe "config/load-merged-hooks/compat-flags" (fn []
                                                    (it "ignores .claude when compat.claude is false"
                                                        (fn []
                                                          (let [cwd @tmp-root]
                                                            (write-json! (path/join cwd ".claude" "settings.json")
                                                                         {:hooks {:PreToolUse [{:matcher "Bash"
                                                                                                :hooks   [{:type "command"
                                                                                                           :command "from-claude"}]}]}})
                                                            (let [{:keys [hooks]} (load {:claude false :agents false})]
                                                              (-> (expect (count (get hooks "PreToolUse" []))) (.toBe 0))))))

                                                    (it "loads .claude when compat.claude is true"
                                                        (fn []
                                                          (let [cwd @tmp-root]
                                                            (write-json! (path/join cwd ".claude" "settings.json")
                                                                         {:hooks {:PreToolUse [{:matcher "Bash"
                                                                                                :hooks   [{:type "command"
                                                                                                           :command "from-claude"}]}]}})
                                                            (let [{:keys [hooks]} (load {:claude true :agents false})
                                                                  spec (aget (.-hooks (first (get hooks "PreToolUse"))) 0)]
                                                              (-> (expect (.-command spec)) (.toBe "from-claude"))))))

                                                    (it "loads .agents when compat.agents is true (bare hooks.json)"
                                                        (fn []
                                                          (let [cwd @tmp-root]
                                                            (write-json! (path/join cwd ".agents" "hooks.json")
                                                                         {:Stop [{:matcher "*"
                                                                                  :hooks   [{:type "command"
                                                                                             :command "from-agents"}]}]})
                                                            (let [{:keys [hooks]} (load {:claude false :agents true})]
                                                              (-> (expect (count (get hooks "Stop"))) (.toBe 1))))))))

;;; ─── compat flag loader ──────────────────────────────────────────────────

(describe "config/load-compat-flags" (fn []
                                       (it "defaults to both off"
                                           (fn []
                                             (let [flags (config/load-compat-flags @tmp-root)]
                                               (-> (expect (:claude flags)) (.toBe false))
                                               (-> (expect (:agents flags)) (.toBe false)))))

                                       (it "reads claude:true from project settings"
                                           (fn []
                                             (let [cwd @tmp-root]
                                               (write-json! (path/join cwd ".nyma" "settings.json")
                                                            {:hooks-compat {:claude true}})
                                               (let [flags (config/load-compat-flags cwd)]
                                                 (-> (expect (:claude flags)) (.toBe true))
                                                 (-> (expect (:agents flags)) (.toBe false))))))))
