(ns agent.extensions.token-suite.shared
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── Stats tracking ───────────────────────────────────────────
(def suite-stats
  (atom {:kv-cache         {:turns 0 :cache-hits 0 :cache-misses 0 :cached-tokens 0}
         :repo-map         {:files 0 :symbols 0 :last-index-ms 0}
         :priority-assembly {:turns 0 :messages-pruned 0 :tokens-saved 0}
         :diff-edit          {:hunks-applied 0 :fuzzy-matches 0 :chars-saved 0 :calls 0}
         :structured-context {:files-discovered 0 :hot-tokens 0 :warm-tokens 0 :cache-hits 0}
         :smart-compaction   {:full-compactions 0}
         :anthropic-compaction {:turns 0 :requests-with-context-mgmt 0
                                :compactions-observed 0}}))

;; ── Default configuration ────────────────────────────────────
(def default-config
  {:kv-cache         {:enabled true
                      :min-system-tokens 500
                      :cache-messages true
                      :checkpoint-every-turns 4
                      :max-message-breakpoints 2
                      :extra-providers {:anthropic [] :google []}}
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
                        :full-threshold 0.85
                        :use-llm-summary false
                        :summarization-model nil}
   :anthropic-compaction {:enabled true
                          :trigger-tokens 150000
                          :pause-after-compaction false}})

;; ── Utility functions ────────────────────────────────────────

(defn count-lines
  "Line count without allocating. `(count (re-seq #\"\\n\" s))` built one string
   per newline — ~5k throwaway allocations for a 200 KB file, once per file
   indexed by repo_map / structured_context."
  [s]
  (if (or (nil? s) (= s "")) 0
      (let [str' (str s)
            len  (count str')]
        (loop [i 0 n 1]
          (if (>= i len)
            n
            (recur (inc i) (if (= 10 (.charCodeAt str' i)) (inc n) n)))))))

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

(def supported-compaction-models
  "Claude models that support the server-side compaction API
   (`compact-2026-01-12` beta). Match by prefix to tolerate
   `:date` suffixes (e.g. `claude-sonnet-4-6-20260601`)."
  ["claude-mythos-preview"
   "claude-opus-4-7"
   "claude-opus-4-6"
   "claude-sonnet-4-6"])

(defn model-supports-compaction?
  "True when the given model-id matches one of the supported
   server-side compaction models."
  [model-id]
  (let [m (str model-id)]
    (boolean (some #(.startsWith m %) supported-compaction-models))))

(defn detect-cache-provider
  "Return :anthropic, :google, or nil based on model-id.
   Used to route cache_control providerOptions to the right key.
   OpenAI/Groq/Kimi etc. return nil — they don't accept explicit
   breakpoints (OpenAI uses automatic caching, Groq has none).

   `extra-providers` lets settings opt in non-Claude providers that
   also accept Anthropic-format cache_control (e.g. minimax via its
   Anthropic-compat endpoint)."
  ([model-id] (detect-cache-provider model-id nil))
  ([model-id extra-providers]
   (let [m (str model-id)
         in? (fn [provider]
               (some #(.startsWith m %) (get extra-providers provider [])))]
     (cond
       (or (.startsWith m "claude")
           (.startsWith m "anthropic.")
           (in? :anthropic))           :anthropic
       (or (.startsWith m "gemini")
           (in? :google))              :google
       :else                           nil))))

;; AI SDK provider tags, as reported by `model.provider`:
;;   @ai-sdk/anthropic          -> "anthropic.messages"
;;   @ai-sdk/google             -> "google.generative-ai"
;;   @ai-sdk/openai (.chat)     -> "openai.chat"
;;   custom_provider_claude_native -> "claude-native"
(defn- extras-match
  "Only the settings-declared `extra-providers` prefixes — deliberately NOT the
   built-in `claude*` heuristic, which is what this routing exists to bypass."
  [id extra-providers]
  (let [m   (str id)
        in? (fn [provider]
              (some #(.startsWith m %) (get extra-providers provider [])))]
    (cond
      (in? :anthropic) :anthropic
      (in? :google)    :google
      :else            nil)))

(defn- provider-tag->cache-provider [tag]
  (let [t (str tag)]
    (cond
      (or (.startsWith t "anthropic") (.startsWith t "claude-native")) :anthropic
      (.startsWith t "google")                                         :google
      :else                                                            nil)))

(defn detect-cache-provider-for-model
  "Which cache_control dialect (if any) `model` accepts.

   Routes on the model's PROVIDER, not its id. Routing on the id was wrong in a
   way that cost money silently: a Claude model reached over an OpenAI-compatible
   endpoint (a relay, or an Anthropic-compat shim) has an id starting `claude`,
   so it looked cacheable — but @ai-sdk/openai ignores providerOptions.anthropic
   entirely, so the breakpoints were computed, counted against the 4-breakpoint
   budget, and never reached the wire.

   Falls back to the id heuristic only when the model exposes no provider tag,
   which keeps string model values and hand-rolled test doubles working."
  ([model] (detect-cache-provider-for-model model nil))
  ([model extra-providers]
   (let [tag (when (and model (not (string? model))) (.-provider model))
         id  (if (string? model) model (when model (.-modelId model)))]
     (if (seq (str tag))
       ;; A provider tag is authoritative — except that settings-declared extras
       ;; still apply, since those name Anthropic-compat endpoints served over an
       ;; OpenAI-shaped SDK (e.g. minimax), which no tag can express.
       (or (provider-tag->cache-provider tag)
           (extras-match id extra-providers))
       (detect-cache-provider id extra-providers)))))

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
              suite  (aget parsed "token-suite")]
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
