(ns handoff.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            [agent.extensions.handoff.shared :as shared]
            [agent.extensions.handoff.index :as h]))

(describe "handoff shared" (fn []
                             (it "format-transcript truncates tool noise, keeps prose"
                                 (fn []
                                   (let [msgs [{:role "user" :content "fix the login bug"}
                                               {:role "tool_result" :content (.repeat "x" 2000)}
                                               {:role "assistant" :content "done"}]
                                         text (shared/format-transcript msgs)]
                                     (-> (expect text) (.toContain "user: fix the login bug"))
                                     (-> (expect text) (.toContain "assistant: done"))
          ;; tool_result capped at 500 chars
                                     (-> (expect (.-length text)) (.toBeLessThan 1200)))))

                             (it "injection-block wraps the brief with continuation framing"
                                 (fn []
                                   (let [b (shared/injection-block "## Goal\nship it")]
                                     (-> (expect b) (.toContain "Handoff brief"))
                                     (-> (expect b) (.toContain "ship it"))
                                     (-> (expect b) (.toContain "Next steps")))))))

(describe "handoff generate-brief!" (fn []
                                      (it "returns early on an empty transcript"
                                          (^:async fn []
                                            (let [api #js {:getState (fn [] {:messages []})}
                                                  msg (js-await (h/generate-brief! api (fn [_] (js/Promise.resolve #js {:text "x"}))))]
                                              (-> (expect msg) (.toContain "Nothing to hand off")))))

                                      (it "generates via the injected gen-fn and reports the path"
                                          (^:async fn []
                                            (let [state (atom {:model #js {:modelId "test-model"}})
                                                  api   #js {:getState (fn [] {:messages [{:role "user" :content "hello"}]})
                                                             :__state_atom state}
                                                  seen  (atom nil)
                                                  msg   (js-await (h/generate-brief!
                                                                   api
                                                                   (fn [opts]
                                                                     (reset! seen opts)
                                                                     (js/Promise.resolve #js {:text "## Goal\ntest"}))))]
                                              (-> (expect msg) (.toContain "handoff.md"))
                                              (-> (expect (.-system @seen)) (.toContain "handoff brief"))
          ;; cleanup the file the test wrote
                                              (do
                                                (when (fs/existsSync (shared/handoff-path))
                                                  (fs/unlinkSync (shared/handoff-path)))))))))
