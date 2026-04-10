(ns agent.ui.footer
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            [agent.ui.key-hints :refer [hint-row]]))

(def ^:private default-hints
  "Actions shown in the footer hint row, in display order."
  [["app.help"           "help"]
   ["app.history.search" "history"]
   ["app.model.show"     "model"]
   ["app.interrupt"      "interrupt"]])

(defn Footer [{:keys [agent theme statuses]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        registry (when-let [r (and agent (:keybinding-registry agent))] @r)
        hints (if registry
                (hint-row registry default-hints)
                ;; Fallback for callers that don't pass a registry (tests, early boot).
                "? help  ^R history  ^L model  esc interrupt")
        status-str (when (and statuses (seq statuses))
                     (str " " (->> (vals statuses)
                                   (interpose " | ")
                                   (apply str))))]
    #jsx [Box {:paddingX 1
               :flexShrink 0}
          [Box {:flexGrow 1 :flexShrink 1 :overflow "hidden"}
           [Text {:color muted :wrap "truncate"} hints]]
          [Text {:color muted}
           (str (or status-str "") " | nyma v0.1.0")]]))
