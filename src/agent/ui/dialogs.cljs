(ns agent.ui.dialogs
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState]]
            ["ink" :refer [Box Text useInput]]
            ["ink-text-input$default" :as TextInput]))

;;; ─── Confirm Dialog ─────────────────────────────────────

(defn ConfirmDialog [{:keys [title message on-confirm on-cancel]}]
  (useInput
    (fn [input key]
      (cond
        (or (= input "y") (= input "Y")) (when on-confirm (on-confirm))
        (or (= input "n") (= input "N") (.-escape key)) (when on-cancel (on-cancel)))))
  #jsx [Box {:flexDirection "column"}
        (when title #jsx [Text {:bold true} title])
        [Text message]
        [Box {:marginTop 1 :gap 2}
         [Text {:color "#9ece6a"} "[Y]es"]
         [Text {:color "#f7768e"} "[N]o"]]])

;;; ─── Select Dialog ──────────────────────────────────────

(defn SelectDialog [{:keys [title options on-select on-cancel theme]}]
  (let [[selected set-selected] (useState 0)
        muted   (get-in theme [:colors :muted] "#565f89")
        primary (get-in theme [:colors :primary] "#7aa2f7")]
    (useInput
      (fn [input key]
        (cond
          (.-upArrow key)   (set-selected (fn [s] (max 0 (dec s))))
          (.-downArrow key) (set-selected (fn [s] (min (dec (count options)) (inc s))))
          (.-return key)    (when on-select (on-select (nth options selected)))
          (.-escape key)    (when on-cancel (on-cancel)))))
    #jsx [Box {:flexDirection "column"}
          [Text {:bold true} title]
          [Box {:flexDirection "column" :marginTop 1}
           (map-indexed
             (fn [i opt]
               (let [label (if (string? opt) opt (or (.-label opt) (str opt)))]
                 #jsx [Box {:key i}
                       [Text {:color (if (= i selected) primary muted)}
                        (str (if (= i selected) "> " "  ")
                             (inc i) ". " label)]]))
             options)]
          [Box {:marginTop 1}
           [Text {:color muted} "Up/Down navigate  Enter select  Esc cancel"]]]))

;;; ─── Input Dialog ───────────────────────────────────────

(defn InputDialog [{:keys [title placeholder on-submit on-cancel theme]}]
  (let [[value set-value] (useState "")
        muted (get-in theme [:colors :muted] "#565f89")]
    (useInput
      (fn [_input key]
        (when (.-escape key)
          (when on-cancel (on-cancel)))))
    #jsx [Box {:flexDirection "column"}
          [Text {:bold true} title]
          [Box {:marginTop 1 :borderStyle "round"
                :borderColor (get-in theme [:colors :border] "#3b4261")
                :paddingX 1}
           [TextInput {:value value
                       :onChange set-value
                       :placeholder (or placeholder "")
                       :onSubmit (fn [text]
                                   (when (and on-submit (seq (.trim text)))
                                     (on-submit (.trim text))))}]]
          [Box {:marginTop 1}
           [Text {:color muted} "Enter submit  Esc cancel"]]]))
