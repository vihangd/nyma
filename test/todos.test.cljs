(ns todos.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.todos.shared :as t]))

(describe "todos shared" (fn []
                           (it "normalizes status synonyms"
                               (fn []
                                 (-> (expect (t/normalize-status "completed")) (.toBe :done))
                                 (-> (expect (t/normalize-status "done")) (.toBe :done))
                                 (-> (expect (t/normalize-status "in_progress")) (.toBe :in-progress))
                                 (-> (expect (t/normalize-status "doing")) (.toBe :in-progress))
                                 (-> (expect (t/normalize-status nil)) (.toBe :pending))))

                           (it "parses a JS todo array, dropping blanks"
                               (fn []
                                 (let [p (t/parse-todos #js [#js {:content "a" :status "completed"}
                                                             #js {:content "  " :status "pending"}
                                                             #js {:content "b"}])]
                                   (-> (expect (count p)) (.toBe 2))
                                   (-> (expect (:status (first p))) (.toBe :done))
                                   (-> (expect (:status (nth p 1))) (.toBe :pending)))))

                           (it "counts open/total"
                               (fn []
                                 (let [c (t/counts [{:content "a" :status :done} {:content "b" :status :pending}
                                                    {:content "c" :status :in-progress}])]
                                   (-> (expect (:open c)) (.toBe 2))
                                   (-> (expect (:total c)) (.toBe 3)))))

                           (it "render collapses completed items (HIPIF), shows live in full"
                               (fn []
                                 (let [s (t/render-ledger [{:content "done1" :status :done}
                                                           {:content "open1" :status :pending}
                                                           {:content "doing1" :status :in-progress}])]
                                   (-> (expect (.includes s "- [ ] open1")) (.toBe true))
                                   (-> (expect (.includes s "- [~] doing1")) (.toBe true))
                                   (-> (expect (.includes s "done1")) (.toBe false))     ; completed content collapsed
                                   (-> (expect (.includes s "1 completed")) (.toBe true)))))

                           (it "render is nil for an empty ledger"
                               (fn []
                                 (-> (expect (t/render-ledger [])) (.toBeNil))))))
