(ns diff-lines.test
  "Line diff behind the expanded `edit` view."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.ui.diff-lines :refer [diff-lines lcs-cap]]))

(defn- ops [rows] (str/join "" (map (fn [[op _]] (name op)) rows)))

(describe "diff-lines" (fn []
                         (it "identical input is all unchanged"
                             (fn []
                               (-> (expect (ops (diff-lines "a\nb" "a\nb"))) (.toBe "=="))))

                         (it "a replaced line is a removal followed by an addition"
                             (fn []
                               (let [rows (diff-lines "a\nb\nc" "a\nB\nc")]
                                 (-> (expect (ops rows)) (.toBe "=-+="))
                                 (-> (expect (second (nth rows 1))) (.toBe "b"))
                                 (-> (expect (second (nth rows 2))) (.toBe "B")))))

                         (it "keeps the common subsequence when lines are inserted and deleted"
                             (fn []
                               (-> (expect (ops (diff-lines "a\nb\nc\nd" "b\nc\nx\nd"))) (.toBe "-==+="))))

                         (it "an empty old side is all additions, an empty new side all removals"
                             (fn []
                               (-> (expect (ops (diff-lines "" "x\ny"))) (.toBe "++"))
                               (-> (expect (ops (diff-lines nil "x"))) (.toBe "+"))
                               (-> (expect (ops (diff-lines "x\ny" ""))) (.toBe "--"))
                               (-> (expect (count (diff-lines "" ""))) (.toBe 0))))

                         (it "falls back to removed-then-added above the LCS cap"
                             (fn []
                               (let [big  (str/join "\n" (repeat (inc lcs-cap) "same"))
                                     rows (diff-lines big big)]
          ;; Same text both sides — an LCS would say all `=`; the fallback
          ;; does not look.
                                 (-> (expect (count rows)) (.toBe (* 2 (inc lcs-cap))))
                                 (-> (expect (first (first rows))) (.toBe :-))
                                 (-> (expect (first (last rows))) (.toBe :+)))))))
