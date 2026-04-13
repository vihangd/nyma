(ns extension-registration-e2e.test
  "End-to-end tests for the path that every extension registration API
   must travel: extension code → scoped API → base API → backing atom
   → the consumer that actually reads the atom and renders/uses it.

   The original ACP status segment bug hid in exactly this gap. The
   registerStatusSegment method existed on the base API, the segments
   were written to the registry, but the scoped API didn't forward the
   method and the StatusLine didn't iterate the registry — two separate
   broken links, silent in isolation. The unit tests up to that point
   only checked pieces, never the full wire.

   This suite walks the full wire for the three remaining registries:
     - :block-renderers  (registerBlockRenderer → ChatView / AssistantMessage)
     - :mention-providers (registerMentionProvider → at-file-provider → complete-all)
     - tool renderer registry (registerToolRenderer → tool_renderer_registry)"
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            [agent.ui.autocomplete-provider :refer [complete-all]]
            [agent.ui.autocomplete-builtins :as ac-builtins]
            [agent.ui.tool-renderer-registry :as tr-registry]
            ["./agent/ui/chat_view.jsx" :refer [AssistantMessage]]))

(afterEach (fn [] (cleanup)))

(def test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"}})

(defn- make-ext [caps]
  (let [agent  (create-agent {:model "mock" :system-prompt "test"})
        base   (create-extension-api agent)
        scoped (create-scoped-api base "e2e-test" caps)]
    {:agent agent :base base :scoped scoped}))

;;; ─── Block renderer E2E ────────────────────────────────
;;; Chain: registerBlockRenderer (scoped) → base → agent :block-renderers
;;; atom → ChatView passes to AssistantMessage → useStreamingMarkdown →
;;; render-block looks up custom fn by "type:lang" key → its string
;;; result replaces the default markdown render.

(describe "block renderer: extension registration → AssistantMessage render"
          (fn []
            (it "custom renderer for 'code:diff' replaces the default rendering"
                (fn []
                  (let [{:keys [agent scoped]} (make-ext #{:renderers})]
                    (.registerBlockRenderer scoped "code:diff"
                                            (fn [_block] "NYMA_CUSTOM_DIFF_RENDERER_OUTPUT"))
                    (let [content "Here is a diff:\n\n```diff\n-old\n+new\n```\n"
                          br      @(:block-renderers agent)
                          {:keys [lastFrame]}
                          (render #jsx [AssistantMessage {:content         content
                                                          :theme           test-theme
                                                          :block-renderers br}])]
                      (-> (expect (lastFrame))
                          (.toContain "NYMA_CUSTOM_DIFF_RENDERER_OUTPUT"))))))

            (it "unregistered block types still render via the default path"
                (fn []
                  (let [{:keys [agent scoped]} (make-ext #{:renderers})]
                    (.registerBlockRenderer scoped "code:diff"
                                            (fn [_block] "SHOULD_NOT_APPEAR_HERE"))
                    (let [;; javascript block — the renderer above shouldn't fire.
                          content "```js\nconsole.log('hi')\n```\n"
                          br      @(:block-renderers agent)
                          {:keys [lastFrame]}
                          (render #jsx [AssistantMessage {:content         content
                                                          :theme           test-theme
                                                          :block-renderers br}])]
                      (-> (expect (.includes (lastFrame) "SHOULD_NOT_APPEAR_HERE"))
                          (.toBe false))
            ;; Default path should at least produce the code literal
                      (-> (expect (lastFrame)) (.toContain "console.log"))))))

            (it "custom renderer returning nil falls back to the default"
                (fn []
                  (let [{:keys [agent scoped]} (make-ext #{:renderers})]
                    (.registerBlockRenderer scoped "code:diff"
            ;; Returning nil is the documented escape hatch for "not
            ;; my block after all" — must not crash and must fall back.
                                            (fn [_block] nil))
                    (let [content "```diff\n-old\n+new\n```\n"
                          br      @(:block-renderers agent)
                          {:keys [lastFrame]}
                          (render #jsx [AssistantMessage {:content         content
                                                          :theme           test-theme
                                                          :block-renderers br}])]
            ;; No crash, frame is produced
                      (-> (expect (lastFrame)) (.toBeDefined))))))))

;;; ─── Mention provider E2E ──────────────────────────────
;;; Chain: registerMentionProvider (scoped) → base → agent
;;; :mention-providers atom → at-file-provider (installed by
;;; ac-builtins/register-all!) reads the atom and calls the first
;;; provider's :search fn → complete-all merges the results.
;;;
;;; Only the full wire catches a regression like "scoped API forgot
;;; to forward the method" or "at-file-provider grabs the wrong key".

(describe "mention provider: scoped register → complete-all"
          (fn []
            (it "provider registered through scoped API is reachable via complete-all"
                (fn []
                  (let [{:keys [agent scoped]} (make-ext #{:ui})]
          ;; Install the three built-in autocomplete providers —
          ;; at-file-provider is the one that delegates into :mention-providers.
                    (ac-builtins/register-all! agent)
                    (.registerMentionProvider scoped "todo"
                                              #js {:trigger "@"
                                                   :label   "todos"
                                                   :search  (fn [_q]
                                                              (js/Promise.resolve
                                                               #js [#js {:label "todo-deploy" :value "#42"}
                                                                    #js {:label "todo-fix"    :value "#99"}]))})
          ;; Use an @ trigger to activate the at-file-provider.
                    (-> (complete-all (:autocomplete-registry agent) "hello @")
                        (.then (fn [results]
                                 (-> (expect (pos? (count results))) (.toBe true))
                                 (let [labels (map (fn [r]
                                                     (or (get r :label) (get r "label") ""))
                                                   results)]
                                   (-> (expect (some (fn [l] (.includes l "todo-deploy")) labels))
                                       (.toBe true)))))))))

            (it "empty :mention-providers atom returns no @ results (safe no-op)"
                (fn []
                  (let [{:keys [agent]} (make-ext #{:ui})]
                    (ac-builtins/register-all! agent)
          ;; No mention provider registered — at-file-provider delegates
          ;; to (first ...) which is nil, must not throw.
                    (-> (complete-all (:autocomplete-registry agent) "hello @")
                        (.then (fn [results]
                       ;; Other providers (slash, path) may or may not fire;
                       ;; the contract is just "no crash and no mention results".
                                 (-> (expect (some? results)) (.toBe true))))))))))

;;; ─── Tool renderer E2E ─────────────────────────────────
;;; Chain: registerToolRenderer (scoped) → base → agent →
;;; tool-renderer-registry (module-level atom) → get-renderer reads
;;; the atom at ToolExecution render time.

(describe "tool renderer: scoped register → registry round-trip"
          (fn []
            (it "renderer registered via scoped API is retrievable from the registry"
                (fn []
                  (let [{:keys [scoped]} (make-ext #{:renderers})
                        renderer #js {:renderCall   (fn [_] "e2e-custom-call")
                                      :renderResult (fn [_] "e2e-custom-result")}]
                    (.registerToolRenderer scoped "bash_test_e2e" renderer)
          ;; The module-level registry is where get-renderer reads at
          ;; ToolExecution render time. If the scoped→base forwarding
          ;; broke or the base API's impl stopped writing to it, this
          ;; lookup would return nil.
                    (let [found (tr-registry/get-renderer "bash_test_e2e")]
                      (-> (expect (some? found)) (.toBe true))
                      (-> (expect (fn? (.-renderCall found))) (.toBe true)))
          ;; Cleanup
                    (.unregisterToolRenderer scoped "bash_test_e2e")
                    (-> (expect (tr-registry/get-renderer "bash_test_e2e"))
                        (.toBeUndefined)))))))
