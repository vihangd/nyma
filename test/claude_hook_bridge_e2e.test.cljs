(ns claude-hook-bridge-e2e.test
  "End-to-end integration: writes a real .nyma/settings.json with a
   PreToolUse hook, drives load → match → dispatch → command → response,
   and verifies the merged result mutates the args."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.claude-hook-bridge.config :as config]
            [agent.extensions.claude-hook-bridge.dispatch :as dispatch]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))

(defn- write-json! [p obj]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p (js/JSON.stringify (clj->js obj))))

(beforeEach (fn []
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bridge-e2e-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bridge-e2eh-")))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

(defn ^:async test-rewrite-via-cc-shape-hook []
  ;; Simulate what `rtk hook claude` does: read JSON, emit JSON
  ;; with updatedInput.command rewritten.
  (write-json! (path/join @tmp-root ".nyma" "settings.json")
               {:hooks
                {:PreToolUse
                 [{:matcher "Bash"
                   :hooks   [{:type    "command"
                              :command "sh -c 'cat | sed \"s/git status/rtk git status/\" | jq \"{hookSpecificOutput:{hookEventName:\\\"PreToolUse\\\",updatedInput:.tool_input}}\"'"}]}]}})
  (let [{:keys [hooks]} (config/load-merged-hooks @tmp-root {:claude false :agents false} @tmp-home)
        merged (js-await
                (dispatch/dispatch
                 {:hooks-map     hooks
                  :event-name    "PreToolUse"
                  :discriminator "Bash"
                  :stdin-payload #js {:tool_name "Bash"
                                      :tool_input #js {:command "git status"}}
                  :cwd           @tmp-root}))
        updated (:updated-input merged)]
    (-> (expect (some? updated)) (.toBe true))
    (-> (expect (.-command updated)) (.toBe "rtk git status"))))

(defn ^:async test-deny-blocks-tool []
  (write-json! (path/join @tmp-root ".nyma" "settings.json")
               {:hooks
                {:PreToolUse
                 [{:matcher "Bash"
                   :hooks   [{:type    "command"
                              :command "sh -c 'echo dangerous-command-blocked >&2; exit 2'"}]}]}})
  (let [{:keys [hooks]} (config/load-merged-hooks @tmp-root {:claude false :agents false} @tmp-home)
        merged (js-await
                (dispatch/dispatch
                 {:hooks-map     hooks
                  :event-name    "PreToolUse"
                  :discriminator "Bash"
                  :stdin-payload #js {:tool_name "Bash"
                                      :tool_input #js {:command "rm -rf /"}}
                  :cwd           @tmp-root}))]
    (-> (expect (:permission-decision merged)) (.toBe "deny"))
    (-> (expect (.includes (str (:permission-reason merged)) "dangerous-command-blocked"))
        (.toBe true))))

(defn ^:async test-no-match-returns-nil []
  (write-json! (path/join @tmp-root ".nyma" "settings.json")
               {:hooks
                {:PreToolUse
                 [{:matcher "Edit"  ;; <-- only fires for Edit
                   :hooks   [{:type "command" :command "echo never"}]}]}})
  (let [{:keys [hooks]} (config/load-merged-hooks @tmp-root {:claude false :agents false} @tmp-home)
        merged (js-await
                (dispatch/dispatch
                 {:hooks-map     hooks
                  :event-name    "PreToolUse"
                  :discriminator "Bash"
                  :stdin-payload #js {}
                  :cwd           @tmp-root}))]
    (-> (expect merged) (.toBeUndefined))))

(defn ^:async test-multi-hook-precedence []
  ;; First hook allows, second hook denies — deny must win per CC.
  (write-json! (path/join @tmp-root ".nyma" "settings.json")
               {:hooks
                {:PreToolUse
                 [{:matcher "Bash"
                   :hooks
                   [{:type    "command"
                     :command "sh -c 'echo \"{\\\"hookSpecificOutput\\\":{\\\"hookEventName\\\":\\\"PreToolUse\\\",\\\"permissionDecision\\\":\\\"allow\\\"}}\"'"}
                    {:type    "command"
                     :command "sh -c 'echo \"{\\\"hookSpecificOutput\\\":{\\\"hookEventName\\\":\\\"PreToolUse\\\",\\\"permissionDecision\\\":\\\"deny\\\",\\\"permissionDecisionReason\\\":\\\"absolutely not\\\"}}\"'"}]}]}})
  (let [{:keys [hooks]} (config/load-merged-hooks @tmp-root {:claude false :agents false} @tmp-home)
        merged (js-await
                (dispatch/dispatch
                 {:hooks-map     hooks
                  :event-name    "PreToolUse"
                  :discriminator "Bash"
                  :stdin-payload #js {}
                  :cwd           @tmp-root}))]
    (-> (expect (:permission-decision merged)) (.toBe "deny"))
    (-> (expect (:permission-reason merged)) (.toBe "absolutely not"))))

(describe "e2e/rewrite" (fn []
                          (it "PreToolUse hook can rewrite tool args via updatedInput" test-rewrite-via-cc-shape-hook)))

(describe "e2e/deny" (fn []
                       (it "exit 2 + stderr propagates as deny + reason" test-deny-blocks-tool)))

(describe "e2e/matcher" (fn []
                          (it "non-matching matcher produces no merged result" test-no-match-returns-nil)))

(describe "e2e/precedence" (fn []
                             (it "deny wins over allow regardless of order" test-multi-hook-precedence)))
