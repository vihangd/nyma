(ns small-model-edit-routing.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.profiles :refer [edit-tools-to-hide]]))

;; editStrategy → which edit tools are hidden (Aider/OpenCode-style routing).
(describe "small-model:edit-tools-to-hide" (fn []
                                             (it "\"whole\" hides both exact edit and multi_edit (write only)"
                                                 (fn []
                                                   (let [h (edit-tools-to-hide "whole")]
                                                     (-> (expect (contains? h "edit")) (.toBe true))
                                                     (-> (expect (contains? h "multi_edit")) (.toBe true)))))

                                             (it "\"fuzzy\" hides only the brittle exact edit"
                                                 (fn []
                                                   (let [h (edit-tools-to-hide "fuzzy")]
                                                     (-> (expect (contains? h "edit")) (.toBe true))
                                                     (-> (expect (contains? h "multi_edit")) (.toBe false)))))

                                             (it "\"patch\" behaves like fuzzy (hides exact edit)"
                                                 (fn []
                                                   (-> (expect (contains? (edit-tools-to-hide "patch") "edit")) (.toBe true))))

                                             (it "\"exact\" and unset hide nothing"
                                                 (fn []
                                                   (-> (expect (count (edit-tools-to-hide "exact"))) (.toBe 0))
                                                   (-> (expect (count (edit-tools-to-hide nil))) (.toBe 0))))))
