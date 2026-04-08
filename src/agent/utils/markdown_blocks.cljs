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

(defn render-block
  "Render a single block's raw markdown to ANSI string.
   If custom-renderers has a matching renderer for the block type+lang,
   call it instead. Custom renderer signature: (fn [{:keys [type raw lang]}] -> string | nil).
   Returns nil from custom renderer to fall back to default."
  [block custom-renderers]
  (let [type       (:type block)
        lang       (:lang block)
        ;; Check for custom renderer: try type+lang first (e.g. "code:diff"), then type only
        renderer-key (when lang (str type ":" lang))
        custom-fn    (or (when renderer-key (get custom-renderers renderer-key))
                         (get custom-renderers type))]
    (if custom-fn
      (let [result (try (custom-fn block) (catch :default _ nil))]
        (if (some? result)
          (str result)
          ;; Custom returned nil → fall back to default
          (md/render-markdown (:raw block))))
      (md/render-markdown (:raw block)))))

;;; ─── Incremental render ───────────────────────────────────────

(defn incremental-render
  "Incrementally render markdown content using block-level caching.
   prev-cache is the cache from the previous call (or nil on first call).
   custom-renderers is a map of {type-key → render-fn}.
   Returns {:blocks [...], :rendered-blocks [...], :rendered string}."
  [content prev-cache custom-renderers]
  (if (or (nil? content) (= content ""))
    {:blocks [] :rendered-blocks [] :rendered ""}
    (let [blocks (split-blocks content)
          n      (count blocks)]
      (if (zero? n)
        {:blocks [] :rendered-blocks [] :rendered ""}
        (let [prev-blocks   (:blocks prev-cache)
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
                      (render-block block custom-renderers)))
                  blocks))
              ;; Join all rendered blocks
              rendered (.join (clj->js rendered-blocks) "\n")]
          {:blocks          blocks
           :rendered-blocks rendered-blocks
           :rendered        rendered})))))
