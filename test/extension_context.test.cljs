(ns extension-context.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extension-context :refer [create-extension-context]]
            [agent.core :refer [create-agent]]))

(describe "create-extension-context" (fn []
  (it "provides cwd"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            ctx   (create-extension-context agent)]
        (-> (expect (.-cwd ctx)) (.toBe (js/process.cwd))))))

  (it "hasUI is false when no extension-api wired"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            ctx   (create-extension-context agent)]
        (-> (expect (.-hasUI ctx)) (.toBe false)))))

  (it "hasUI is true when ui.available is set"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            api   #js {:ui #js {:available true}}]
        (set! (.-extension-api agent) api)
        (let [ctx (create-extension-context agent)]
          (-> (expect (.-hasUI ctx)) (.toBe true))))))

  (it "ui is accessible from context"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            ui    #js {:available true :showOverlay (fn [_] nil)}
            api   #js {:ui ui}]
        (set! (.-extension-api agent) api)
        (let [ctx (create-extension-context agent)]
          (-> (expect (.-available (.-ui ctx))) (.toBe true))
          (-> (expect (fn? (.-showOverlay (.-ui ctx)))) (.toBe true))))))

  (it "provides getSessionDirectory"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            ctx   (create-extension-context agent)]
        (-> (expect (fn? (.-getSessionDirectory ctx))) (.toBe true))
        (-> (expect (.getSessionDirectory ctx)) (.toContain ".nyma/sessions")))))

  (it "provides getSystemPrompt"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test-prompt"})
            ctx   (create-extension-context agent)]
        (-> (expect (.getSystemPrompt ctx)) (.toBe "test-prompt")))))

  (it "provides getContextUsage with defaults"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            ctx   (create-extension-context agent)
            usage (.getContextUsage ctx)]
        (-> (expect (.-inputTokens usage)) (.toBe 0))
        (-> (expect (.-outputTokens usage)) (.toBe 0))
        (-> (expect (.-cost usage)) (.toBe 0))
        (-> (expect (.-turns usage)) (.toBe 0)))))

  (it "isIdle returns true when no executions"
    (fn []
      (let [agent (create-agent {:model "mock" :system-prompt "test"})
            ctx   (create-extension-context agent)]
        (-> (expect (.isIdle ctx)) (.toBe true)))))))
