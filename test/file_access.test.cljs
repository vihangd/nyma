(ns file-access.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.file-access :refer [matches-pattern? check-access outside-root?]]))

(describe "file-access:matches-pattern" (fn []
  (it "*.env matches .env"
    (fn []
      (-> (expect (matches-pattern? ".env" "*.env")) (.toBe true))))

  (it "*.env matches production.env"
    (fn []
      (-> (expect (matches-pattern? "production.env" "*.env")) (.toBe true))))

  (it "*.env does not match env.js"
    (fn []
      (-> (expect (matches-pattern? "env.js" "*.env")) (.toBe false))))

  (it "node_modules/** matches nested paths"
    (fn []
      (-> (expect (matches-pattern? "node_modules/foo/bar.js" "node_modules/**")) (.toBe true))))

  (it "src/*.ts matches files in src"
    (fn []
      (-> (expect (matches-pattern? "src/index.ts" "src/*.ts")) (.toBe true))))

  (it "src/*.ts does not match nested paths"
    (fn []
      (-> (expect (matches-pattern? "src/deep/index.ts" "src/*.ts")) (.toBe false))))))

(describe "file-access:check-access" (fn []
  (it "returns allowed for non-matching paths"
    (fn []
      (let [result (check-access "src/main.cljs" ["*.env" "node_modules/**"])]
        (-> (expect (:allowed result)) (.toBe true)))))

  (it "returns denied for matching paths"
    (fn []
      (let [result (check-access ".env" ["*.env"])]
        (-> (expect (:allowed result)) (.toBe false))
        (-> (expect (:reason result)) (.toContain ".nymaignore")))))

  (it "empty patterns allow everything"
    (fn []
      (let [result (check-access "anything.txt" [])]
        (-> (expect (:allowed result)) (.toBe true)))))))

(describe "file-access:outside-root?"
          (fn []
            (it "holds against a sibling name and a symlink, and allows a new file"
                (fn []
                  (let [d    (fs/mkdtempSync (path/join (os/tmpdir) "nyma-root-"))
                        root (path/join d "wt")]
                    (fs/mkdirSync root)
                    (fs/symlinkSync d (path/join root "out"))
                    (-> (expect (outside-root? root (path/join root "new" "a.txt"))) (.toBe false))
                    (-> (expect (outside-root? root (path/join d "wt2" "a.txt"))) (.toBe true))
                    (-> (expect (outside-root? root (path/join root "out" "a.txt"))) (.toBe true))
                    (-> (expect (outside-root? root "/etc/x")) (.toBe true))
                    ;; dangling: the write would create the target outside
                    (fs/symlinkSync (path/join d "nope" "x") (path/join root "dang"))
                    (-> (expect (outside-root? root (path/join root "dang"))) (.toBe true))
                    (fs/symlinkSync "new-in" (path/join root "rel"))
                    (-> (expect (outside-root? root (path/join root "rel"))) (.toBe false))
                    (fs/rmSync d #js {:recursive true :force true}))))))
