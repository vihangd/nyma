(ns lsp-edits.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.lsp-suite.lsp-edits :refer [apply-text-edits]]))

(defn- edit [sl sc el ec text]
  #js {:range #js {:start #js {:line sl :character sc}
                   :end   #js {:line el :character ec}}
       :newText text})

(describe "apply-text-edits" (fn []

                               (it "replaces a single-line range"
                                   (fn []
                                     (-> (expect (apply-text-edits "abc\ndef\nghi" #js [(edit 1 0 1 3 "XYZ")]))
                                         (.toBe "abc\nXYZ\nghi"))))

                               (it "inserts at a zero-width range"
                                   (fn []
                                     (-> (expect (apply-text-edits "ab" #js [(edit 0 1 0 1 "X")])) (.toBe "aXb"))))

                               (it "deletes a range (empty newText)"
                                   (fn []
                                     (-> (expect (apply-text-edits "abc" #js [(edit 0 1 0 2 "")])) (.toBe "ac"))))

                               (it "applies multiple edits on one line correctly (order-independent)"
                                   (fn []
      ;; "hello world" → replace hello→hi, world→earth
                                     (let [edits #js [(edit 0 0 0 5 "hi") (edit 0 6 0 11 "earth")]]
                                       (-> (expect (apply-text-edits "hello world" edits)) (.toBe "hi earth"))
        ;; reversed input order → same result (sorted internally)
                                       (-> (expect (apply-text-edits "hello world" #js [(edit 0 6 0 11 "earth") (edit 0 0 0 5 "hi")]))
                                           (.toBe "hi earth")))))

                               (it "replaces a multi-line range"
                                   (fn []
                                     (-> (expect (apply-text-edits "a\nb\nc" #js [(edit 0 1 2 0 "")])) (.toBe "ac"))))

                               (it "clamps a character past end-of-line"
                                   (fn []
      ;; end character 999 on line 0 clamps to line length
                                     (-> (expect (apply-text-edits "abc\nxyz" #js [(edit 0 0 0 999 "Q")])) (.toBe "Q\nxyz"))))

                               (it "is a no-op for empty edits"
                                   (fn []
                                     (-> (expect (apply-text-edits "unchanged" #js [])) (.toBe "unchanged"))))))
