(ns agent.extensions.checkpoints.shared
  "Pure/state helpers for file checkpoints + /rewind.

   Model: before an editing tool touches a file, capture that file's pre-turn
   state (content, or :absent if it didn't exist). Checkpoints group by turn;
   /rewind restores the newest group (repeat to go further back). Session-
   scoped and in-memory — a trust net for accept-edits/full-auto, not a VCS."
  (:require ["node:fs" :as fs]
            [agent.tool-metadata :as tool-metadata]))

(def max-snapshot-bytes (* 2 1024 1024))

(defn edit-tool? [tool-name]
  (tool-metadata/file-editing? tool-name))

(def max-turn-groups
  "Rewind depth cap — snapshots hold file contents in memory, so old groups
   are pruned (rewinding 20+ turns back is not a real workflow)."
  20)

(defn- read-state
  "Current state of `path`: content string, :absent, or nil (too big to hold)."
  [path]
  (if (fs/existsSync path)
    (let [size (.-size (fs/statSync path))]
      (when (<= size max-snapshot-bytes)
        (fs/readFileSync path "utf8")))
    :absent))

(defn capture-pending!
  "Capture `path`'s pre-execution state into pending* ({path state}) — first
   capture per path wins. Pending states only become rewind points via
   promote! after the tool actually ran."
  [pending* path]
  (when (and path (not (contains? @pending* path)))
    (when-let [state (read-state path)]
      (swap! pending* assoc path state))))

(defn promote!
  "Move `path`'s pending pre-state into the rewind group for `turn`, pruning
   groups beyond max-turn-groups. First promotion per path per turn wins
   (that IS the pre-turn state)."
  [pending* checkpoints* turn path]
  (when-let [state (get @pending* path)]
    (when-not (get-in @checkpoints* [turn path])
      (swap! checkpoints* assoc-in [turn path] state)
      (let [ks (sort-by js/Number (keys @checkpoints*))]
        (when (> (count ks) max-turn-groups)
          (swap! checkpoints* dissoc (first ks)))))))

(defn snapshot!
  "Record `path`'s current state directly into the group for `turn` — first
   snapshot per path per turn wins. (Direct form used by tests; the extension
   goes through capture-pending!/promote! so denied calls never checkpoint.)"
  [checkpoints* turn path]
  (when (and path (not (get-in @checkpoints* [turn path])))
    (when-let [state (read-state path)]
      (swap! checkpoints* assoc-in [turn path] state))))

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
