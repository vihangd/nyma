(ns agent.extensions.agent-shell.ui.header
  "Custom header component showing agent name, model, mode, and session."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]))

(defn AgentHeader [{:keys [theme]}]
  (let [agent-key @shared/active-agent
        primary   (or (get-in theme [:colors :primary]) "#7aa2f7")
        muted     (or (get-in theme [:colors :muted]) "#565f89")
        border    (or (get-in theme [:colors :border]) "#3b4261")]
    (if-not agent-key
      ;; No agent — show default nyma header
      #jsx [Box {:borderStyle   "single"
                 :borderColor   border
                 :paddingX      1
                 :justifyContent "space-between"}
            [Text {:color primary :bold true} "nyma"]
            [Text {:color muted} "no agent connected"]]
      ;; Agent connected
      (let [state       (get @shared/agent-state agent-key)
            agent-def   (get registry/agents agent-key)
            agent-name  (or (:name agent-def) (shared/kw-name agent-key))
            model       (:model state)
            mode        (:mode state)
            session-title (:session-title state)]
        #jsx [Box {:borderStyle    "single"
                   :borderColor    border
                   :paddingX       1
                   :justifyContent "space-between"}
              [Box {:gap 1}
               [Text {:color primary :bold true} "nyma"]
               [Text {:color muted} "\u00d7"]
               [Text {:color "#bb9af7" :bold true} agent-name]
               (when model
                 #jsx [Text {:color muted} (str "\u2502 " model)])
               (when mode
                 #jsx [Text {:color "#e0af68"} (str "\u2502 " mode)])]
              [Box {:gap 1}
               (when session-title
                 #jsx [Text {:color muted} session-title])]]))))

(defn render
  "Factory function for ui.setHeader()."
  []
  #jsx [AgentHeader {}])
