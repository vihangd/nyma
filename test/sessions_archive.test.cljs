(ns sessions-archive.test
  "Compressing session logs is only safe if every read path takes both shapes
   and nothing ever appends to a frame."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.sessions.archive :as archive]
            [agent.sessions.listing :refer [list-sessions]]
            [agent.sessions.manager :refer [create-session-manager]]))

(def ^:private tmp (atom nil))

(defn- jsonl [& entries]
  (str (str/join "\n" (map #(js/JSON.stringify (clj->js %)) entries)) "\n"))

(defn- bulk
  "Session-shaped filler. Fixtures have to be bigger than a zstd frame's own
   overhead, or archive-file! correctly declines to make the file larger."
  [tag]
  (str tag " " (.repeat "the quick brown fox jumps over the lazy dog. " 200)))

(beforeEach (fn [] (reset! tmp (fs/mkdtempSync (path/join (os/tmpdir) "nyma-arch-")))))
(afterEach  (fn [] (try (fs/rmSync @tmp #js {:recursive true :force true}) (catch :default _ nil))))

(defn- write-session! [name & entries]
  (let [p (path/join @tmp name)]
    (fs/writeFileSync p (apply jsonl entries))
    p))

(describe "archive round-trip"
          (fn []
            (it "compresses, keeps the bytes, and removes the original"
                (fn []
                  (let [p     (write-session! "1.jsonl"
                                              {:role "user" :content (bulk "hello") :id "a"}
                                              {:role "assistant" :content (bulk "hi") :id "b" :parent-id "a"})
                        text  (fs/readFileSync p "utf8")
                        saved (archive/archive-file! p)]
                    (-> (expect (fs/existsSync p)) (.toBe false))
                    (-> (expect (fs/existsSync (archive/archived-path p))) (.toBe true))
                    (-> (expect (archive/read-text p)) (.toBe text))
                    (-> (expect (pos? saved)) (.toBe true)))))

            (it "reads a plain session unchanged"
                (fn []
                  (let [p (write-session! "2.jsonl" {:role "user" :content (bulk "plain")})]
                    (-> (expect (archive/read-text p)) (.toBe (fs/readFileSync p "utf8"))))))

            (it "returns nil when neither shape exists"
                (fn []
                  (-> (expect (archive/read-text (path/join @tmp "nope.jsonl"))) (.toBeNil))))

            (it "restores an archive to a plain file"
                (fn []
                  (let [p    (write-session! "3.jsonl" {:role "user" :content (bulk "restore me")})
                        text (fs/readFileSync p "utf8")]
                    (archive/archive-file! p)
                    (-> (expect (archive/restore! p)) (.toBe true))
                    (-> (expect (fs/existsSync p)) (.toBe true))
                    (-> (expect (fs/existsSync (archive/archived-path p))) (.toBe false))
                    (-> (expect (fs/readFileSync p "utf8")) (.toBe text)))))

            (it "restore is a no-op when the plain file is already there"
                (fn []
                  (let [p (write-session! "4.jsonl" {:role "user" :content (bulk "live")})]
                    (-> (expect (archive/restore! p)) (.toBe true))
                    (-> (expect (fs/readFileSync p "utf8")) (.toContain "live")))))

            ;; The picker sorts by mtime. An archive pass that stamped "now" on
            ;; every file would reorder the whole list into "most recently
            ;; compressed", which is not a fact about the sessions.
            (it "keeps the original mtime"
                (fn []
                  (let [p        (write-session! "dated.jsonl" {:role "user" :content (bulk "dated")})
                        long-ago (- (js/Date.now) (* 40 24 60 60 1000))]
                    (fs/utimesSync p (/ long-ago 1000) (/ long-ago 1000))
                    (archive/archive-file! p)
                    (let [m (.-mtimeMs (fs/statSync (archive/archived-path p)))]
                      (-> (expect (js/Math.abs (- m long-ago))) (.toBeLessThan 2000)))
                    (archive/restore! p)
                    (-> (expect (js/Math.abs (- (.-mtimeMs (fs/statSync p)) long-ago)))
                        (.toBeLessThan 2000)))))

            (it "writes the archive owner-only"
                (fn []
                  (let [p (write-session! "5.jsonl" {:role "user" :content (bulk "secret")})]
                    (archive/archive-file! p)
                    (-> (expect (bit-and (.-mode (fs/statSync (archive/archived-path p))) 0777))
                        (.toBe 0600)))))))

(describe "sweep rules"
          (fn []
            ;; The live session is excluded outright rather than trusted to look
            ;; recent — its mtime is only recent until a window sits open
            ;; overnight, and compressing it would break the next append.
            (it "never archives the active session, however old it looks"
                (fn []
                  (let [active (write-session! "active.jsonl" {:role "user" :content (bulk "now")})
                        old    (write-session! "old.jsonl" {:role "user" :content (bulk "then")})
                        long-ago (- (js/Date.now) (* 40 24 60 60 1000))]
                    (doseq [p [active old]]
                      (fs/utimesSync p (/ long-ago 1000) (/ long-ago 1000)))
                    (let [res (archive/sweep! @tmp 30 active)]
                      (-> (expect (:archived res)) (.toBe 1))
                      (-> (expect (fs/existsSync active)) (.toBe true))
                      (-> (expect (fs/existsSync old)) (.toBe false))
                      (-> (expect (fs/existsSync (archive/archived-path old))) (.toBe true))))))

            (it "leaves recent sessions alone"
                (fn []
                  (let [p (write-session! "fresh.jsonl" {:role "user" :content (bulk "today")})]
                    (-> (expect (:archived (archive/sweep! @tmp 30 nil))) (.toBe 0))
                    (-> (expect (fs/existsSync p)) (.toBe true)))))

            (it "is off at 0 days and nil"
                (fn []
                  (let [p (write-session! "any.jsonl" {:role "user" :content (bulk "x")})
                        long-ago (- (js/Date.now) (* 400 24 60 60 1000))]
                    (fs/utimesSync p (/ long-ago 1000) (/ long-ago 1000))
                    (-> (expect (:archived (archive/sweep! @tmp 0 nil))) (.toBe 0))
                    (-> (expect (:archived (archive/sweep! @tmp nil nil))) (.toBe 0))
                    (-> (expect (fs/existsSync p)) (.toBe true)))))

            (it "does not re-archive what it already archived"
                (fn []
                  (let [p (write-session! "twice.jsonl" {:role "user" :content (bulk "x")})
                        long-ago (- (js/Date.now) (* 40 24 60 60 1000))]
                    (fs/utimesSync p (/ long-ago 1000) (/ long-ago 1000))
                    (-> (expect (:archived (archive/sweep! @tmp 30 nil))) (.toBe 1))
                    (-> (expect (:archived (archive/sweep! @tmp 30 nil))) (.toBe 0)))))))

(describe "the read paths take both shapes"
          (fn []
            ;; A session that disappeared from the picker because it got
            ;; compressed would be worse than never compressing it.
            (it "an archived session still lists, with its title"
                (fn []
                  (let [p (write-session! "listed.jsonl"
                                          {:role "user" :content (bulk "fix the parser") :id "a"})]
                    (archive/archive-file! p)
                    (let [rows (list-sessions @tmp)]
                      (-> (expect (count rows)) (.toBe 1))
                      (-> (expect (:title (first rows))) (.toContain "fix the parser"))
                      ;; :path is the PLAIN path, because that is what every
                      ;; caller opens and what restore! takes.
                      (-> (expect (:path (first rows))) (.toBe p))
                      (-> (expect (:archived? (first rows))) (.toBe true))))))

            (it "the manager loads an archived session"
                (fn []
                  (let [p (write-session! "loaded.jsonl"
                                          {:role "user" :content (bulk "first") :id "a"}
                                          {:role "assistant" :content (bulk "second") :id "b" :parent-id "a"})]
                    (archive/archive-file! p)
                    (let [s (create-session-manager p)]
                      ((:load s))
                      (-> (expect (count ((:get-tree s)))) (.toBe 2))
                      (-> (expect (count ((:build-context s)))) (.toBe 2))))))

            (it "appending after a restore keeps the history"
                (fn []
                  ;; The write path: restore, then append. Appending to a frame
                  ;; would corrupt it, which is why cli restores before opening.
                  (let [p (write-session! "appended.jsonl" {:role "user" :content (bulk "old turn") :id "a"})]
                    (archive/archive-file! p)
                    (archive/restore! p)
                    (let [s (create-session-manager p)]
                      ((:load s))
                      ((:append s) {:role "user" :content "new turn"})
                      (let [reloaded (create-session-manager p)]
                        ((:load reloaded))
                        (-> (expect (count ((:get-tree reloaded)))) (.toBe 2))
                        (-> (expect (:content (last ((:get-tree reloaded))))) (.toBe "new turn")))))))))
