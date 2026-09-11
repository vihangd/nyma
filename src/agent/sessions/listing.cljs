(ns agent.sessions.listing
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.sessions.project :as project]
            [agent.sessions.archive :as archive]
            ;; picker_frame takes only pure helpers from pi-tui (visibleWidth,
            ;; truncateToWidth) — no TUI is started, so this is safe on the
            ;; pre-TUI `-r` path.
            [agent.ui.picker-frame :refer [two-col-row truncate-to]]
            [agent.utils.time :as time]))

(def ^:private title-max
  "Characters of the first user message kept as a session title. Long enough to
   recognise the work, short enough to leave room for the metadata column."
  60)

(defn- parse-lines
  "Parse JSONL text into JS entries, skipping unparseable lines. A half-written
   final line (killed mid-append) must not lose the whole session."
  [content]
  (->> (.split (.trim content) "\n")
       (filter seq)
       (map (fn [line] (try (js/JSON.parse line) (catch :default _ nil))))
       (filter some?)
       vec))

(defn- tidy
  "Collapse whitespace and clip to `title-max` display width."
  [s]
  (let [t (str/trim (str/replace (str s) #"\s+" " "))]
    (when (seq t)
      (if (> (count t) title-max)
        (str (.slice t 0 (dec title-max)) "…")
        t))))

(defn explicit-name
  "Content of an explicit `session-name` entry, if any.

   That is what the `setSessionName` extension API is for. Nothing in-repo
   writes one today, which is the whole story behind the bare-number rows: the
   old code read ONLY this and fell back to the filename, so the fallback was
   the only path ever taken."
  [entries]
  (some->> (or entries [])
           (filter #(= (.-role %) "session-name"))
           last
           (#(.-content %))
           tidy))

(defn derive-title
  "A human label for a session: its explicit name, else its first user message.

   Returns nil for a session with neither, so the caller decides how an
   untitled session reads rather than getting an epoch number silently.

   Distinct from `:name`, which stays an IDENTITY field (explicit name, else
   the file's id) — callers that print an identifier keep working unchanged."
  [entries]
  (let [entries (or entries [])]
    (or (explicit-name entries)
        (some->> entries
                 (filter #(= (.-role %) "user"))
                 first
                 (#(.-content %))
                 tidy))))

(defn- conversation-count
  "User + assistant turns only.

   The old row said \"N msgs\" using the raw JSONL line count, which includes
   every tool_call and compaction entry — a 12-turn session read as 261."
  [entries]
  (->> (or entries [])
       (filter #(contains? #{"user" "assistant"} (.-role %)))
       count))

(defn list-sessions
  "Scan a directory for .jsonl session files.

   Returns [{:path :name :file :modified :entry-count :title :msg-count :cwd}]
   sorted by modified desc. Every field comes out of one pass over lines this
   function already had to read and parse."
  [dir]
  (if-not (and dir (fs/existsSync dir))
    []
    ;; Archived sessions (`<id>.jsonl.zstd`) list beside live ones — a session
    ;; that vanished from the picker because it got compressed would be worse
    ;; than never compressing it.
    (let [files (->> (fs/readdirSync dir)
                     (filter #(or (.endsWith % ".jsonl")
                                  (.endsWith % ".jsonl.zstd"))))]
      (->> files
           (map (fn [file]
                  (try
                    (let [full    (path/join dir file)
                          stat    (fs/statSync full)
                          ;; `:path` stays the PLAIN path for an archive, because
                          ;; that is what every caller opens; archive/read-text
                          ;; and restore! both take that shape.
                          plain   (if (archive/archived? full)
                                    (.slice full 0 (- (count full) 5))
                                    full)
                          content (archive/read-text plain)
                          entries (parse-lines (or content ""))]
                      {:path         plain
                       :archived?    (archive/archived? full)
                       :name         (or (explicit-name entries)
                                         (-> file (.replace ".jsonl.zstd" "") (.replace ".jsonl" "")))
                       :file         file
                       :modified     (.-mtimeMs stat)
                       :entry-count  (count entries)
                       :title        (derive-title entries)
                       :msg-count    (conversation-count entries)
                       :cwd          (project/session-cwd entries)})
                    (catch :default _ nil))))
           (filter some?)
           (sort-by :modified >)
           vec))))

(defn scope-to-project
  "Split sessions into [in-project other] for `cwd`.

   Returns both halves rather than filtering: the caller must be able to say how
   many it hid. A scoped list that drops sessions silently is how one becomes
   unfindable, which is the whole complaint this feature answers."
  [sessions cwd]
  [(vec (filter #(project/same-project? (:cwd %) cwd) sessions))
   (vec (remove #(project/same-project? (:cwd %) cwd) sessions))])

(defn format-row
  "One session as `<title>` + right-flushed `<project> <age> <n> msgs`.

   The title is truncated HEAD-first against the left budget before layout.
   `two-col-row` truncates its left column with `truncate-tail`, which keeps the
   END of the string — right for a model id (`anthropic/claude-opus-5`), wrong
   for a sentence, where it produced rows like `… the pcap in this folder, and`
   with the identifying opening words cut off."
  [session width]
  (let [width (max 20 (or width 80))
        label (or (:title session) (:name session) "(empty session)")
        proj  (or (project/project-label (:cwd session)) "—")
        meta  (str proj "  " (time/relative-time (:modified session))
                   "  " (:msg-count session) " msgs")
        ;; Mirrors two-col-row's own budget so the head-truncation happens here
        ;; rather than its tail-truncation happening there.
        budget (max 1 (- width (count meta) 2))]
    (two-col-row (truncate-to label budget) meta width)))
