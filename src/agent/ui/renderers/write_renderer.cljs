(ns agent.ui.renderers.write-renderer
  "Renderer for the built-in `write` tool."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["ink-spinner$default" :as Spinner]
            [agent.ui.tool-renderer-registry :refer [register-renderer]]))

(defn- WriteCall [{:keys [args theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        path  (or (get args :path) (get args "path") "?")
        content (or (get args :content) (get args "content") "")
        bytes (.-length (str content))]
    #jsx [Box {:flexDirection "row"}
          [Text {:color muted} " "]
          [Spinner {:type "dots"}]
          [Text {:color muted} (str " write " path " (" bytes " bytes)")]]))

(defn- WriteResult [{:keys [args duration theme]}]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        muted   (get-in theme [:colors :muted] "#565f89")
        path    (or (get args :path) (get args "path") "?")
        content (or (get args :content) (get args "content") "")
        bytes   (.-length (str content))
        dur-ms  (when duration (str " " (.toFixed (/ duration 1000) 1) "s"))]
    #jsx [Box {:flexDirection "row"}
          [Text {:color success} (str "✓ write " path)]
          [Text {:color muted} (str " — " bytes " bytes")]
          (when dur-ms #jsx [Text {:color muted} dur-ms])]))

(register-renderer "write"
  {:render-call   WriteCall
   :render-result WriteResult
   :inline?       true})
