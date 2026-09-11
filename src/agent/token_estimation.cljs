(ns agent.token-estimation)

(defn- unicode-whitespace?
  "The non-ASCII half of JS whitespace. Split out so the hot loop below never
   runs these ten comparisons for the ASCII that is almost every character."
  [c]
  (or (= c 160)                      ;; nbsp
      (= c 5760)                     ;; ogham space mark
      (and (>= c 8192) (<= c 8202))  ;; en quad … hair space
      (= c 8232) (= c 8233)          ;; line / paragraph separator
      (= c 8239)                     ;; narrow nbsp
      (= c 8287)                     ;; medium mathematical space
      (= c 12288)                    ;; ideographic space
      (= c 65279)))                  ;; zero-width nbsp / BOM

(defn- whitespace?
  "JS whitespace (regex \\s) exactly, by code point.

   Worth being exact rather than testing space/tab/CR/LF and stopping: the class
   includes nbsp (160), which is ordinary in web_fetch output. Treating nbsp as
   non-alpha pushes the ratio up, flips the \"looks like code\" branch to 3.2
   chars/token, and inflates the estimate on exactly that content; an inflated
   estimate compacts early, and every early compaction is a full prompt-cache
   miss."
  [c]
  (if (< c 128)
    (or (= c 32) (and (>= c 9) (<= c 13)))
    (unicode-whitespace? c)))

(defn count-non-alpha
  "Characters that are neither ASCII letters nor whitespace. Exposed so the
   differential test can exercise it directly."
  [s len]
  (loop [i 0 n 0]
    (if (>= i len)
      n
      (let [c (.charCodeAt s i)]
        (recur (inc i)
               (if (or (and (>= c 65) (<= c 90))
                       (and (>= c 97) (<= c 122))
                       (whitespace? c))
                 n
                 (inc n)))))))

(defn estimate-tokens
  "Content-aware token estimation. Code averages ~3.2 chars/token,
   prose ~3.8 chars/token."
  [text]
  (let [s (str text)
        len (count s)]
    (if (zero? len)
      0
      (let [;; Heuristic: if >20% non-alpha chars, likely code.
            ;;
            ;; Counted with a charCode loop rather than `(count (re-seq …))`,
            ;; which materialised one single-character string per match — ~100k
            ;; allocations to measure a 230 KB message, 13.8 ms a call, from
            ;; sixteen call sites that mostly walk every message every turn
            ;; (getTokenBudget, the context_assembly payload, the compaction
            ;; threshold check, headroom, priority_assembly, budget). The loop
            ;; is 0.7 ms and allocates nothing.
            non-alpha (count-non-alpha s len)
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
