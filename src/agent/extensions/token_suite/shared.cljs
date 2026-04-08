(ns agent.extensions.token-suite.shared
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── Stats tracking ───────────────────────────────────────────
(def suite-stats
  (atom {:observation-mask {:turns 0 :messages-masked 0 :tokens-saved 0}
         :kv-cache         {:turns 0 :cache-hits 0 :cached-tokens 0}
         :expired-context  {:turns 0 :stale-replaced 0 :tokens-saved 0}
         :tool-truncation  {:calls 0 :chars-saved 0}
         :repo-map         {:files 0 :symbols 0 :last-index-ms 0}
         :priority-assembly {:turns 0 :messages-pruned 0 :tokens-saved 0}
         :diff-edit          {:hunks-applied 0 :fuzzy-matches 0 :chars-saved 0 :calls 0}
         :structured-context {:files-discovered 0 :hot-tokens 0 :warm-tokens 0 :cache-hits 0}
         :smart-compaction   {:background-updates 0 :offloads 0 :full-compactions 0
                              :tokens-archived 0 :re-reads 0}
         :context-folding    {:foci-started 0 :foci-completed 0 :messages-folded 0
                              :tokens-freed 0}}))

;; ── Default configuration ────────────────────────────────────
(def default-config
  {:observation-mask {:keep-recent 10}
   :kv-cache         {:enabled true :min-system-tokens 500}
   :expired-context  {:track-reads true :track-greps true}
   :tool-truncation  {:max-chars 10000
                      :head-lines 100
                      :tail-lines 50
                      :per-tool {"bash" {:head-lines 20 :tail-lines 80}
                                 "read" {:head-lines 100 :tail-lines 50}
                                 "grep" {:max-per-match 500}}}
   :repo-map         {:max-tokens 2000 :reindex-on-edit true
                      :extensions #{"js" "ts" "tsx" "cljs" "py" "rs" "go" "java" "rb"}}
   :priority-assembly {:min-keep 3 :always-keep-compaction true}
   :diff-edit          {:fuzzy-enabled true
                        :whitespace-threshold 0.85
                        :compress-results true}
   :structured-context {:enabled true
                        :hot-budget 2000
                        :warm-budget 4000
                        :scan-depth 3
                        :file-patterns ["CLAUDE.md" "CONTEXT.md" ".cursorrules"]
                        :mdc-dir ".cursor/rules"}
   :smart-compaction   {:background-threshold 0.50
                        :offload-threshold 0.70
                        :full-threshold 0.85
                        :offload-min-tokens 2000
                        :cache-dir ".nyma/context-cache"
                        :max-preview-lines 5
                        :use-llm-summary false
                        :summarization-model nil}
   :context-folding    {:enabled true
                        :max-depth 3
                        :inject-instructions true}})

;; ── Utility functions ────────────────────────────────────────

(defn count-lines [s]
  (if (or (nil? s) (= s "")) 0
    (let [matches (re-seq #"\n" s)]
      (inc (count matches)))))

(defn count-chars [s]
  (count (str s)))

(defn truncate-head-tail
  "Keep first `head` lines and last `tail` lines, with truncation notice."
  [text head-lines tail-lines]
  (let [lines (.split (str text) "\n")
        total (.-length lines)]
    (if (<= total (+ head-lines tail-lines))
      text
      (let [head-part (.slice lines 0 head-lines)
            tail-part (.slice lines (- total tail-lines))
            omitted   (- total head-lines tail-lines)]
        (str (.join head-part "\n")
             "\n... (" omitted " lines truncated) ...\n"
             (.join tail-part "\n"))))))

(defn is-claude-model? [model-id]
  (and (string? model-id)
       (.startsWith (str model-id) "claude")))

(defn msg-role [msg]
  (or (when (map? msg) (:role msg))
      (when (object? msg) (.-role msg))
      ""))

(defn msg-content [msg]
  (or (when (map? msg) (:content msg))
      (when (object? msg) (.-content msg))
      ""))

(defn has-error-pattern? [text]
  (boolean (re-find #"(?i)error:|Error |FAIL|panic|exception|stack trace|TypeError|ReferenceError" (str text))))

(defn load-config
  "Load config from .nyma/settings.json token-suite key, merge with defaults."
  []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")]
    (if (fs/existsSync settings-path)
      (try
        (let [raw (fs/readFileSync settings-path "utf8")
              parsed (js/JSON.parse raw)
              suite  (.-token-suite parsed)]
          (if suite
            (merge-with merge default-config (js/JSON.parse (js/JSON.stringify suite)))
            default-config))
        (catch :default _e default-config))
      default-config)))

(defn levenshtein-distance
  "Compute Levenshtein edit distance between two strings."
  [a b]
  (let [la (count a)
        lb (count b)]
    (if (zero? la) lb
      (if (zero? lb) la
        (let [prev (js/Int32Array. (inc lb))]
          ;; Initialize first row
          (loop [j 0]
            (when (<= j lb)
              (aset prev j j)
              (recur (inc j))))
          ;; Fill matrix row by row
          (loop [i 1]
            (if (> i la)
              (aget prev lb)
              (let [curr (js/Int32Array. (inc lb))
                    ci (.charCodeAt a (dec i))]
                (aset curr 0 i)
                (loop [j 1]
                  (when (<= j lb)
                    (let [cost (if (= ci (.charCodeAt b (dec j))) 0 1)]
                      (aset curr j (js/Math.min
                                     (inc (aget curr (dec j)))
                                     (inc (aget prev j))
                                     (+ (aget prev (dec j)) cost))))
                    (recur (inc j))))
                ;; Copy curr to prev for next iteration
                (loop [j 0]
                  (when (<= j lb)
                    (aset prev j (aget curr j))
                    (recur (inc j))))
                (recur (inc i))))))))))

(defn levenshtein-similarity
  "Normalized similarity between 0.0 (completely different) and 1.0 (identical)."
  [a b]
  (let [max-len (max (count a) (count b))]
    (if (zero? max-len) 1.0
      (- 1.0 (/ (levenshtein-distance a b) max-len)))))

(defn hash-content
  "Generate a short hex hash of text content for cache filenames."
  [text]
  (.toString (js/Bun.hash (str text)) 16))
