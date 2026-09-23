(ns compaction-strategy.test
  "Compaction's prompts are a registered strategy. Before this the only seam
   was a whole summary handed over blind through `before_compact`, which is
   why `extension-summary-usable?` exists."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.sessions.compaction :as c]))

(defn- gen-capturing [seen]
  (fn [cfg] (swap! seen conj (.-system cfg)) (js/Promise.resolve #js {:text "SUMMARY"})))

(describe "compaction:strategies" (fn []
                                    (afterEach (fn [] (c/reset-strategies!) nil))

                                    (it "default and unknown names resolve to the built-in prompts"
                                        (fn []
                                          (-> (expect (:system-prompt (c/strategy-for nil))) (.toBe c/compact-system-prompt))
                                          (-> (expect (:system-prompt (c/strategy-for "default"))) (.toBe c/compact-system-prompt))
                                          (-> (expect (:system-prompt (c/strategy-for "nope"))) (.toBe c/compact-system-prompt))))

                                    (it "a registered strategy supplies the system prompt compact-with-retry sends"
                                        (fn []
                                          (c/register-strategy! "terse" {:system-prompt "BE TERSE"})
                                          (let [seen (atom [])]
                                            (-> (c/compact-with-retry #js {:modelId "m"} "user prompt" [] [] (gen-capturing seen) (c/strategy-for "terse"))
                                                (.then (fn [text]
                                                         (-> (expect text) (.toBe "SUMMARY"))
                                                         (-> (expect (first @seen)) (.toBe "BE TERSE"))))))))

                                    (it "a missing key falls back to the default's, and unregister restores the default"
                                        (fn []
                                          (c/register-strategy! "half" {:build-user-prompt (fn [_] "UP")})
                                          (let [s (c/strategy-for "half")]
                                            (-> (expect (:system-prompt s)) (.toBe c/compact-system-prompt))
                                            (-> (expect ((:build-user-prompt s) {})) (.toBe "UP")))
                                          (c/unregister-strategy! "half")
                                          (-> (expect (:build-user-prompt (c/strategy-for "half"))) (.toBe c/build-compact-user-prompt))))

                                    (it "settings->opts carries compaction.strategy"
                                        (fn []
                                          (-> (expect (:strategy (c/settings->opts {:compaction {:strategy "terse"}}))) (.toBe "terse"))
                                          (-> (expect (:strategy (c/settings->opts {:compaction {}}))) (.toBeNil))))))
