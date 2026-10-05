(ns agent.utils.markdown-blocks
  "Incremental markdown rendering via block-level tokenization.
   Splits content into blocks using marked.lexer(), caches rendered ANSI
   for all blocks except the last one, and only re-renders the last block
   on each streaming chunk. Supports custom block renderers registered
   by extensions."
  (:require [agent.utils.markdown :as md]))

;;; ─── Block splitting ──────────────────────────────────────────

(defn split-blocks
  "Split markdown content into block-level tokens using marked.lexer().
   Returns a vector of {:type string, :raw string, :lang string?}."
  [content]
  (if (or (nil? content) (= content ""))
    []
    (try
      (let [tokens (md/lexer content)]
        (vec
         (keep (fn [token]
                 (let [t (.-type token)
                       raw (.-raw token)]
                    ;; Skip pure whitespace tokens
                   (when (and t raw (not= t "space"))
                     {:type t
                      :raw  raw
                      :lang (.-lang token)})))
               tokens)))
      (catch :default _e
        [{:type "paragraph" :raw content}]))))

;;; ─── Single block rendering ───────────────────────────────────

(def ^:private fence-re #"^\s*(>\s*)*(```|~~~)")
(def ^:private no-break-before-re
  "Lines a hard break must not precede: a new list item, a fence, a heading,
   a setext underline, a bare `>` (an empty quote line)."
  #"^\s*(([-*+]|\d+[.)])\s|(>\s*)*(```|~~~)|#{1,6}\s|(=+|-+)\s*$|(>\s*)+$)")

(defn- backtick-count
  "Unescaped backticks in a line."
  [line]
  (count (.match line (js/RegExp. "(?<!\\\\)`" "g"))))

(defn keep-line-breaks
  "Turn single newlines inside a paragraph, list or quote into hard breaks.

   Bun.markdown.ansi follows CommonMark and joins a soft break into a space
   (`a\nb` → `a b`); its `hardSoftBreaks` option is not honoured by `ansi` on
   1.4.2. marked-terminal ran with reflowText off and kept them, and models
   write one-thing-per-line text — `- src/foo.cljs\n  renamed bar`, a quoted
   excerpt — that means exactly that. A trailing backslash is CommonMark's
   own hard break, so it goes at the end of a line ONLY where the next line
   continues the same paragraph:
   - not before a new item, a fence, a heading or an empty quote line, and
     not on the last line — there it renders as a literal backslash;
   - not inside a fence nested in a list item, where it would be code;
   - not inside an open inline code span, which Bun already breaks on its
     own and where the backslash would be literal."
  [raw]
  (let [lines (.split raw "\n")
        n     (.-length lines)
        out   #js []]
    (loop [i 0, in-fence false, ticks 0]
      (if (>= i n)
        (.join out "\n")
        (let [line   (aget lines i)
              nxt    (when (< (inc i) n) (aget lines (inc i)))
              blank? (= "" (.trim line))
              fence? (boolean (.test fence-re line))
              ticks  (if blank? 0 (+ ticks (backtick-count line)))
              break? (and (not in-fence) (not fence?) (not blank?)
                          (some? nxt) (seq (.trim nxt))
                          (not (.test no-break-before-re nxt))
                          (even? ticks)
                          (not (.endsWith line "\\"))
                          (not (.endsWith line "  ")))]
          (.push out (if break? (str line "\\") line))
          (recur (inc i) (if fence? (not in-fence) in-fence) ticks))))))

(def ^:private sgr "(?:\u001b\\[[0-9;]*m)*")
(def ^:private sgr-re (js/RegExp. "\u001b\\[[0-9;]*m" "g"))
(def ^:private frame-top-re
  (js/RegExp. (str "^" sgr "(\\s*)" sgr "(?:┌─|\\+-) ?" "(?:\u001b\\[0m)?")))
(def ^:private frame-line-re
  (js/RegExp. (str "^" sgr "(\\s*)" sgr "(?:│|\\|) ?" "(?:\u001b\\[0m)?")))

(defn- bare [l] (.replace l sgr-re ""))

(defn- frame-end
  "Index of the line closing a code frame opened at line i, or nil. The
   frame is `┌─[ lang]` … `└─` at one indent, with `│` opening every line
   between. A table border (`┌───┬`) is not a frame: after `┌─` comes `─`."
  [lines i]
  (when-let [[_ indent] (.exec (js/RegExp. "^(\\s*)(?:┌─|\\+-)(?: \\S.*)?$") (.trimEnd (bare (aget lines i))))]
    (let [n (.-length lines)]
      (loop [j (inc i)]
        (when (< j n)
          (let [b (.trimEnd (bare (aget lines j)))]
            (cond
              (or (= b (str indent "└─")) (= b (str indent "+-"))) j
              (or (.startsWith b (str indent "│")) (.startsWith b (str indent "|"))) (recur (inc j))
              :else nil)))))))

(defn unframe-code
  "Replace Bun's box around a code block with a plain two-space indent.

   Bun draws `┌─ lang` / `│ code` / `└─` (`+-` / `| ` with colours off), so
   every line selected in the terminal carried `│ ` into the paste. The
   language label stays; highlighting is untouched. Works wherever the
   frame sits — a fence inside a list item is rendered by the list block.
   Only the frame's top and bottom lines and each line's leading glyph are
   touched, so code that itself contains `│` survives. Shape pinned by
   test/markdown_ansi_pin."
  [rendered]
  (let [lines (.split rendered "\n")
        n     (.-length lines)
        out   #js []]
    (loop [i 0]
      (if (>= i n)
        (.join out "\n")
        (if-let [end (frame-end lines i)]
          (let [top (.replace (aget lines i) frame-top-re "$1  ")]
            (when (seq (.trim (bare top))) (.push out top))
            (doseq [k (range (inc i) end)]
              (.push out (.replace (aget lines k) frame-line-re "$1  ")))
            (recur (inc end)))
          (do (.push out (aget lines i)) (recur (inc i))))))))

(defn render-block
  "Render a single block's raw markdown to ANSI string.
   If custom-renderers has a matching renderer for the block type+lang,
   call it instead. Custom renderer signature: (fn [{:keys [type raw lang]}] -> string | nil).
   Returns nil from custom renderer to fall back to default."
  ([block custom-renderers] (render-block block custom-renderers nil))
  ([block custom-renderers columns]
   (let [type       (:type block)
         lang       (:lang block)
        ;; Check for custom renderer: try type+lang first (e.g. "code:diff"), then type only
         renderer-key (when lang (str type ":" lang))
         custom-fn    (or (when renderer-key (get custom-renderers renderer-key))
                          (get custom-renderers type))
         raw          (if (contains? #{"paragraph" "list" "blockquote"} type)
                        (keep-line-breaks (:raw block))
                        (:raw block))
         default      (fn []
                        (cond-> (md/render-markdown raw columns)
                          (contains? #{"code" "list" "blockquote"} type) unframe-code))]
     (if custom-fn
       (let [result (try (custom-fn block) (catch :default _ nil))]
         (if (some? result)
           (str result)
          ;; Custom returned nil → fall back to default
           (default)))
       (default)))))

;;; ─── Incremental render ───────────────────────────────────────

(defn incremental-render
  "Incrementally render markdown content using block-level caching.
   prev-cache is the cache from the previous call (or nil on first call).
   custom-renderers is a map of {type-key → render-fn}. `columns` is the
   width blocks are laid out for; a cache built for another width is a full
   miss, since a table rendered for the old width is wrong at the new one.
   Returns {:blocks [...], :rendered-blocks [...], :rendered string, :columns}."
  ([content prev-cache custom-renderers]
   (incremental-render content prev-cache custom-renderers nil))
  ([content prev-cache custom-renderers columns]
   (if (or (nil? content) (= content ""))
     {:blocks [] :rendered-blocks [] :rendered ""}
     (let [blocks (split-blocks content)
           n      (count blocks)]
       (if (zero? n)
         {:blocks [] :rendered-blocks [] :rendered ""}
         (let [prev-cache    (when (= columns (:columns prev-cache)) prev-cache)
               prev-blocks   (:blocks prev-cache)
               prev-rendered (:rendered-blocks prev-cache)
               prev-n        (count prev-blocks)
              ;; For each block except the last, check if it matches the cached version
               rendered-blocks
               (vec
                (map-indexed
                 (fn [i block]
                   (if (and (< i (dec n))             ;; not the last block
                            (< i prev-n)              ;; was in previous cache
                            (= (:raw block) (:raw (nth prev-blocks i)))) ;; unchanged
                      ;; Reuse cached render
                     (nth prev-rendered i)
                      ;; Re-render this block
                     (render-block block custom-renderers columns)))
                 blocks))
              ;; Join all rendered blocks
               rendered (.join (clj->js rendered-blocks) "\n")]
           {:blocks          blocks
            :rendered-blocks rendered-blocks
            :rendered        rendered
            :columns         columns}))))))
