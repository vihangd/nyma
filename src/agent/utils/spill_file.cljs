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

(defn- our-uid
  "The current uid, or nil where the platform has no such notion (Windows)."
  []
  (when (fn? (.-getuid js/process)) (.getuid js/process)))

(defn shared-root?
  "Is `dir` the OS temp root itself (/tmp, /var/folders/…/T)?

   Never to be tightened, whoever owns it. As root — which is every default
   container — chmodding /tmp to 0700 SUCCEEDS, and takes the temp directory
   away from every other process on the machine. Measured in a bun container:
   /tmp went from 1777 to 700 after one spill."
  [dir]
  (= (path/resolve (str dir)) (path/resolve (os/tmpdir))))

(defn ensure-private-dir!
  "Create `dir` 0700, and tighten it when it already exists with looser bits.

   The tightening branch exists because a persistent spill directory (bash's,
   which `retrieve_bash_output` reads from later) may have been created by an
   older nyma, and because its path is user-configurable — we cannot assume we
   made it. Which is exactly why it does not tighten unconditionally: we only
   chmod a directory we own, and never the OS temp root.

   Returns the directory, or nil when it cannot be made usable — a caller that
   cannot get a private directory must not spill, not spill anyway."
  [dir]
  (try
    (if (fs/existsSync dir)
      (let [st   (fs/statSync dir)
            mode (bit-and (.-mode st) 0777)
            uid  (our-uid)]
        (cond
          (= mode dir-mode) dir
          ;; Someone else's directory, or the shared temp root. Refuse rather
          ;; than change permissions on something that is not ours.
          (or (shared-root? dir)
              (and (some? uid) (not= uid (.-uid st))))
          nil

          :else (do (fs/chmodSync dir dir-mode) dir)))
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
