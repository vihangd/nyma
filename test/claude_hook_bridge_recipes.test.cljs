(ns claude-hook-bridge-recipes.test
  "Integration tests for the recipes published in docs/hooks.md.
   Each test writes the recipe verbatim into a tmp project and
   exercises the full pipeline so doc drift breaks the build."
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
  (fs/writeFileSync p (js/JSON.stringify (clj->js obj) nil 2)))

(defn- write-script! [p content]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p content)
  ;; 0o755 → decimal 493 (squint can't parse 0o literals)
  (fs/chmodSync p 493))

(beforeEach (fn []
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-recipe-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-recipe-h-")))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

;;; ─── Recipe: SessionStart additionalContext ─────────────────────────────

(defn ^:async test-session-start-additional-context-recipe []
  (let [script-path (path/join @tmp-root ".nyma" "hooks" "branch.sh")]
    (write-script! script-path
                   (str "#!/usr/bin/env bash\n"
                        "echo '{\"hookSpecificOutput\":"
                        "{\"hookEventName\":\"SessionStart\","
                        "\"additionalContext\":\"Working branch: main\"}}'"
                        "\n"))
    (write-json! (path/join @tmp-root ".nyma" "settings.json")
                 {:hooks
                  {:SessionStart
                   [{:matcher "startup"
                     :hooks [{:type "command"
                              :command script-path}]}]}})
    (let [{:keys [hooks]} (config/load-merged-hooks
                           @tmp-root {:claude false :agents false} @tmp-home)
          merged (js-await
                  (dispatch/dispatch
                   {:hooks-map     hooks
                    :event-name    "SessionStart"
                    :discriminator "startup"
                    :stdin-payload #js {:hook_event_name "SessionStart"
                                        :source "startup"}
                    :cwd           @tmp-root}))]
      (-> (expect (some? merged)) (.toBe true))
      (-> (expect (:additional-context merged))
          (.toBe "Working branch: main")))))

;;; ─── Recipe: matcher pipe-list (Edit|Write) ─────────────────────────────

(defn ^:async test-pipe-list-matcher-fires-for-both-tools []
  (let [script-path (path/join @tmp-root ".nyma" "hooks" "lint.sh")]
    (write-script! script-path
                   "#!/usr/bin/env bash\necho '{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"linted\"}}'")
    (write-json! (path/join @tmp-root ".nyma" "settings.json")
                 {:hooks
                  {:PostToolUse
                   [{:matcher "Edit|Write"
                     :hooks [{:type "command"
                              :command script-path}]}]}})
    (let [{:keys [hooks]} (config/load-merged-hooks
                           @tmp-root {:claude false :agents false} @tmp-home)
          edit-merged   (js-await
                         (dispatch/dispatch
                          {:hooks-map     hooks
                           :event-name    "PostToolUse"
                           :discriminator "Edit"
                           :stdin-payload #js {}
                           :cwd           @tmp-root}))
          write-merged  (js-await
                         (dispatch/dispatch
                          {:hooks-map     hooks
                           :event-name    "PostToolUse"
                           :discriminator "Write"
                           :stdin-payload #js {}
                           :cwd           @tmp-root}))
          read-merged   (js-await
                         (dispatch/dispatch
                          {:hooks-map     hooks
                           :event-name    "PostToolUse"
                           :discriminator "Read"
                           :stdin-payload #js {}
                           :cwd           @tmp-root}))]
      (-> (expect (:additional-context edit-merged))  (.toBe "linted"))
      (-> (expect (:additional-context write-merged)) (.toBe "linted"))
      (-> (expect read-merged) (.toBeUndefined)))))

;;; ─── Recipe: deny via exit 2 + stderr ────────────────────────────────────

(defn ^:async test-deny-via-exit-2-recipe []
  (let [script-path (path/join @tmp-root ".nyma" "hooks" "guard.sh")]
    (write-script! script-path
                   (str "#!/usr/bin/env bash\n"
                        "input=$(cat)\n"
                        "if echo \"$input\" | grep -q 'rm -rf'; then\n"
                        "  echo 'destructive command refused' >&2\n"
                        "  exit 2\n"
                        "fi\n"
                        "exit 0\n"))
    (write-json! (path/join @tmp-root ".nyma" "settings.json")
                 {:hooks
                  {:PreToolUse
                   [{:matcher "Bash"
                     :hooks [{:type "command"
                              :command script-path}]}]}})
    (let [{:keys [hooks]} (config/load-merged-hooks
                           @tmp-root {:claude false :agents false} @tmp-home)
          ;; safe command — no deny
          safe-merged (js-await
                       (dispatch/dispatch
                        {:hooks-map     hooks
                         :event-name    "PreToolUse"
                         :discriminator "Bash"
                         :stdin-payload #js {:tool_input #js {:command "ls"}}
                         :cwd           @tmp-root}))
          ;; dangerous command — denied
          bad-merged  (js-await
                       (dispatch/dispatch
                        {:hooks-map     hooks
                         :event-name    "PreToolUse"
                         :discriminator "Bash"
                         :stdin-payload #js {:tool_input #js {:command "rm -rf /tmp/x"}}
                         :cwd           @tmp-root}))]
      ;; safe → no decision, hook returned exit 0 with no output
      (-> (expect (or (nil? safe-merged) (nil? (:permission-decision safe-merged))))
          (.toBe true))
      ;; dangerous → deny + reason
      (-> (expect (:permission-decision bad-merged)) (.toBe "deny"))
      (-> (expect (.includes (str (:permission-reason bad-merged)) "destructive"))
          (.toBe true)))))

(describe "recipes/SessionStart-additional-context"
          (fn []
            (it "branch.sh writes branch to additionalContext"
                test-session-start-additional-context-recipe)))

(describe "recipes/pipe-list-matcher"
          (fn []
            (it "matcher 'Edit|Write' fires for both, not Read"
                test-pipe-list-matcher-fires-for-both-tools)))

(describe "recipes/deny-via-exit-2"
          (fn []
            (it "exit 2 + stderr blocks dangerous bash with reason"
                test-deny-via-exit-2-recipe)))
