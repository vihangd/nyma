(ns ext-claude-native-tools.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-claude-native.tools :as tools]))

;; ── tools->anthropic ─────────────────────────────────────────

(describe "claude-native/tools — tools->anthropic" (fn []
                                                     (it "returns nil for nil input"
                                                         (fn []
                                                           (-> (expect (nil? (tools/tools->anthropic nil))) (.toBe true))))

                                                     (it "returns nil for empty array"
                                                         (fn []
                                                           (-> (expect (nil? (tools/tools->anthropic #js []))) (.toBe true))))

                                                     (it "converts a single function tool"
                                                         (fn []
                                                           (let [tool  #js {:type "function" :name "bash" :description "Run shell"
                                                                            :inputSchema #js {:type "object" :properties #js {:cmd #js {:type "string"}}}}
                                                                 res   (tools/tools->anthropic #js [tool])
                                                                 first (aget res 0)]
                                                             (-> (expect (.-length res))          (.toBe 1))
                                                             (-> (expect (.-name first))          (.toBe "bash"))
                                                             (-> (expect (.-description first))   (.toBe "Run shell"))
                                                             (-> (expect (.-input_schema first))  (.toBeTruthy)))))

                                                     (it "omits description when not provided"
                                                         (fn []
                                                           (let [tool #js {:type "function" :name "noop"
                                                                           :inputSchema #js {:type "object"}}
                                                                 res  (tools/tools->anthropic #js [tool])]
                                                             (-> (expect (.-description (aget res 0))) (.toBeUndefined)))))

                                                     (it "converts multiple tools preserving order"
                                                         (fn []
                                                           (let [t1  #js {:type "function" :name "read"  :inputSchema #js {:type "object"}}
                                                                 t2  #js {:type "function" :name "write" :inputSchema #js {:type "object"}}
                                                                 res (tools/tools->anthropic #js [t1 t2])]
                                                             (-> (expect (.-length res))          (.toBe 2))
                                                             (-> (expect (.-name (aget res 0)))   (.toBe "read"))
                                                             (-> (expect (.-name (aget res 1)))   (.toBe "write")))))

                                                     (it "skips non-function tools"
                                                         (fn []
                                                           (let [t1  #js {:type "provider-defined" :name "advisor"}
                                                                 t2  #js {:type "function"         :name "bash" :inputSchema #js {:type "object"}}
                                                                 res (tools/tools->anthropic #js [t1 t2])]
                                                             (-> (expect (.-length res)) (.toBe 1))
                                                             (-> (expect (.-name (aget res 0))) (.toBe "bash")))))))

;; ── tool-choice->anthropic ───────────────────────────────────

(describe "claude-native/tools — tool-choice->anthropic" (fn []
                                                           (it "returns nil for nil"
                                                               (fn []
                                                                 (-> (expect (nil? (tools/tool-choice->anthropic nil))) (.toBe true))))

                                                           (it "maps auto → {type:auto}"
                                                               (fn []
                                                                 (let [tc (tools/tool-choice->anthropic #js {:type "auto"})]
                                                                   (-> (expect (.-type tc)) (.toBe "auto")))))

                                                           (it "maps none → {type:none}"
                                                               (fn []
                                                                 (let [tc (tools/tool-choice->anthropic #js {:type "none"})]
                                                                   (-> (expect (.-type tc)) (.toBe "none")))))

                                                           (it "maps required → {type:any}"
                                                               (fn []
                                                                 (let [tc (tools/tool-choice->anthropic #js {:type "required"})]
                                                                   (-> (expect (.-type tc)) (.toBe "any")))))

                                                           (it "maps tool with toolName → {type:tool, name}"
                                                               (fn []
                                                                 (let [tc (tools/tool-choice->anthropic #js {:type "tool" :toolName "bash"})]
                                                                   (-> (expect (.-type tc)) (.toBe "tool"))
                                                                   (-> (expect (.-name tc)) (.toBe "bash")))))))
