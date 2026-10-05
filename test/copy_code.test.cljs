(ns copy-code-test
  "`/copy code [N]` — a code block from the last reply, as its exact source.
   A terminal selection carries the renderer's indent and wrapping; this is
   the copy that cannot."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.builtins :refer [copy-target]]
            [agent.utils.markdown-blocks :as mb]))

(def ^:private reply
  (str "Intro\n\n```js\nconst a = 1;\nconst b = 2;\n```\n\n"
       "- step\n\n  ```py\n  x = 1\n  ```\n"))

(describe "code-blocks"
          (fn []
            (it "finds top-level and nested fences, in order, as exact source"
                (fn []
                  (-> (expect (clj->js (mb/code-blocks reply)))
                      (.toEqual (clj->js [{:lang "js" :text "const a = 1;\nconst b = 2;"}
                                          {:lang "py" :text "x = 1"}])))))

            (it "none in plain text, nil, or empty"
                (fn []
                  (doseq [t ["just words" nil ""]]
                    (-> (expect (count (mb/code-blocks t))) (.toBe 0)))))))

(describe "copy-target"
          (fn []
            (it "no args: the whole reply"
                (fn []
                  (-> (expect (:text (copy-target reply []))) (.toBe reply))))

            (it "code: the LAST block, with a receipt naming it"
                (fn []
                  (let [r (copy-target reply ["code"])]
                    (-> (expect (:text r)) (.toBe "x = 1"))
                    (-> (expect (:receipt r)) (.toBe "Copied code block (py, 1 line)")))))

            (it "code N: Nth from last"
                (fn []
                  (let [r (copy-target reply ["code" "2"])]
                    (-> (expect (:text r)) (.toBe "const a = 1;\nconst b = 2;"))
                    (-> (expect (:receipt r)) (.toBe "Copied code block (js, 2 lines)")))))

            (it "errors say what went wrong"
                (fn []
                  (-> (expect (:error (copy-target "words" ["code"])))
                      (.toBe "No code block in the last reply"))
                  (-> (expect (:error (copy-target reply ["code" "3"])))
                      (.toBe "Only 2 code blocks in the last reply"))
                  (doseq [bad ["0" "x" "-1"]]
                    (-> (expect (:error (copy-target reply ["code" bad])))
                        (.toContain "expected a positive number")))
                  (-> (expect (:error (copy-target reply ["nope"])))
                      (.toContain "/copy code [N]"))))))
