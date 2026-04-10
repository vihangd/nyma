(ns agent.ui.renderers.edit-renderer
  "Renderer for the built-in `edit` tool. When the result contains a
   diff-shaped payload, show the word-level diff view; otherwise fall
   back to a compact summary row."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["ink-spinner$default" :as Spinner]
            ["../diff_view.jsx" :refer [DiffView]]
            [agent.ui.tool-renderer-registry :refer [register-renderer]]))

(defn- diff-shaped?
  "A result is diff-shaped when at least one line starts with '+N|',
   '-N|', or ' N|'. This matches parse-diff-line's accepted forms."
  [s]
  (and (string? s)
       (some? (.match s (js/RegExp. "(^|\\n)[-+ ]\\s*\\d+\\s*\\|")))))

(defn- EditCall [{:keys [args theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        path  (or (get args :path) (get args "path") "?")]
    #jsx [Box {:flexDirection "row"}
          [Text {:color muted} " "]
          [Spinner {:type "dots"}]
          [Text {:color muted} (str " edit " path)]]))

(defn- EditResult [{:keys [args result duration theme]}]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        muted   (get-in theme [:colors :muted] "#565f89")
        path    (or (get args :path) (get args "path") "?")
        dur-ms  (when duration (str " " (.toFixed (/ duration 1000) 1) "s"))
        show-diff? (diff-shaped? result)]
    #jsx [Box {:flexDirection "column"}
          [Box {:flexDirection "row"}
           [Text {:color success} (str "✓ edit " path)]
           (when dur-ms #jsx [Text {:color muted} dur-ms])]
          (when show-diff?
            #jsx [Box {:marginLeft 2}
                  [DiffView {:diff-text result :theme theme}]])]))

(register-renderer "edit"
  {:render-call   EditCall
   :render-result EditResult
   :inline?       true})
