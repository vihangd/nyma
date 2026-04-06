(ns agent.extensions.token-suite.observation-mask
  (:require [agent.extensions.token-suite.shared :as shared]
            [clojure.string :as str]))

(defn- extract-tool-name
  "Try to extract tool name from a tool_result message."
  [msg i messages]
  ;; Look at the preceding tool_call for tool name
  (loop [j (dec i)]
    (if (< j 0)
      "unknown"
      (let [prev (aget messages j)]
        (if (= (shared/msg-role prev) "tool_call")
          (or (.-toolName prev)
              (when-let [m (.-metadata prev)] (.-tool-name m))
              "unknown")
          (recur (dec j)))))))

(defn- extract-key-facts
  "Extract key facts from content before masking for the placeholder."
  [content tool-name]
  (let [facts (atom [])]
    ;; File path extraction
    (when-let [path-match (re-find #"(?m)^(/[^\s]+)" content)]
      (swap! facts conj (str "path:" (if (string? path-match) path-match (first path-match)))))
    ;; Error mentions
    (let [error-count (count (re-seq #"(?i)error|Error|FAIL" content))]
      (when (pos? error-count)
        (swap! facts conj (str error-count " error mention" (when (> error-count 1) "s")))))
    ;; Grep match count
    (when (= tool-name "grep")
      (let [match-count (count (re-seq #"\n" content))]
        (swap! facts conj (str match-count " matches"))))
    @facts))

(defn activate [api]
  (let [config      (shared/load-config)
        keep-recent (get-in config [:observation-mask :keep-recent] 10)
        turn-stats  (atom {:masked 0 :tokens-saved 0})]

    ;; context_assembly — priority 90 (runs first)
    (.on api "context_assembly"
      (fn [event _ctx]
        (let [messages (.-messages event)
              total    (.-length messages)
              ;; Collect indices of tool_result messages
              result-indices
              (loop [i 0 acc []]
                (if (>= i total) acc
                  (let [msg (aget messages i)
                        role (shared/msg-role msg)]
                    (recur (inc i)
                           (if (= role "tool_result") (conj acc i) acc)))))
              mask-count (max 0 (- (count result-indices) keep-recent))
              to-mask    (set (take mask-count result-indices))]

          ;; Mutate in place
          (doseq [i to-mask]
            (let [msg       (aget messages i)
                  content   (shared/msg-content msg)
                  tool-name (extract-tool-name msg i messages)
                  lines     (shared/count-lines content)
                  chars     (shared/count-chars content)
                  old-tokens (.estimateTokens api content)
                  facts      (extract-key-facts content tool-name)
                  facts-str  (when (seq facts) (str " | " (str/join ", " facts)))
                  placeholder (str "[tool_result: " tool-name
                                   " — " lines " lines, " chars " chars"
                                   facts-str "]")
                  new-tokens (.estimateTokens api placeholder)]
              (aset msg "content" placeholder)
              (swap! turn-stats update :masked inc)
              (swap! turn-stats update :tokens-saved + (- old-tokens new-tokens))))

          ;; Return nil — mutations visible to subsequent handlers
          nil))
      90)

    ;; after_provider_request — stats
    (.on api "after_provider_request"
      (fn [_event _ctx]
        (let [{:keys [masked tokens-saved]} @turn-stats]
          (when (pos? masked)
            (swap! shared/suite-stats update :observation-mask
              (fn [s] (-> s
                          (update :turns inc)
                          (update :messages-masked + masked)
                          (update :tokens-saved + tokens-saved)))))
          (reset! turn-stats {:masked 0 :tokens-saved 0}))))

    ;; Return deactivate
    (fn [] nil)))
