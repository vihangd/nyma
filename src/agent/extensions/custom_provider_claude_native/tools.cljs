(ns agent.extensions.custom-provider-claude-native.tools
  "Convert LanguageModelV3 tool specs → Anthropic Messages API tool objects.")

;; ── Function tool conversion ─────────────────────────────────

(defn- function-tool->anthropic [tool]
  ;; V3: {type:"function", name, description?, inputSchema}
  ;; Anthropic: {name, description?, input_schema}
  (let [obj #js {:name        (.-name tool)
                 :input_schema (.-inputSchema tool)}]
    (when (.-description tool)
      (aset obj "description" (.-description tool)))
    obj))

;; ── Tool choice conversion ───────────────────────────────────

(defn tool-choice->anthropic
  "Convert LanguageModelV3ToolChoice → Anthropic tool_choice object."
  [tc]
  (when tc
    (case (.-type tc)
      "auto"     #js {:type "auto"}
      "none"     #js {:type "none"}
      "required" #js {:type "any"}
      "tool"     #js {:type "tool" :name (.-toolName tc)}
      nil)))

;; ── Public ───────────────────────────────────────────────────

(defn tools->anthropic
  "Convert a JS array of LanguageModelV3FunctionTool objects to the Anthropic
   tools array. Non-function tools are skipped. Returns nil if empty."
  [tools]
  (when (and tools (pos? (.-length tools)))
    (let [result #js []]
      (.forEach tools (fn [t]
                        (when (= (.-type t) "function")
                          (.push result (function-tool->anthropic t)))))
      (when (pos? (.-length result))
        result))))
