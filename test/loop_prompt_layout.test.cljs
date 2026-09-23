(ns loop-prompt-layout.test
  "Cache-aware system prompt layout. Everything stable comes first; per-turn
   text an extension declares as `volatile-additions` goes after an explicit
   `---` boundary, so the provider's prefix cache covers the stable part and
   kv_cache has an exact place for its breakpoint. Before this, a todo ledger
   or a step reminder in the middle of the block invalidated the whole prefix."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.loop :refer [run volatile-boundary]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn- captured-system
  "Run one blocked turn with the given before_agent_start handlers and return
   the system prompt that reached before_provider_request."
  [handlers]
  (let [agent (make-test-agent)
        seen  (atom nil)]
    (doseq [h handlers] ((:on (:events agent)) "before_agent_start" h))
    ((:on (:events agent)) "before_provider_request"
                           (fn [cfg] (reset! seen (.-system cfg)) #js {:block true}))
    (-> (run agent "hi") (.then (fn [] @seen)))))

(describe "loop:prompt layout" (fn []
                                 (it "volatile additions go last, after the boundary; static ones stay before it"
                                     (fn []
                                       (-> (captured-system [(fn [_] #js {"system-prompt-additions" #js ["STATIC-A"]})
                                                             (fn [_] #js {"volatile-additions" #js ["TODO-LEDGER"]})
                                                             (fn [_] #js {"prompt-sections" #js [#js {"content" "SECTION" "priority" 1}]})])
                                           (.then (fn [sys]
                                                    (let [b (.indexOf sys volatile-boundary)]
                                                      (-> (expect b) (.toBeGreaterThan 0))
                                                      (-> (expect (.indexOf sys "STATIC-A")) (.toBeLessThan b))
                                                      (-> (expect (.indexOf sys "SECTION")) (.toBeLessThan b))
                                                      (-> (expect (.indexOf sys "TODO-LEDGER")) (.toBeGreaterThan b))
                       ;; the stable prefix is byte-identical whatever the tail says
                                                      (-> (expect (.slice sys 0 b)) (.toContain "You are a test agent."))))))))

                                 (it "two volatile contributors concatenate after one boundary"
                                     (fn []
                                       (-> (captured-system [(fn [_] #js {"volatile-additions" #js ["V1"]})
                                                             (fn [_] #js {"volatile-additions" #js ["V2"]})])
                                           (.then (fn [sys]
                                                    (-> (expect (count (.split sys volatile-boundary))) (.toBe 2))
                                                    (-> (expect sys) (.toContain "V1"))
                                                    (-> (expect sys) (.toContain "V2")))))))

                                 (it "no volatile additions → no boundary at all"
                                     (fn []
                                       (-> (captured-system [(fn [_] #js {"system-prompt-additions" #js ["STATIC"]})])
                                           (.then (fn [sys]
                                                    (-> (expect (.includes sys volatile-boundary)) (.toBe false)))))))))
