(ns tool-ctx-fixture.test
  "Tests for the shared tool-ctx-fixture module. Guards against drift
   between `create-extension-context`'s real shape in
   src/agent/extension_context.cljs and the fixture's default — if
   either adds a field, a test here should fire so the fixture
   catches up."
  (:require ["bun:test" :refer [describe it expect]]
            [tool-ctx-fixture :refer [mk-tool-ctx mk-api-mock]]))

;;; ─── mk-tool-ctx ─────────────────────────────────────

(describe "mk-tool-ctx: defaults"
          (fn []
            (it "provides middleware-enriched fields (toolCallId, abortSignal, onUpdate)"
                (fn []
                  (let [ctx (mk-tool-ctx)]
                    (-> (expect (.-toolCallId ctx)) (.toBe "test-tool-call-id"))
                    (-> (expect (some? (.-abortSignal ctx))) (.toBe true))
                    (-> (expect (fn? (.-onUpdate ctx))) (.toBe true)))))

            (it "provides a live ui with :available true and a notify no-op"
                (fn []
                  (let [ctx (mk-tool-ctx)]
                    (-> (expect (.-available (.-ui ctx))) (.toBe true))
                    (-> (expect (fn? (.-notify (.-ui ctx)))) (.toBe true))
                    (-> (expect (.-hasUI ctx)) (.toBe true)))))

            (it "provides a working directory defaulted to process.cwd()"
                (fn []
                  (-> (expect (.-cwd (mk-tool-ctx))) (.toBe (js/process.cwd)))))

            (it "every required field of create-extension-context is present"
                (fn []
                  ;; Contract: any test calling .-foo on ctx for any
                  ;; field listed in extension_context.cljs must succeed.
                  ;; We check via JS .includes on Object.keys rather
                  ;; than js->clj (which isn't exposed by Squint).
                  (let [ctx (mk-tool-ctx)
                        keys (js/Object.keys ctx)
                        fields ["ui" "hasUI" "cwd" "signal" "sessionManager"
                                "modelRegistry" "model" "isIdle" "abort"
                                "hasPendingMessages" "shutdown" "getContextUsage"
                                "compact" "getSystemPrompt" "getSessionDirectory"
                                "getTokenBudget" "getModelInfo" "estimateTokens"
                                "getContextProviders"]]
                    (doseq [f fields]
                      (-> (expect (.includes keys f)) (.toBe true))))))))

;;; ─── mk-tool-ctx overrides ──────────────────────────

(describe "mk-tool-ctx: overrides"
          (fn []
            (it "accepts a custom cwd"
                (fn []
                  (-> (expect (.-cwd (mk-tool-ctx {:cwd "/custom/path"})))
                      (.toBe "/custom/path"))))

            (it "accepts a pre-built abort-controller so tests can trigger cancellation"
                (fn []
                  (let [ctrl (js/AbortController.)
                        ctx  (mk-tool-ctx {:abort-controller ctrl})]
                    (-> (expect (.-aborted (.-abortSignal ctx))) (.toBe false))
                    (.abort ctrl)
                    (-> (expect (.-aborted (.-abortSignal ctx))) (.toBe true)))))

            (it "onUpdate captures calls into the :updates atom"
                (fn []
                  (let [captured (atom [])
                        ctx      (mk-tool-ctx {:updates captured})]
                    ((.-onUpdate ctx) "progress-1")
                    ((.-onUpdate ctx) #js {:type "chunk"})
                    (-> (expect (count @captured)) (.toBe 2))
                    (-> (expect (first @captured)) (.toBe "progress-1")))))

            (it "accepts an explicit tool-call-id"
                (fn []
                  (-> (expect (.-toolCallId (mk-tool-ctx {:tool-call-id "abc123"})))
                      (.toBe "abc123"))))

            (it "extras map is merged as extra JS fields"
                (fn []
                  (let [ctx (mk-tool-ctx {:extras {:customField "hello"}})]
                    (-> (expect (aget ctx "customField")) (.toBe "hello")))))))

;;; ─── mk-api-mock ─────────────────────────────────────

(describe "mk-api-mock: defaults"
          (fn []
            (it "exposes a :ui with notify that captures into :_notifications"
                (fn []
                  (let [api (mk-api-mock)]
                    ((.-notify (.-ui api)) "hello" "info")
                    (let [notes @(.-_notifications api)]
                      (-> (expect (count notes)) (.toBe 1))
                      (-> (expect (:message (first notes))) (.toBe "hello"))))))

            (it "registerCommand populates the :_commands atom"
                (fn []
                  (let [api (mk-api-mock)]
                    ((.-registerCommand api) "test-cmd" #js {:description "x"})
                    (-> (expect (contains? @(.-_commands api) "test-cmd")) (.toBe true)))))

            (it "unregisterCommand removes entries"
                (fn []
                  (let [api (mk-api-mock)]
                    ((.-registerCommand api) "t" #js {})
                    ((.-unregisterCommand api) "t")
                    (-> (expect (contains? @(.-_commands api) "t")) (.toBe false)))))

            (it "on/off/emit round-trip an event through the handler list"
                (fn []
                  (let [api      (mk-api-mock)
                        received (atom nil)
                        handler  (fn [data] (reset! received data))]
                    ((.-on api) "my-event" handler)
                    ((.-emit api) "my-event" #js {:value 42})
                    (-> (expect (some? @received)) (.toBe true))
                    (-> (expect (.-value @received)) (.toBe 42))
                    ((.-off api) "my-event" handler)
                    (reset! received nil)
                    ((.-emit api) "my-event" #js {:value 99})
                    (-> (expect (nil? @received)) (.toBe true)))))))

(describe "mk-api-mock: overrides"
          (fn []
            (it "ui-overrides extend the default :ui object"
                (fn []
                  (let [api (mk-api-mock {:ui-overrides {:customUiMethod (fn [] "x")}})]
                    (-> (expect (fn? (.-customUiMethod (.-ui api)))) (.toBe true)))))))
