(ns spill-file.test
  "Spill files carry model-visible content — the whole span being compacted, or a
   command's full stdout. They used to land at a predictable path in a shared
   /tmp with default permissions (0644 under umask 022)."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.utils.spill-file :as spill]))

(defn- mode-of [p] (bit-and (.-mode (fs/statSync p)) 0777))

(describe "spill-file"
          (fn []
            (it "writes owner-only into an owner-only directory"
                (fn []
                  (let [dir  (spill/private-temp-dir! "nyma-spill-test")
                        fpath (path/join dir (spill/random-name ".txt"))]
                    (-> (expect (mode-of dir)) (.toBe 0700))
                    (-> (expect (spill/write! fpath "secret")) (.toBe fpath))
                    (-> (expect (mode-of fpath)) (.toBe 0600))
                    (-> (expect (fs/readFileSync fpath "utf8")) (.toBe "secret"))
                    (fs/unlinkSync fpath)
                    (fs/rmdirSync dir))))

            ;; The load-bearing half: an existing path is an ERROR, never a
            ;; write. A symlink planted at a guessable name would otherwise
            ;; redirect the conversation dump into someone else's file.
            (it "refuses to write a path that already exists"
                (fn []
                  (let [dir   (spill/private-temp-dir! "nyma-spill-test")
                        fpath (path/join dir "taken.txt")]
                    (fs/writeFileSync fpath "original")
                    (-> (expect (spill/write! fpath "overwrite")) (.toBeNil))
                    (-> (expect (fs/readFileSync fpath "utf8")) (.toBe "original"))
                    (fs/unlinkSync fpath)
                    (fs/rmdirSync dir))))

            (it "names files unguessably"
                (fn []
                  (-> (expect (= (spill/random-name ".txt") (spill/random-name ".txt")))
                      (.toBe false))
                  (-> (expect (.endsWith (spill/random-name ".txt") ".txt")) (.toBe true))))

            ;; mkdirSync's :mode is ignored for a directory that already exists,
            ;; and the bash spill dir persists across calls (retrieve_bash_output
            ;; reads it later) and is user-configurable — so it may arrive loose.
            (it "tightens an existing permissive directory"
                (fn []
                  (let [dir (path/join (os/tmpdir) (str "nyma-loose-" (js/Date.now)))]
                    (fs/mkdirSync dir #js {:recursive true :mode 0755})
                    (-> (expect (mode-of dir)) (.toBe 0755))
                    (-> (expect (spill/ensure-private-dir! dir)) (.toBe dir))
                    (-> (expect (mode-of dir)) (.toBe 0700))
                    (fs/rmdirSync dir))))

            (it "creates a missing directory owner-only"
                (fn []
                  (let [dir (path/join (os/tmpdir) (str "nyma-fresh-" (js/Date.now)))]
                    (-> (expect (spill/ensure-private-dir! dir)) (.toBe dir))
                    (-> (expect (mode-of dir)) (.toBe 0700))
                    (fs/rmdirSync dir))))))


;;; ─── never tighten what is not ours ─────────────────────────

(describe "ensure-private-dir! refuses shared directories"
          (fn []
            (it "will not chmod the OS temp root"
                (fn []
                  ;; As root — every default container — chmodding /tmp to 0700
                  ;; SUCCEEDS and takes the temp directory away from every other
                  ;; process on the machine. Measured in a bun container: /tmp
                  ;; went 1777 -> 700 after a single oversized bash output.
                  ;;
                  ;; The invariant is about the mode, not the return value: on
                  ;; macOS the temp root is a per-user 0700 directory already, so
                  ;; there is nothing to refuse and nothing to change.
                  (let [before (mode-of (os/tmpdir))]
                    (spill/ensure-private-dir! (os/tmpdir))
                    (-> (expect (mode-of (os/tmpdir))) (.toBe before))
                    ;; Where it is the shared 1777 /tmp, it must also decline to
                    ;; hand the caller a directory to spill into.
                    (when (not= before 0700)
                      (-> (expect (spill/ensure-private-dir! (os/tmpdir))) (.toBeNil))))))

            (it "recognises the temp root through a non-normalised path"
                (fn []
                  (-> (expect (spill/shared-root? (path/join (os/tmpdir) "x" ".."))) (.toBe true))
                  (-> (expect (spill/shared-root? (path/join (os/tmpdir) "nyma-bash-output")))
                      (.toBe false))))

            (it "still creates and tightens a directory of our own"
                (fn []
                  ;; Guard the guard: the refusal above must not have turned the
                  ;; whole function off.
                  (let [dir (path/join (fs/mkdtempSync (path/join (os/tmpdir) "nyma-spill-t-")) "sub")]
                    (-> (expect (spill/ensure-private-dir! dir)) (.toBe dir))
                    (-> (expect (bit-and (.-mode (fs/statSync dir)) 0777)) (.toBe 0700))
                    ;; …including loosening that an older nyma may have left.
                    (fs/chmodSync dir 0755)
                    (-> (expect (spill/ensure-private-dir! dir)) (.toBe dir))
                    (-> (expect (bit-and (.-mode (fs/statSync dir)) 0777)) (.toBe 0700))
                    (fs/rmSync dir #js {:recursive true :force true}))))))
