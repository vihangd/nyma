(ns agent.ui.chat-view
  {:squint/extension "jsx"}
  (:require ["react" :refer [useMemo]]
            ["ink" :refer [Box Text]]
            ["./tool_status.jsx" :refer [ToolGroupStatus group_messages]]
            ["./tool_execution.jsx" :refer [ToolExecution]]
            ["./streaming_markdown.mjs" :refer [useStreamingMarkdown]]))

(defn role-prefix [role]
  (case role "user" "❯ " "assistant" "● " "thinking" "💭 " "plan" "📋 " "tool-call" "⚙ " "error" "✗ " "  "))

(defn role-color [theme role]
  (case role
    "user"      (get-in theme [:colors :primary])
    "assistant" (get-in theme [:colors :secondary])
    "thinking"  "gray"
    "plan"      "cyan"
    "tool-call" (get-in theme [:colors :muted])
    "error"     (get-in theme [:colors :error])
    nil))

(defn AssistantMessage
  "Separate component for assistant messages — uses streaming markdown hook."
  [{:keys [content theme block-renderers]}]
  (let [rendered (useStreamingMarkdown content block-renderers)
        color    (role-color theme "assistant")]
    #jsx [Box {:flexDirection "column" :marginBottom 1}
          [Box {:flexDirection "row"}
           [Text {:color color} (role-prefix "assistant")]
           [Box {:flexDirection "column" :flexShrink 1}
            [Text {:wrap "word"} rendered]]]]))

(defn EvalMessage
  "Rendered result of an editor eval-mode expression (`$expr` /
   `$$expr`). Sibling to BashMessage; uses a `λ` header glyph and
   the theme's primary (blue) accent to distinguish at a glance.
   Falls back to an install hint when bb is not on PATH."
  [{:keys [message theme]}]
  (let [eval-color (get-in theme [:colors :primary] "#7aa2f7")
        err-color  (get-in theme [:colors :error] "#f7768e")
        muted      (get-in theme [:colors :muted] "#565f89")
        expr       (:expr message)
        stdout     (:stdout message)
        stderr     (:stderr message)
        exit-code  (:exit-code message)
        unavail?   (:unavailable? message)
        install    (:install-hint message)
        hidden?    (:local-only message)]
    #jsx [Box {:flexDirection "column" :marginBottom 1}
          [Box {:flexDirection "row"}
           [Text {:color eval-color :bold true} "λ "]
           [Text {:color eval-color :bold true} (str expr)]
           (when hidden?
             #jsx [Text {:color muted :dimColor true} "  (hidden from LLM)"])]
          (cond
            unavail?
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  [Text {:color err-color :bold true}
                   "Babashka (`bb`) is not installed or not on PATH."]
                  [Text {:color muted} "Install options:"]
                  [Text {:color muted}
                   "  brew install borkdude/brew/babashka"]
                  [Text {:color muted}
                   "  bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)"]]

            :else
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  (when (and stdout (> (count stdout) 0))
                    #jsx [Text {:wrap "word"} stdout])
                  (when (and stderr (> (count stderr) 0))
                    #jsx [Box {:flexDirection "column"}
                          [Text {:color muted :dimColor true} "stderr:"]
                          [Text {:color err-color :wrap "word"} stderr]])
                  (when (and exit-code (not (zero? exit-code)))
                    #jsx [Text {:color err-color :bold true}
                          (str "exit " exit-code)])])]))

(defn BashMessage
  "Rendered result of an editor bash-mode command (`!cmd` / `!!cmd`).
   Shows:
     - a bold command header in the editor-bash warning color
     - stdout in the default text color
     - stderr under a dim 'stderr:' label in the error color
     - an exit-code footer when non-zero
     - a 'hidden from LLM' hint when the message is :local-only
     - a 'BLOCKED' block when bash_suite vetoed the command"
  [{:keys [message theme]}]
  (let [bash-color  (get-in theme [:colors :warning] "#e0af68")
        err-color   (get-in theme [:colors :error] "#f7768e")
        muted       (get-in theme [:colors :muted] "#565f89")
        command     (:command message)
        stdout      (:stdout message)
        stderr      (:stderr message)
        exit-code   (:exit-code message)
        blocked?    (:blocked? message)
        reason      (:reason message)
        hidden?     (:local-only message)]
    #jsx [Box {:flexDirection "column" :marginBottom 1}
          [Box {:flexDirection "row"}
           [Text {:color bash-color :bold true} "❯ "]
           [Text {:color bash-color :bold true} (str command)]
           (when hidden?
             #jsx [Text {:color muted :dimColor true} "  (hidden from LLM)"])]
          (cond
            blocked?
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  [Text {:color err-color}
                   (str "BLOCKED: " (or reason "command blocked"))]]

            :else
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  (when (and stdout (> (count stdout) 0))
                    #jsx [Text {:wrap "word"} stdout])
                  (when (and stderr (> (count stderr) 0))
                    #jsx [Box {:flexDirection "column"}
                          [Text {:color muted :dimColor true} "stderr:"]
                          [Text {:color err-color :wrap "word"} stderr]])
                  (when (and exit-code (not (zero? exit-code)))
                    #jsx [Text {:color err-color :bold true}
                          (str "exit " exit-code)])])]))

(defn- MessageBubbleByRole
  "Role-based rendering (original MessageBubble logic). Extracted so
   that MessageBubble can branch on :kind first before dispatching
   on :role."
  [{:keys [message theme block-renderers]}]
  (let [role (:role message)]
    (case role
      "tool-group"
      #jsx [ToolGroupStatus {:tool-name (:tool-name message)
                             :items     (:items message)
                             :theme     theme}]

      "tool-start"
      #jsx [ToolExecution {:tool-name            (:tool-name message)
                           :args                 (:args message)
                           :verbosity            (:verbosity message)
                           :theme                theme
                           :is-partial           true
                           :custom-one-line-args (:custom-one-line-args message)
                           :custom-status-text   (:custom-status-text message)
                           :custom-icon          (:custom-icon message)}]

      "tool-end"
      #jsx [ToolExecution {:tool-name              (:tool-name message)
                           :args                   (:args message)
                           :duration               (:duration message)
                           :result                 (:result message)
                           :verbosity              (:verbosity message)
                           :max-lines              (:max-lines message)
                           :theme                  theme
                           :is-partial             false
                           :custom-one-line-result (:custom-one-line-result message)
                           :custom-icon            (:custom-icon message)}]

      ;; Default: text-based messages (user, assistant, error, etc.)
      (let [content (:content message)
            color   (role-color theme role)]
        (case role
          "assistant"
          #jsx [AssistantMessage {:content content :theme theme
                                  :block-renderers block-renderers}]

          "thinking"
          ;; Thinking messages: dimmed italic style
          #jsx [Box {:flexDirection "column" :marginBottom 0}
                [Text {:color "gray" :dimColor true}
                 (str (role-prefix role) content)]]

          "plan"
          ;; Plan messages: structured checklist style
          #jsx [Box {:flexDirection "column" :marginBottom 1}
                [Text {:color "cyan" :bold true} (role-prefix role)]
                [Box {:flexDirection "column" :marginLeft 2}
                 [Text {:color "cyan" :wrap "word"} content]]]

          ;; Other roles: plain text rendering
          #jsx [Box {:flexDirection "column" :marginBottom 1}
                [Text {:color color :bold (= role "user")}
                 (str (role-prefix role) content)]])))))

(defn MessageBubble [{:keys [message theme block-renderers]}]
  (let [kind (:kind message)]
    (cond
      ;; Editor bash mode (!cmd / !!cmd) — dedicated renderer. Checked
      ;; before :role because the message is still tagged :role
      ;; "assistant" so build-context picks it up for LLM context on
      ;; the next turn (unless :local-only, which is the !! case).
      (= kind :bash)
      #jsx [BashMessage {:message message :theme theme}]

      ;; Editor eval mode ($expr / $$expr) — sibling renderer with a
      ;; λ header and primary-color accent. Same :local-only rules
      ;; apply.
      (= kind :eval)
      #jsx [EvalMessage {:message message :theme theme}]

      :else
      #jsx [MessageBubbleByRole {:message message :theme theme
                                 :block-renderers block-renderers}])))

(defn ChatView [{:keys [messages theme block-renderers]}]
  (let [grouped (useMemo #(group_messages messages) #js [messages])]
    #jsx [Box {:flexDirection "column" :flexGrow 1 :overflow "hidden"
               :justifyContent "flex-end"}
          (map-indexed
           (fn [i msg]
             #jsx [MessageBubble {:key i :message msg :theme theme
                                  :block-renderers block-renderers}])
           grouped)]))
