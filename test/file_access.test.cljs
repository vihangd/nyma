(ns file-access.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.file-access :refer [matches-pattern? check-access]]))

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
