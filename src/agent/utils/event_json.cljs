(ns agent.utils.event-json
  "Pure mappers from the AI SDK StepResult nyma hands to `turn_end` into the
   plain shapes any JSONL consumer (pi-rpc, `-p --output-format stream-json`)
   writes to the wire. Shared so the two protocols cannot drift on what a
   token or a tool result looks like.")

(defn step-usage
  "AI SDK StepResult usage → `{inputTokens, outputTokens}`.

   Pi carries usage on the turn's message, and `@agentproto/adapter-pi` derives
   its own `usage_update` from it rather than from a separate event — so
   dropping this field silently costs a consumer its token accounting."
  [step]
  (when-let [u (and step (.-usage step))]
    {:inputTokens  (or (.-inputTokens u) 0)
     :outputTokens (or (.-outputTokens u) 0)}))

(defn tool-results
  "StepResult toolResults → pi's wire shape, one entry per result."
  [step]
  (let [rs (and step (.-toolResults step))]
    (vec (for [r (vec (or rs #js []))]
           {:toolCallId (or (.-toolCallId r) "")
            :toolName   (or (.-toolName r) "")
            :result     {:content [{:type "text"
                                    ;; `(or o nil)` would turn a literal false
                                    ;; into the text "null". Squint's `or` uses
                                    ;; CLJS truthiness so 0 and "" survive it,
                                    ;; but false does not.
                                    :text (let [o (.-output r)]
                                            (cond (string? o)    o
                                                  (undefined? o) ""
                                                  :else          (js/JSON.stringify o)))}]
                         :details {}}}))))
