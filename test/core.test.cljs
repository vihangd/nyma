(ns core.test
  (:require ["bun:test" :refer [describe it expect]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [agent.core :refer [create-agent]]))

(describe "agent.core"
  (fn []
    (it "returns object with expected shape"
      (fn []
        (let [agent (create-agent {:model "test-model"
                                   :system-prompt "you are helpful"})]
          (-> (expect (:events agent)) (.toBeTruthy))
          (-> (expect (:config agent)) (.toBeTruthy))
          (-> (expect (:tool-registry agent)) (.toBeTruthy))
          (-> (expect (:state agent)) (.toBeTruthy))
          (-> (expect (:steer-queue agent)) (.toBeTruthy))
          (-> (expect (:follow-queue agent)) (.toBeTruthy))
          (-> (expect (:commands agent)) (.toBeTruthy))
          (-> (expect (:shortcuts agent)) (.toBeTruthy)))))

    (it "config contains model and system-prompt"
      (fn []
        (let [agent (create-agent {:model "claude"
                                   :system-prompt "test prompt"})]
          (-> (expect (:model (:config agent))) (.toBe "claude"))
          (-> (expect (:system-prompt (:config agent))) (.toBe "test prompt")))))

    (it "defaults max-steps to 20"
      (fn []
        (let [agent (create-agent {:model "m"})]
          (-> (expect (:max-steps (:config agent))) (.toBe 20)))))

    (it "respects custom max-steps"
      (fn []
        (let [agent (create-agent {:model "m" :max-steps 5})]
          (-> (expect (:max-steps (:config agent))) (.toBe 5)))))

    (it "tool-registry contains builtin tools"
      (fn []
        (let [agent (create-agent {:model "m"})
              all   ((:all (:tool-registry agent)))]
          (doseq [name ["read" "write" "edit" "bash" "think" "ls" "glob" "grep" "web_fetch" "web_search"]]
            (-> (expect (get all name)) (.toBeTruthy))))))

    (it "merges custom tools with builtins"
      (fn []
        (let [custom-tool {:description "custom"}
              agent (create-agent {:model "m"
                                   :tools {"my-tool" custom-tool}})
              all   ((:all (:tool-registry agent)))]
          (-> (expect (get all "my-tool")) (.toBeTruthy))
          (-> (expect (get all "read")) (.toBeTruthy)))))

    (it "state starts with empty messages"
      (fn []
        (let [agent (create-agent {:model "m"})
              state @(:state agent)]
          (-> (expect (count (:messages state))) (.toBe 0)))))

    (it "event bus on agent is functional"
      (fn []
        (let [agent   (create-agent {:model "m"})
              events  (:events agent)
              called  (atom false)]
          ((:on events) "test" (fn [_] (reset! called true)))
          ((:emit events) "test" {})
          (-> (expect @called) (.toBe true)))))

    ;; Regression: passing a vector of tool names as :tools corrupted the registry.
    ;; Squint's merge treats vectors as maps (pairs), overwriting builtin tool entries
    ;; with strings instead of tool objects. :tools must be nil or a map of extra tools.
    (it "does not corrupt builtin tools when :tools is nil"
      (fn []
        (let [agent (create-agent {:model "m"})
              all   ((:all (:tool-registry agent)))]
          (doseq [[name t] all]
            ;; Every tool must be an object with inputSchema, not a string
            (-> (expect (= "object" (js/typeof t))) (.toBe true))
            (-> (expect (.-inputSchema t)) (.toBeTruthy))
            (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
              (-> (expect (.-type schema)) (.toBe "object")))))))

    ;; Regression: cli.cljs once passed ["read","write","edit","bash"] as :tools.
    ;; Squint merge treated the vector as pairs, overwriting read→"write", edit→"bash".
    (it "passing a vector as :tools does not corrupt builtin tools"
      (fn []
        (let [agent (create-agent {:model "m" :tools ["read" "write" "edit" "bash"]})
              all   ((:all (:tool-registry agent)))]
          ;; Every builtin must still be a tool object, not a string
          (doseq [name ["read" "write" "edit" "bash"]]
            (let [t (get all name)]
              (-> (expect (= "object" (js/typeof t))) (.toBe true))
              (-> (expect (.-inputSchema t)) (.toBeTruthy))
              (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
                (-> (expect (.-type schema)) (.toBe "object"))))))))

    (it "additional :tools are objects merged with builtins"
      (fn []
        (let [extra-tool #js {:description "extra" :inputSchema nil :execute (fn [_] "ok")}
              agent (create-agent {:model "m" :tools {"extra" extra-tool}})
              all   ((:all (:tool-registry agent)))]
          ;; Extra tool is present
          (-> (expect (.-description (get all "extra"))) (.toBe "extra"))
          ;; Builtins are NOT corrupted
          (-> (expect (.-inputSchema (get all "read"))) (.toBeTruthy))
          (-> (expect (.-inputSchema (get all "write"))) (.toBeTruthy)))))))
