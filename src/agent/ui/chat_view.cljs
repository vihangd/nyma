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

(defn MessageBubble [{:keys [message theme block-renderers]}]
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

(defn ChatView [{:keys [messages theme block-renderers]}]
  (let [grouped (useMemo #(group_messages messages) #js [messages])]
    #jsx [Box {:flexDirection "column" :flexGrow 1 :overflow "hidden"
               :justifyContent "flex-end"}
          (map-indexed
            (fn [i msg]
              #jsx [MessageBubble {:key i :message msg :theme theme
                                    :block-renderers block-renderers}])
            grouped)]))
