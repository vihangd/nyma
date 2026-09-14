(ns tool-system-v2.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-registry :refer [create-registry]]))

;; ── Tool override with original preservation ─────────────────

(defn test-override-preserves-original []
  (let [original #js {:execute (fn [_] "original")}
        override #js {:execute (fn [_] "override")}
        reg      (create-registry {"test" original})]
    ;; Override
    ((:register reg) "test" override)
    (-> (expect (.-execute (get ((:all reg)) "test")))
        (.toBe (.-execute override)))
    ;; Unregister restores original
    ((:unregister reg) "test")
    (-> (expect (.-execute (get ((:all reg)) "test")))
        (.toBe (.-execute original)))))

(defn test-unregister-without-override-removes []
  (let [tool #js {:execute (fn [_] "tool")}
        reg  (create-registry {})]
    ((:register reg) "new-tool" tool)
    (-> (expect (some? (get ((:all reg)) "new-tool"))) (.toBe true))
    ((:unregister reg) "new-tool")
    (-> (expect (get ((:all reg)) "new-tool")) (.toBeUndefined))))

;; ── describe blocks ──────────────────────────────────────────

(describe "tool override" (fn []
  (it "preserves original and restores on unregister" test-override-preserves-original)
  (it "removes tool when no original exists" test-unregister-without-override-removes)))
