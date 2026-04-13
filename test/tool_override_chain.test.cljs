(ns tool-override-chain.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-registry :refer [create-registry]]))

(describe "tool-override-chain" (fn []
  (it "overriding tool gets __original reference"
    (fn []
      (let [original #js {:execute (fn [_] "original-result")}
            override #js {:execute (fn [_] "override-result")}
            reg (create-registry {"my-tool" original})]
        ((:register reg) "my-tool" override)
        (let [tool (get ((:all reg)) "my-tool")]
          (-> (expect (.-__original tool)) (.toBeDefined))
          (-> (expect ((.-execute (.-__original tool)) nil)) (.toBe "original-result"))))))

  (it "__original.execute calls original implementation"
    (fn []
      (let [original #js {:execute (fn [args] (str "read:" (.-path args)))}
            wrapper  #js {:execute (fn [args] "placeholder")}
            reg (create-registry {"read" original})]
        ;; Register wrapper — __original will be set by register
        ((:register reg) "read" wrapper)
        ;; Now wrapper.__original is set, test chaining
        (let [tool (get ((:all reg)) "read")
              orig-result ((.-execute (.-__original tool)) #js {:path "foo.txt"})]
          (-> (expect orig-result) (.toBe "read:foo.txt"))))))

  (it "unregistering restores original without __original"
    (fn []
      (let [original #js {:execute (fn [_] "original")}
            override #js {:execute (fn [_] "override")}
            reg (create-registry {"tool" original})]
        ((:register reg) "tool" override)
        ((:unregister reg) "tool")
        (let [tool (get ((:all reg)) "tool")]
          (-> (expect ((.-execute tool) nil)) (.toBe "original"))
          (-> (expect (.-__original tool)) (.toBeUndefined))))))

  (it "new tool registration has no __original"
    (fn []
      (let [tool #js {:execute (fn [_] "new")}
            reg  (create-registry {})]
        ((:register reg) "new-tool" tool)
        (let [t (get ((:all reg)) "new-tool")]
          (-> (expect (.-__original t)) (.toBeUndefined))))))))
