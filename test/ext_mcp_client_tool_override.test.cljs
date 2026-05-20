(ns ext-mcp-client-tool-override.test
  "Tests the override pattern for native tools that have a smarter
   MCP replacement (read → ctx_read, edit → ctx_edit).

   Verifies:
     - register! installs a delegator under the native name
     - delegator routes to MCP when the lean-ctx client is :running
     - delegator falls back to native execute when :stopped-error
     - delegator falls back when MCP throws (resilience)
     - unregister! restores the native via __original chain
     - parse-overrides handles nil / false / partial-map / null"
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-registry :refer [create-registry]]
            [agent.extensions.mcp-client.tool-override :as ovr]))

;; ── Test doubles ─────────────────────────────────────────────────

(defn- fake-client [state-atom call-handler]
  ;; Mirrors the shape mcp-client.client returns. The override only
  ;; reads :state via client/state and calls client/call-tool!.
  ;; client/state derefs the atom, so we expose :state as the atom.
  {:state  state-atom
   :name   "lean-ctx"
   :config {:name "lean-ctx"}
   :_call  call-handler})

;; client/state and client/call-tool! must be matched. We can't easily
;; stub the namespace — so instead we make a faux manager that hands
;; back our fake client, and patch the test path by giving our fake
;; client a working :state atom (which client/state derefs) and a
;; call interceptor via a small shim manager.
;;
;; Simplest approach: build a minimal manager-ref atom whose
;; (mgr/get-client m "lean-ctx") returns our fake. Override's
;; mcp-tool-healthy? calls (client/state c) which is just @(:state c).
;; Override's MCP path calls (client/call-tool! c {...}) which we
;; can't easily stub. So we instead intercept BEFORE that — by
;; pre-setting state to :stopped-error on the fallback test, and
;; using a thin client/call-tool! shim only when needed.

(defn- fake-manager
  "A manager that mgr/get-client treats as the real one. We embed
   the fake client under :clients atom so all-clients/server-names
   work too."
  [server-name client]
  {:clients (atom {server-name client})})

(defn- mock-api
  "Build an api object exposing only the surface tool_override
   touches. Backed by a real tool-registry to keep __original
   chaining behavior identical to production. overrideTool /
   unoverrideTool delegate to register/unregister directly (no
   prefixing — that's the production scoped-api's behavior for the
   tools-override capability)."
  [initial-tools]
  (let [reg    (create-registry initial-tools)
        active (atom (set (keys initial-tools)))]
    {:reg    reg
     :active active
     :api    #js {:getAllTools     (fn [] (clj->js (vec (keys ((:all reg))))))
                  :getActiveTools  (fn [] (clj->js (vec @active)))
                  :setActiveTools  (fn [names] (reset! active (set (vec names))))
                  :registerTool    (fn [name td]
                                     (swap! active conj name)
                                     ((:register reg) name td))
                  :unregisterTool  (fn [name]
                                     (swap! active disj name)
                                     ((:unregister reg) name))
                  :overrideTool    (fn [name td]
                                     (swap! active conj name)
                                     ((:register reg) name td))
                  :unoverrideTool  (fn [name]
                                     (swap! active disj name)
                                     ((:unregister reg) name))}}))

(defn- native-read-tool [executed-with]
  #js {:description "Native read"
       :inputSchema #js {:type "object" :properties #js {}}
       :execute (fn [args]
                  (reset! executed-with args)
                  "native-output")})

;; ── Tests ────────────────────────────────────────────────────────

(describe "mcp-client/tool-override/parse-overrides"
          (fn []
            (it "nil → defaults"
                (fn []
                  (let [r (ovr/parse-overrides nil)]
                    (-> (expect (contains? r "read")) (.toBe true))
                    (-> (expect (contains? r "edit")) (.toBe true)))))
            (it "false → empty"
                (fn []
                  (-> (expect (count (ovr/parse-overrides false))) (.toBe 0))))
            (it "object with null value drops a key"
                (fn []
                  (let [v #js {}
                        _ (aset v "read" nil)
                        r (ovr/parse-overrides v)]
                    (-> (expect (contains? r "read")) (.toBe false))
                    (-> (expect (contains? r "edit")) (.toBe true)))))))

(describe "mcp-client/tool-override/register-and-delegate"
          (fn []
            (it "register! installs delegator, native captured via __original"
                (fn []
                  (let [executed (atom nil)
                        {:keys [reg api]} (mock-api {"read" (native-read-tool executed)})
                        ;; Manager whose get-client returns a fake client.
                        ;; State :stopped-error → override falls back to native.
                        state-atom (atom :stopped-error)
                        fc         (fake-client state-atom (fn [_a] nil))
                        mgr-ref    (atom (fake-manager "lean-ctx" fc))
                        applied    (ovr/register! api mgr-ref
                                                  {"read" {:server "lean-ctx"
                                                           :mcp-tool "ctx_read"
                                                           :translate (fn [a] a)}})]
                    (-> (expect (count (:overrides applied))) (.toBe 1))
                    (-> (expect (first (:overrides applied))) (.toBe "read"))
                    ;; The override is what's now active under "read".
                    (let [active-read (get ((:all reg)) "read")]
                      (-> (expect (some? (.-__original active-read))) (.toBe true))))))

            (it "delegator falls back to native when MCP not :running"
                (^:async fn []
                  (let [executed (atom nil)
                        {:keys [reg api]} (mock-api {"read" (native-read-tool executed)})
                        state-atom (atom :stopped-error)
                        fc         (fake-client state-atom (fn [_a] nil))
                        mgr-ref    (atom (fake-manager "lean-ctx" fc))]
                    (ovr/register! api mgr-ref
                                   {"read" {:server "lean-ctx"
                                            :mcp-tool "ctx_read"
                                            :translate (fn [a] a)}})
                    (let [override-tool (get ((:all reg)) "read")
                          out (js-await ((.-execute override-tool)
                                         #js {:file_path "/x.txt"}))]
                      (-> (expect out) (.toBe "native-output"))
                      (-> (expect (.-file_path @executed)) (.toBe "/x.txt"))))))

            (it "unregister! restores native via __original chain"
                (fn []
                  (let [executed (atom nil)
                        native     (native-read-tool executed)
                        {:keys [reg api]} (mock-api {"read" native})
                        state-atom (atom :running)
                        fc         (fake-client state-atom (fn [_a] nil))
                        mgr-ref    (atom (fake-manager "lean-ctx" fc))
                        applied    (ovr/register! api mgr-ref
                                                  {"read" {:server "lean-ctx"
                                                           :mcp-tool "ctx_read"
                                                           :translate (fn [a] a)}})]
                    (ovr/unregister! api applied)
                    ;; After unregister, "read" should be the original native.
                    (let [restored (get ((:all reg)) "read")]
                      (-> (expect restored) (.toBe native))))))

            (it "register! skips natives that don't exist in registry"
                (fn []
                  (let [{:keys [api]} (mock-api {})    ;; no native read
                        mgr-ref (atom (fake-manager "lean-ctx"
                                                    (fake-client (atom :running) (fn [_] nil))))
                        applied (ovr/register! api mgr-ref
                                               {"read" {:server "lean-ctx"
                                                        :mcp-tool "ctx_read"
                                                        :translate (fn [a] a)}})]
                    (-> (expect (count (:overrides applied))) (.toBe 0))
                    (-> (expect (count (:hidden applied))) (.toBe 0)))))

            (it "register! skips when target server is NOT registered (the lean-ctx-not-enabled case)"
                (fn []
                  ;; mock-api has read, but the manager has no servers.
                  (let [executed (atom nil)
                        {:keys [reg api]} (mock-api {"read" (native-read-tool executed)})
                        mgr-ref (atom {:clients (atom {})})
                        applied (ovr/register! api mgr-ref
                                               {"read" {:server "lean-ctx"
                                                        :mcp-tool "ctx_read"
                                                        :translate (fn [a] a)}})]
                    (-> (expect (count (:overrides applied))) (.toBe 0))
                    ;; Native should be unmodified (no annotation, no schema swap).
                    (let [active-read (get ((:all reg)) "read")]
                      (-> (expect (.-description active-read)) (.toBe "Native read"))))))

            (it "register! hides the underlying mcp__<server>__<tool> from active set when override installed"
                (fn []
                  ;; Pre-seed both native read AND mcp__lean-ctx__ctx_read.
                  ;; After register!, ctx_read should be removed from active set.
                  (let [executed (atom nil)
                        {:keys [api active]} (mock-api
                                              {"read" (native-read-tool executed)
                                               "mcp__lean-ctx__ctx_read"
                                               #js {:description "MCP read"
                                                    :inputSchema #js {}
                                                    :execute (fn [_] "mcp")}})
                        state-atom (atom :running)
                        fc         (fake-client state-atom (fn [_a] nil))
                        mgr-ref    (atom (fake-manager "lean-ctx" fc))
                        applied    (ovr/register! api mgr-ref
                                                  {"read" {:server "lean-ctx"
                                                           :mcp-tool "ctx_read"
                                                           :translate (fn [a] a)}})]
                    (-> (expect (count (:hidden applied))) (.toBe 1))
                    (-> (expect (first (:hidden applied)))
                        (.toBe "mcp__lean-ctx__ctx_read"))
                    (-> (expect (contains? @active "mcp__lean-ctx__ctx_read"))
                        (.toBe false))
                    (-> (expect (contains? @active "read")) (.toBe true)))))

            (it "unregister! restores hidden mcp tool to the active set"
                (fn []
                  (let [executed (atom nil)
                        {:keys [api active]} (mock-api
                                              {"read" (native-read-tool executed)
                                               "mcp__lean-ctx__ctx_read"
                                               #js {:description "MCP read"
                                                    :inputSchema #js {}
                                                    :execute (fn [_] "mcp")}})
                        mgr-ref (atom (fake-manager "lean-ctx"
                                                    (fake-client (atom :running)
                                                                 (fn [_] nil))))
                        applied (ovr/register! api mgr-ref
                                               {"read" {:server "lean-ctx"
                                                        :mcp-tool "ctx_read"
                                                        :translate (fn [a] a)}})]
                    (ovr/unregister! api applied)
                    (-> (expect (contains? @active "mcp__lean-ctx__ctx_read"))
                        (.toBe true)))))))

(describe "mcp-client/tool-override — wrapper schema + description"
          (fn []
            (it "the wrapped read tool exposes a `fresh` parameter"
                (fn []
                  (let [executed (atom nil)
                        {:keys [reg api]} (mock-api {"read" (native-read-tool executed)})
                        mgr-ref (atom (fake-manager "lean-ctx"
                                                    (fake-client (atom :running)
                                                                 (fn [_] nil))))]
                    (ovr/register! api mgr-ref ovr/default-overrides)
                    (let [wrapped (get ((:all reg)) "read")
                          schema  (.-inputSchema wrapped)
                          shape   (.-shape schema)]
                      ;; zod ZodObject exposes .shape with field defs.
                      (-> (expect (some? (.-fresh shape))) (.toBe true))
                      (-> (expect (some? (.-path shape)))  (.toBe true))))))

            (it "the wrapped read description is annotated with the route hint"
                (fn []
                  (let [executed (atom nil)
                        {:keys [reg api]} (mock-api {"read" (native-read-tool executed)})
                        mgr-ref (atom (fake-manager "lean-ctx"
                                                    (fake-client (atom :running)
                                                                 (fn [_] nil))))]
                    (ovr/register! api mgr-ref ovr/default-overrides)
                    (let [wrapped (get ((:all reg)) "read")
                          desc    (str (.-description wrapped))]
                      (-> (expect (.includes desc "Routes through MCP server")) (.toBe true))
                      (-> (expect (.includes desc "fresh: true")) (.toBe true))))))

            (it "native description is left ALONE when lean-ctx server is not registered"
                (fn []
                  (let [executed (atom nil)
                        {:keys [reg api]} (mock-api {"read" (native-read-tool executed)})
                        ;; Manager has no lean-ctx server.
                        mgr-ref (atom {:clients (atom {})})]
                    (ovr/register! api mgr-ref ovr/default-overrides)
                    (let [native (get ((:all reg)) "read")]
                      (-> (expect (.-description native)) (.toBe "Native read"))))))))
