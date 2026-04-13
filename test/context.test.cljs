(ns context.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.context :refer [build-context get-active-tools]]))

(describe "agent.context"
          (fn []
            (it "filters to valid LLM roles"
                (fn []
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "hi"}
                            {:role "assistant" :content "hello"}
                            {:role "tool_call" :content "call"}
                            {:role "tool_result" :content "result"}])
                    (let [ctx (build-context agent)]
                      (-> (expect (count ctx)) (.toBe 4))))))

            (it "excludes :local-only messages (editor !!cmd bash mode)"
                (fn []
        ;; Editor bash mode (!!cmd) tags its assistant message with
        ;; :local-only so it renders on-screen but never reaches the
        ;; model. build-context is the choke point that enforces this.
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "!!echo secret"}
                            {:role "assistant" :content "$ echo secret\nsecret\n"
                             :kind :bash :local-only true}
                            {:role "user" :content "what did I just run?"}])
                    (let [ctx (build-context agent)]
                      (-> (expect (count ctx)) (.toBe 2))
            ;; The second user message survives; the tagged assistant
            ;; message is stripped.
                      (-> (expect (:role (first ctx)))  (.toBe "user"))
                      (-> (expect (:role (second ctx))) (.toBe "user"))))))

            (it "excludes :kind :eval :local-only messages ($$expr hidden mode)"
                (fn []
          ;; Phase 23: same :local-only contract covers editor eval
          ;; mode. $$(+ 1 2) renders on-screen but must never reach
          ;; the model. build-context is the single filter point
          ;; for both bash and eval.
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "$$(+ 1 2)"}
                            {:role "assistant" :content "λ (+ 1 2)\n3\n"
                             :kind :eval :local-only true}
                            {:role "user" :content "what did I just run?"}])
                    (let [ctx (build-context agent)]
                      (-> (expect (count ctx)) (.toBe 2))
                      (-> (expect (:role (first ctx)))  (.toBe "user"))
                      (-> (expect (:role (second ctx))) (.toBe "user"))))))

            (it "keeps :kind :eval messages WITHOUT :local-only in the context"
                (fn []
          ;; Plain `$expr` (single dollar) — the output should
          ;; participate in the conversation so the model can
          ;; reason about it on the next turn.
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "$(+ 1 2)"}
                            {:role "assistant" :content "λ (+ 1 2)\n3\n"
                             :kind :eval}])
                    (let [ctx (build-context agent)]
                      (-> (expect (count ctx)) (.toBe 2))))))

            (it "keeps :kind :bash messages WITHOUT :local-only in the context"
                (fn []
        ;; The plain `!cmd` (no !!) case: the output should participate
        ;; in the conversation so the model can reason about it next
        ;; turn. Only :local-only is filtered.
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "!echo public"}
                            {:role "assistant" :content "$ echo public\npublic\n"
                             :kind :bash}])
                    (let [ctx (build-context agent)]
                      (-> (expect (count ctx)) (.toBe 2))))))

            (it "excludes compaction and system roles"
                (fn []
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "hi"}
                            {:role "compaction" :content "summary"}
                            {:role "system" :content "sys"}
                            {:role "assistant" :content "hello"}])
                    (let [ctx (build-context agent)]
                      (-> (expect (count ctx)) (.toBe 2))))))

            (it "empty messages yields empty array"
                (fn []
                  (let [agent (create-agent {:model "m"})
                        ctx   (build-context agent)]
                    (-> (expect (count ctx)) (.toBe 0)))))

            (it "preserves insertion order"
                (fn []
                  (let [agent (create-agent {:model "m"})]
                    (swap! (:state agent) assoc :messages
                           [{:role "user" :content "A"}
                            {:role "assistant" :content "B"}
                            {:role "user" :content "C"}])
                    (let [ctx (build-context agent)]
                      (-> (expect (:content (first ctx))) (.toBe "A"))
                      (-> (expect (:content (last ctx))) (.toBe "C"))))))

            (it "get-active-tools returns tools from registry"
                (fn []
                  (let [agent (create-agent {:model "m"})
                        tools (get-active-tools agent)]
                    (-> (expect (get tools "read")) (.toBeTruthy))
                    (-> (expect (get tools "bash")) (.toBeTruthy)))))))
