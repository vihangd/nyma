(ns agent.ui.streaming-markdown
  (:require ["react" :refer [useRef useMemo]]
            [agent.utils.markdown-blocks :as mb]))

(defn useStreamingMarkdown
  "React hook for incremental markdown rendering.
   Caches block-level ANSI output and only re-renders the last block on each update.
   custom-renderers is a map of {type-key → (fn [block] -> string | nil)}."
  [content custom-renderers]
  (let [cache-ref (useRef nil)]
    (useMemo
      (fn []
        (let [prev-cache (.-current cache-ref)
              result     (mb/incremental-render content prev-cache (or custom-renderers {}))
              rendered   (:rendered result)]
          (set! (.-current cache-ref) result)
          (or rendered "")))
      #js [content custom-renderers])))
