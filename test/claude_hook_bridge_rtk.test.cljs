(ns claude-hook-bridge-rtk.test
  "End-to-end test of the README's rtk integration example.

   Writes the documented .nyma/settings.json verbatim, fires
   PreToolUse with a real bash command nyma would generate, runs
   `rtk hook claude` as the actual subprocess, and verifies the
   merged response contains the expected updatedInput.command
   rewrite.

   Skips if rtk isn't on PATH (so CI without rtk doesn't fail).
   Documents the canonical user-facing config so any future
   regression in the bridge will surface here."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["node:child_process" :as cp]
            [agent.extensions.claude-hook-bridge.config :as config]
            [agent.extensions.claude-hook-bridge.dispatch :as dispatch]
            [agent.extensions.claude-hook-bridge.tool-names :as tool-names]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))

(defn- rtk-on-path? []
  (try
    (let [r (cp/spawnSync "which" #js ["rtk"])]
      (zero? (.-status r)))
    (catch :default _e false)))

(defn- write-json! [p obj]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p (js/JSON.stringify (clj->js obj) nil 2)))

(beforeEach (fn []
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-rtk-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-rtk-h-")))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

(defn ^:async test-readme-example-rewrites-git-status []
  (when (rtk-on-path?)
    ;; The literal README example.
    (write-json! (path/join @tmp-root ".nyma" "settings.json")
                 {:hooks
                  {:PreToolUse
                   [{:matcher "Bash"
                     :hooks [{:type "command"
                              :command "rtk hook claude"}]}]}})
    (let [{:keys [hooks]} (config/load-merged-hooks
                           @tmp-root {:claude false :agents false} @tmp-home)
          ;; Build the same stdin payload pre_tool_use.cljs would build.
          stdin   #js {:session_id      "test-session"
                       :transcript_path ""
                       :cwd             @tmp-root
                       :permission_mode "default"
                       :hook_event_name "PreToolUse"
                       :tool_name       (tool-names/cc-name "bash")
                       :tool_input      #js {:command "git status"}
                       :tool_use_id     "toolu_test"}
          merged  (js-await
                   (dispatch/dispatch
                    {:hooks-map     hooks
                     :event-name    "PreToolUse"
                     :discriminator (tool-names/cc-name "bash")
                     :stdin-payload stdin
                     :cwd           @tmp-root}))]
      ;; rtk should have rewritten the command.
      (-> (expect (some? (:updated-input merged))) (.toBe true))
      (let [updated (:updated-input merged)
            cmd     (.-command updated)]
        (-> (expect (string? cmd)) (.toBe true))
        (-> (expect (.includes cmd "git status")) (.toBe true))
        (-> (expect (.startsWith cmd "rtk ")) (.toBe true))))))

(defn ^:async test-readme-example-passes-through-non-rtk-cmd []
  (when (rtk-on-path?)
    (write-json! (path/join @tmp-root ".nyma" "settings.json")
                 {:hooks
                  {:PreToolUse
                   [{:matcher "Bash"
                     :hooks [{:type "command"
                              :command "rtk hook claude"}]}]}})
    (let [{:keys [hooks]} (config/load-merged-hooks
                           @tmp-root {:claude false :agents false} @tmp-home)
          stdin   #js {:hook_event_name "PreToolUse"
                       :tool_name       "Bash"
                       :tool_input      #js {:command "echo hello"}}
          merged  (js-await
                   (dispatch/dispatch
                    {:hooks-map     hooks
                     :event-name    "PreToolUse"
                     :discriminator "Bash"
                     :stdin-payload stdin
                     :cwd           @tmp-root}))]
      ;; rtk doesn't rewrite `echo` — bridge should produce no merged
      ;; effect (no updatedInput, no block).
      (-> (expect (or (nil? merged) (nil? (:updated-input merged)))) (.toBe true)))))

(defn ^:async test-readme-example-non-bash-tool-skipped []
  (when (rtk-on-path?)
    (write-json! (path/join @tmp-root ".nyma" "settings.json")
                 {:hooks
                  {:PreToolUse
                   [{:matcher "Bash"
                     :hooks [{:type "command"
                              :command "rtk hook claude"}]}]}})
    (let [{:keys [hooks]} (config/load-merged-hooks
                           @tmp-root {:claude false :agents false} @tmp-home)
          ;; Read tool — matcher "Bash" should NOT fire for this.
          merged  (js-await
                   (dispatch/dispatch
                    {:hooks-map     hooks
                     :event-name    "PreToolUse"
                     :discriminator "Read"
                     :stdin-payload #js {}
                     :cwd           @tmp-root}))]
      (-> (expect merged) (.toBeUndefined)))))

(describe "rtk-readme-example" (fn []
                                 (it "the README config rewrites `git status` → `rtk git status`"
                                     test-readme-example-rewrites-git-status)
                                 (it "non-rtk commands pass through unrewritten"
                                     test-readme-example-passes-through-non-rtk-cmd)
                                 (it "matcher \"Bash\" doesn't fire for non-bash tools"
                                     test-readme-example-non-bash-tool-skipped)))
