(ns agent.token-estimation)

(defn estimate-tokens
  "Content-aware token estimation. Code averages ~3.2 chars/token,
   prose ~3.8 chars/token."
  [text]
  (let [s (str text)
        len (count s)]
    (if (zero? len)
      0
      (let [;; Heuristic: if >20% non-alpha chars, likely code
            non-alpha (count (re-seq #"[^a-zA-Z\s]" s))
            code? (> non-alpha (* len 0.2))
            chars-per-token (if code? 3.2 3.8)]
        (js/Math.ceil (/ len chars-per-token))))))

(defn estimate-messages-tokens
  "Estimate total tokens for a message array. Adds ~4 tokens per-message overhead."
  [messages]
  (reduce (fn [total msg]
            (let [content (or (:content msg) (when (object? msg) (.-content msg)) "")]
              (+ total 4 (estimate-tokens content))))
          0 messages))
