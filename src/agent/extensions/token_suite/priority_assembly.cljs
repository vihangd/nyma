(ns agent.extensions.token-suite.priority-assembly
  (:require [agent.extensions.token-suite.shared :as shared]))

(def ^:private role-base-scores
  {"compaction"  200
   "user"        100
   "assistant"   80
   "tool_call"   60
   "tool_result"  40})

(defn- is-edit-call? [msg]
  (let [tool-name (or (.-toolName msg)
                      (when-let [m (.-metadata msg)] (.-tool-name m))
                      "")]
    (contains? #{"edit" "write" "multi_edit"} tool-name)))

(defn- is-focus-summary? [msg]
  (let [content (str (shared/msg-content msg))]
    (.startsWith content "[Focus:")))

(defn- score-message [msg index total]
  (let [role     (shared/msg-role msg)
        base     (get role-base-scores role 30)
        recency  (if (>= index (- total 5)) 50 0)
        edit-bonus (if (and (= role "tool_call") (is-edit-call? msg)) 30 0)
        focus-bonus (if (and (= role "assistant") (is-focus-summary? msg)) 50 0)]
    (+ base recency edit-bonus focus-bonus)))

(defn- prune-messages [api event pa-cfg]
  (let [messages    (.-messages event)
        total       (.-length messages)
        budget      (.-tokenBudget event)
        sys-prompt  (.-systemPrompt event)
        available   (- (.-inputBudget budget)
                       (.estimateTokens api (str sys-prompt)))
        min-keep    (or (:min-keep pa-cfg) 3)

        ;; Estimate current token usage
        current     (atom 0)
        _           (doseq [i (range total)]
                      (swap! current + 4
                        (.estimateTokens api (str (shared/msg-content (aget messages i))))))

        ;; Under budget — no pruning
        over?       (> @current available)]

    (when over?
      ;; Score each message
      (let [scored (vec (map (fn [i]
                               {:msg (aget messages i)
                                :score (score-message (aget messages i) i total)
                                :index i})
                             (range total)))

            ;; Must-keep indices
            must-keep (set
                        (concat
                          [0]
                          (range (max 0 (- total min-keep)) total)
                          (when (:always-keep-compaction pa-cfg)
                            (keep (fn [{:keys [index]}]
                                    (when (= (shared/msg-role (aget messages index)) "compaction")
                                      index))
                                  scored))))

            ;; Sort prunable by score ascending
            prunable (->> scored
                          (remove #(contains? must-keep (:index %)))
                          (sort-by :score))

            removed  (atom #{})
            tokens   (atom @current)]

        ;; Remove lowest until under budget
        (doseq [{:keys [msg index]} prunable]
          (when (> @tokens available)
            (let [t (+ 4 (.estimateTokens api (str (shared/msg-content msg))))]
              (swap! removed conj index)
              (swap! tokens - t))))

        (let [pruned-count (count @removed)]
          (when (pos? pruned-count)
            ;; Rebuild in original order
            (let [final (atom [])]
              (doseq [i (range total)]
                (when-not (contains? @removed i)
                  (swap! final conj (aget messages i))))
              ;; Insert pruning notice
              (let [notice #js {:role "system"
                                :content (str "[" pruned-count " messages pruned to fit context window]")}
                    arr (clj->js @final)]
                (.splice arr 1 0 notice)
                ;; Stats
                (swap! shared/suite-stats update :priority-assembly
                  (fn [s] (-> s
                              (update :turns inc)
                              (update :messages-pruned + pruned-count)
                              (update :tokens-saved + (- @current @tokens)))))
                #js {:messages arr}))))))))

(defn activate [api]
  (let [config (shared/load-config)
        pa-cfg (:priority-assembly config)]

    (.on api "context_assembly"
      (fn [event _ctx]
        (prune-messages api event pa-cfg))
      70)

    (fn [] nil)))
