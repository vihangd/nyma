(ns agent.extensions.checkpoints.index
  "File checkpoints + /rewind: snapshot a file's pre-turn state before any
   editing tool touches it; /rewind restores the newest turn's files (repeat
   to go further back). Session-scoped, in-memory."
  (:require [agent.extensions.checkpoints.shared :as shared]
            ["node:path" :as path]
            [agent.tool-metadata :as tool-metadata]))

(defn ^:export activate [api]
  (let [checkpoints* (atom {})
        pending*     (atom {})   ;; path → pre-state, awaiting confirmation
        turn*        (atom 0)
        ;; Every file this session changed — edit tools and bash alike — for
        ;; /diff. Never pruned with the rewind groups.
        touched*     (atom #{})
        touch!       (fn [p] (when p (swap! touched* conj (path/resolve (str p)))))
        on-bash-files (fn [data] (doseq [p (or (.-paths data) [])] (touch! p)) nil)

        ;; before_tool_call fires for EVERY handler regardless of another
        ;; handler blocking the call (emit-collect merges block afterwards),
        ;; and the permission gate's deny doesn't stop the event either. So:
        ;; capture the pre-state here (the file is still untouched), but only
        ;; PROMOTE it to a rewind point once tool_complete confirms the tool
        ;; actually ran — otherwise a denied edit creates a phantom rewind
        ;; point that shadows the previous turn's real one.
        on-before-tool
        (fn [event _ctx]
          (when (shared/edit-tool? (.-toolName event))
            (when-let [path (tool-metadata/tool-path (.-args event))]
              (shared/capture-pending! pending* path)))
          nil)

        on-complete
        (fn [event _ctx]
          (when (and (shared/edit-tool? (.-toolName event))
                     (not (.-cancelled event))
                     (not (.-isError event)))
            (when-let [p (tool-metadata/tool-path (.-args event))]
              (shared/promote! pending* checkpoints* @turn* p)
              (touch! p)))
          nil)

        on-finalize
        (fn [_event _ctx]
          (reset! pending* {})   ;; denied/failed leftovers are dropped
          (swap! turn* inc)
          nil)]

    (.on api "before_tool_call" on-before-tool)
    (.on api "tool_complete" on-complete)
    (.on api "turn_finalize" on-finalize)
    ;; Files a shell command changed (bash_suite edit-diff).
    (when-let [ev (.-events api)] (when (.-on ev) (.on ev "bash-suite__bash:files-changed" on-bash-files)))

    (.registerCommand api "diff"
                      #js {:description "Everything this session changed: git diff vs HEAD for the files it touched, plus new files"
                           :handler
                           (fn [_args ctx]
                             (let [text (shared/diff-report (js/process.cwd) (vec @touched*))
                                   ui   (some-> ctx .-ui)]
                               (cond
                                 (and ui (.-showOverlay ui)) (.showOverlay ui text)
                                 (and ui (.-notify ui))      (.notify ui text "info")
                                 :else                        (js/console.log text))
                               text))})

    (.registerCommand api "rewind"
                      ;; `undo` is what anyone reaches for; `rewind` is what it
                      ;; was called. The resolver already resolves aliases and
                      ;; extension_scope passes opts through untouched.
                      #js {:aliases #js ["undo"]
                           :description "Restore files edited in the last turn. Usage: /rewind | /undo | /rewind list"
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
      (.off api "tool_complete" on-complete)
      (.off api "turn_finalize" on-finalize)
      (when-let [ev (.-events api)] (when (.-off ev) (.off ev "bash-suite__bash:files-changed" on-bash-files)))
      (.unregisterCommand api "diff")
      (.unregisterCommand api "rewind"))))

(def ^:export default activate)
