(ns verify-gate.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.verify-gate.shared :as shared]
            [agent.extensions.verify-gate.index :as vg]))

(defn- make-api [cmd sent]
  (let [handlers (atom {})]
    {:api #js {:getSettings     (fn [] (if cmd #js {:verify #js {:cmd cmd :max-attempts 2}} #js {}))
               :on              (fn [evt h] (swap! handlers assoc evt h))
               :off             (fn [evt _] (swap! handlers dissoc evt))
               :sendMessage     (fn [_ _])
               :sendUserMessage (fn [text opts] (swap! sent conj {:text text :opts opts}))}
     :handlers handlers}))

(defn- fire [handlers evt data]
  (when-let [h (get @handlers evt)] (h data nil)))

(describe "verify-gate shared" (fn []
                                 (it "config defaults + settings override"
                                     (fn []
                                       (-> (expect (:cmd (shared/config nil))) (.toBeFalsy))
                                       (let [c (shared/config #js {:verify #js {:cmd "bun test" :max-attempts 3}})]
                                         (-> (expect (:cmd c)) (.toBe "bun test"))
                                         (-> (expect (:max-attempts c)) (.toBe 3))
                                         (-> (expect (:timeout-ms c)) (.toBe 120000)))))

                                 (it "edit-tool? matches only editing tools"
                                     (fn []
                                       (-> (expect (shared/edit-tool? "edit")) (.toBe true))
                                       (-> (expect (shared/edit-tool? "write")) (.toBe true))
                                       (-> (expect (shared/edit-tool? "read")) (.toBe false))
                                       (-> (expect (shared/edit-tool? nil)) (.toBe false))))

                                 (it "failure-message tails long output"
                                     (fn []
                                       (let [long-out (.join (.map (js/Array.from #js {:length 100} (fn [_ i] i))
                                                                   (fn [i] (str "line" i)))
                                                             "\n")
                                             msg      (shared/failure-message "bun test" 1 long-out 1 2)]
                                         (-> (expect msg) (.toContain "line99"))
                                         (-> (expect msg) (.-not) (.toContain "line10\n"))
                                         (-> (expect msg) (.toContain "attempt 1/2")))))))

(describe "verify-gate loop" (fn []
                               (it "does nothing when no cmd configured"
                                   (fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api nil sent)]
                                       (vg/activate api)
                                       (-> (expect (count @handlers)) (.toBe 0)))))

                               (it "injects follow-up when the gate fails after an edit"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "echo FAILWHALE; exit 1" sent)]
                                       (vg/activate api)
                                       (fire handlers "tool_complete" #js {:toolName "edit" :isError false})
                                       (js-await (fire handlers "turn_finalize" #js {:error false}))
                                       (-> (expect (count @sent)) (.toBe 1))
                                       (-> (expect (:text (first @sent))) (.toContain "FAILWHALE"))
                                       (-> (expect (aget (:opts (first @sent)) "deliverAs")) (.toBe "followUp")))))

                               (it "stays quiet when the gate passes"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "exit 0" sent)]
                                       (vg/activate api)
                                       (fire handlers "tool_complete" #js {:toolName "write" :isError false})
                                       (js-await (fire handlers "turn_finalize" #js {:error false}))
                                       (-> (expect (count @sent)) (.toBe 0)))))

                               (it "stays quiet when nothing was edited"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "exit 1" sent)]
                                       (vg/activate api)
                                       (fire handlers "tool_complete" #js {:toolName "read" :isError false})
                                       (js-await (fire handlers "turn_finalize" #js {:error false}))
                                       (-> (expect (count @sent)) (.toBe 0)))))

                               (it "gives up after max-attempts"
                                   (^:async fn []
                                     (let [sent (atom [])
                                           {:keys [api handlers]} (make-api "exit 1" sent)]
                                       (vg/activate api)
                                       (dotimes [_ 3]
                                         (fire handlers "tool_complete" #js {:toolName "edit" :isError false})
                                         (js-await (fire handlers "turn_finalize" #js {:error false})))
                                       ;; max-attempts 2 → only 2 follow-ups, third turn passes through
                                       (-> (expect (count @sent)) (.toBe 2)))))))
