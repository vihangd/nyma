(ns agent.ui.streaming-markdown
  (:require ["react" :refer [useRef useMemo]]
            [agent.debug :as dbg]
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
         ;; Diagnostic: always-emitted warn to ~/.nyma/debug.log so we can
         ;; measure whether the streaming content alone is overflowing the
         ;; terminal height (suspected root cause of same-row overwrite).
         (when rendered
           (let [line-count (count (.split rendered "\n"))
                 term-rows  (or (.-rows js/process.stdout) 24)]
             (when (> line-count (* 0.5 term-rows))
               (dbg/warn "height" "md exceeds half terminal"
                         {:md-lines  line-count
                          :term-rows term-rows
                          :ratio     (.toFixed (/ line-count term-rows) 2)}))))
         (or rendered "")))
     #js [content])))
