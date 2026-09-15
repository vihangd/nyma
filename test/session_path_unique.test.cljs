(ns session-path-unique.test
  "Session files launched in the same millisecond must not share a name.

   Observed on the binary: five instances started by one script all wrote
   `<ms>.jsonl` for the same ms, and /tree in each showed the others' entries."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.sessions.manager :refer [new-session-path]]
            [agent.sessions.listing :refer [list-sessions]]))

(describe "new-session-path"
          (fn []
            (it "1000 names generated back to back are all distinct and keep the <ms>-<6 base36>.jsonl shape"
                (fn []
                  (let [names (repeatedly 1000 #(new-session-path "/tmp/x"))]
                    (-> (expect (count (set names))) (.toBe 1000))
                    (doseq [n names]
                      (-> (expect (re-find #"^/tmp/x/\d+-[0-9a-z]{6}\.jsonl$" n)) (.toBeTruthy))))))

            (it "an optional prefix lands between the directory and the timestamp"
                (fn []
                  (-> (expect (re-find #"^/tmp/nyma-sdk-session-\d+-[0-9a-z]{6}\.jsonl$"
                                       (new-session-path "/tmp" "nyma-sdk-session-")))
                      (.toBeTruthy))))

            (it "listing still sorts newest first with the suffix present"
                (fn []
                  (let [dir   (fs/mkdtempSync (path/join (os/tmpdir) "nyma-session-path-"))
                        older (new-session-path dir)
                        newer (new-session-path dir)
                        line  "{\"id\":\"a\",\"role\":\"user\",\"content\":\"hello\"}\n"]
                    (try
                      (fs/writeFileSync older line)
                      (fs/writeFileSync newer line)
                      ;; mtime decides, not the name: make `older` older.
                      (fs/utimesSync older (/ (- (js/Date.now) 60000) 1000) (/ (- (js/Date.now) 60000) 1000))
                      (let [listed (list-sessions dir)]
                        (-> (expect (count listed)) (.toBe 2))
                        (-> (expect (:path (first listed))) (.toBe newer))
                        (-> (expect (:name (first listed))) (.toBe (path/basename newer ".jsonl"))))
                      (finally
                        (fs/rmSync dir #js {:recursive true :force true}))))))))
