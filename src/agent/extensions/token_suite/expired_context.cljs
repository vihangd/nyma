(ns agent.extensions.token-suite.expired-context
  (:require [agent.extensions.token-suite.shared :as shared]))

(defn- extract-path-from-event
  "Extract file path from tool execution event data."
  [event]
  (or (when-let [args (.-args event)]
        (or (.-path args) (aget args "path")))
      nil))

(defn activate [api]
  (let [config       (shared/load-config)
        file-ops     (atom {})   ;; path → [{:turn N :type "read"|"edit"|"write"}]
        turn-counter (atom 0)
        turn-stats   (atom {:stale-replaced 0 :tokens-saved 0})]

    ;; Track file operations via tool_execution_end
    (.on api "tool_execution_end"
      (fn [event _ctx]
        (let [tool-name (or (.-toolName event) "")
              op-type   (cond
                          (= tool-name "read")  "read"
                          (contains? #{"edit" "multi_edit"} tool-name) "edit"
                          (= tool-name "write") "write"
                          :else nil)]
          (when op-type
            (when-let [fpath (extract-path-from-event event)]
              (swap! file-ops update fpath
                (fn [ops] (conj (or ops []) {:turn @turn-counter :type op-type})))
              ;; Emit inter-extension event for repo-map
              (when-let [events (.-events api)]
                (.emit events "context:file-op"
                  #js {:type op-type :path fpath :turn @turn-counter})))))))

    ;; Increment turn counter on turn_end
    (.on api "turn_end"
      (fn [_ _] (swap! turn-counter inc)))

    ;; context_assembly — priority 80 (after observation mask at 90)
    (.on api "context_assembly"
      (fn [event _ctx]
        (let [messages (.-messages event)
              total    (.-length messages)
              ops      @file-ops

              ;; Build stale set: reads that happened before a later edit/write of same path
              stale-reads
              (reduce-kv
                (fn [acc fpath ops-list]
                  (let [last-modify (->> ops-list
                                         (filter #(contains? #{"edit" "write"} (:type %)))
                                         (map :turn)
                                         (apply max -1))
                        stale-turns (->> ops-list
                                         (filter #(and (= (:type %) "read")
                                                       (< (:turn %) last-modify)))
                                         (map :turn))]
                    (reduce (fn [a t] (assoc a (str fpath ":" t) last-modify))
                            acc stale-turns)))
                {} ops)]

          ;; Walk messages, find tool_call/tool_result pairs
          ;; Track which tool_calls are reads and their paths
          (let [call-info (atom {})  ;; index → {:tool-name :path}
                msg-turn  (atom 0)]
            (doseq [i (range total)]
              (let [msg  (aget messages i)
                    role (shared/msg-role msg)]

                ;; Track tool_call metadata
                (when (= role "tool_call")
                  (let [tname (or (.-toolName msg)
                                  (when-let [m (.-metadata msg)] (.-tool-name m))
                                  "")]
                    (when (= tname "read")
                      (let [fpath (or (when-let [m (.-metadata msg)]
                                        (or (.-path (.-args m))
                                            (aget (.-args m) "path")))
                                      nil)]
                        (when fpath
                          (swap! call-info assoc i {:tool-name tname :path fpath}))))))

                ;; Check tool_result for staleness
                (when (= role "tool_result")
                  ;; Find the preceding tool_call
                  (let [call-idx (loop [j (dec i)]
                                   (if (< j 0) nil
                                     (if (= (shared/msg-role (aget messages j)) "tool_call")
                                       j (recur (dec j)))))
                        info     (when call-idx (get @call-info call-idx))]
                    (when (and info (:path info))
                      (let [content (shared/msg-content msg)]
                        ;; Skip already-masked messages
                        (when-not (.startsWith (str content) "[tool_result:")
                          (let [key (str (:path info) ":" @msg-turn)]
                            (when (get stale-reads key)
                              (let [old-tokens (.estimateTokens api content)
                                    placeholder (str "[stale: " (:path info)
                                                     " was modified at turn "
                                                     (get stale-reads key) "]")
                                    new-tokens (.estimateTokens api placeholder)]
                                (aset msg "content" placeholder)
                                (swap! turn-stats update :stale-replaced inc)
                                (swap! turn-stats update :tokens-saved
                                  + (- old-tokens new-tokens))))))))))

                ;; Approximate turn tracking within messages
                (when (= role "assistant")
                  (swap! msg-turn inc)))))

          ;; Return nil — mutations in place
          nil))
      80)

    ;; after_provider_request — stats
    (.on api "after_provider_request"
      (fn [_ _]
        (let [{:keys [stale-replaced tokens-saved]} @turn-stats]
          (when (pos? stale-replaced)
            (swap! shared/suite-stats update :expired-context
              (fn [s] (-> s
                          (update :turns inc)
                          (update :stale-replaced + stale-replaced)
                          (update :tokens-saved + tokens-saved)))))
          (reset! turn-stats {:stale-replaced 0 :tokens-saved 0}))))

    ;; Return deactivate
    (fn []
      (reset! file-ops {})
      (reset! turn-counter 0))))
