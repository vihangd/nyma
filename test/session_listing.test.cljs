(ns session-listing.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]
            [agent.context :refer [build-context]]
            [agent.cli :refer [resolve-session]]
            [agent.sessions.listing :refer [list-sessions scope-to-project format-row]]))

(def ^:private test-dir (atom nil))

(beforeEach
  (fn []
    (let [dir (path/join (os/tmpdir) (str "nyma-test-sessions-" (js/Date.now)))]
      (fs/mkdirSync dir #js {:recursive true})
      (reset! test-dir dir))))

(afterEach
  (fn []
    (when @test-dir
      (try (fs/rmSync @test-dir #js {:recursive true :force true})
           (catch :default _)))))

(describe "list-sessions" (fn []
  (it "returns empty vec for nonexistent directory"
    (fn []
      (let [result (list-sessions "/nonexistent/path/12345")]
        (-> (expect (count result)) (.toBe 0)))))

  (it "returns empty vec for nil directory"
    (fn []
      (let [result (list-sessions nil)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "returns empty vec for empty directory"
    (fn []
      (let [result (list-sessions @test-dir)]
        (-> (expect (count result)) (.toBe 0)))))

  (it "finds .jsonl files and returns metadata"
    (fn []
      (let [dir @test-dir
            f1  (path/join dir "session1.jsonl")
            f2  (path/join dir "session2.jsonl")]
        (fs/writeFileSync f1 "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hello\"}\n")
        (fs/writeFileSync f2 "{\"id\":\"b\",\"role\":\"user\",\"content\":\"world\"}\n{\"id\":\"c\",\"role\":\"assistant\",\"content\":\"hi\"}\n")
        (let [result (list-sessions dir)]
          (-> (expect (count result)) (.toBe 2))
          ;; Both have paths and entry counts
          (-> (expect (:entry-count (first result))) (.toBeGreaterThan 0))
          (-> (expect (:path (first result))) (.toBeTruthy))))))

  (it "extracts session name from session-name entry"
    (fn []
      (let [dir @test-dir
            f   (path/join dir "named.jsonl")]
        (fs/writeFileSync f
          (str "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hello\"}\n"
               "{\"id\":\"b\",\"role\":\"session-name\",\"content\":\"My Project\"}\n"))
        (let [result (list-sessions dir)
              sess   (first result)]
          (-> (expect (:name sess)) (.toBe "My Project"))))))

  (it "uses filename as fallback name"
    (fn []
      (let [dir @test-dir
            f   (path/join dir "fallback.jsonl")]
        (fs/writeFileSync f "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hi\"}\n")
        (let [result (list-sessions dir)
              sess   (first result)]
          (-> (expect (:name sess)) (.toBe "fallback"))))))

  (it "sorts by modified time descending"
    (fn []
      (let [dir @test-dir
            f1  (path/join dir "old.jsonl")
            f2  (path/join dir "new.jsonl")]
        ;; Write old first, then set its mtime to the past
        (fs/writeFileSync f1 "{\"id\":\"a\",\"role\":\"user\",\"content\":\"old\"}\n")
        (let [past (/ (- (js/Date.now) 60000) 1000)]
          (fs/utimesSync f1 past past))
        ;; Write new — will have current mtime (more recent)
        (fs/writeFileSync f2 "{\"id\":\"b\",\"role\":\"user\",\"content\":\"new\"}\n")
        (let [result (list-sessions dir)]
          ;; Most recently modified should be first
          (-> (expect (:file (first result))) (.toBe "new.jsonl"))))))

  (it "ignores non-jsonl files"
    (fn []
      (let [dir @test-dir]
        (fs/writeFileSync (path/join dir "notes.txt") "not a session")
        (fs/writeFileSync (path/join dir "real.jsonl") "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hi\"}\n")
        (let [result (list-sessions dir)]
          (-> (expect (count result)) (.toBe 1))
          (-> (expect (:file (first result))) (.toBe "real.jsonl"))))))))

;;; ─── titles, real counts, project scoping ────────────────────────────────
;;; `-r` used to list bare epoch numbers. The cause was a dead reader:
;;; list-sessions resolved a name from an entry with role "session-name" that
;;; NOTHING in the repo ever wrote, so the filename fallback was the only path
;;; ever taken.

(defn- write-session!
  "Write a session file. `entries` are maps; returns the path."
  [dir file entries]
  (let [p (path/join dir file)]
    (fs/writeFileSync p (str (str/join "\n" (map #(js/JSON.stringify (clj->js %)) entries)) "\n"))
    p))

(describe "session titles and counts"
          (fn []
            (it "titles a session from its first user message"
                (fn []
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "user" :content "Investigate the overlay focus bug"}
                                   {:role "assistant" :content "Found it."}])
                  (let [s (first (list-sessions @test-dir))]
                    (-> (expect (:title s)) (.toBe "Investigate the overlay focus bug")))))

            (it "collapses newlines so a pasted blob stays one row"
                (fn []
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "user" :content "line one\n\n  line two\tthree"}])
                  (-> (expect (:title (first (list-sessions @test-dir))))
                      (.toBe "line one line two three"))))

            (it "counts conversation turns, not JSONL lines"
                (fn []
                  ;; The old row said "N msgs" using the raw line count, so a
                  ;; 2-turn session with 200 tool calls read as 202 msgs.
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "user" :content "q"}
                                   {:role "tool_call" :content "{}"}
                                   {:role "tool_call" :content "{}"}
                                   {:role "compaction" :content "..."}
                                   {:role "assistant" :content "a"}])
                  (let [s (first (list-sessions @test-dir))]
                    (-> (expect (:msg-count s)) (.toBe 2))
                    (-> (expect (:entry-count s)) (.toBe 5)))))

            (it "survives a truncated final line"
                (fn []
                  ;; A session killed mid-append must still list.
                  (let [p (path/join @test-dir "1000.jsonl")]
                    (fs/writeFileSync p (str (js/JSON.stringify #js {:role "user" :content "hi"})
                                             "\n{\"role\":\"assist")))
                  (let [s (first (list-sessions @test-dir))]
                    (-> (expect (:title s)) (.toBe "hi"))
                    (-> (expect (:msg-count s)) (.toBe 1)))))

            (it "leaves an empty session titleless rather than inventing one"
                (fn []
                  (write-session! @test-dir "1000.jsonl" [{:role "tool_call" :content "{}"}])
                  (-> (expect (:title (first (list-sessions @test-dir)))) (.toBeFalsy))))))

(describe "project scoping"
          (fn []
            (it "reads cwd from a session-meta entry"
                (fn []
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "session-meta" :metadata {:cwd "/repo/alpha"}}
                                   {:role "user" :content "hi"}])
                  (-> (expect (:cwd (first (list-sessions @test-dir)))) (.toBe "/repo/alpha"))))

            (it "splits sessions into in-project and other, never dropping any"
                (fn []
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "session-meta" :metadata {:cwd "/repo/alpha"}}
                                   {:role "user" :content "a"}])
                  (write-session! @test-dir "2000.jsonl"
                                  [{:role "session-meta" :metadata {:cwd "/repo/beta"}}
                                   {:role "user" :content "b"}])
                  (let [all (list-sessions @test-dir)
                        [mine other] (scope-to-project all "/repo/alpha")]
                    (-> (expect (count mine)) (.toBe 1))
                    (-> (expect (count other)) (.toBe 1))
                    ;; The halves must account for every session — the hidden
                    ;; count shown to the user is derived from this.
                    (-> (expect (+ (count mine) (count other))) (.toBe (count all))))))

            (it "never files an unknown-cwd session under the current project"
                (fn []
                  ;; Guessing is worse than admitting ignorance: a session filed
                  ;; under the wrong project is invisible under a scoped list.
                  (write-session! @test-dir "1000.jsonl" [{:role "user" :content "no tools here"}])
                  (let [[mine _] (scope-to-project (list-sessions @test-dir) "/repo/alpha")]
                    (-> (expect (count mine)) (.toBe 0)))))))

(describe "format-row"
          (fn []
            (it "shows title, project, age and turn count"
                (fn []
                  (let [row (format-row {:title "Fix the picker" :cwd "/repo/alpha"
                                         :modified (js/Date.now) :msg-count 7}
                                        80)]
                    (-> (expect row) (.toContain "Fix the picker"))
                    (-> (expect row) (.toContain "alpha"))
                    (-> (expect row) (.toContain "7 msgs"))))) 

            (it "falls back to the id when a session has no title"
                (fn []
                  (let [row (format-row {:name "1786619539330" :modified (js/Date.now)
                                         :msg-count 0}
                                        80)]
                    (-> (expect row) (.toContain "1786619539330"))
                    ;; No project known — placeholder, not a wrong guess.
                    (-> (expect row) (.toContain "—")))))))

;;; ─── the meta entry must stay invisible to the model ─────────────────────
;;; The project is recorded as a `session-meta` line inside the session file
;;; rather than a sidecar. That is only safe because both context builders
;;; filter to known roles; if either ever stopped, the marker would show up in
;;; the replayed transcript as a stray message.

(describe "session-meta is not conversation"
          (fn []
            (it "is excluded from a replayed transcript"
                (fn []
                  (let [entries [{:role "session-meta" :metadata {:cwd "/repo/alpha"}}
                                 {:role "user" :content "hi"}
                                 {:role "assistant" :content "hello"}]
                        agent   {:state (atom {:messages entries})}
                        ctx     (build-context agent)]
                    (-> (expect (count ctx)) (.toBe 2))
                    (-> (expect (some #(= "session-meta" (:role %)) ctx)) (.toBeFalsy)))))

            (it "is not counted as a conversation turn"
                (fn []
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "session-meta" :metadata {:cwd "/repo/alpha"}}
                                   {:role "user" :content "hi"}])
                  (-> (expect (:msg-count (first (list-sessions @test-dir)))) (.toBe 1))))

            (it "does not become the title"
                (fn []
                  (write-session! @test-dir "1000.jsonl"
                                  [{:role "session-meta" :metadata {:cwd "/repo/alpha"}}
                                   {:role "user" :content "the real first message"}])
                  (-> (expect (:title (first (list-sessions @test-dir))))
                      (.toBe "the real first message"))))))

;;; ─── the meta line is actually written ───────────────────────────────────
;;; listing can READ a session-meta entry and build-context ignores it — but
;;; neither proves cli.cljs writes one. Wiring that is correct in isolation and
;;; never called is the failure mode that has bitten this session repeatedly,
;;; so this drives the real resolve-session.

(describe "new sessions record their project"
          (fn []
            (it "writes a session-meta entry with the cwd"
                (^:async fn []
                  (let [work (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cwd-"))
                        sdir (path/join work "sessions")
                        prev (js/process.cwd)]
                    (try
                      (js/process.chdir work)
                      (js-await (resolve-session {} "interactive" sdir))
                      (let [files (fs/readdirSync sdir)
                            entries (-> (path/join sdir (first files))
                                        (#(.readFileSync fs % "utf8"))
                                        .trim
                                        (.split "\n")
                                        (#(map (fn [l] (js/JSON.parse l)) %)))
                            meta (first (filter #(= (.-role %) "session-meta") entries))]
                        (-> (expect (count files)) (.toBe 1))
                        (-> (expect (some? meta)) (.toBe true))
                        ;; realpath: macOS /var is a symlink to /private/var.
                        (-> (expect (fs/realpathSync (aget (aget meta "metadata") "cwd")))
                            (.toBe (fs/realpathSync work))))
                      (finally
                        (js/process.chdir prev)
                        (try (fs/rmSync work #js {:recursive true :force true})
                             (catch :default _ nil)))))))

            (it "does not add a second marker when resuming an existing file"
                (^:async fn []
                  ;; The marker is per FILE, not per launch — a resumed session
                  ;; must not accumulate one per open.
                  (let [work (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cwd2-"))
                        sdir (path/join work "sessions")
                        f    (path/join sdir "9999.jsonl")]
                    (fs/mkdirSync sdir #js {:recursive true})
                    (fs/writeFileSync f (str (js/JSON.stringify #js {:role "user" :content "hi"}) "\n"))
                    (js-await (resolve-session {:session f} "interactive" sdir))
                    (let [lines (-> (.readFileSync fs f "utf8") .trim (.split "\n"))]
                      (-> (expect (count lines)) (.toBe 1)))
                    (try (fs/rmSync work #js {:recursive true :force true})
                         (catch :default _ nil)))))))
