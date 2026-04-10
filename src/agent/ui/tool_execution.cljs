(ns agent.ui.tool-execution
  "One component per tool call. Dispatches to a renderer looked up by
   tool-name in the tool-renderer-registry.

   When no renderer is registered, falls back to a composite view:
     * ToolStartStatus / ToolEndStatus for the header (icon + name +
       duration + one-line summary), and
     * JsonTree below for full args / result when verbosity is
       'full' or 'summary'.

   This gives unregistered tools richer expansion than the raw
   JSON.stringify that tool_status.cljs used to produce, without
   changing the default 'collapsed' UX."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            [agent.ui.tool-renderer-registry :refer [get-renderer]]
            ["./tool_status.jsx" :refer [ToolStartStatus ToolEndStatus]]
            ["./json_tree.jsx" :refer [JsonTree]]))

(defn expanded-verbosity?
  "True when the caller has requested an expanded body view (full / summary)."
  [v]
  (or (= v "full") (= v "summary")))

(defn has-data?
  "A JsonTree is only worth rendering when the underlying value
   contains something. Treats empty strings and nil as absent."
  [v]
  (cond
    (nil? v) false
    (string? v) (pos? (count v))
    (js/Array.isArray v) (pos? (.-length v))
    (map? v) (pos? (count v))
    (object? v) (pos? (.-length (js/Object.keys v)))
    :else true))

(defn- FallbackCall
  "Fallback header + JsonTree body for a tool_execution_start when no
   renderer is registered for tool-name."
  [{:keys [tool-name args verbosity theme
           custom-one-line-args custom-status-text custom-icon]
    :as props}]
  #jsx [Box {:flexDirection "column"}
        [ToolStartStatus {:tool-name            tool-name
                          :args                 args
                          ;; Force header-only rendering — body is ours.
                          :verbosity            "collapsed"
                          :theme                theme
                          :custom-one-line-args custom-one-line-args
                          :custom-status-text   custom-status-text
                          :custom-icon          custom-icon}]
        (when (and (expanded-verbosity? verbosity) (has-data? args))
          #jsx [Box {:marginLeft 2}
                [JsonTree {:data args :theme theme}]])])

(defn- FallbackResult
  "Fallback header + JsonTree body for a finished tool when no renderer
   is registered for tool-name."
  [{:keys [tool-name args result duration verbosity theme
           custom-one-line-result custom-icon]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        ;; Try to parse stringified JSON results so JsonTree can walk
        ;; them. Plain strings fall through unchanged.
        parsed-result (cond
                        (nil? result) nil
                        (string? result)
                        (let [trimmed (.trim result)]
                          (if (and (seq trimmed)
                                   (or (.startsWith trimmed "{")
                                       (.startsWith trimmed "[")))
                            (try (js/JSON.parse trimmed) (catch :default _ result))
                            result))
                        :else result)]
    #jsx [Box {:flexDirection "column"}
          [ToolEndStatus {:tool-name              tool-name
                          :duration               duration
                          :result                 result
                          :verbosity              "collapsed"
                          :theme                  theme
                          :custom-one-line-result custom-one-line-result
                          :custom-icon            custom-icon}]
          (when (expanded-verbosity? verbosity)
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  (when (has-data? args)
                    #jsx [Box {:flexDirection "column"}
                          [Text {:color muted} "args:"]
                          [JsonTree {:data args :theme theme}]])
                  (when (has-data? parsed-result)
                    #jsx [Box {:flexDirection "column"}
                          [Text {:color muted} "result:"]
                          [JsonTree {:data parsed-result :theme theme}]])])]))

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
                        FallbackCall)]
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
                          FallbackResult)]
        (result-fn {:tool-name              tool-name
                    :args                   args
                    :result                 result
                    :duration               duration
                    :verbosity              verbosity
                    :max-lines              max-lines
                    :theme                  theme
                    :custom-one-line-result custom-one-line-result
                    :custom-icon            custom-icon})))))
