(ns ext-mcp-client-tool-shadow.test
  "Tests the tool-shadowing feature: when lean-ctx (or any MCP server)
   registers a tool that supersedes a native nyma tool, the native one
   is removed from the active set so the LLM only sees the cached /
   compressed variant.

   Scope here is the pure compute step — `compute-shadow-set` and
   `parse-shadow-tools`. Full activation flow is covered indirectly
   by the load-smoke test."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.mcp-client.index :as idx]))

(describe "mcp-client/tool-shadow/compute-shadow-set"
          (fn []
            (it "returns native names whose mcp replacement is active"
                (fn []
                  (let [shadow {"read" "mcp__lean-ctx__ctx_read"
                                "ls"   "mcp__lean-ctx__ctx_tree"
                                "grep" "mcp__lean-ctx__ctx_search"}
                        active ["read" "ls" "grep" "edit" "bash"
                                "mcp__lean-ctx__ctx_read"
                                "mcp__lean-ctx__ctx_search"]
                        result (idx/compute-shadow-set shadow active)]
                    ;; ctx_read present → hide read
                    ;; ctx_search present → hide grep
                    ;; ctx_tree NOT present → keep ls
                    (-> (expect (contains? result "read"))  (.toBe true))
                    (-> (expect (contains? result "grep"))  (.toBe true))
                    (-> (expect (contains? result "ls"))    (.toBe false))
                    (-> (expect (contains? result "edit"))  (.toBe false))
                    (-> (expect (contains? result "bash"))  (.toBe false)))))

            (it "empty shadow map → empty result"
                (fn []
                  (let [r (idx/compute-shadow-set {} ["read" "ls" "grep"])]
                    (-> (expect (count r)) (.toBe 0)))))

            (it "no mcp tools active → empty result"
                (fn []
                  (let [shadow {"read" "x__ctx_read"}
                        r (idx/compute-shadow-set shadow ["read" "ls" "grep"])]
                    (-> (expect (count r)) (.toBe 0)))))))

(describe "mcp-client/tool-shadow/parse-shadow-tools"
          (fn []
            (it "nil → uses defaults (ls/grep) — read/edit handled by tool_override"
                (fn []
                  (let [r (idx/parse-shadow-tools nil)]
                    (-> (expect (contains? r "ls"))   (.toBe true))
                    (-> (expect (contains? r "grep")) (.toBe true))
                    ;; read is intentionally NOT in shadow defaults —
                    ;; tool_override owns it (delegator + native fallback).
                    (-> (expect (contains? r "read")) (.toBe false))
                    (-> (expect (contains? r "edit")) (.toBe false)))))

            (it "false → disabled (empty map)"
                (fn []
                  (let [r (idx/parse-shadow-tools false)]
                    (-> (expect (count r)) (.toBe 0)))))

            (it "user object — overrides default for that key"
                (fn []
                  (let [user-val #js {:read "custom__alt"}
                        r (idx/parse-shadow-tools user-val)]
                    (-> (expect (get r "read")) (.toBe "custom__alt"))
                    ;; Other defaults preserved
                    (-> (expect (contains? r "ls"))   (.toBe true))
                    (-> (expect (contains? r "grep")) (.toBe true)))))

            (it "user object with null value drops the default"
                (fn []
                  ;; Use Object.create to allow explicit null assignment
                  (let [user-val #js {}
                        _        (aset user-val "read" nil)
                        r        (idx/parse-shadow-tools user-val)]
                    (-> (expect (contains? r "read")) (.toBe false))
                    (-> (expect (contains? r "ls"))   (.toBe true)))))))

(describe "mcp-client/parse-hidden-tools"
          (fn []
            (it "nil → uses defaults (Serena memory CRUD + replace_content)"
                (fn []
                  (let [r (set (idx/parse-hidden-tools nil))]
                    (-> (expect (contains? r "mcp__serena__delete_memory")) (.toBe true))
                    (-> (expect (contains? r "mcp__serena__edit_memory"))   (.toBe true))
                    (-> (expect (contains? r "mcp__serena__rename_memory")) (.toBe true))
                    (-> (expect (contains? r "mcp__serena__replace_content")) (.toBe true)))))

            (it "false → disabled (empty)"
                (fn []
                  (let [r (idx/parse-hidden-tools false)]
                    (-> (expect (count r)) (.toBe 0)))))

            (it "JS array → replaces defaults"
                (fn []
                  (let [r (set (idx/parse-hidden-tools #js ["mcp__foo__bar"]))]
                    (-> (expect (contains? r "mcp__foo__bar")) (.toBe true))
                    ;; default no longer present
                    (-> (expect (contains? r "mcp__serena__delete_memory")) (.toBe false)))))

            (it "JS object adds entries on top of defaults"
                (fn []
                  (let [user-val #js {:mcp__foo__bar true}
                        r        (set (idx/parse-hidden-tools user-val))]
                    (-> (expect (contains? r "mcp__foo__bar")) (.toBe true))
                    (-> (expect (contains? r "mcp__serena__delete_memory")) (.toBe true)))))

            (it "JS object with null value removes a default"
                (fn []
                  (let [user-val #js {}
                        _        (aset user-val "mcp__serena__delete_memory" nil)
                        r        (set (idx/parse-hidden-tools user-val))]
                    (-> (expect (contains? r "mcp__serena__delete_memory")) (.toBe false))
                    (-> (expect (contains? r "mcp__serena__edit_memory"))   (.toBe true)))))))
