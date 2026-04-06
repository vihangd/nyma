(ns tool-registry.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-registry :refer [create-registry]]))

(describe "agent.tool-registry"
  (fn []
    (it "all returns initial tools"
      (fn []
        (let [reg (create-registry {"read" {:desc "r"} "write" {:desc "w"}})]
          (-> (expect (count ((:all reg)))) (.toBe 2))
          (-> (expect (:desc (get ((:all reg)) "read"))) (.toBe "r")))))

    (it "all initial tools start active"
      (fn []
        (let [reg (create-registry {"a" 1 "b" 2})]
          (-> (expect (count ((:get-active reg)))) (.toBe 2)))))

    (it "register adds tool to both all and active"
      (fn []
        (let [reg (create-registry {"a" 1})]
          ((:register reg) "b" 2)
          (-> (expect (count ((:all reg)))) (.toBe 2))
          (-> (expect (get ((:get-active reg)) "b")) (.toBe 2)))))

    (it "unregister removes from both all and active"
      (fn []
        (let [reg (create-registry {"a" 1 "b" 2})]
          ((:unregister reg) "a")
          (-> (expect (count ((:all reg)))) (.toBe 1))
          (-> (expect (get ((:all reg)) "a")) (.toBeUndefined)))))

    (it "set-active limits get-active without affecting all"
      (fn []
        (let [reg (create-registry {"a" 1 "b" 2 "c" 3})]
          ((:set-active reg) ["a" "c"])
          (-> (expect (count ((:get-active reg)))) (.toBe 2))
          (-> (expect (get ((:get-active reg)) "b")) (.toBeUndefined))
          (-> (expect (count ((:all reg)))) (.toBe 3)))))

    (it "set-active with empty list yields empty get-active"
      (fn []
        (let [reg (create-registry {"a" 1})]
          ((:set-active reg) [])
          (-> (expect (count ((:get-active reg)))) (.toBe 0)))))

    (it "set-active with nonexistent name is harmless"
      (fn []
        (let [reg (create-registry {"a" 1 "b" 2})]
          ((:set-active reg) ["a" "ghost"])
          (-> (expect (count ((:get-active reg)))) (.toBe 1))
          (-> (expect (get ((:get-active reg)) "a")) (.toBe 1)))))

    (it "register after set-active adds new tool to active set"
      (fn []
        (let [reg (create-registry {"a" 1})]
          ((:set-active reg) ["a"])
          ((:register reg) "b" 2)
          (-> (expect (count ((:get-active reg)))) (.toBe 2)))))

    (it "registered tool names conform to Anthropic API pattern"
      (fn []
        (let [valid-re #"^[a-zA-Z0-9_\-]{1,128}$"
              reg (create-registry {"read" {:desc "r"} "bash__run" {:desc "b"} "my-tool" {:desc "m"}})]
          (doseq [name (keys ((:all reg)))]
            (-> (expect name) (.toMatch valid-re))))))))
