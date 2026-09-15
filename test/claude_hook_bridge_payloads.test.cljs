(ns claude-hook-bridge-payloads.test
  "The JSON each bridge event writes to a hook's stdin.

   Thirteen bridge tests cover matching, config, response parsing and the
   audit log; none of them ever looked at the payload a hook script actually
   receives. A hook written for Claude Code reads fixed field names, so a
   renamed or dropped key is a silent no-op for every script in the
   ecosystem. Each case registers one event module against a fake api, fires
   the nyma event it listens on, and pins the object a `cat > file` hook saw.

   Field names are pinned as the builders emit them today. Where they
   diverge from the Claude Code hooks reference the case says so in a
   comment rather than asserting the reference — the divergence is a
   contract question for the extension, not for this test."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.claude-hook-bridge.events.pre-tool-use :as pre]
            [agent.extensions.claude-hook-bridge.events.post-tool-use :as post]
            [agent.extensions.claude-hook-bridge.events.session :as session]
            [agent.extensions.claude-hook-bridge.events.stop :as stop]
            [agent.extensions.claude-hook-bridge.events.permission-request :as perm]
            [agent.extensions.claude-hook-bridge.events.user-prompt-submit :as ups]
            [agent.extensions.claude-hook-bridge.events.compact :as compact]))

(def tmp (atom nil))

(beforeEach (fn [] (reset! tmp (fs/mkdtempSync (path/join (os/tmpdir) "nyma-hook-payload-")))))
(afterEach (fn [] (try (fs/rmSync @tmp #js {:recursive true :force true})
                       (catch :default _e nil))))

(defn- capture-file [] (path/join @tmp "stdin.json"))

(defn- hooks-for
  "A hooks map with one unmatched command hook on `event` that copies its
   stdin verbatim to the capture file. Unmatched, so every discriminator
   (tool name, session source, nil for Stop) fires it."
  [event]
  {event [#js {:hooks #js [#js {:type "command"
                                :command (str "sh -c 'cat > " (capture-file) "'")}]}]})

(defn- fake-api
  "Enough of the extension api for register!: the subscription table and the
   state the PreToolUse builder reads its session fields from."
  []
  (let [handlers (atom {})]
    {:api #js {:on  (fn [ev h & _] (swap! handlers assoc ev h))
               :off (fn [ev _] (swap! handlers dissoc ev))
               :getState (fn [] #js {:sessionId "sess-42"
                                     :transcriptPath "/tmp/t.jsonl"
                                     :permissionMode "acceptEdits"})
               :abortController nil
               :sendMessage (fn [_] nil)}
     :handlers handlers}))

(defn- ^:async fire!
  "Register `register-fn` for `cc-event`, fire nyma's `nyma-event` with
   `data`, and return the parsed stdin JSON the hook received."
  [register-fn cc-event nyma-event data]
  (let [{:keys [api handlers]} (fake-api)
        dispose (register-fn {:api api :hooks-atom (atom (hooks-for cc-event)) :cwd @tmp})
        handler (get @handlers nyma-event)]
    (-> (expect (fn? handler)) (.toBe true))
    (js-await (handler data))
    (dispose)
    (-> (expect (count @handlers)) (.toBe 0))
    (js/JSON.parse (fs/readFileSync (capture-file) "utf8"))))

(defn- common-fields [p event-name]
  (-> (expect (.-hook_event_name p)) (.toBe event-name))
  (-> (expect (.-cwd p)) (.toBe (js/process.cwd)))
  (-> (expect (string? (.-session_id p))) (.toBe true))
  (-> (expect (string? (.-transcript_path p))) (.toBe true)))

(describe "PreToolUse payload" (fn []
                                 (it "carries the CC tool name, tool_input, tool_use_id and the live session fields"
                                     (^:async fn []
                                       (let [p (js-await (fire! pre/register! "PreToolUse" "before_tool_call"
                                                                #js {:name "bash" :toolName "bash"
                                                                     :args #js {:command "git status"}
                                                                     :execId "exec-7"}))]
                                         (common-fields p "PreToolUse")
                                         (-> (expect (.-tool_name p)) (.toBe "Bash"))
                                         (-> (expect (.. p -tool_input -command)) (.toBe "git status"))
                                         (-> (expect (.-tool_use_id p)) (.toBe "exec-7"))
          ;; The only builder that reads api.getState — the others hardcode
          ;; "session" / "" / "default" (pinned below).
                                         (-> (expect (.-session_id p)) (.toBe "sess-42"))
                                         (-> (expect (.-transcript_path p)) (.toBe "/tmp/t.jsonl"))
                                         (-> (expect (.-permission_mode p)) (.toBe "acceptEdits")))))

                                 (it "passes an mcp__ tool name through and defaults tool_use_id to empty"
                                     (^:async fn []
                                       (let [p (js-await (fire! pre/register! "PreToolUse" "before_tool_call"
                                                                #js {:name "mcp__memory__store" :args #js {}}))]
                                         (-> (expect (.-tool_name p)) (.toBe "mcp__memory__store"))
                                         (-> (expect (.-tool_use_id p)) (.toBe "")))))))

(describe "PostToolUse / PostToolUseFailure payload" (fn []
                                                       (it "success: tool_result holds the stringified result and error_message is absent"
                                                           (^:async fn []
                                                             (let [p (js-await (fire! post/register! "PostToolUse" "tool_complete"
                                                                                      #js {:toolName "read" :args #js {:path "a.txt"}
                                                                                           :toolCallId "call-1" :result "contents"
                                                                                           :isError false}))]
                                                               (common-fields p "PostToolUse")
                                                               (-> (expect (.-tool_name p)) (.toBe "Read"))
                                                               (-> (expect (.. p -tool_input -path)) (.toBe "a.txt"))
                                                               (-> (expect (.-tool_use_id p)) (.toBe "call-1"))
          ;; Claude Code names this field `tool_response`; the bridge emits
          ;; `tool_result`. Pinned as emitted.
                                                               (-> (expect (.-tool_result p)) (.toBe "contents"))
                                                               (-> (expect (nil? (.-error_message p))) (.toBe true))
                                                               (-> (expect (.-permission_mode p)) (.toBe "default")))))

                                                       (it "failure: switches the event name and moves the result into error_message"
                                                           (^:async fn []
                                                             (let [p (js-await (fire! post/register! "PostToolUseFailure" "tool_complete"
                                                                                      #js {:toolName "bash" :args #js {:command "x"}
                                                                                           :toolCallId "call-2" :result "boom"
                                                                                           :isError true}))]
                                                               (common-fields p "PostToolUseFailure")
                                                               (-> (expect (.-tool_name p)) (.toBe "Bash"))
          ;; Claude Code names this `error`; the bridge emits `error_message`.
                                                               (-> (expect (.-error_message p)) (.toBe "boom"))
                                                               (-> (expect (nil? (.-tool_result p))) (.toBe true)))))

                                                       (it "failure: falls back to errorMessage when there is no result"
                                                           (^:async fn []
                                                             (let [p (js-await (fire! post/register! "PostToolUseFailure" "tool_complete"
                                                                                      #js {:toolName "bash" :args #js {}
                                                                                           :errorMessage "denied" :isError true}))]
                                                               (-> (expect (.-error_message p)) (.toBe "denied")))))))

(describe "SessionStart / SessionEnd payload" (fn []
                                                (it "SessionStart maps nyma's reason to a CC source (new → startup)"
                                                    (^:async fn []
                                                      (let [p (js-await (fire! session/register! "SessionStart" "session_start"
                                                                               #js {:reason "new"}))]
                                                        (common-fields p "SessionStart")
                                                        (-> (expect (.-source p)) (.toBe "startup"))
          ;; Emitted alongside `source` with the same value; CC's SessionStart
          ;; has no `reason` field.
                                                        (-> (expect (.-reason p)) (.toBe "startup")))))

                                                (it "SessionStart: fork and tree both read as resume"
                                                    (^:async fn []
                                                      (-> (expect (.-source (js-await (fire! session/register! "SessionStart" "session_start"
                                                                                             #js {:reason "fork"}))))
                                                          (.toBe "resume"))
                                                      (-> (expect (.-source (js-await (fire! session/register! "SessionStart" "session_start"
                                                                                             #js {:reason "tree"}))))
                                                          (.toBe "resume"))))

                                                (it "SessionEnd maps clear → clear and everything else → other"
                                                    (^:async fn []
                                                      (let [p (js-await (fire! session/register! "SessionEnd" "session_end"
                                                                               #js {:reason "clear"}))]
                                                        (common-fields p "SessionEnd")
                                                        (-> (expect (.-reason p)) (.toBe "clear")))
                                                      (-> (expect (.-reason (js-await (fire! session/register! "SessionEnd" "session_shutdown"
                                                                                             #js {:reason "sigint"}))))
                                                          (.toBe "other"))))))

(describe "Stop / StopFailure payload" (fn []
                                         (it "Stop reads finishReason and usage.outputTokens off agent_end"
                                             (^:async fn []
                                               (let [p (js-await (fire! stop/register! "Stop" "agent_end"
                                                                        #js {:text "done" :finishReason "stop"
                                                                             :usage #js {:outputTokens 17}}))]
                                                 (common-fields p "Stop")
          ;; CC's Stop payload carries `stop_hook_active`; neither of these
          ;; two is in the reference. Pinned as emitted.
                                                 (-> (expect (.-stop_reason p)) (.toBe "stop"))
                                                 (-> (expect (.-output_tokens p)) (.toBe 17)))))

                                         (it "Stop defaults to end_turn / 0 for the bare finish part"
                                             (^:async fn []
                                               (let [p (js-await (fire! stop/register! "Stop" "agent_end" #js {}))]
                                                 (-> (expect (.-stop_reason p)) (.toBe "end_turn"))
                                                 (-> (expect (.-output_tokens p)) (.toBe 0)))))

                                         (it "StopFailure carries error_type and the message string or nested error"
                                             (^:async fn []
                                               (let [p (js-await (fire! stop/register! "StopFailure" "provider_error"
                                                                        #js {:errorType "rate_limit" :message "slow down"}))]
                                                 (common-fields p "StopFailure")
                                                 (-> (expect (.-error_type p)) (.toBe "rate_limit"))
                                                 (-> (expect (.-error_message p)) (.toBe "slow down")))
                                               (let [p (js-await (fire! stop/register! "StopFailure" "provider_error"
                                                                        #js {:message #js {:error "nested"}}))]
                                                 (-> (expect (.-error_type p)) (.toBe "unknown"))
                                                 (-> (expect (.-error_message p)) (.toBe "nested")))))))

(describe "PermissionRequest payload" (fn []
                                        (it "reads the tool off :tool (not :name) and CC-cases it"
                                            (^:async fn []
                                              (let [p (js-await (fire! perm/register! "PermissionRequest" "permission_request"
                                                                       #js {:tool "write" :args #js {:path "out.txt"}}))]
                                                (common-fields p "PermissionRequest")
                                                (-> (expect (.-tool_name p)) (.toBe "Write"))
                                                (-> (expect (.. p -tool_input -path)) (.toBe "out.txt"))
                                                (-> (expect (.-permission_mode p)) (.toBe "default")))))))

(describe "UserPromptSubmit payload" (fn []
                                       (it "carries the prompt from :text, falling back to :prompt"
                                           (^:async fn []
                                             (let [p (js-await (fire! ups/register! "UserPromptSubmit" "input_submit"
                                                                      #js {:text "fix the tests"}))]
                                               (common-fields p "UserPromptSubmit")
                                               (-> (expect (.-prompt p)) (.toBe "fix the tests")))
                                             (-> (expect (.-prompt (js-await (fire! ups/register! "UserPromptSubmit" "input_submit"
                                                                                    #js {:prompt "via prompt"}))))
                                                 (.toBe "via prompt"))))))

(describe "PreCompact / PostCompact payload" (fn []
                                               (it "PreCompact carries the trigger, defaulting to auto"
                                                   (^:async fn []
                                                     (let [p (js-await (fire! compact/register! "PreCompact" "before_compact"
                                                                              #js {:trigger "manual"}))]
                                                       (common-fields p "PreCompact")
                                                       (-> (expect (.-trigger p)) (.toBe "manual")))
                                                     (-> (expect (.-trigger (js-await (fire! compact/register! "PreCompact" "before_compact"
                                                                                             #js {}))))
                                                         (.toBe "auto"))))

                                               (it "PostCompact adds tokens_removed"
                                                   (^:async fn []
                                                     (let [p (js-await (fire! compact/register! "PostCompact" "compact"
                                                                              #js {:trigger "auto" :tokensRemoved 1234}))]
                                                       (common-fields p "PostCompact")
                                                       (-> (expect (.-trigger p)) (.toBe "auto"))
          ;; Not a CC field (the reference has `compact_summary`); pinned.
                                                       (-> (expect (.-tokens_removed p)) (.toBe 1234)))))))
