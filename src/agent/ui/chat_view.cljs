(ns agent.ui.chat-view
  {:squint/extension "jsx"}
  (:require ["react" :refer [useMemo]]
            ["ink" :refer [Box Text Static]]
            ["./tool_status.jsx" :refer [ToolStartStatus ToolEndStatus]]))

(defn role-prefix [role]
  (case role "user" "❯ " "assistant" "● " "tool-call" "⚙ " "error" "✗ " "  "))

(defn role-color [theme role]
  (case role
    "user"      (get-in theme [:colors :primary])
    "assistant" (get-in theme [:colors :secondary])
    "tool-call" (get-in theme [:colors :muted])
    "error"     (get-in theme [:colors :error])
    nil))

(defn MessageBubble [{:keys [message theme]}]
  (let [role (:role message)]
    (case role
      "tool-start"
      #jsx [ToolStartStatus {:tool-name (:tool-name message)
                              :args      (:args message)
                              :verbosity (:verbosity message)
                              :theme     theme}]

      "tool-end"
      #jsx [ToolEndStatus {:tool-name (:tool-name message)
                            :duration  (:duration message)
                            :result    (:result message)
                            :verbosity (:verbosity message)
                            :max-lines (:max-lines message)
                            :theme     theme}]

      ;; Default: text-based messages (user, assistant, error, etc.)
      (let [content (:content message)
            color   (role-color theme role)]
        #jsx [Box {:flexDirection "column" :marginBottom 1}
              [Text {:color color :bold (= role "user")}
               (str (role-prefix role) content)]]))))

(defn ChatView [{:keys [messages theme]}]
  (let [completed (when (> (count messages) 1) (butlast messages))
        current   (last messages)]
    #jsx [Box {:flexDirection "column" :flexGrow 1}
          [Static {:items (clj->js (or completed []))}
           (fn [msg i]
             #jsx [MessageBubble {:key i :message msg :theme theme}])]
          (when current
            #jsx [MessageBubble {:message current :theme theme}])]))
