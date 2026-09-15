(ns claude-hook-bridge-payloads.test
  "The JSON each bridge event writes to a hook's stdin.

   Thirteen bridge tests cover matching, config, response parsing and the
   audit log; none of them ever looked at the payload a hook script actually
   receives. A hook written for Claude Code reads fixed field names, so a
   renamed or dropped key is a silent no-op for every script in the
   ecosystem. Each case registers one event module against a fake api, fires
   the nyma event it listens on, and pins the object a `cat > file` hook saw.

   Field names follow the Claude Code hooks reference
   (https://code.claude.com/docs/en/hooks): the common input fields on every
   event, plus each event's documented extras. nyma-only extras are pinned
   too, marked as such."
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
  "A hooks map with one unmatched command hook on `event` running `command`
   (default: copy stdin verbatim to the capture file). Unmatched, so every
   discriminator (tool name, session source, nil for Stop) fires it."
  ([event] (hooks-for event (str "sh -c 'cat > " (capture-file) "'")))
  ([event command]
   {event [#js {:hooks #js [#js {:type "command" :command command}]}]}))

(def session-file "/tmp/nyma-sessions/sess-42.jsonl")

(defn- fake-api
  "Enough of the extension api for register!: the subscription table plus
   the session file and state the common-field builder reads from. The
   state is a squint map, i.e. a plain object keyed `permission-mode` — the
   real agent state, not a camelCase stand-in."
  []
  (let [handlers (atom {})]
    {:api #js {:on  (fn [ev h & _] (swap! handlers assoc ev h))
               :off (fn [ev _] (swap! handlers dissoc ev))
               :getState (fn [] {:permission-mode "acceptEdits"})
               :getSessionFile (fn [] session-file)
               :abortController nil
               :sendMessage (fn [_] nil)}
     :handlers handlers}))

(defn- ^:async fire!
  "Register `register-fn` for `cc-event`, fire nyma's `nyma-event` with
   `data`, and return the parsed stdin JSON the hook received."
  ([register-fn cc-event nyma-event data]
   (fire! register-fn cc-event nyma-event data (hooks-for cc-event)))
  ([register-fn cc-event nyma-event data hooks]
   (let [{:keys [api handlers]} (fake-api)
         dispose (register-fn {:api api :hooks-atom (atom hooks) :cwd @tmp})
         handler (get @handlers nyma-event)]
     (-> (expect (fn? handler)) (.toBe true))
     (js-await (handler data))
     (dispose)
     (-> (expect (count @handlers)) (.toBe 0))
     (when (fs/existsSync (capture-file))
       (js/JSON.parse (fs/readFileSync (capture-file) "utf8"))))))

(defn- common-fields
  "The five common input fields, read live: session_id is the session file's
   basename (the id /resume lists), transcript_path the file itself."
  [p event-name]
  (-> (expect (.-hook_event_name p)) (.toBe event-name))
  (-> (expect (.-cwd p)) (.toBe (js/process.cwd)))
  (-> (expect (.-session_id p)) (.toBe "sess-42"))
  (-> (expect (.-transcript_path p)) (.toBe session-file))
  (-> (expect (.-permission_mode p)) (.toBe "acceptEdits")))

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
                                         (-> (expect (.-tool_use_id p)) (.toBe "exec-7")))))

                                 (it "passes an mcp__ tool name through and defaults tool_use_id to empty"
                                     (^:async fn []
                                       (let [p (js-await (fire! pre/register! "PreToolUse" "before_tool_call"
                                                                #js {:name "mcp__memory__store" :args #js {}}))]
                                         (-> (expect (.-tool_name p)) (.toBe "mcp__memory__store"))
                                         (-> (expect (.-tool_use_id p)) (.toBe "")))))

                                 (it "falls back to session / empty / default when the api has no session yet"
                                     (^:async fn []
                                       (let [handlers (atom {})
                                             api #js {:on (fn [ev h & _] (swap! handlers assoc ev h))
                                                      :off (fn [ev _] (swap! handlers dissoc ev))
                                                      :getState (fn [] nil)
                                                      :getSessionFile (fn [] nil)}
                                             dispose (pre/register! {:api api :hooks-atom (atom (hooks-for "PreToolUse")) :cwd @tmp})]
                                         (js-await ((get @handlers "before_tool_call") #js {:name "bash" :args #js {}}))
                                         (dispose)
                                         (let [p (js/JSON.parse (fs/readFileSync (capture-file) "utf8"))]
                                           (-> (expect (.-session_id p)) (.toBe "session"))
                                           (-> (expect (.-transcript_path p)) (.toBe ""))
                                           (-> (expect (.-permission_mode p)) (.toBe "default"))))))))

(describe "PostToolUse / PostToolUseFailure payload" (fn []
                                                       (it "success: tool_response holds the stringified result and error is absent"
                                                           (^:async fn []
                                                             (let [p (js-await (fire! post/register! "PostToolUse" "tool_complete"
                                                                                      #js {:toolName "read" :args #js {:path "a.txt"}
                                                                                           :toolCallId "call-1" :result "contents"
                                                                                           :isError false}))]
                                                               (common-fields p "PostToolUse")
                                                               (-> (expect (.-tool_name p)) (.toBe "Read"))
                                                               (-> (expect (.. p -tool_input -path)) (.toBe "a.txt"))
                                                               (-> (expect (.-tool_use_id p)) (.toBe "call-1"))
                                                               (-> (expect (.-tool_response p)) (.toBe "contents"))
                                                               (-> (expect (nil? (.-error p))) (.toBe true))
                                                               (-> (expect (nil? (.-tool_result p))) (.toBe true)))))

                                                       (it "failure: switches the event name and moves the result into error"
                                                           (^:async fn []
                                                             (let [p (js-await (fire! post/register! "PostToolUseFailure" "tool_complete"
                                                                                      #js {:toolName "bash" :args #js {:command "x"}
                                                                                           :toolCallId "call-2" :result "boom"
                                                                                           :isError true}))]
                                                               (common-fields p "PostToolUseFailure")
                                                               (-> (expect (.-tool_name p)) (.toBe "Bash"))
                                                               (-> (expect (.-error p)) (.toBe "boom"))
                                                               (-> (expect (nil? (.-tool_response p))) (.toBe true))
                                                               (-> (expect (nil? (.-error_message p))) (.toBe true)))))

                                                       (it "failure: falls back to errorMessage when there is no result"
                                                           (^:async fn []
                                                             (let [p (js-await (fire! post/register! "PostToolUseFailure" "tool_complete"
                                                                                      #js {:toolName "bash" :args #js {}
                                                                                           :errorMessage "denied" :isError true}))]
                                                               (-> (expect (.-error p)) (.toBe "denied")))))

                                                       (it "a Claude Code hook script reading .tool_response with plain sh works unchanged"
                                                           (^:async fn []
                                                             ;; No jq: the script pulls the field out of the raw JSON the
                                                             ;; way a portable CC hook would, and writes what it read.
                                                             (let [script (path/join @tmp "hook.sh")
                                                                   out    (path/join @tmp "seen.txt")]
                                                               (fs/writeFileSync script
                                                                                 (str "#!/bin/sh\n"
                                                                                      "input=$(cat)\n"
                                                                                      "printf '%s' \"$input\" | sed 's/.*\"tool_response\":\"\\([^\"]*\\)\".*/\\1/' > " out "\n"))
                                                               (js-await (fire! post/register! "PostToolUse" "tool_complete"
                                                                                #js {:toolName "read" :args #js {} :toolCallId "c"
                                                                                     :result "hello from the tool" :isError false}
                                                                                (hooks-for "PostToolUse" (str "sh " script))))
                                                               (-> (expect (fs/readFileSync out "utf8")) (.toBe "hello from the tool")))))))

(describe "SessionStart / SessionEnd payload" (fn []
                                                (it "SessionStart maps nyma's reason to a CC source (new → startup) and emits no reason"
                                                    (^:async fn []
                                                      (let [p (js-await (fire! session/register! "SessionStart" "session_start"
                                                                               #js {:reason "new"}))]
                                                        (common-fields p "SessionStart")
                                                        (-> (expect (.-source p)) (.toBe "startup"))
                                                        (-> (expect (nil? (.-reason p))) (.toBe true)))))

                                                (it "SessionStart: fork and tree both read as resume"
                                                    (^:async fn []
                                                      (-> (expect (.-source (js-await (fire! session/register! "SessionStart" "session_start"
                                                                                             #js {:reason "fork"}))))
                                                          (.toBe "resume"))
                                                      (-> (expect (.-source (js-await (fire! session/register! "SessionStart" "session_start"
                                                                                             #js {:reason "tree"}))))
                                                          (.toBe "resume"))))

                                                (it "SessionEnd carries reason (no source): clear → clear, user-exit → prompt_input_exit, else other"
                                                    (^:async fn []
                                                      (let [p (js-await (fire! session/register! "SessionEnd" "session_end"
                                                                               #js {:reason "clear"}))]
                                                        (common-fields p "SessionEnd")
                                                        (-> (expect (.-reason p)) (.toBe "clear"))
                                                        (-> (expect (nil? (.-source p))) (.toBe true)))
                                                      (-> (expect (.-reason (js-await (fire! session/register! "SessionEnd" "session_shutdown"
                                                                                             #js {:reason "user-exit"}))))
                                                          (.toBe "prompt_input_exit"))
                                                      (-> (expect (.-reason (js-await (fire! session/register! "SessionEnd" "session_shutdown"
                                                                                             #js {:reason "sigint"}))))
                                                          (.toBe "other"))))))

(describe "Stop / StopFailure payload" (fn []
                                         (it "Stop carries stop_hook_active false and last_assistant_message, plus nyma's stop_reason/output_tokens"
                                             (^:async fn []
                                               (let [p (js-await (fire! stop/register! "Stop" "agent_end"
                                                                        #js {:text "done" :finishReason "stop"
                                                                             :usage #js {:outputTokens 17}}))]
                                                 (common-fields p "Stop")
                                                 (-> (expect (.-stop_hook_active p)) (.toBe false))
                                                 (-> (expect (.-last_assistant_message p)) (.toBe "done"))
                                                 (-> (expect (.-stop_reason p)) (.toBe "stop"))
                                                 (-> (expect (.-output_tokens p)) (.toBe 17)))))

                                         (it "Stop defaults to end_turn / 0 / empty message for the bare finish part"
                                             (^:async fn []
                                               (let [p (js-await (fire! stop/register! "Stop" "agent_end" #js {}))]
                                                 (-> (expect (.-stop_reason p)) (.toBe "end_turn"))
                                                 (-> (expect (.-output_tokens p)) (.toBe 0))
                                                 (-> (expect (.-last_assistant_message p)) (.toBe "")))))

                                         (it "StopFailure carries the error type as `error` and the message as error_details"
                                             (^:async fn []
                                               (let [p (js-await (fire! stop/register! "StopFailure" "provider_error"
                                                                        #js {:errorType "rate_limit" :message "slow down"}))]
                                                 (common-fields p "StopFailure")
                                                 (-> (expect (.-error p)) (.toBe "rate_limit"))
                                                 (-> (expect (.-error_details p)) (.toBe "slow down"))
                                                 (-> (expect (nil? (.-error_type p))) (.toBe true)))
                                               (let [p (js-await (fire! stop/register! "StopFailure" "provider_error"
                                                                        #js {:message #js {:error "nested"}}))]
                                                 (-> (expect (.-error p)) (.toBe "unknown"))
                                                 (-> (expect (.-error_details p)) (.toBe "nested")))))))

(describe "PermissionRequest payload" (fn []
                                        (it "reads the tool off :tool (not :name), CC-cases it, and carries the live session fields"
                                            (^:async fn []
                                              (let [p (js-await (fire! perm/register! "PermissionRequest" "permission_request"
                                                                       #js {:tool "write" :args #js {:path "out.txt"}}))]
                                                (common-fields p "PermissionRequest")
                                                (-> (expect (.-tool_name p)) (.toBe "Write"))
                                                (-> (expect (.. p -tool_input -path)) (.toBe "out.txt")))))))

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
                                               (it "PreCompact carries the trigger (default auto) and custom_instructions (null when none)"
                                                   (^:async fn []
                                                     (let [p (js-await (fire! compact/register! "PreCompact" "before_compact"
                                                                              #js {:trigger "manual" :customInstructions "keep the TODOs"}))]
                                                       (common-fields p "PreCompact")
                                                       (-> (expect (.-trigger p)) (.toBe "manual"))
                                                       (-> (expect (.-custom_instructions p)) (.toBe "keep the TODOs")))
                                                     (let [p (js-await (fire! compact/register! "PreCompact" "before_compact" #js {}))]
                                                       (-> (expect (.-trigger p)) (.toBe "auto"))
                                                       (-> (expect (.-custom_instructions p)) (.toBe nil)))))

                                               (it "PostCompact carries compact_summary from the compact event, plus nyma's tokens_removed"
                                                   (^:async fn []
                                                     (let [p (js-await (fire! compact/register! "PostCompact" "compact"
                                                                              #js {:trigger "auto" :summary "we did things" :tokensRemoved 1234}))]
                                                       (common-fields p "PostCompact")
                                                       (-> (expect (.-trigger p)) (.toBe "auto"))
                                                       (-> (expect (.-compact_summary p)) (.toBe "we did things"))
                                                       (-> (expect (.-tokens_removed p)) (.toBe 1234)))))))
