(ns agent.ui.settings-ui
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState]]
            ["ink" :refer [Box Text useInput]]))

(defn SettingsUI [{:keys [settings onClose theme]}]
  (let [[selected set-selected] (useState 0)
        current-settings ((:get settings))
        setting-keys     (keys current-settings)]
    (useInput
      (fn [_input key]
        (cond
          (.-escape key) (onClose)
          (.-upArrow key) (set-selected (fn [s] (max 0 (dec s))))
          (.-downArrow key) (set-selected (fn [s] (min (dec (count setting-keys)) (inc s)))))))
    #jsx [Box {:flexDirection "column" :padding 1}
          [Text {:bold true :color (get-in theme [:colors :primary])}
           "Settings"]
          (map-indexed
            (fn [i k]
              #jsx [Box {:key i :marginLeft 2}
                    [Text {:color (if (= i selected)
                                    (get-in theme [:colors :primary])
                                    (get-in theme [:colors :muted]))}
                     (str k ": " (get current-settings k))]])
            setting-keys)]))
