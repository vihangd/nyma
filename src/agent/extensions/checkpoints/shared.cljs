(ns agent.extensions.checkpoints.shared
  "Pure/state helpers for file checkpoints + /rewind.

   Model: before an editing tool touches a file, capture that file's pre-turn
   state (content, or :absent if it didn't exist). Checkpoints group by turn;
   /rewind restores the newest group (repeat to go further back). Session-
   scoped and in-memory — a trust net for accept-edits/full-auto, not a VCS."
  (:require ["node:fs" :as fs]))

(def edit-tools #{"write" "edit" "multi_edit"})

(def max-snapshot-bytes (* 2 1024 1024))

(defn edit-tool? [tool-name]
  (contains? edit-tools (str tool-name)))

(defn snapshot!
  "Record `path`'s current state into the group for `turn` — first snapshot
   per path per turn wins (that IS the pre-turn state). checkpoints* is an
   atom of {turn {path content-or-:absent}}."
  [checkpoints* turn path]
  (when (and path (not (get-in @checkpoints* [turn path])))
    (let [state (if (fs/existsSync path)
                  (let [size (.-size (fs/statSync path))]
                    (when (<= size max-snapshot-bytes)
                      (fs/readFileSync path "utf8")))
                  :absent)]
      ;; nil state = file too big to snapshot — skip rather than hold it.
      (when (some? state)
        (swap! checkpoints* assoc-in [turn path] state)))))

(defn restore!
  "Restore every file in the newest turn group; returns [turn paths] or nil
   when nothing to rewind. Removes the group (repeat /rewind → further back)."
  [checkpoints*]
  ;; Map keys are strings in squint — compare numerically or turn 10 sorts
  ;; before turn 2.
  (when (seq @checkpoints*)
    (let [turn  (apply max (map js/Number (keys @checkpoints*)))
          group (get @checkpoints* turn)]
      (doseq [[path state] group]
        (if (= state :absent)
          (when (fs/existsSync path) (fs/unlinkSync path))
          (fs/writeFileSync path state)))
      (swap! checkpoints* dissoc turn)
      [turn (vec (keys group))])))

(defn describe
  "Human list of rewind points, newest first."
  [checkpoints*]
  (->> (sort-by js/Number (keys @checkpoints*))
       reverse
       (map (fn [turn]
              (str "turn " turn ": " (.join (clj->js (vec (keys (get @checkpoints* turn)))) ", "))))
       vec))
