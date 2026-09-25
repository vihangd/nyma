(ns tool-name-matching.test
  "Extension-registered tools carry a namespace prefix. `registerTool` on the
   scoped api rewrites the name to `<ns>__<name>` (extension_scope), while
   `overrideTool` deliberately keeps the real name — so a native tool is `edit`
   and an extension's is `token-suite__multi_edit`.

   Anything matching a tool by BARE name against the live active set therefore
   misses every extension tool. Measured across 25 benchmark tasks: `edit` was
   called 0 times with a \"patch\" profile that is supposed to route the model onto
   `multi_edit`, because the keep-set said `multi_edit` and the offered name was
   `token-suite__multi_edit`. The unit test for it passed on synthetic bare names.

   The same flaw sits in `gateway-tool-names`, whose whole job is to keep
   mcp_search/mcp_call reachable through an allowlist that predates them —
   `mcp-client__mcp_search` never matched, so it never protected them."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-metadata :as tm]))

;;; Production-shaped: what `getActiveTools` actually returns.
(def ^:private offered
  ["read" "write" "edit" "bash" "glob" "grep"
   "token-suite__multi_edit" "token-suite__context_files"
   "mcp-client__mcp_search" "mcp-client__mcp_call"
   "retrieve_result" "lsp-suite__hover" "small-model__respond"])

(describe "tool-name matching: bare name against a namespaced active set" (fn []

  (it "matches a native tool by its exact name"
      (fn []
        (-> (expect (tm/matches-tool-name? "edit" "edit")) (.toBe true))
        (-> (expect (tm/matches-tool-name? "edit" "read")) (.toBe false))))

  (it "matches an extension tool through its namespace prefix"
      (fn []
        (-> (expect (tm/matches-tool-name? "token-suite__multi_edit" "multi_edit")) (.toBe true))
        (-> (expect (tm/matches-tool-name? "mcp-client__mcp_search" "mcp_search")) (.toBe true))))

  (it "does not match a name that merely ends in the same word"
      (fn []
        ;; `my_multi_edit` is a different tool; only a `__` boundary counts
        (-> (expect (tm/matches-tool-name? "my_multi_edit" "multi_edit")) (.toBe false))
        (-> (expect (tm/matches-tool-name? "multi_editor" "multi_edit")) (.toBe false))))

  (it "finds the offered spelling of a bare name"
      (fn []
        (-> (expect (tm/offered-names offered ["multi_edit"]))
            (.toEqual (clj->js ["token-suite__multi_edit"])))))

  (it "finds every gateway tool in a real active set"
      (fn []
        ;; this is the assertion that would have caught it: two of the three are
        ;; namespaced in production, and a bare-name filter found only the native
        (let [found (set (tm/offered-names offered tm/gateway-tool-names))]
          (-> (expect (contains? found "mcp-client__mcp_search")) (.toBe true))
          (-> (expect (contains? found "mcp-client__mcp_call")) (.toBe true))
          (-> (expect (contains? found "retrieve_result")) (.toBe true))
          (-> (expect (count found)) (.toBe 3)))))

  (it "returns nothing for a tool that is not on offer"
      (fn []
        (-> (expect (tm/offered-names offered ["nonexistent"])) (.toEqual (clj->js [])))))

  (it "an unprefixed registration still matches, for a host that does not prefix"
      (fn []
        (-> (expect (tm/offered-names ["multi_edit" "read"] ["multi_edit"]))
            (.toEqual (clj->js ["multi_edit"])))))))
