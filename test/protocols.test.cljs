(ns protocols.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.protocols :refer [ISessionStore IToolProvider IContextBuilder
                                     session-load session-append session-build-context
                                     session-branch session-get-tree session-leaf-id
                                     provide-tools register-tool unregister-tool
                                     set-active-tools get-active-tools
                                     build-ctx
                                     ISessionStore_session_load
                                     IToolProvider_provide_tools
                                     IContextBuilder_build_ctx]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.tool-registry :refer [create-registry]]
            [agent.context :refer [default-context-builder]]))

(describe "ISessionStore protocol" (fn []
  (it "session-manager conforms to ISessionStore"
    (fn []
      (let [mgr (create-session-manager nil)]
        ;; Protocol dispatch should work
        (-> (expect (fn? (aget mgr ISessionStore_session_load))) (.toBe true)))))

  (it "session-load via protocol dispatch"
    (fn []
      (let [mgr (create-session-manager nil)]
        ;; Should not throw
        (session-load mgr)
        (-> (expect (session-get-tree mgr)) (.toEqual #js [])))))

  (it "session-append via protocol dispatch"
    (fn []
      (let [mgr (create-session-manager nil)
            id  (session-append mgr {:role "user" :content "hello"})]
        (-> (expect (string? id)) (.toBe true))
        (-> (expect (count (session-get-tree mgr))) (.toBe 1)))))

  (it "session-branch via protocol dispatch"
    (fn []
      (let [mgr (create-session-manager nil)
            id1 (session-append mgr {:role "user" :content "msg1"})
            _id2 (session-append mgr {:role "assistant" :content "reply"})]
        (session-branch mgr id1)
        (-> (expect (session-leaf-id mgr)) (.toBe id1)))))

  (it "backwards compat - map-style access still works"
    (fn []
      (let [mgr (create-session-manager nil)]
        ((:load mgr))
        ((:append mgr) {:role "user" :content "test"})
        (-> (expect (count ((:get-tree mgr)))) (.toBe 1)))))))

(describe "IToolProvider protocol" (fn []
  (it "tool-registry conforms to IToolProvider"
    (fn []
      (let [reg (create-registry {"read" :mock-tool})]
        (-> (expect (fn? (aget reg IToolProvider_provide_tools))) (.toBe true)))))

  (it "provide-tools via protocol dispatch"
    (fn []
      (let [reg (create-registry {"read" :mock-tool "write" :mock-tool})]
        (-> (expect (count (provide-tools reg))) (.toBe 2)))))

  (it "register-tool via protocol dispatch"
    (fn []
      (let [reg (create-registry {})]
        (register-tool reg "bash" :bash-tool)
        (-> (expect (count (provide-tools reg))) (.toBe 1)))))

  (it "unregister-tool via protocol dispatch"
    (fn []
      (let [reg (create-registry {"read" :mock})]
        (unregister-tool reg "read")
        (-> (expect (count (provide-tools reg))) (.toBe 0)))))

  (it "set-active-tools and get-active-tools via protocol"
    (fn []
      (let [reg (create-registry {"read" :r "write" :w "bash" :b})]
        (set-active-tools reg #{"read" "write"})
        (-> (expect (count (get-active-tools reg))) (.toBe 2)))))

  (it "backwards compat - map-style access still works"
    (fn []
      (let [reg (create-registry {"read" :mock})]
        ((:register reg) "write" :mock2)
        (-> (expect (count ((:all reg)))) (.toBe 2)))))))

(describe "IContextBuilder protocol" (fn []
  (it "default-context-builder conforms to IContextBuilder"
    (fn []
      (-> (expect (fn? (aget default-context-builder IContextBuilder_build_ctx))) (.toBe true))))

  (it "build-ctx via protocol dispatch"
    (fn []
      (let [agent {:state (atom {:messages [{:role "user" :content "hi"}
                                             {:role "assistant" :content "hello"}
                                             {:role "system" :content "ignored"}]})
                   :tool-registry (create-registry {})}
            ctx (build-ctx default-context-builder agent nil)]
        (-> (expect (count ctx)) (.toBe 2)))))))
