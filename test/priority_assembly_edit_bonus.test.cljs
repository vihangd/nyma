(ns priority-assembly-edit-bonus-test
  "The edit bonus has to recognise the tool's LIVE name.

   `score-message` gives a file-editing tool_call 30 extra points so a prune
   keeps the edit rather than the chatter around it. It used to test a literal
   `#{\"edit\" \"write\" \"multi_edit\"}`, and the multi-edit tool is registered
   by the token_suite extension, so its live name is `token-suite__multi_edit`
   — the scoped `registerTool` prefixes with the namespace. The bonus therefore
   never reached the one tool that rewrites a whole file.

   The bug was invisible from the handler: a missing 30 points only shows up as
   the wrong message being dropped, and only under budget pressure. So this
   asserts the score directly, and specifically against the prefixed spelling —
   a test over bare names passes while the hole stays open, which is exactly
   how `matches-tool-name?` came to be needed elsewhere in this repo."
  (:require ["bun:test" :refer [test expect describe beforeEach]]
            [agent.extensions.token-suite.priority-assembly :as pa]
            [agent.tool-metadata :as tm]))

(defn- tool-call [name]
  #js {:role "tool_call" :toolName name :content "{}"})

(defn- register-as-the-extension-does!
  "What `extensions.cljs` does with `diff_edit`'s `:safety` — register metadata
   under the name the tool was registered with, which the scope has already
   prefixed. Reproduced rather than mocked, because the whole question is
   whether the live name resolves."
  []
  (tm/register-metadata! "token-suite__multi_edit"
                         {:destructive? true :requires-confirmation? true
                          :capabilities #{"filesystem" "write"}
                          :category :file}))

(describe "score-message edit bonus"
          (fn []
            (beforeEach (fn [] (tm/reset-extension-metadata!)))

            (test "applies to the native edit tools"
                  (fn []
        ;; index 0 of 100 keeps recency out of it: 60 base + 30 edit.
                    (doseq [n ["edit" "write" "multi_edit"]]
                      (-> (expect (pa/score-message (tool-call n) 0 100)) (.toBe 90)))))

            (test "applies to the extension's PREFIXED multi_edit once registered"
                  (fn []
                    (register-as-the-extension-does!)
                    (-> (expect (pa/score-message (tool-call "token-suite__multi_edit") 0 100))
                        (.toBe 90))))

            (test "does not apply to a read-only tool"
                  (fn []
                    (-> (expect (pa/score-message (tool-call "read") 0 100)) (.toBe 60))))

            (test "reads the name from metadata when toolName is absent"
                  (fn []
                    (register-as-the-extension-does!)
                    (let [msg #js {:role "tool_call" :content "{}"
                                   :metadata #js {"tool-name" "token-suite__multi_edit"}}]
                      (-> (expect (pa/score-message msg 0 100)) (.toBe 90)))))

            (test "a nameless tool_call scores as a plain tool_call, not a crash"
                  (fn []
                    (-> (expect (pa/score-message #js {:role "tool_call" :content "{}"} 0 100))
                        (.toBe 60))))))

(describe "the prune keeps the edit"
          (fn []
            (beforeEach (fn [] (tm/reset-extension-metadata!)))

            (test "a prefixed multi_edit outscores every other prunable tool_call"
                  (fn []
                    (register-as-the-extension-does!)
        ;; What the prune does: sort prunable ascending by score, drop from the
        ;; bottom. The edit must not be at the bottom.
                    (let [msgs   [(tool-call "read") (tool-call "bash")
                                  (tool-call "token-suite__multi_edit") (tool-call "grep")]
                          total  40
                          scored (map-indexed (fn [i m] [(pa/score-message m i total) i]) msgs)
                          lowest (first (sort-by first scored))]
                      (-> (expect (second lowest)) (.not.toBe 2)))))))
