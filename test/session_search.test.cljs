(ns session-search-test
  "`/resume <words>` finds the sessions that mention them."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string]
            [agent.sessions.listing :as listing]))

(defn- session! [dir name msgs]
  (fs/writeFileSync (path/join dir (str name ".jsonl"))
                    (str (->> msgs
                              (map-indexed (fn [i [role content]]
                                             (js/JSON.stringify #js {:id (str name i) :role role :content content})))
                              (clojure.string/join "\n"))
                         "\n")))

(describe "matching-sessions"
          (fn []
            (it "matches message content, case-insensitively, and nothing else"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-search-"))]
                    (session! d "a" [["user" "fix the Auth bug"] ["assistant" "done"]])
                    (session! d "b" [["user" "write docs"] ["assistant" "ok"]])
                    (session! d "c" [["user" "x"] ["assistant" #js [#js {:type "text" :text "auth token refreshed"}]]])
                    (let [all (listing/list-sessions d)
                          hit (fn [q] (sort (map :name (listing/matching-sessions all q))))]
                      (-> (expect (clj->js (hit "auth"))) (.toEqual #js ["a" "c"]))
                      (-> (expect (clj->js (hit "DOCS"))) (.toEqual #js ["b"]))
                      (-> (expect (clj->js (hit "nothing-like-this"))) (.toEqual #js []))
                      (-> (expect (count (listing/matching-sessions all "  "))) (.toBe 3)))
                    (fs/rmSync d #js {:recursive true}))))))
