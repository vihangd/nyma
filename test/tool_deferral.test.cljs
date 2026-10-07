(ns tool-deferral-test
  "Generic tool deferral: withhold a tool's schema until the model asks.

   `mcp_client/deferred` measured the prize — 9,993 of an 18,816-token standing
   prefix was MCP schemas, ~8,700 of it JSON Schema a description cap cannot
   touch — and then only ever applied it to MCP. Any tool's schema costs the
   same per request, so this is the same idea driven by a setting.

   The properties worth pinning are the ones that make it safe rather than the
   ones that make it work:

   - `tool_search` can never withhold itself, by its live PREFIXED name. The
     scope rewrites it to `token-suite__tool_search`, so a string comparison
     would have missed and made every deferred tool permanently unreachable.
   - It is in `gateway-tool-names`, so a capability allowlist written before it
     existed unions it back in instead of silently dropping it — the exact
     failure that left mcp_search and mcp_call unprotected.
   - It is NOT exempt from a permission boundary. Plan mode withholding it is
     correct and must stay that way."
  (:require ["bun:test" :refer [test expect describe]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.extensions.token-suite.tool-deferral :as td]
            [agent.extensions.token-suite.shared :as shared]
            [agent.tool-metadata :as tm]))

(describe "pattern matching"
          (fn []
            (test "an exact bare name catches the prefixed live name"
                  (fn []
        ;; A user writes `multi_edit`; the live tool is token-suite__multi_edit.
                    (-> (expect (td/deferred? "token-suite__multi_edit" ["multi_edit"])) (.toBe true))
                    (-> (expect (td/deferred? "multi_edit" ["multi_edit"])) (.toBe true))))

            (test "a trailing * matches a namespace"
                  (fn []
                    (-> (expect (td/deferred? "mcp__fs__read" ["mcp__*"])) (.toBe true))
                    (-> (expect (td/deferred? "token-suite__multi_edit" ["mcp__*"])) (.toBe false))))

            (test "the __ boundary is respected, not merely the suffix"
                  (fn []
        ;; my_multi_edit is a different tool from multi_edit.
                    (-> (expect (td/deferred? "my_multi_edit" ["multi_edit"])) (.toBe false))))

            (test "a pattern cannot smuggle in a regex"
                  (fn []
        ;; `.` and `+` are literal; only `*` is a wildcard.
                    (-> (expect (td/deferred? "readx" ["read."])) (.toBe false))
                    (-> (expect (td/deferred? "read." ["read."])) (.toBe true))))

            (test "no patterns defers nothing"
                  (fn []
                    (-> (expect (td/deferred? "read" [])) (.toBe false))
                    (-> (expect (td/deferred? "read" nil)) (.toBe false))))))

(describe "withheld"
          (fn []
            (test "withholds the matches and leaves everything else"
                  (fn []
                    (let [out (td/withheld ["read" "mcp__fs__read" "mcp__git__log" "bash"]
                                           ["mcp__*"] #{})]
                      (-> (expect (clj->js (sort out)))
                          (.toEqual (clj->js ["mcp__fs__read" "mcp__git__log"]))))))

            (test "NEVER withholds tool_search, under its prefixed live name"
                  (fn []
        ;; The failure this guards is total: withhold the only route to the
        ;; withheld tools and they are unreachable for the rest of the session.
                    (let [out (td/withheld ["token-suite__tool_search" "mcp__fs__read"]
                                           ["*"] #{})]
                      (-> (expect (contains? out "token-suite__tool_search")) (.toBe false))
                      (-> (expect (contains? out "mcp__fs__read")) (.toBe true)))
        ;; and under the bare name too
                    (-> (expect (contains? (td/withheld ["tool_search"] ["*"] #{}) "tool_search"))
                        (.toBe false))))

            (test "stops withholding what has been activated"
                  (fn []
                    (-> (expect (td/withheld ["mcp__fs__read"] ["mcp__*"] #{"mcp__fs__read"}))
                        (.toEqual #{}))))))

(describe "search scoring"
          (fn []
            (test "an exact name beats a substring, which beats a description hit"
                  (fn []
                    (let [exact (td/score-match "read" "read" "writes things")
                          sub   (td/score-match "read" "mcp__fs__read_file" "")
                          desc  (td/score-match "read" "mcp__fs__cat" "read a file")]
                      (-> (expect (> exact sub)) (.toBe true))
                      (-> (expect (> sub desc)) (.toBe true)))))

            (test "a multi-word query still finds the tool"
                  (fn []
                    (-> (expect (td/score-match "read a file" "mcp__fs__read" "")) (.toBeTruthy))))

            (test "an empty query lists everything"
                  (fn []
                    (-> (expect (td/score-match "" "anything" nil)) (.toBeTruthy))))

            (test "nil for a genuine miss"
                  (fn []
                    (-> (expect (td/score-match "kubernetes" "read" "read a file")) (.toBeNil))))))

(describe "search results"
          (fn []
            (test "carry the schema — the reason the tool was withheld in the first place"
                  (fn []
                    (let [out (td/format-results [{:name "mcp__fs__read"
                                                   :description "Read a file"
                                                   :schema {:type "object"}}])]
                      (-> (expect out) (.toContain "mcp__fs__read"))
                      (-> (expect out) (.toContain "Read a file"))
                      (-> (expect out) (.toContain "\"type\":\"object\"")))))

            (test "say the tool is callable NEXT message, not this one"
                  (fn []
        ;; loop.cljs builds the tool map once per turn, so a model told it can
        ;; call the tool now would get a NoSuchTool error.
                    (-> (expect (td/format-results [{:name "x" :schema {}}])) (.toContain "NEXT"))))

            (test "a miss says so without implying the tool does not exist"
                  (fn []
                    (-> (expect (td/format-results [])) (.toContain "No deferred tools matched"))))))

(describe "the gateway contract"
          (fn []
            (test "tool_search is in gateway-tool-names"
                  (fn []
        ;; So a capability narrower unions it back in rather than dropping it —
        ;; the failure that left mcp_search and mcp_call unprotected.
                    (-> (expect (contains? tm/gateway-tool-names "tool_search")) (.toBe true))))

            (test "offered-names finds it by its prefixed spelling"
                  (fn []
                    (-> (expect (clj->js (tm/offered-names ["token-suite__tool_search" "read"]
                                                           tm/gateway-tool-names)))
                        (.toEqual (clj->js ["token-suite__tool_search"])))))))

(describe "the setting"
          (fn []
            (test "is off by default — it changes what the model can see"
                  (fn []
                    (-> (expect (get-in shared/default-config [:tool-deferral :enabled])) (.toBe false))
                    (-> (expect (get-in shared/default-config [:tool-deferral :defer]))
                        (.toEqual (clj->js [])))))

            (test "activate is a no-op when off, registering nothing"
                  (fn []
                    (let [registered (atom [])
                          api #js {:registerTool (fn [n _t] (swap! registered conj n))
                                   :on (fn [& _] nil)
                                   :off (fn [& _] nil)
                                   :getAllTools (fn [] #js [])
                                   :getTool (fn [_] nil)}
                          stop (td/activate api)]
                      (-> (expect (count @registered)) (.toBe 0))
                      (-> (expect (fn? stop)) (.toBe true))
                      (stop))))))

(defn- with-project-settings [settings f]
  (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-defer-"))
        prev (js/process.cwd)]
    (fs/mkdirSync (path/join dir ".nyma"))
    (fs/writeFileSync (path/join dir ".nyma" "settings.json") (js/JSON.stringify (clj->js settings)))
    (js/process.chdir dir)
    (try (f)
         (finally (js/process.chdir prev)
                  (fs/rmSync dir #js {:recursive true :force true})))))

(describe "tool_search over a real tool list"
          (fn []
            ;; The other tests mock getAllTools as #js [], which is why a
            ;; ReferenceError turning every search into [] went unnoticed.
            (test "finds and reveals a withheld tool"
                  (fn []
                    (with-project-settings
                      {"token-suite" {"tool-deferral" {"enabled" true "defer" ["lsp_*"]}}}
                      (fn []
                        (let [tools {"read"      #js {:description "Read a file"}
                                     "lsp_hover" #js {:description "Show hover information for a symbol"}}
                              search (atom nil)
                              api #js {:registerTool (fn [n t] (when (= n "tool_search") (reset! search t)))
                                       :unregisterTool (fn [_] nil)
                                       :on (fn [& _] nil) :off (fn [& _] nil)
                                       :getAllTools (fn [] (clj->js (keys tools)))
                                       :getTool (fn [n] (get tools n))}
                              stop (td/activate api)
                              out  (str ((.-execute @search) #js {:query "hover"}))]
                          (-> (expect out) (.toContain "lsp_hover"))
                          (-> (expect out) (.not.toContain "No deferred tools matched"))
                          (stop))))))))
