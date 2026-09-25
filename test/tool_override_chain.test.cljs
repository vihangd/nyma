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

;;; ─── two extensions, one tool ──────────────────────────────────
;;
;; `overridden` was a name → original map written ONCE, so a second overrider's
;; `__original` was the TRUE NATIVE rather than the wrapper it displaced, and
;; that wrapper was dropped from the registry with nothing said.
;;
;; Measured consequence: small-model's read-guard wraps `read` to record which
;; paths have been read, and wraps `write`/`edit` to refuse an unread overwrite.
;; mcp_client then overrides `read` and `edit` onto lean-ctx and activates LATER
;; (async, after MCP servers connect). So the recorder never ran, the edit guard
;; was silently discarded, and the write guard survived consulting a permanently
;; empty set — every write onto an existing file refused, for the whole session.

(defn- ex [tag] #js {:execute (fn [_] tag)})
(defn- run [reg name] ((.-execute (get ((:all reg)) name)) nil))

(describe "tool-override-chain: a stack, not one slot" (fn []

  (it "the second overrider chains to the FIRST wrapper, not past it"
      (fn []
        (let [reg (create-registry {"read" (ex "native")})]
          ((:register reg) "read" (ex "guard"))
          ((:register reg) "read" (ex "mcp"))
          (let [top (get ((:all reg)) "read")]
            (-> (expect ((.-execute top) nil)) (.toBe "mcp"))
            ;; this was "native" — the guard was stepped over
            (-> (expect ((.-execute (.-__original top)) nil)) (.toBe "guard"))
            (-> (expect ((.-execute (.-__original (.-__original top))) nil)) (.toBe "native"))))))

  (it "unregister pops one layer instead of jumping to the native"
      (fn []
        (let [reg (create-registry {"read" (ex "native")})]
          ((:register reg) "read" (ex "guard"))
          ((:register reg) "read" (ex "mcp"))
          ((:unregister reg) "read")
          ;; mcp unloading must leave the guard in place, not delete it
          (-> (expect (run reg "read")) (.toBe "guard"))
          ((:unregister reg) "read")
          (-> (expect (run reg "read")) (.toBe "native")))))

  (it "unwinds cleanly however deep it goes"
      (fn []
        (let [reg (create-registry {"t" (ex "native")})]
          (doseq [tag ["a" "b" "c" "d"]] ((:register reg) "t" (ex tag)))
          (-> (expect (run reg "t")) (.toBe "d"))
          (dotimes [_ 4] ((:unregister reg) "t"))
          (-> (expect (run reg "t")) (.toBe "native"))
          (-> (expect (.-__original (get ((:all reg)) "t"))) (.toBeUndefined)))))

  (it "the stub-then-wrapper pattern still sees what it displaced"
      (fn []
        ;; read_guard and tool_override both register a throwaway stub purely to
        ;; read `__original` off it, then override again with the real wrapper
        (let [reg (create-registry {"read" (ex "native")})
              stub (ex "stub")]
          ((:register reg) "read" stub)
          (-> (expect ((.-execute (.-__original stub)) nil)) (.toBe "native"))
          ((:register reg) "read" (ex "wrapper"))
          ;; two overrides, two pops
          ((:unregister reg) "read")
          ((:unregister reg) "read")
          (-> (expect (run reg "read")) (.toBe "native")))))

  (it "a tool that never existed is still removed, not resurrected"
      (fn []
        (let [reg (create-registry {})]
          ((:register reg) "brand-new" (ex "x"))
          ((:unregister reg) "brand-new")
          (-> (expect (contains? ((:all reg)) "brand-new")) (.toBe false)))))

  (it "reports who owns the restore so a scope sweep can unwind exactly once"
      (fn []
        (let [reg (create-registry {"read" (ex "native")})]
          (-> (expect ((:register reg) "fresh" (ex "a"))) (.toBe :new))
          (-> (expect ((:register reg) "read" (ex "b"))) (.toBe :owner))
          ;; every later override also displaced something, so it also owes a pop
          (-> (expect ((:register reg) "read" (ex "c"))) (.toBe :reoverride)))))))
