(ns mcp-deferred.test
  "Deferred MCP tools. Measured on this machine: the standing prompt prefix was
   18,816 tokens per request and the five configured MCP servers were 9,993 of
   it — 53% — for tools most tasks never call. Capping descriptions from 1200
   to 80 chars recovered only 1,274, because the bulk is parameter JSON Schema.

   Two fixed tools replace N schemas: `mcp_search` finds a tool and returns its
   schema as text, `mcp_call` runs it. The bridged tools stay registered and are
   only withheld from the model."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.mcp-client.deferred :as d]))

(def ^:private cat
  [{:server "serena" :name "find_symbol" :nyma-name "mcp__serena__find_symbol"
    :description "Find a symbol in the project"
    :schema #js {:type "object"
                 :properties #js {:name_path #js {:type "string" :description "symbol path"}}}}
   {:server "lean-ctx" :name "ctx_search" :nyma-name "mcp__lean-ctx__ctx_search"
    :description "Search code with compact results"
    :schema #js {:type "object" :properties #js {:pattern #js {:type "string"}}}}
   {:server "context7" :name "get_docs" :nyma-name "mcp__context7__get_docs"
    :description "Fetch library documentation"
    :schema #js {:type "object" :properties #js {:lib #js {:type "string"}}}}])

(defn- names [entries] (mapv :nyma-name entries))

;;; ─── search ────────────────────────────────────────────────────

(describe "mcp deferred: search" (fn []

                                   (it "a name match outranks a body match"
                                       (fn []
        ;; "search" appears in ctx_search's NAME and in serena's description
                                         (-> (expect (first (names (d/search cat "search")))) (.toBe "mcp__lean-ctx__ctx_search"))))

                                   (it "matches on description"
                                       (fn []
                                         (-> (expect (names (d/search cat "documentation")))
                                             (.toEqual (clj->js ["mcp__context7__get_docs"])))))

                                   (it "matches on a PARAMETER description, not just name and description"
                                       (fn []
        ;; "symbol path" is only in the parameter's description — the field a
        ;; name/description index misses, and one of the four Anthropic searches
                                         (-> (expect (names (d/search cat "symbol path")))
                                             (.toEqual (clj->js ["mcp__serena__find_symbol"])))))

                                   (it "matches on a parameter NAME"
                                       (fn []
                                         (-> (expect (names (d/search cat "pattern")))
                                             (.toEqual (clj->js ["mcp__lean-ctx__ctx_search"])))))

                                   (it "matches on server name, so one search can pull a whole server"
                                       (fn []
                                         (-> (expect (names (d/search cat "serena")))
                                             (.toEqual (clj->js ["mcp__serena__find_symbol"])))))

                                   (it "no match returns empty rather than erroring"
                                       (fn []
                                         (-> (expect (d/search cat "quixotic")) (.toEqual (clj->js [])))))

                                   (it "respects the limit and defaults to five"
                                       (fn []
                                         (-> (expect (count (d/search cat "search code project" 1))) (.toBe 1))
                                         (-> (expect (count (d/search cat "search code project"))) (.toBeLessThanOrEqual 5))))

                                   (it "is stable on ties, so a comparison can be asserted"
                                       (fn []
                                         (-> (expect (names (d/search cat "object"))) (.toEqual (names (d/search cat "object"))))))))

;;; ─── rendering ─────────────────────────────────────────────────

(describe "mcp deferred: rendering" (fn []

                                      (it "a match carries its parameter schema, which is the point"
                                          (fn []
        ;; without the schema the model cannot construct a call: there is no
        ;; tool definition for it to validate against
                                            (let [out (d/render-match (first cat))]
                                              (-> (expect (.includes out "find_symbol")) (.toBe true))
                                              (-> (expect (.includes out "name_path")) (.toBe true)))))

                                      (it "an empty result says so and suggests what to do"
                                          (fn []
                                            (let [out (d/render-results [] "nothing")]
                                              (-> (expect (.includes out "No MCP tool matches")) (.toBe true)))))

                                      (it "the server summary names servers and counts, never their tools"
                                          (fn []
                                            (let [out (d/server-summary cat)]
                                              (-> (expect (.includes out "serena: 1 tools")) (.toBe true))
                                              (-> (expect (.includes out "mcp_search")) (.toBe true))
          ;; listing the tools here would defeat the whole exercise
                                              (-> (expect (.includes out "find_symbol")) (.toBe false)))))

                                      (it "no servers means no prompt block at all"
                                          (fn []
                                            (-> (expect (d/server-summary [])) (.toBeNil))))))

;;; ─── the gate ──────────────────────────────────────────────────

(describe "mcp deferred: the gate" (fn []

                                     (it "withholds the bridged tools and keeps everything else"
                                         (fn []
                                           (-> (expect (d/allowed-after-deferral
                                                        ["read" "write" "mcp__serena__find_symbol" "mcp__context7__get_docs"] cat))
                                               (.toEqual (clj->js ["read" "write"])))))

                                     (it "returns a VECTOR, never nil, even when it withholds everything"
                                         (fn []
        ;; this event merges by set intersection and nil means "no opinion",
        ;; which would silently re-admit every tool — the trap model_roles
        ;; documents
                                           (let [out (d/allowed-after-deferral ["mcp__serena__find_symbol"] cat)]
                                             (-> (expect (js/Array.isArray (clj->js out))) (.toBe true))
                                             (-> (expect (count out)) (.toBe 0)))))

                                     (it "leaves a non-MCP tool that merely looks like one alone"
                                         (fn []
                                           (-> (expect (d/allowed-after-deferral ["mcp_search" "mcp_call" "read"] cat))
                                               (.toEqual (clj->js ["mcp_search" "mcp_call" "read"])))))))

;;; ─── the threshold ─────────────────────────────────────────────

(describe "mcp deferred: threshold" (fn []

                                      (it "defers when the catalog is expensive"
                                          (fn []
        ;; estimator stands in for the host's; a big number means big schemas
                                            (-> (expect (d/defer? cat (fn [_] 9993) 2000)) (.toBe true))))

                                      (it "does not defer a catalog too small to be worth a round trip"
                                          (fn []
                                            (-> (expect (d/defer? cat (fn [_] 300) 2000)) (.toBeFalsy))))

                                      (it "never defers when there are no MCP tools at all"
                                          (fn []
                                            (-> (expect (d/defer? [] (fn [_] 100000) 2000)) (.toBeFalsy))))

                                      (it "measures descriptions AND schemas, since the schemas are the bulk"
                                          (fn []
                                            (let [seen (atom "")]
                                              (d/defer? cat (fn [s] (reset! seen s) 0) 2000)
                                              (-> (expect (.includes @seen "name_path")) (.toBe true))
                                              (-> (expect (.includes @seen "Find a symbol")) (.toBe true)))))))
