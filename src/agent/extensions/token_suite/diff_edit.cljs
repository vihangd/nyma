(ns agent.extensions.token-suite.diff-edit
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.token-suite.shared :as shared]
            [clojure.string :as str]))

;; ── Fuzzy Matching Engine ──────────────────────────────────────

(defn- exact-match
  "Try exact string match. Returns {:start :end :match-type} or nil."
  [content old-str]
  (let [idx (.indexOf content old-str)]
    (when (>= idx 0)
      {:start idx :end (+ idx (count old-str)) :match-type :exact
       :matched-text (subs content idx (+ idx (count old-str)))})))

(defn- normalize-whitespace [s]
  (.replace (str s) (js/RegExp. "\\s+" "g") " "))

(defn- whitespace-norm-match
  "Match after collapsing whitespace runs to single space."
  [content old-str]
  (let [norm-old (normalize-whitespace old-str)
        norm-content (normalize-whitespace content)]
    (when (not= norm-old "")
      (let [idx (.indexOf norm-content norm-old)]
        (when (>= idx 0)
          ;; Map back to original content position
          ;; Walk original content, counting normalized chars to find start/end
          (let [result (atom nil)
                norm-pos (atom 0)
                start-orig (atom -1)
                end-orig (atom -1)
                in-ws (atom false)
                len (count content)]
            (loop [i 0]
              (when (and (< i len) (= @end-orig -1))
                (let [ch (.charAt content i)
                      is-ws (boolean (re-find #"\s" ch))]
                  (when (and (= @norm-pos idx) (= @start-orig -1))
                    (reset! start-orig i))
                  (cond
                    ;; Non-whitespace: always advances norm-pos
                    (not is-ws)
                    (do (swap! norm-pos inc)
                        (reset! in-ws false))
                    ;; First whitespace in a run: advances norm-pos by 1 (the single space)
                    (and is-ws (not @in-ws))
                    (do (swap! norm-pos inc)
                        (reset! in-ws true))
                    ;; Subsequent whitespace: no norm-pos advance
                    :else nil)
                  (when (and (>= @start-orig 0) (= @norm-pos (+ idx (count norm-old))))
                    (reset! end-orig i))
                  (recur (inc i)))))
            (when (and (>= @start-orig 0) (> @end-orig @start-orig))
              {:start @start-orig :end @end-orig :match-type :whitespace
               :matched-text (subs content @start-orig @end-orig)})))))))

(defn- strip-leading-ws [line]
  (.replace (str line) (js/RegExp. "^\\s+") ""))

(defn- get-leading-ws [line]
  (let [m (.match (str line) (js/RegExp. "^(\\s+)"))]
    (if m (aget m 1) "")))

(defn- indent-match
  "Match by stripping leading whitespace from each line."
  [content old-str]
  (let [content-lines (.split content "\n")
        old-lines (.split old-str "\n")
        old-stripped (mapv strip-leading-ws old-lines)
        old-count (count old-lines)
        total (.-length content-lines)]
    (when (and (pos? old-count) (<= old-count total))
      (loop [i 0]
        (when (<= (+ i old-count) total)
          (let [window (.slice content-lines i (+ i old-count))
                window-stripped (mapv strip-leading-ws window)]
            (if (= (vec window-stripped) old-stripped)
              ;; Found match — capture original indentation
              (let [matched-text (.join window "\n")
                    start (.indexOf content matched-text)]
                {:start (if (>= start 0) start 0)
                 :end (+ (if (>= start 0) start 0) (count matched-text))
                 :match-type :indent
                 :matched-text matched-text
                 :original-indent (mapv get-leading-ws window)})
              (recur (inc i)))))))))

(defn- levenshtein-match
  "Sliding window match using Levenshtein similarity. Gated by size."
  [content old-str threshold]
  (let [content-lines (.split content "\n")
        old-lines (.split old-str "\n")
        old-count (count old-lines)
        total (.-length content-lines)]
    (when (and (<= old-count 50) (<= total 10000) (pos? old-count))
      (let [old-text (str/join "\n" old-lines)
            best (atom nil)]
        (loop [i 0]
          (when (<= (+ i old-count) total)
            (let [window (.slice content-lines i (+ i old-count))
                  window-text (.join window "\n")
                  sim (shared/levenshtein-similarity window-text old-text)]
              (when (and (>= sim threshold)
                         (or (nil? @best) (> sim (:similarity @best))))
                (let [start (.indexOf content window-text)]
                  (reset! best {:start (if (>= start 0) start 0)
                                :end (+ (if (>= start 0) start 0) (count window-text))
                                :match-type :levenshtein
                                :matched-text window-text
                                :similarity sim}))))
            (recur (inc i))))
        @best))))

(defn- find-best-match
  "Cascading match: exact → whitespace → indent → levenshtein."
  [content old-str config]
  (or (exact-match content old-str)
      (whitespace-norm-match content old-str)
      (indent-match content old-str)
      (when (:fuzzy-enabled config)
        (levenshtein-match content old-str (or (:whitespace-threshold config) 0.85)))))

(defn- apply-indent
  "Apply original indentation from match to new-string lines."
  [new-str match-info]
  (if-let [indents (:original-indent match-info)]
    (let [new-lines (.split new-str "\n")
          result (map-indexed
                   (fn [i line]
                     (let [indent (if (< i (count indents))
                                    (nth indents i)
                                    (if (seq indents) (last indents) ""))]
                       (str indent (strip-leading-ws line))))
                   new-lines)]
      (str/join "\n" result))
    new-str))

(defn- find-line-number
  "Find the 1-based line number at a character offset."
  [content offset]
  (let [prefix (subs content 0 (min offset (count content)))]
    (inc (count (re-seq #"\n" prefix)))))

;; ── Multi-Edit Tool ───────────��────────────────────────────────

(defn ^:async multi-edit-execute-fn [api config args]
  (let [fpath (.-path args)
        edits-raw (.-edits args)
        edits (map (fn [e] {:old (.-old_string e) :new (.-new_string e)})
                   edits-raw)
        content-ref (atom (js-await (.text (js/Bun.file fpath))))
        results (atom [])
        total (count edits)]

    ;; Apply edits sequentially
    (doseq [i (range total)]
      (let [edit (nth edits i)
            match (find-best-match @content-ref (:old edit) config)]
        (if match
          (let [replacement (if (= (:match-type match) :indent)
                              (apply-indent (:new edit) match)
                              (:new edit))
                before (subs @content-ref 0 (:start match))
                after (subs @content-ref (:end match))
                line-num (find-line-number @content-ref (:start match))]
            (reset! content-ref (str before replacement after))
            (when (not= (:match-type match) :exact)
              (swap! shared/suite-stats update-in [:diff-edit :fuzzy-matches] inc))
            (swap! shared/suite-stats update-in [:diff-edit :hunks-applied] inc)
            (swap! results conj {:applied true :match-type (:match-type match)
                                 :line line-num :hunk (inc i)}))
          (swap! results conj {:applied false :hunk (inc i)}))))

    ;; Write back if any edits applied
    (let [applied-count (count (filter :applied @results))]
      (when (pos? applied-count)
        (js-await (js/Bun.write fpath @content-ref))

        ;; Emit context:file-op for expired_context and repo_map
        (when-let [events (.-events api)]
          (let [emit-fn (.-emit events)]
            (when (fn? emit-fn)
              (emit-fn "context:file-op"
                #js {:type "edit" :path fpath :tool "multi_edit"})))))

      ;; Track stats
      (swap! shared/suite-stats update-in [:diff-edit :calls] inc)
      (let [orig-len (reduce + 0 (map #(count (:old %)) edits))
            new-len (reduce + 0 (map #(count (:new %)) edits))]
        (when (> orig-len new-len)
          (swap! shared/suite-stats update-in [:diff-edit :chars-saved] + (- orig-len new-len))))

      ;; Build compact summary
      (let [match-types (frequencies (map :match-type (filter :applied @results)))
            type-summary (str/join ", "
                           (map (fn [[t c]]
                                  (str c " " (str t)))
                                match-types))
            lines (map (fn [r]
                         (if (:applied r)
                           (str "  L" (:line r) ": hunk " (:hunk r) " applied (" (str (:match-type r)) ")")
                           (str "  hunk " (:hunk r) ": old_string not found")))
                       @results)]
        (str (if (= applied-count total) "Applied" "Partially applied")
             " " applied-count "/" total " edits to " fpath
             (when (seq type-summary) (str " (" type-summary ")"))
             "\n" (str/join "\n" lines))))))

;; ── Compression Middleware ────────────���────────────────────────

(defn- compress-leave [ctx]
  (let [tool-name (or (.-tool-name ctx) (aget ctx "tool-name") "")
        result (or (.-result ctx) (aget ctx "result") "")
        args (or (.-args ctx) (aget ctx "args") #js {})]
    (cond
      (= tool-name "edit")
      (let [fpath (or (.-path args) (aget args "path") "unknown")]
        (aset ctx "result" (str "Edit applied to " fpath))
        ctx)

      (= tool-name "write")
      (let [fpath (or (.-path args) (aget args "path") "unknown")
            content-str (or (.-content args) (aget args "content") "")
            lines (shared/count-lines content-str)]
        (aset ctx "result" (str "Wrote " (count content-str) "B (" lines " lines) to " fpath))
        ctx)

      :else ctx)))

;; ── Activate / Deactivate ──────────────────────────────────────

(defn activate [api]
  (let [config (shared/load-config)
        de-cfg (:diff-edit config)
        multi-edit-tool
        (tool
          #js {:description "Apply multiple search-and-replace edits to a file in one call. Supports fuzzy matching for whitespace and indentation differences."
               :inputSchema (.object z
                              #js {:path  (-> (.string z) (.describe "File path to edit"))
                                   :edits (-> (.array z
                                                (.object z
                                                  #js {:old_string (.string z)
                                                       :new_string (.string z)}))
                                              (.describe "Array of {old_string, new_string} pairs to apply sequentially"))})
               :execute (fn [args] (multi-edit-execute-fn api de-cfg args))})]

    (.registerTool api "multi_edit" multi-edit-tool)

    (when (:compress-results de-cfg)
      (.addMiddleware api
        #js {:name "token-suite/diff-edit-compress"
             :leave compress-leave}))

    ;; Return deactivator
    (fn []
      (.unregisterTool api "multi_edit")
      (.removeMiddleware api "token-suite/diff-edit-compress"))))
