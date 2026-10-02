(ns headroom-compress-options-test
  "The option NAMES nyma hands headroom-ai, asserted against the package.

   This file exists because of a bug no behavioural test could see. The call
   site passed `#js {:proxyUrl … :algorithms … :disableCcr …}` and
   `headroom-ai` reads none of those: searched by name, `proxyUrl`,
   `algorithms` and `disableCcr` appear nowhere in the installed package — not
   in `dist/*.js`, not in the `.d.ts`, not in the source maps. So two settings
   were inert and the third silently bypassed the configured proxy, while
   `probe-proxy` went on reporting that proxy healthy with nyma's own fetch.

   It was *accidentally* harmless at defaults, which is why it survived: the
   package's own `DEFAULT_BASE_URL` is the same `http://localhost:8787` nyma
   defaults to. It bit only a user who configured a different proxy.

   Two layers, because either alone would have missed it:
   - `compress-opts` against the names, so a rename upstream fails here;
   - a live stub server, so `baseUrl` is proved to reach the wire rather than
     merely to be spelled correctly."
  (:require ["bun:test" :refer [test expect describe]]
            ["headroom-ai" :as headroom]
            [agent.extensions.headroom.compress :as c]
            [agent.extensions.headroom.shared :as shared]))

(describe "compress-opts"
          (fn []
            (test "uses baseUrl — the name the package actually reads"
                  (fn []
                    (let [o (c/compress-opts {:base-url "http://example:9999" :model "m"})]
                      (-> (expect (.-baseUrl o)) (.toBe "http://example:9999"))
          ;; The old spelling must not reappear.
                      (-> (expect (.-proxyUrl o)) (.toBeUndefined)))))

            (test "omits tokenBudget when the budget is unknown, rather than sending 0"
                  (fn []
                    (let [o (c/compress-opts {:base-url "u" :model "m" :token-budget nil})]
                      (-> (expect (.hasOwnProperty o "tokenBudget")) (.toBe false)))
                    (let [o (c/compress-opts {:base-url "u" :model "m" :token-budget 0})]
                      (-> (expect (.hasOwnProperty o "tokenBudget")) (.toBe false)))
                    (let [o (c/compress-opts {:base-url "u" :model "m" :token-budget 1234})]
                      (-> (expect (.-tokenBudget o)) (.toBe 1234)))))

            (test "mode rides under config, and is absent by default"
                  (fn []
                    (let [o (c/compress-opts {:base-url "u" :model "m"})]
                      (-> (expect (.hasOwnProperty o "config")) (.toBe false)))
                    (let [o (c/compress-opts {:base-url "u" :model "m" :mode "ccr"})]
                      (-> (expect (.. o -config -mode)) (.toBe "ccr")))))

            (test "every key it emits is one headroom-ai declares"
                  (fn []
        ;; The guard against the whole bug class: a key nyma invents is a key
        ;; the proxy ignores. Keep in step with CompressOptions.
                    (let [declared #{"model" "baseUrl" "apiKey" "timeout" "fallback"
                                     "retries" "client" "tokenBudget" "hooks" "stack"
                                     "config"}
                          o (c/compress-opts {:base-url "u" :model "m"
                                              :token-budget 10 :mode "ccr"})]
                      (doseq [k (js/Object.keys o)]
                        (-> (expect (contains? declared k)) (.toBe true))))))))

(describe "resolve-mode"
          (fn []
            (test "nil by default — the proxy's marker-free pipeline"
                  (fn []
                    (-> (expect (c/resolve-mode {})) (.toBeNil))))

            (test "passes through the three modes the proxy accepts"
                  (fn []
                    (doseq [m ["ccr" "lossy_inline" "lossless_then_lossy"]]
                      (-> (expect (c/resolve-mode {:mode m})) (.toBe m)))))

            (test "flags an unknown mode instead of letting the proxy 400 every call"
                  (fn []
                    (-> (expect (c/resolve-mode {:mode "SmartCrusher"})) (.toBe :agent.extensions.headroom.compress/invalid))))

            (test "honours the one legacy spelling that asked for something: disable-ccr false"
                  (fn []
                    (-> (expect (c/resolve-mode {:disable-ccr false})) (.toBe "ccr"))
        ;; `true` was the default and means the default pipeline — no mode.
                    (-> (expect (c/resolve-mode {:disable-ccr true})) (.toBeNil))))))

(describe "removed settings"
          (fn []
            (test "are not in default-config, so presence means the user wrote them"
                  (fn []
                    (-> (expect (contains? shared/default-config :algorithms)) (.toBe false))
                    (-> (expect (contains? shared/default-config :disable-ccr)) (.toBe false))))

            (test "are reported, each naming its replacement"
                  (fn []
                    (let [ws (shared/removed-key-warnings {:algorithms ["X"] :disable-ccr true})]
                      (-> (expect (count ws)) (.toBe 2))
                      (-> (expect (.join (clj->js ws) " ")) (.toContain "mode")))))

            (test "say nothing for a config that does not use them"
                  (fn []
                    (-> (expect (count (shared/removed-key-warnings shared/default-config)))
                        (.toBe 0))))))

(describe "baseUrl reaches the wire"
          (fn []
            (test "compress POSTs to the configured host, not the package default"
                  (^:async fn []
        ;; Port 0 = let the OS pick, so this never collides with a real proxy
        ;; (and the package default, localhost:8787, is what we are proving it
        ;; does NOT use).
                    (let [hits   (atom [])
                          server (js/Bun.serve
                                  #js {:port 0
                                       :fetch (fn [req]
                                                (swap! hits conj (.-url req))
                                                (js/Response.
                                                 (js/JSON.stringify
                                                  #js {:messages #js []
                                                       :tokensBefore 0 :tokensAfter 0
                                                       :tokensSaved 0 :compressionRatio 1
                                                       :transformsApplied #js []
                                                       :ccrHashes #js [] :compressed false})
                                                 #js {:headers #js {"content-type" "application/json"}}))})
                          ;; Read the port BEFORE stop(): Bun zeroes it on a stopped
              ;; server, and asserting against the zeroed value passes a test
              ;; that proves nothing.
                          port   (.-port server)
                          base   (str "http://localhost:" port)]
                      (try
                        (js-await
                         ((.-compress headroom)
                          #js [#js {:role "user" :content "hello"}]
                          (c/compress-opts {:base-url base :model "test-model"})))
                        (catch :default _e nil))
                      (.stop server true)
          ;; The assertion that matters: the request went to OUR server.
                      (-> (expect (count @hits)) (.toBeGreaterThan 0))
                      (-> (expect (first @hits)) (.toContain (str ":" port))))))))
