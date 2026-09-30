(ns new-session-file.test
  "`/new` advertised \"Start a FRESH session file — the old one stays on disk and
   is resumable\" and did not switch the file. It cleared the store and emitted
   `session_start`, leaving the manager appending to the same JSONL — so a later
   `nyma -c` replayed two conversations as one, which is the opposite of what the
   description promises.

   The machinery was already there: `switch-file` is what /resume and /import
   use, and `new-session-path` mints a fresh name."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.sessions.manager :as sm]))

(def ^:private dirs (atom []))
(afterEach (fn []
             (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
             (reset! dirs [])
             nil))

(defn- tmp []
  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-sess-"))]
    (swap! dirs conj d) d))

(describe "new-session-path" (fn []

  (it "mints a distinct path each call, even within one millisecond"
      (fn []
        ;; the bare-clock name collided when five processes launched together
        (let [d (tmp)
              paths (set (map (fn [_] (sm/new-session-path d)) (range 20)))]
          (-> (expect (count paths)) (.toBe 20)))))

  (it "lands under the directory it was given and ends in .jsonl"
      (fn []
        (let [d (tmp) p (sm/new-session-path d)]
          (-> (expect (.startsWith p d)) (.toBe true))
          (-> (expect (.endsWith p ".jsonl")) (.toBe true)))))))

(describe "switch-file is what /new needs" (fn []

  (it "moves the append target, leaving the old file on disk"
      (fn []
        ;; this is the property /new promises: resumable old session, empty new one
        (let [d    (tmp)
              old  (sm/new-session-path d)
              mgr  (sm/create-session-manager old)]
          ((:append mgr) {:role "user" :content "first conversation"})
          (-> (expect (count ((:get-entries mgr)))) (.toBe 1))
          (let [fresh ((:switch-file mgr) (sm/new-session-path d))]
            ;; the new session starts empty…
            (-> (expect (count ((:get-entries mgr)))) (.toBe 0))
            (-> (expect ((:get-file-path mgr))) (.toBe fresh))
            ((:append mgr) {:role "user" :content "second conversation"})
            (-> (expect (count ((:get-entries mgr)))) (.toBe 1))
            ;; …and the old one is still there, intact and resumable
            (-> (expect (fs/existsSync old)) (.toBe true))
            (let [txt (fs/readFileSync old "utf8")]
              (-> (expect (.includes txt "first conversation")) (.toBe true))
              (-> (expect (.includes txt "second conversation")) (.toBe false)))))))

  (it "the two files do not share entries — the bug's actual symptom"
      (fn []
        ;; before the fix both conversations landed in one file and `nyma -c`
        ;; replayed them as a single session
        (let [d (tmp) old (sm/new-session-path d) mgr (sm/create-session-manager old)]
          ((:append mgr) {:role "user" :content "A"})
          (let [fresh ((:switch-file mgr) (sm/new-session-path d))]
            ((:append mgr) {:role "user" :content "B"})
            (-> (expect (.includes (fs/readFileSync fresh "utf8") "A")) (.toBe false))
            (-> (expect (.includes (fs/readFileSync fresh "utf8") "B")) (.toBe true))))))))
