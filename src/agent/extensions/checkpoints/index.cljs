(ns agent.extensions.checkpoints.index
  "File checkpoints + /rewind (/undo) + /diff.

   Before any editing tool touches a file its pre-turn state is captured;
   files a bash command changed are captured too, when their prior state is
   knowable (bash_suite reports it). Rewind points persist per session under
   ~/.nyma/checkpoints/, so they survive a restart and follow /resume."
  (:require [agent.extensions.checkpoints.shared :as shared]
            ["node:path" :as path]
            [agent.tool-metadata :as tool-metadata]))

(defn ^:export activate [api]
  (let [checkpoints* (atom {})
        pending*     (atom {})   ;; path → pre-state, awaiting confirmation
        turn*        (atom 0)
        turn-entries* (atom {})  ;; turn → the session entry it ended on (for /fork)
        loaded-for   (atom ::none)
        ;; Every file this session changed — edit tools and bash alike — for
        ;; /diff. Never pruned with the rewind groups.
        touched*     (atom #{})
        touch!       (fn [p] (when p (swap! touched* conj (path/resolve (str p)))))

        session-file (fn [] (try (.getSessionFile api) (catch :default _ nil)))
        dir          (fn [] (shared/store-dir (js/process.cwd) (session-file)))

        ;; Load the current session's saved points the first time they are
        ;; needed, and again whenever the session changes (/resume, /new).
        ensure-loaded!
        (fn []
          (let [sf (session-file)]
            (when (not= sf @loaded-for)
              (reset! loaded-for sf)
              (reset! checkpoints* (shared/load-all (dir)))
              (reset! turn-entries* (shared/load-turn-entries (dir)))
              (reset! pending* {})
              (reset! turn* (if (seq @checkpoints*)
                              (inc (apply max (map js/Number (keys @checkpoints*))))
                              0)))))

        ;; before_tool_call fires for EVERY handler regardless of another
        ;; handler blocking the call, and the permission gate's deny doesn't
        ;; stop the event either. So: capture the pre-state here (the file is
        ;; still untouched), but only RECORD it once tool_complete confirms the
        ;; tool actually ran — otherwise a denied edit creates a phantom point.
        on-before-tool
        (fn [event _ctx]
          (ensure-loaded!)
          (when (shared/edit-tool? (.-toolName event))
            (when-let [p (tool-metadata/tool-path (.-args event))]
              (shared/capture-pending! pending* p)))
          nil)

        on-complete
        (fn [event _ctx]
          (when (and (shared/edit-tool? (.-toolName event))
                     (not (.-cancelled event))
                     (not (.-isError event)))
            (when-let [p (tool-metadata/tool-path (.-args event))]
              (when-let [st (get @pending* p)]
                (shared/record-state! checkpoints* (dir) @turn* p st))
              (touch! p)))
          nil)

        ;; Files a shell command changed (bash_suite edit-diff), with their
        ;; prior state where it is knowable.
        on-bash-files
        (fn [data]
          (ensure-loaded!)
          (doseq [p (or (.-paths data) [])] (touch! p))
          (doseq [e (or (.-pre data) [])]
            (shared/record-state! checkpoints* (dir) @turn* (.-path e)
                                  (if (nil? (.-state e)) :absent (.-state e))))
          nil)

        on-finalize
        (fn [_event _ctx]
          (reset! pending* {})   ;; denied/failed leftovers are dropped
          (when-let [leaf (try (.getLeafId api) (catch :default _ nil))]
            (swap! turn-entries* assoc (str @turn*) leaf)
            (shared/save-turn-entries! (dir) @turn-entries*))
          (swap! turn* inc)
          nil)

        ;; /fork moves the conversation back; the files stayed where the
        ;; abandoned branch left them. Offer to restore the ones changed after
        ;; the fork point — asked, never done silently: a fork is also how
        ;; people explore an alternative while KEEPING their work.
        on-before-fork
        ^:async
        (fn [data _ctx]
          (ensure-loaded!)
          (let [new-leaf (or (:new-leaf data) (aget data "new-leaf"))
                entries  (try (.getSessionEntries api) (catch :default _ nil))
                turns    (when (and new-leaf entries)
                           (shared/turns-after-fork (keys @checkpoints*) @turn-entries* entries new-leaf))
                files    (distinct (mapcat #(keys (get @checkpoints* %)) turns))
                ui       (.-ui api)]
            (when (and (seq turns) ui (.-confirm ui))
              (when (js-await (.confirm ui (str "Restore " (count files) " file(s) changed after this point?\n  "
                                                (.join (clj->js (take 10 files)) "\n  "))))
                (let [restored (shared/restore-turns! checkpoints* (dir) turns)]
                  (when (.-notify ui) (.notify ui (str "Restored: " (.join (clj->js (distinct restored)) ", ")) "info"))))))
          nil)]

    (.on api "before_tool_call" on-before-tool)
    (.on api "tool_complete" on-complete)
    (.on api "turn_finalize" on-finalize)
    (.on api "session_before_fork" on-before-fork)
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
                      #js {:aliases #js ["undo"]
                           :description "Restore files to before a turn. /undo picks the point; /rewind last undoes the newest; /rewind list shows them"
                           :handler
                           ^:async
                           (fn [args ctx]
                             (ensure-loaded!)
                             (let [ui     (some-> ctx .-ui)
                                   notify (fn [m l] (when (and ui (.-notify ui)) (.notify ui m l)))
                                   sub    (some-> (first args) str)
                                   points (shared/describe checkpoints*)
                                   turns  (->> (keys @checkpoints*) (map js/Number) sort reverse vec)
                                   report (fn [restored]
                                            (if (seq restored)
                                              (notify (str "Restored to before turn " (first (last restored)) ": "
                                                           (.join (clj->js (distinct (mapcat second restored))) ", "))
                                                      "info")
                                              (notify "Nothing to rewind." "warning")))]
                               (cond
                                 (= sub "list")
                                 (notify (if (seq points)
                                           (str "Rewind points (newest first):\n" (.join (clj->js points) "\n"))
                                           "No rewind points in this session.")
                                         "info")

                                 (empty? turns) (notify "Nothing to rewind." "warning")

                                 ;; A picker when there is a choice to make.
                                 (and (not= sub "last") ui (.-select ui) (> (count turns) 1))
                                 (let [choice (js-await (.select ui "Restore files to before which turn? (everything after it is undone too)"
                                                                 (clj->js (conj points "Cancel"))))
                                       idx    (.indexOf (clj->js points) choice)]
                                   (if (neg? idx)
                                     (notify "Rewind cancelled." "info")
                                     (report (shared/restore-through! checkpoints* (dir) (nth turns idx)))))

                                 :else (report (shared/restore-through! checkpoints* (dir) (first turns))))))})

    (fn []
      (.off api "before_tool_call" on-before-tool)
      (.off api "tool_complete" on-complete)
      (.off api "turn_finalize" on-finalize)
      (.off api "session_before_fork" on-before-fork)
      (when-let [ev (.-events api)] (when (.-off ev) (.off ev "bash-suite__bash:files-changed" on-bash-files)))
      (.unregisterCommand api "diff")
      (.unregisterCommand api "rewind"))))

(def ^:export default activate)
