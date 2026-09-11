(ns agent.utils.spill-file
  "Writing model-visible content to disk, safely.

   Two places spill to the OS temp directory: the pre-compaction dump (the whole
   span being summarized — every prompt, every assistant message, whatever the
   model read out of the repo) and bash's oversized stdout (env dumps, logs,
   config files). Both used a fixed directory name, a guessable file name, and a
   default-mode write, which under the usual umask is 0644 inside a world-
   readable /tmp: any other user on the machine can read the conversation, and a
   pre-planted symlink at the predictable path redirects the write.

   Borrowed from deepseek-harness's defensive-patterns rule: a private (0700)
   directory, random names, and exclusive owner-only opens.

   `wx` is the load-bearing half — it fails on an existing path rather than
   following or truncating it, so a planted symlink is an error and never a
   write into someone else's target. The directory mode is defence in depth,
   because `mkdirSync {:mode …}` is ignored for a directory that already
   exists."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private dir-mode 0700)
(def ^:private file-mode 0600)

(defn random-name
  "A random file name with `ext` — unguessable, so nothing can be planted at it
   ahead of the write."
  [ext]
  (str (.. js/crypto (randomUUID)) ext))

(defn ensure-private-dir!
  "Create `dir` 0700, and tighten it when it already exists with looser bits.

   The tightening branch exists because a persistent spill directory (bash's,
   which `retrieve_bash_output` reads from later) may have been created by an
   older nyma, and because its path is user-configurable — we cannot assume we
   made it. Returns the directory, or nil when it cannot be made usable."
  [dir]
  (try
    (if (fs/existsSync dir)
      (let [mode (bit-and (.-mode (fs/statSync dir)) 0777)]
        (when (not= mode dir-mode) (fs/chmodSync dir dir-mode))
        dir)
      (do (fs/mkdirSync dir #js {:recursive true :mode dir-mode})
          dir))
    (catch :default _ nil)))

(defn private-temp-dir!
  "A fresh, uniquely named 0700 directory under the OS temp dir, for spills that
   are cleaned up within the same run and never looked up again by path."
  [prefix]
  (try
    (let [dir (fs/mkdtempSync (path/join (os/tmpdir) (str prefix "-")))]
      (try (fs/chmodSync dir dir-mode) (catch :default _ nil))
      dir)
    (catch :default _ nil)))

(defn write!
  "Write `content` to `fpath` owner-only, failing if the path already exists.
   Returns the path, or nil when the write fails."
  [fpath content]
  (try
    (fs/writeFileSync fpath (str content) #js {:mode file-mode :flag "wx"})
    fpath
    (catch :default _ nil)))
