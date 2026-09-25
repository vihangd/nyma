(ns small-model-respond-tool.test
  "The respond tool forces a small model to choose between calling a real tool
   and calling `respond(message=...)`, so there is always a structured path.
   Forge reports the technique moving an 8B model from single digits to 84% on
   structured tool-calling.

   It had never worked here. It was `aset` into `st-config.tools` from
   `before_provider_request`, but tools are wrapped ONCE at the top of the loop
   iteration — before that hook runs. So the injected object never passed
   through `wrap-tools-with-middleware`, its own `:leave` never fired, `pending`
   was never set, and the block that terminates the turn was unreachable. It
   also skipped `normalize-tool!`, so its `:parameters` was never converted to a
   wrapped `inputSchema`, and it was invisible to `getAllTools` — which is what
   the quality-monitor's hallucination check consults.

   Registering it is what makes all four of those true at once."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.respond-tool :as rt]
            [agent.extensions.small-model.quality-monitor :as qm]
            [agent.extensions.small-model.finalize-warn :as fw]))

(defn- harness []
  (let [hooks   (atom {})
        mws     (atom {})
        tools   (atom {})
        api #js {:on               (fn [ev f] (swap! hooks assoc ev f) nil)
                 :off              (fn [ev _] (swap! hooks dissoc ev) nil)
                 :addMiddleware    (fn [m] (swap! mws assoc (.-name m) m) nil)
                 :removeMiddleware (fn [n] (swap! mws dissoc n) nil)
                 :registerTool     (fn [n td] (swap! tools assoc (str "small-model__" n) td) nil)
                 :unregisterTool   (fn [n] (swap! tools dissoc (str "small-model__" n)) nil)}
        cleanup (rt/activate api {})]
    {:hooks hooks :mws mws :tools tools :cleanup cleanup :api api}))

(defn- fire [h ev data] ((get @(:hooks h) ev) data nil))

;;; ─── it has to be a real tool ──────────────────────────────────

(describe "respond-tool: registered, not smuggled in" (fn []

  (it "is in the registry, so the loop wraps it like any other tool"
      (fn []
        (let [h (harness)]
          (-> (expect (contains? @(:tools h) rt/respond-tool-name)) (.toBe true)))))

  (it "is no longer aset into the per-request tools map"
      (fn []
        ;; that map is built from the ALREADY-wrapped tools, so anything added
        ;; here bypasses the middleware pipeline entirely
        (let [h (harness)
              st-tools #js {}]
          (fire h "before_provider_request" #js {:tools st-tools})
          (-> (expect (js/Object.keys st-tools)) (.toEqual (clj->js []))))))

  (it "carries a schema the AI SDK can use"
      (fn []
        ;; registerTool runs normalize-tool!, which migrates :parameters to
        ;; :inputSchema; without it streamText throws "schema is not a function"
        (let [h (harness)
              td (get @(:tools h) rt/respond-tool-name)]
          (-> (expect (or (.-inputSchema td) (.-parameters td))) (.toBeDefined)))))

  (it "is named the way every other tool in this extension is"
      (fn []
        ;; the evidence tools are small-model__*; this was small_model__*
        (-> (expect rt/respond-tool-name) (.toBe "small-model__respond"))))))

;;; ─── the termination path ──────────────────────────────────────

(describe "respond-tool: a respond call ends the turn" (fn []

  (it "captures the message, then blocks the next request with it"
      (fn []
        (let [h   (harness)
              mw  (get @(:mws h) "small-model/respond-tool")
              ctx (js-obj "tool-name" rt/respond-tool-name "result" "all four tests pass")]
          ((.-leave mw) ctx)
          ;; the tool result itself is emptied — it is not conversation content
          (-> (expect (aget ctx "result")) (.toBe ""))
          (let [out (fire h "before_provider_request" #js {:tools #js {}})]
            (-> (expect (.-block out)) (.toBe true))
            (-> (expect (.-reason out)) (.toBe "all four tests pass"))))))

  (it "blocks once, not on every following turn"
      (fn []
        (let [h  (harness)
              mw (get @(:mws h) "small-model/respond-tool")]
          ((.-leave mw) (js-obj "tool-name" rt/respond-tool-name "result" "done"))
          (fire h "before_provider_request" #js {:tools #js {}})
          (-> (expect (fire h "before_provider_request" #js {:tools #js {}})) (.toBeNil)))))

  (it "ignores a different tool's result"
      (fn []
        (let [h  (harness)
              mw (get @(:mws h) "small-model/respond-tool")
              ctx (js-obj "tool-name" "bash" "result" "PASS")]
          ((.-leave mw) ctx)
          (-> (expect (aget ctx "result")) (.toBe "PASS"))
          (-> (expect (fire h "before_provider_request" #js {:tools #js {}})) (.toBeNil)))))))

;;; ─── it lets go on unload ──────────────────────────────────────

(describe "respond-tool: cleanup" (fn []

  (it "removes the tool and BOTH handlers, not just the middleware"
      (fn []
        ;; the cleanup fn only called removeMiddleware; before_provider_request
        ;; and message_before_store stayed subscribed after unload
        (let [h (harness)]
          ((:cleanup h))
          (-> (expect (contains? @(:tools h) rt/respond-tool-name)) (.toBe false))
          (-> (expect (contains? @(:mws h) "small-model/respond-tool")) (.toBe false))
          (-> (expect (contains? @(:hooks h) "before_provider_request")) (.toBe false))
          (-> (expect (contains? @(:hooks h) "message_before_store")) (.toBe false)))))))

;;; ─── the nudges name something that exists ─────────────────────

(describe "respond-tool: the prompts name the real tool" (fn []

  (it "every nudge that mentions responding names the registered tool"
      (fn []
        ;; seven strings told the model to call `respond()` while the tool was
        ;; registered under a different name entirely
        (let [msgs (concat (qm/nudge-messages) (fw/nudge-messages))
              bad  (filter (fn [m]
                             (and (.includes (str m) "respond")
                                  (not (.includes (str m) rt/respond-tool-name))))
                           msgs)]
          (-> (expect (vec bad)) (.toEqual (clj->js []))))))))
