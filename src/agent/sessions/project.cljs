(ns agent.sessions.project
  "Which project does a session belong to?

   New sessions record their cwd in a `session-meta` entry, but the sessions
   written before that existed have no such marker — and a resume list scoped
   to the current project would simply lose them. So for those we infer the
   project from what the session actually did: the first absolute path any tool
   call touched, walked up to its enclosing repo.

   Best-effort by design. Inference that cannot decide returns nil, and the
   caller groups those as unknown rather than guessing wrong — a session filed
   under the wrong project is worse than one filed under none."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private max-walk-up
  "Levels to climb looking for a repo root before giving up. Deep enough for a
   nested package, shallow enough that a stray /tmp path cannot walk to /."
  10)

(defn repo-root
  "Nearest ancestor of `dir` containing .git, or nil.

   Bounded: a path that never hits a repo stops rather than walking to the
   filesystem root and claiming it."
  [dir]
  (when (and dir (string? dir) (.startsWith dir "/"))
    (loop [cur dir n 0]
      (cond
        (>= n max-walk-up)                         nil
        (or (= cur "/") (= cur "") (nil? cur))     nil
        (fs/existsSync (path/join cur ".git"))     cur
        :else (recur (path/dirname cur) (inc n))))))

(def ^:private abs-path-re
  ;; Absolute paths ANYWHERE in a value, not just at the start. Most tool calls
  ;; are bash, whose only arg is a command string with the path buried inside
  ;; it ("cd /x && ls", "rg foo /y") — anchoring at position 0 found almost
  ;; nothing. Global flag: a command often names several paths and only some
  ;; sit in a repo.
  (js/RegExp. "/[A-Za-z0-9._~@%+-]+(?:/[A-Za-z0-9._~@%+-]+)+" "g"))

(defn- absolute-paths-in
  "Every absolute-looking path among a tool call's argument values.

   Args are free-form per tool (`path`, `file_path`, `command`, …), so this
   reads values rather than trusting key names."
  [args]
  (when args
    (->> (js/Object.values args)
         (filter string?)
         (mapcat (fn [s] (vec (or (.match s abs-path-re) #js []))))
         vec)))

(defn infer-cwd
  "Best-effort project root for a session, from already-parsed JS entries.

   Walks tool calls in order and returns the repo root of the first absolute
   path found. nil when nothing in the session points anywhere on disk."
  [entries]
  (->> (or entries [])
       (filter #(= (.-role %) "tool_call"))
       ;; `metadata` is reached with aget, never .-metadata-args — squint reads
       ;; a hyphenated property access as a subtraction.
       (map (fn [e] (some-> (aget e "metadata") (aget "args"))))
       (filter some?)
       (mapcat absolute-paths-in)
       ;; A file's repo is its directory's repo. Most candidates (/usr/bin/…,
       ;; /tmp/…) resolve to nothing, which is why this keeps going rather than
       ;; committing to the first path it sees.
       (map (fn [p] (repo-root (if (and (fs/existsSync p)
                                        (.isDirectory (fs/statSync p)))
                                 p
                                 (path/dirname p)))))
       (filter some?)
       ;; MOST FREQUENT root, not the first. A session that happens to glance at
       ;; another repo early — one read, one grep — would otherwise be filed
       ;; under it wholesale. The project actually being worked in dominates the
       ;; path distribution, so counting is far more robust than order, and
       ;; under a scoped list a misfiled session is an invisible one.
       frequencies
       (sort-by (fn [[_ n]] (- n)))
       ffirst))

(defn session-cwd
  "The session's project root: the recorded `session-meta` cwd if present,
   otherwise inferred. Recorded always wins — inference is the fallback for
   sessions written before the marker existed."
  [entries]
  (or (->> (or entries [])
           (filter #(= (.-role %) "session-meta"))
           (map (fn [e] (some-> (aget e "metadata") (aget "cwd"))))
           (filter some?)
           first)
      (infer-cwd entries)))

(defn project-label
  "Short display name for a project root — its basename. nil stays nil so the
   caller can render its own placeholder."
  [cwd]
  (when (and cwd (string? cwd) (seq cwd))
    (path/basename cwd)))

(defn same-project?
  "Do these two roots refer to the same project? A session with no known root
   never matches, so unknowns are excluded from a scoped list rather than
   silently swept into whatever project happens to be current."
  [a b]
  (boolean (and a b (= a b))))
