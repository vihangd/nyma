(ns agent.ui.renderers.read-renderer
  "Renderer for the built-in `read` tool.
   Registers itself on load."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["ink-spinner$default" :as Spinner]
            [agent.ui.tool-renderer-registry :refer [register-renderer]]))

(defn- range-str [r]
  (cond
    (nil? r) ""
    (and (array? r) (>= (.-length r) 2)) (str ":" (aget r 0) "-" (aget r 1))
    :else ""))

(defn- ReadCall [{:keys [args theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        path  (or (get args :path) (get args "path") "?")
        range (or (get args :range) (get args "range"))]
    #jsx [Box {:flexDirection "row"}
          [Text {:color muted} " "]
          [Spinner {:type "dots"}]
          [Text {:color muted} (str " read " path (range-str range))]]))

(defn- ReadResult [{:keys [args result duration theme]}]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        muted   (get-in theme [:colors :muted] "#565f89")
        path    (or (get args :path) (get args "path") "?")
        lines   (when (and result (not= result ""))
                  (count (.split (str result) "\n")))
        dur-ms  (when duration (str " " (.toFixed (/ duration 1000) 1) "s"))]
    #jsx [Box {:flexDirection "row"}
          [Text {:color success} (str "✓ read " path)]
          (when lines
            #jsx [Text {:color muted} (str " — " lines " lines")])
          (when dur-ms #jsx [Text {:color muted} dur-ms])]))

(register-renderer "read"
  {:render-call   ReadCall
   :render-result ReadResult
   :inline?       true})
