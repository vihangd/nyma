(ns prompts.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            [agent.resources.prompts :refer [expand-template discover-prompts]]))

(describe "expand-template" (fn []
  (it "replaces single variable"
    (fn []
      (-> (expect (expand-template "Hello {{name}}!" {:name "World"}))
          (.toBe "Hello World!"))))

  (it "replaces multiple variables"
    (fn []
      (-> (expect (expand-template "{{greeting}} {{name}}!" {:greeting "Hi" :name "Bob"}))
          (.toBe "Hi Bob!"))))

  (it "leaves unmatched placeholders"
    (fn []
      (-> (expect (expand-template "Hello {{unknown}}!" {}))
          (.toBe "Hello {{unknown}}!"))))

  (it "handles empty template"
    (fn []
      (-> (expect (expand-template "" {:name "test"}))
          (.toBe ""))))

  (it "replaces repeated occurrences"
    (fn []
      (-> (expect (expand-template "{{x}} and {{x}}" {:x "same"}))
          (.toBe "same and same"))))))

(describe "discover-prompts" (fn []
  (it "finds .md files in directory"
    (fn []
      (let [dir (str "/tmp/nyma-prompts-test-" (js/Date.now))]
        (fs/mkdirSync dir #js {:recursive true})
        (fs/writeFileSync (str dir "/greet.md") "Hello {{name}}!")
        (fs/writeFileSync (str dir "/bye.md") "Goodbye!")
        (let [result (discover-prompts dir)]
          (-> (expect (get result "greet")) (.toBeDefined))
          (-> (expect (:template (get result "greet"))) (.toBe "Hello {{name}}!"))
          (-> (expect (get result "bye")) (.toBeDefined)))
        ;; Cleanup
        (fs/unlinkSync (str dir "/greet.md"))
        (fs/unlinkSync (str dir "/bye.md"))
        (fs/rmdirSync dir))))

  (it "returns nil for missing directory"
    (fn []
      (let [result (discover-prompts "/tmp/nonexistent-prompts-dir-xyz")]
        (-> (expect result) (.toBeUndefined)))))

  (it "returns empty map for directory with no .md files"
    (fn []
      (let [dir (str "/tmp/nyma-prompts-empty-" (js/Date.now))]
        (fs/mkdirSync dir #js {:recursive true})
        (fs/writeFileSync (str dir "/readme.txt") "not a prompt")
        (let [result (discover-prompts dir)]
          (-> (expect (count result)) (.toBe 0)))
        (fs/unlinkSync (str dir "/readme.txt"))
        (fs/rmdirSync dir))))))
