(ns agent.ui.bash-execution
  "Rich renderer for streaming bash/python tool calls.

   Shows a bordered block containing:
     * the command (prefixed with $ or >>>)
     * a running tail of the command output, truncated to a few visible
       terminal rows when collapsed
     * a status row: spinner+hint while running, (exit N) / (cancelled) /
       (truncated) once complete
     * a bottom border

   The component is fed by app.cljs via `lines` (vector of strings) and
   `status` (keyword). It delegates ctrl+o expansion to the parent via
   onToggle — the parent owns whether the block is currently expanded."
  {:squint/extension "jsx"}
  (:require ["react" :refer [useMemo]]
            ["ink" :refer [Box Text useInput useStdout]]
            ["ink-spinner$default" :as Spinner]
            ["./dynamic_border.jsx" :refer [DynamicBorder]]
            [agent.ui.visual-truncate :refer [truncate-to-visual-lines]]))

(def ^:private PREVIEW-ROWS 8)

(defn- status-row
  [status exit-code hidden-count theme]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        error-c (get-in theme [:colors :error]   "#f7768e")
        warn    (get-in theme [:colors :warning] "#e0af68")
        muted   (get-in theme [:colors :muted]   "#565f89")
        trunc-note (when (pos? hidden-count)
                     (str "  (truncated — " hidden-count " earlier lines hidden)"))]
    (case status
      :running
      #jsx [Box {:flexDirection "row"}
            [Text {:color muted} " "]
            [Spinner {:type "dots"}]
            [Text {:color muted} " running  "]
            [Text {:color muted} "esc to cancel"]]

      :complete
      #jsx [Box {:flexDirection "row"}
            [Text {:color (if (zero? (or exit-code 0)) success error-c)}
             (str " (exit " (or exit-code 0) ")")]
            (when trunc-note #jsx [Text {:color muted} trunc-note])]

      :cancelled
      #jsx [Box {:flexDirection "row"}
            [Text {:color warn} " (cancelled)"]
            (when trunc-note #jsx [Text {:color muted} trunc-note])]

      :error
      #jsx [Box {:flexDirection "row"}
            [Text {:color error-c} (str " (error exit " (or exit-code 1) ")")]
            (when trunc-note #jsx [Text {:color muted} trunc-note])]

      ;; default
      #jsx [Text {:color muted} " "])))

(defn BashExecution
  "Props:
     :command      string
     :is-python    bool
     :lines        vector of strings (each is one output line)
     :status       :running :complete :cancelled :error
     :exit-code    int
     :expanded     bool
     :onToggle     fn [] — called when ctrl+o pressed
     :is-active    bool — whether this block owns keyboard focus
     :theme        theme map"
  [{:keys [command is-python lines status exit-code expanded onToggle is-active theme]}]
  (let [{:keys [stdout]} (useStdout)
        cols   (or (.-columns stdout) 80)
        border (get-in theme [:colors :border] "#3b4261")
        muted  (get-in theme [:colors :muted]  "#565f89")
        primary (get-in theme [:colors :primary] "#7aa2f7")
        header-glyph (if is-python ">>> " "$ ")
        {:keys [visible-lines hidden-count]}
        (useMemo
          (fn []
            (if expanded
              {:visible-lines (vec lines) :hidden-count 0}
              (truncate-to-visual-lines lines PREVIEW-ROWS cols)))
          #js [lines expanded cols])]

    (useInput
      (fn [input key]
        (when (and is-active (.-ctrl key) (= input "o"))
          (when onToggle (onToggle))))
      #js {:isActive (boolean is-active)})

    #jsx [Box {:flexDirection "column" :flexShrink 0}
          [DynamicBorder {:color border}]
          [Box {:flexDirection "row"}
           [Text {:color primary :bold true} header-glyph]
           [Text {:color primary} (or command "")]]
          (when (pos? (count visible-lines))
            #jsx [Box {:flexDirection "column"}
                  (for [[i line] (map-indexed vector visible-lines)]
                    #jsx [Text {:key i :color muted} (or line "")])])
          [status-row status exit-code hidden-count theme]
          [DynamicBorder {:color border}]]))
