(ns mcp-redirect-policy-test
  "MCP HTTP transports and the SDK 1.32 redirect change.

   @modelcontextprotocol/sdk 1.32 made StreamableHTTP and SSE transports follow
   only SAME-ORIGIN redirects by default. That is a breaking change for any MCP
   server that redirects to another host, and a correct one: following a
   cross-origin redirect would carry that server's headers — bearer token
   included — to whatever host the redirect names.

   nyma keeps the safe default and lets a server opt in per entry with
   `\"redirectPolicy\": \"follow\"`. Asserted on the options object nyma
   builds, and on the SDK's declared type so a rename is caught. Not asserted:
   the SDK's own redirect handling end to end — that needs a second origin
   speaking MCP, and it is the SDK's behaviour to test, not nyma's."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.mcp-client.client :as client]))

(describe "http-transport-opts"
          (fn []
            (it "passes nothing by default, so the SDK's same-origin default holds"
                (fn []
                  (let [o (client/http-transport-opts {:name "s" :url "http://x"})]
                    (-> (expect (.hasOwnProperty o "redirectPolicy")) (.toBe false)))))

            (it "passes redirectPolicy when a server asks for it, in either spelling"
                (fn []
                  (-> (expect (.-redirectPolicy (client/http-transport-opts {:redirect-policy "follow"})))
                      (.toBe "follow"))
                  (-> (expect (.-redirectPolicy (client/http-transport-opts {"redirectPolicy" "follow"})))
                      (.toBe "follow"))))

            (it "drops a value the SDK would reject, leaving the safe default"
                (fn []
                  (doseq [bad ["always" "true" "" "FOLLOW"]]
                    (-> (expect #js [bad (.hasOwnProperty (client/http-transport-opts {:redirect-policy bad})
                                                          "redirectPolicy")])
                        (.toEqual #js [bad false])))))

            (it "still carries headers"
                (fn []
                  (let [o (client/http-transport-opts {:headers {"Authorization" "Bearer t"}})]
                    (-> (expect (some? (.-requestInit o))) (.toBe true)))))))

(describe "the SDK's declared contract"
          (fn []
            (it "both transports declare redirectPolicy with exactly these two values"
                (fn []
        ;; If the SDK renames or widens this, the passthrough above is wrong —
        ;; asserted against the installed type, the same discipline as
        ;; provider_settings_contract.
                  (doseq [f ["streamableHttp.d.ts" "sse.d.ts"]]
                    (let [src (fs/readFileSync
                               (path/join (js/process.cwd) "node_modules" "@modelcontextprotocol"
                                          "sdk" "dist" "esm" "client" f) "utf8")]
                      (-> (expect #js [f (.includes src "redirectPolicy?: 'same-origin' | 'follow'")])
                          (.toEqual #js [f true]))))))))
