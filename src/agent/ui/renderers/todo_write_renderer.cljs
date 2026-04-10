(ns agent.ui.renderers.todo-write-renderer
  "Renderer for the `todo_write` tool. Displays each todo as a checkbox
   row grouped under a header."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["ink-spinner$default" :as Spinner]
            [agent.ui.tool-renderer-registry :refer [register-renderer]]))

(defn- todos-from [args]
  (or (get args :todos) (get args "todos") []))

(defn- status-glyph [status]
  (case (str status)
    "completed"   "[x]"
    "in_progress" "[⋯]"
    "cancelled"   "[~]"
    "[ ]"))

(defn- status-color [status theme]
  (case (str status)
    "completed"   (get-in theme [:colors :success] "#9ece6a")
    "in_progress" (get-in theme [:colors :warning] "#e0af68")
    "cancelled"   (get-in theme [:colors :muted]   "#565f89")
    (get-in theme [:colors :muted] "#565f89")))

(defn- TodoList [{:keys [todos theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")]
    #jsx [Box {:flexDirection "column" :marginLeft 2}
          (for [[i todo] (map-indexed vector todos)]
            (let [status (or (get todo :status) (get todo "status"))
                  content (or (get todo :content) (get todo "content") "")]
              #jsx [Box {:key i :flexDirection "row"}
                    [Text {:color (status-color status theme)}
                     (str (status-glyph status) " ")]
                    [Text {:color muted} content]]))]))

(defn- TodoCall [{:keys [args theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        n     (count (todos-from args))]
    #jsx [Box {:flexDirection "column"}
          [Box {:flexDirection "row"}
           [Text {:color muted} " "]
           [Spinner {:type "dots"}]
           [Text {:color muted} (str " todo_write — " n " items")]]
          [TodoList {:todos (todos-from args) :theme theme}]]))

(defn- TodoResult [{:keys [args theme]}]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        n       (count (todos-from args))]
    #jsx [Box {:flexDirection "column"}
          [Box {:flexDirection "row"}
           [Text {:color success} (str "✓ todo_write — " n " items")]]
          [TodoList {:todos (todos-from args) :theme theme}]]))

(register-renderer "todo_write"
  {:render-call   TodoCall
   :render-result TodoResult
   :inline?       true})
