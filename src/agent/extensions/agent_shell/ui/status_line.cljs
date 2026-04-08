(ns agent.extensions.agent-shell.ui.status-line
  "Custom footer component showing ACP agent status, usage, and cost."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]))

(defn- progress-bar
  "Render a text-based progress bar."
  [ratio width]
  (let [filled (js/Math.round (* ratio width))
        empty  (- width filled)]
    (str (apply str (repeat filled "\u2588"))
         (apply str (repeat empty "\u2591")))))

(defn StatusLine [{:keys [theme]}]
  (let [agent-key  @shared/active-agent
        muted      (or (get-in theme [:colors :muted]) "#565f89")
        accent     (or (get-in theme [:colors :primary]) "#7aa2f7")]
    (if-not agent-key
      ;; No agent connected — show default footer
      #jsx [Box {:paddingX 1 :justifyContent "space-between"}
            [Text {:color muted} "ctrl+c exit  /help commands  /agent-shell__agent connect"]
            [Text {:color muted} "nyma v0.1.0"]]
      ;; Agent connected — show rich status line
      (let [state      (get @shared/agent-state agent-key)
            agent-def  (get registry/agents agent-key)
            agent-name (or (:name agent-def) (shared/kw-name agent-key))
            model      (:model state)
            mode       (:mode state)
            usage      (:usage state)
            turn-usage (:turn-usage state)
            used       (:used usage)
            size       (:size usage)
            cost       (:cost usage)
            ratio      (when (and used size (> size 0)) (/ used size))]
        #jsx [Box {:paddingX 1 :justifyContent "space-between"}
              [Box {:gap 1}
               [Text {:color accent :bold true} (str "[" (shared/kw-name agent-key) "]")]
               (when model
                 #jsx [Text {:color "#bb9af7"} model])
               (when mode
                 #jsx [Text {:color "#e0af68"} (str "\u2502 " mode)])]
              [Box {:gap 1}
               (when ratio
                 #jsx [Text {:color muted}
                       (str "\u2502 ctx: " (shared/format-k used) "/" (shared/format-k size)
                            " (" (js/Math.round (* 100 ratio)) "%) "
                            (progress-bar ratio 8))])
               (when cost
                 #jsx [Text {:color "#9ece6a"}
                       (str "\u2502 $" (.toFixed (:amount cost) 2))])
               (when (:input-tokens turn-usage)
                 #jsx [Text {:color muted}
                       (str "\u2502 \u2191" (shared/format-k (:input-tokens turn-usage))
                            " \u2193" (shared/format-k (:output-tokens turn-usage)))])
               (when (:cached-read turn-usage)
                 #jsx [Text {:color muted}
                       (str "\u2502 cache:" (shared/format-k (:cached-read turn-usage)))])]]))))

(defn render
  "Factory function for ui.setFooter()."
  []
  #jsx [StatusLine {}])
