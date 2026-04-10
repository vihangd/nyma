(ns agent.ui.tool-execution
  "One component per tool call. Dispatches to a renderer looked up by
   tool-name in the tool-renderer-registry. Falls back to the legacy
   ToolStartStatus / ToolEndStatus pair when no custom renderer exists.

   Individual renderers are responsible for their own expand/collapse UX
   (see BashExecution for an example). ToolExecution is a pure dispatcher."
  {:squint/extension "jsx"}
  (:require [agent.ui.tool-renderer-registry :refer [get-renderer]]
            ["./tool_status.jsx" :refer [ToolStartStatus ToolEndStatus]]))

(defn- fallback-render-call [props]
  #jsx [ToolStartStatus props])

(defn- fallback-render-result [props]
  #jsx [ToolEndStatus props])

(defn ToolExecution
  "Props:
     :tool-name     string
     :args          map         — tool call args
     :result        string?     — populated once tool finishes
     :duration      ms?
     :verbosity     string
     :max-lines     int
     :theme         theme map
     :is-partial    bool        — true between tool_start and tool_end
     :custom-*      propagated custom display fields"
  [{:keys [tool-name args result duration verbosity max-lines theme
           is-partial
           custom-one-line-args custom-status-text custom-icon
           custom-one-line-result]
    :as   props}]
  (let [renderer (get-renderer tool-name)
        merge?   (and renderer (:merge-call-and-result? renderer))]
    (cond
      ;; Renderer handles both streaming and finished states in one call.
      merge?
      ((:render-call renderer) props)

      ;; Partial (tool_execution_start) — use :render-call or fallback.
      is-partial
      (let [call-fn (or (and renderer (:render-call renderer))
                        fallback-render-call)]
        (call-fn {:tool-name            tool-name
                  :args                 args
                  :verbosity            verbosity
                  :theme                theme
                  :custom-one-line-args custom-one-line-args
                  :custom-status-text   custom-status-text
                  :custom-icon          custom-icon}))

      ;; Final (tool_execution_end) — prefer :render-result, fall back.
      :else
      (let [result-fn (or (and renderer (:render-result renderer))
                          (and renderer (:render-call renderer))
                          fallback-render-result)]
        (result-fn {:tool-name              tool-name
                    :args                   args
                    :result                 result
                    :duration               duration
                    :verbosity              verbosity
                    :max-lines              max-lines
                    :theme                  theme
                    :custom-one-line-result custom-one-line-result
                    :custom-icon            custom-icon})))))
