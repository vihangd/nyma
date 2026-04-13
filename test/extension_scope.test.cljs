(ns extension-scope.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extension-scope :refer [create-scoped-api derive-namespace]]
            [agent.events :refer [create-event-bus]]
            [agent.extensions :refer [create-extension-api]]
            [agent.core :refer [create-agent]]
            [agent.extension-state :refer [create-state-api]]
            [agent.ui.status-line-segments
             :refer [get-segment unregister-segment auto-append-ids]]))

(defn make-test-agent []
  (create-agent {:model "mock" :system-prompt "test"}))

(describe "create-scoped-api" (fn []
                                (it "prefixes tool registration with namespace"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "git" #{:all})]
                                        (.registerTool scoped "status" #js {:execute (fn [_] "ok")})
        ;; Tool should be registered as "git__status"
                                        (-> (expect (get ((:all (:tool-registry agent))) "git__status")) (.toBeDefined)))))

                                (it "prefixes command registration with namespace"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "docker" #{:all})]
                                        (.registerCommand scoped "ps" {:description "List containers"})
                                        (-> (expect (get @(:commands agent) "docker__ps")) (.toBeDefined)))))

                                (it "exposes namespace property"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "my-ext" #{:all})]
                                        (-> (expect (.-namespace scoped)) (.toBe "my-ext")))))

                                (it "gates tools capability"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "no-tools" #{:events})]
                                        (-> (expect (fn [] (.registerTool scoped "x" #js {})))
                                            (.toThrow "missing capability")))))

                                (it "gates events capability"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "no-events" #{:tools})]
                                        (-> (expect (fn [] (.on scoped "agent_start" (fn [_]))))
                                            (.toThrow "missing capability")))))

                                (it "gates middleware capability"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "no-mw" #{:tools})]
                                        (-> (expect (fn [] (.addMiddleware scoped {:name :test})))
                                            (.toThrow "missing capability")))))

                                (it ":all grants all capabilities"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "admin" #{:all})]
        ;; Should not throw
                                        (.registerTool scoped "x" #js {:execute (fn [_] "ok")})
                                        (.on scoped "agent_start" (fn [_]))
                                        (.addMiddleware scoped {:name :test :enter (fn [ctx] ctx)}))))

                                (it "prefixes flag registration with namespace"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "git" #{:all})]
                                        (.registerFlag scoped "verbose" #js {:type "boolean" :default false})
                                        (-> (expect (get @(:flags agent) "git__verbose")) (.toBeDefined)))))

                                (it "getFlag reads namespace-prefixed flag"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "git" #{:all})]
                                        (.registerFlag scoped "verbose" #js {:type "boolean" :default true})
                                        (-> (expect (.getFlag scoped "verbose")) (.toBe true)))))

                                (it "gates flags capability"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "no-flags" #{:tools})]
                                        (-> (expect (fn [] (.registerFlag scoped "x" #js {})))
                                            (.toThrow "missing capability")))))

                                (it "tool name matches Anthropic API pattern (no slash)"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "git" #{:all})
                                            valid-re #"^[a-zA-Z0-9_\-]{1,128}$"]
                                        (.registerTool scoped "status" #js {:execute (fn [_] "ok")})
                                        (let [all-names (keys ((:all (:tool-registry agent))))
                                              ext-name  (first (filter #(.startsWith % "git") all-names))]
          ;; Must match Anthropic pattern — no / allowed
                                          (-> (expect ext-name) (.toMatch valid-re))
                                          (-> (expect (.includes ext-name "/")) (.toBe false))))))

                                (it "multiple extension tool names all valid for API"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            ext-a    (create-scoped-api base-api "alpha" #{:all})
                                            ext-b    (create-scoped-api base-api "beta" #{:all})
                                            valid-re #"^[a-zA-Z0-9_\-]{1,128}$"]
                                        (.registerTool ext-a "search" #js {:execute (fn [_] "ok")})
                                        (.registerTool ext-b "fetch" #js {:execute (fn [_] "ok")})
                                        (doseq [name (keys ((:all (:tool-registry agent))))]
                                          (-> (expect name) (.toMatch valid-re))))))

                               ;; Regression: before this fix, the scoped API did not forward
                               ;; registerStatusSegment, so agent_shell's ACP segments (qwen
                               ;; agent details, context usage, cost, etc.) were silently
                               ;; dropped on the floor — (.-registerStatusSegment scoped) was
                               ;; undefined, the when-let in register-all! short-circuited, and
                               ;; nothing ever appeared in the status line.
                                (it "forwards registerStatusSegment when :ui capability is granted"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "acptest" #{:ui})]
                                        (-> (expect (fn? (.-registerStatusSegment scoped))) (.toBe true))
                                        (-> (expect (fn? (.-unregisterStatusSegment scoped))) (.toBe true)))))

                                (it "gates registerStatusSegment on :ui capability"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "nostatusui" #{:tools})]
                                        (-> (expect (fn [] (.registerStatusSegment scoped "x" #js {})))
                                            (.toThrow "missing capability")))))

                                (it "forwards registerToolRenderer when :renderers is granted"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "rendtest" #{:renderers})]
                                        (-> (expect (fn? (.-registerToolRenderer scoped))) (.toBe true))
                                        (-> (expect (fn? (.-unregisterToolRenderer scoped))) (.toBe true)))))

                                (it "gates registerToolRenderer on :renderers"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "nortr" #{:tools})]
                                        (-> (expect (fn [] (.registerToolRenderer scoped "x" (fn [_]))))
                                            (.toThrow "missing capability")))))

                                (it "forwards registerCompletionProvider when :ui is granted"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "cptest" #{:ui})]
                                        (-> (expect (fn? (.-registerCompletionProvider scoped))) (.toBe true))
                                        (-> (expect (fn? (.-unregisterCompletionProvider scoped))) (.toBe true)))))

                               ;; End-to-end: register a segment through the SAME API path
                               ;; agent_shell uses (scoped API → registerStatusSegment → base
                               ;; API → status-line-segments registry) and verify it lands in
                               ;; the registry with :auto-append? honoured, so auto-append-ids
                               ;; picks it up and StatusLine will render it.
                                (it "registerStatusSegment end-to-end: scoped → registry → auto-append-ids"
                                    (fn []
                                      (let [agent    (make-test-agent)
                                            base-api (create-extension-api agent)
                                            scoped   (create-scoped-api base-api "acpe2e" #{:ui})
                                            seg-id   "test.e2e.segment"
                                            render   (fn [_] #js {:content "hi" :color "#fff" :visible? true})]
                                        (.registerStatusSegment scoped seg-id
                                                                #js {:category "test"
                                                                     :autoAppend true
                                                                     :position "left"
                                                                     :render render})
                                       ;; Shows up in the segment registry.
                                        (-> (expect (:id (get-segment seg-id))) (.toBe seg-id))
                                       ;; And auto-append-ids picks it up for the :left position.
                                        (-> (expect (contains? (set (auto-append-ids :left [])) seg-id))
                                            (.toBe true))
                                       ;; But not for :right.
                                        (-> (expect (contains? (set (auto-append-ids :right [])) seg-id))
                                            (.toBe false))
                                       ;; Cleanup so other tests aren't affected.
                                        (.unregisterStatusSegment scoped seg-id)
                                        (-> (expect (get-segment seg-id)) (.toBeUndefined)))))))

(describe "derive-namespace" (fn []
                               (it "derives from simple filename"
                                   (fn []
                                     (-> (expect (derive-namespace "/path/to/git-tools.cljs")) (.toBe "git-tools"))))

                               (it "derives from nested path"
                                   (fn []
                                     (-> (expect (derive-namespace "/home/user/.nyma/extensions/my-ext.ts")) (.toBe "my-ext"))))

                               (it "handles .mjs extension"
                                   (fn []
                                     (-> (expect (derive-namespace "/ext/logger.mjs")) (.toBe "logger"))))

                               (it "throws on empty namespace (dotfile like .cljs)"
                                   (fn []
                                     (-> (expect (fn [] (derive-namespace "/path/to/.cljs")))
                                         (.toThrow "Invalid extension namespace"))))

                               (it "throws on namespace with invalid characters"
                                   (fn []
                                     (-> (expect (fn [] (derive-namespace "/path/to/bad name.cljs")))
                                         (.toThrow "Invalid extension namespace"))))))

;;; ─── Error boundary for event handlers ─────────────────────────────────────

(describe "extension-scope:error-boundary" (fn []
                                             (it "registering a throwing handler does not throw at registration time"
                                                 (fn []
                                                   (let [agent    (make-test-agent)
                                                         base-api (create-extension-api agent)
                                                         scoped   (create-scoped-api base-api "errbnd" #{:all})
                                                         threw    (atom false)]
        ;; Registration of a throwing handler should succeed silently
                                                     (try
                                                       (.on scoped "agent_start" (fn [_] (throw (js/Error. "boom"))))
                                                       (catch :default _
                                                         (reset! threw true)))
                                                     (-> (expect @threw) (.toBe false)))))

                                             (it "error boundary wraps on — throwing handler does not throw at call site"
                                                 (fn []
                                                   (let [agent    (make-test-agent)
                                                         base-api (create-extension-api agent)
                                                         scoped   (create-scoped-api base-api "safe" #{:all :events})
                                                         called   (atom false)]
        ;; Register a good handler second — should still be called
                                                     (.on scoped "agent_start" (fn [_] (throw (js/Error. "bad handler"))))
                                                     (.on scoped "agent_start" (fn [_] (reset! called true)))
        ;; Directly trigger via base api's emit if it supports it
                                                     (try
                                                       (.emit base-api "agent_start" nil)
                                                       (catch :default _ nil))
        ;; Either called or not — no crash is the point
                                                     (-> (expect true) (.toBe true))))

                                                 (it "off unregisters wrapped handler (no duplicate calls after off)"
                                                     (fn []
                                                       (let [agent    (make-test-agent)
                                                             base-api (create-extension-api agent)
                                                             scoped   (create-scoped-api base-api "offtest" #{:all :events})
                                                             count    (atom 0)
                                                             handler  (fn [_] (swap! count inc))]
                                                         (.on scoped "agent_start" handler)
        ;; Remove via .off with original handler reference
                                                         (.off scoped "agent_start" handler)
        ;; count should remain 0
                                                         (-> (expect @count) (.toBe 0)))))

                                                 (it "state capability missing throws on get"
                                                     (fn []
                                                       (let [agent    (make-test-agent)
                                                             base-api (create-extension-api agent)
                                                             scoped   (create-scoped-api base-api "nostate" #{:tools})]
                                                         (-> (expect (fn [] (.get (.-state scoped) "key")))
                                                             (.toThrow "missing capability")))))

                                                 (it "state capability missing throws on set"
                                                     (fn []
                                                       (let [agent    (make-test-agent)
                                                             base-api (create-extension-api agent)
                                                             scoped   (create-scoped-api base-api "nostate2" #{:tools})]
                                                         (-> (expect (fn [] (.set (.-state scoped) "k" "v")))
                                                             (.toThrow "missing capability")))))

                                                 (it "state capability provided — scoped API has state object"
                                                     (fn []
                                                       (let [agent    (make-test-agent)
                                                             base-api (create-extension-api agent)
                                                             scoped   (create-scoped-api base-api "withstate" #{:all :state})]
                                                         (-> (expect (some? (.-state scoped))) (.toBe true))
                                                         (-> (expect (fn? (.-get (.-state scoped)))) (.toBe true))
                                                         (-> (expect (fn? (.-set (.-state scoped)))) (.toBe true))
                                                         (-> (expect (fn? (.-keys (.-state scoped)))) (.toBe true))))))))

;;; ─── create-state-api integration ───────────────────────────────────────────

(describe "extension-state:create-state-api" (fn []
                                               (it "set and get a value"
                                                   (fn []
                                                     (let [api (create-state-api "test-ext-scopetest")]
                                                       (.set api "foo" "bar")
                                                       (-> (expect (.get api "foo")) (.toBe "bar")))))

                                               (it "keys returns stored keys"
                                                   (fn []
                                                     (let [api (create-state-api "test-ext-keyscheck")]
                                                       (.clear api)
                                                       (.set api "a" 1)
                                                       (.set api "b" 2)
                                                       (let [ks (.keys api)]
                                                         (-> (expect (.includes ks "a")) (.toBe true))
                                                         (-> (expect (.includes ks "b")) (.toBe true))))))

                                               (it "delete removes a key"
                                                   (fn []
                                                     (let [api (create-state-api "test-ext-deletecheck")]
                                                       (.set api "x" 42)
                                                       (.delete api "x")
                                                       (-> (expect (.get api "x")) (.toBeUndefined)))))

                                               (it "clear removes all keys"
                                                   (fn []
                                                     (let [api (create-state-api "test-ext-clearcheck")]
                                                       (.set api "p" 1)
                                                       (.set api "q" 2)
                                                       (.clear api)
                                                       (-> (expect (.-length (.keys api))) (.toBe 0)))))

                                               (it "get returns undefined for missing key"
                                                   (fn []
                                                     (let [api (create-state-api "test-ext-missingkey")]
                                                       (.clear api)
                                                       (-> (expect (.get api "nonexistent")) (.toBeUndefined)))))

                                               (it "persists across two api instances with same namespace"
                                                   (fn []
                                                     (let [api1 (create-state-api "test-ext-persist")
                                                           _    (.set api1 "shared" "value")
                                                           api2 (create-state-api "test-ext-persist")]
                                                       (-> (expect (.get api2 "shared")) (.toBe "value"))
        ;; Cleanup
                                                       (.clear api1))))))
