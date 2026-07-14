(ns agent.extensions.checkpoints.index
  "File checkpoints + /rewind: snapshot a file's pre-turn state before any
   editing tool touches it; /rewind restores the newest turn's files (repeat
   to go further back). Session-scoped, in-memory."
  (:require [agent.extensions.checkpoints.shared :as shared]))

(defn ^:export activate [api]
  (let [checkpoints* (atom {})
        turn*        (atom 0)

        on-before-tool
        (fn [event _ctx]
          (when (shared/edit-tool? (.-toolName event))
            (when-let [path (some-> (.-args event) (aget "path"))]
              (shared/snapshot! checkpoints* @turn* path)))
          nil)

        on-finalize
        (fn [_event _ctx] (swap! turn* inc) nil)]

    (.on api "before_tool_call" on-before-tool)
    (.on api "turn_finalize" on-finalize)

    (.registerCommand api "rewind"
                      #js {:description "Restore files edited in the last turn. Usage: /rewind | /rewind list"
                           :handler
                           (fn [args ctx]
                             (let [notify (fn [m l] (when-let [ui (.-ui ctx)]
                                                      (when (.-notify ui) (.notify ui m l))))
                                   sub    (some-> (first args) str)]
                               (if (= sub "list")
                                 (let [points (shared/describe checkpoints*)]
                                   (notify (if (seq points)
                                             (str "Rewind points (newest first):\n"
                                                  (.join (clj->js points) "\n"))
                                             "No rewind points in this session.")
                                           "info"))
                                 (if-let [[turn paths] (shared/restore! checkpoints*)]
                                   (notify (str "Rewound turn " turn " — restored: "
                                                (.join (clj->js paths) ", "))
                                           "info")
                                   (notify "Nothing to rewind." "warning")))))})

    (fn []
      (.off api "before_tool_call" on-before-tool)
      (.off api "turn_finalize" on-finalize)
      (.unregisterCommand api "rewind"))))

(def ^:export default activate)
