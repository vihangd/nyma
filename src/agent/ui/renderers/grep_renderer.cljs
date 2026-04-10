(ns agent.ui.renderers.grep-renderer
  "Renderer for the built-in `grep` tool. Shows the result summary with
   the match count."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["ink-spinner$default" :as Spinner]
            [agent.ui.tool-renderer-registry :refer [register-renderer]]))

(defn- GrepCall [{:keys [args theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        pat   (or (get args :pattern) (get args "pattern") "?")
        path  (or (get args :path) (get args "path"))]
    #jsx [Box {:flexDirection "row"}
          [Text {:color muted} " "]
          [Spinner {:type "dots"}]
          [Text {:color muted}
           (str " grep \"" pat "\"" (when (seq path) (str " in " path)))]]))

(defn- GrepResult [{:keys [args result duration theme]}]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        muted   (get-in theme [:colors :muted] "#565f89")
        pat     (or (get args :pattern) (get args "pattern") "?")
        matches (when (and result (not= result ""))
                  (count (.split (str result) "\n")))
        dur-ms  (when duration (str " " (.toFixed (/ duration 1000) 1) "s"))]
    #jsx [Box {:flexDirection "row"}
          [Text {:color success} (str "✓ grep \"" pat "\"")]
          (when matches
            #jsx [Text {:color muted} (str " — " matches " matches")])
          (when dur-ms #jsx [Text {:color muted} dur-ms])]))

(register-renderer "grep"
  {:render-call   GrepCall
   :render-result GrepResult
   :inline?       true})
