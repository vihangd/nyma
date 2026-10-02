(ns provider-settings-contract-test
  "The option names nyma hands the AI SDK provider factories, asserted against
   the installed packages.

   `:compatibility \"compatible\"` was passed to `createOpenAI` at five sites.
   It is an ai-SDK-**v3** option: `OpenAIProviderSettings` in v4 declares
   `baseURL apiKey organization project headers name fetch webSocket` and
   nothing reads `compatibility` — the only occurrences in the shipped bundle
   are comments and an unrelated `type: \"compatibility\"` warning kind. So the
   option was inert at every call site, and THREE test files passed it too
   (`custom_provider_local_active_tools`, `kv_cache_provider_routing`,
   `thinking`), pinning the fiction. I expected two and found three only by
   running this test — which is the argument for it existing.

   That is the same failure as `headroom-ai` being sent `proxyUrl`,
   `algorithms` and `disableCcr` — three names it has never had. Both got
   through because the tests were written against OUR call site rather than
   against the dependency. `test/headroom_compress_options.test.cljs` is the
   template this follows.

   The assertion deliberately reads the installed `.d.ts`. That is the only
   artifact that states the contract; a behavioural test cannot see an ignored
   option, which is precisely why nothing caught this."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn- dts
  "The installed package's type declarations."
  [pkg]
  (fs/readFileSync
   (path/join (js/process.cwd) "node_modules" pkg "dist" "index.d.ts") "utf8"))

(defn- declares?
  "Does `iface` in `src` declare `field`? Scoped to the interface body so a
   same-named field on an unrelated type cannot satisfy it."
  [src iface field]
  (let [i (.indexOf (str src) (str "interface " iface))]
    (and (not (neg? i))
         (let [body (.slice (str src) i (+ i 2000))
               end  (.indexOf body "\n}")
               body (if (neg? end) body (.slice body 0 end))]
           (boolean (re-find (js/RegExp. (str "\\b" field "\\s*\\??\\s*:")) body))))))

(defn- src-files []
  (->> ["src/agent/providers/registry.cljs"
        "src/agent/providers/builtins.cljs"
        "src/agent/extensions/custom_provider_relay/index.cljs"
        "src/agent/extensions/custom_provider_local/index.cljs"
        "src/agent/extensions/custom_provider_groq/index.cljs"
        "src/agent/extensions/custom_provider_kimi/index.cljs"
        "src/agent/extensions/custom_provider_minimax/index.cljs"
        "src/agent/extensions/custom_provider_deepseek/index.cljs"
        "src/agent/extensions/custom_provider_openrouter/index.cljs"]
       (map (fn [p] [p (path/join (js/process.cwd) p)]))
       (filter (fn [[_ abs]] (fs/existsSync abs)))
       (map (fn [[rel abs]] [rel (fs/readFileSync abs "utf8")]))))

(describe "the type declarations say what we think"
          (fn []
            (it "OpenAIProviderSettings declares the options nyma actually uses"
                (fn []
                  (let [d (dts "@ai-sdk/openai")]
                    (doseq [f ["apiKey" "baseURL" "headers" "fetch"]]
                      (-> (expect #js [f (declares? d "OpenAIProviderSettings" f)])
                          (.toEqual #js [f true]))))))

            (it "and does NOT declare `compatibility` — the option that was inert"
                (fn []
        ;; Guard the guard: if this ever starts declaring it, the removal below
        ;; should be reconsidered rather than silently kept.
                  (-> (expect (declares? (dts "@ai-sdk/openai") "OpenAIProviderSettings" "compatibility"))
                      (.toBe false))))

            (it "AnthropicProviderSettings declares what registry.cljs passes"
                (fn []
                  (let [d (dts "@ai-sdk/anthropic")]
                    (doseq [f ["apiKey" "baseURL" "headers"]]
                      (-> (expect #js [f (declares? d "AnthropicProviderSettings" f)])
                          (.toEqual #js [f true]))))))))

(describe "no src file passes an undeclared provider option"
          (fn []
            (it "scans the provider call sites"
                (fn []
        ;; Guard the guard — a path typo would make the next test vacuous.
                  (-> (expect (count (src-files))) (.toBeGreaterThan 5))))

            (it "`compatibility` is gone from every provider construction"
                (fn []
                  (let [offenders (->> (src-files)
                                       (keep (fn [[rel src]]
                                               (when (.includes src ":compatibility \"compatible\"") rel)))
                                       vec)]
                    (-> (expect (clj->js offenders)) (.toEqual (clj->js []))))))

            (it "no test fixture reintroduces it either"
                (fn []
        ;; The fixtures that pinned it are the reason it survived five call
        ;; sites, so the test directory is scanned too. Matched on the provider
        ;; VALUE `:compatibility "compatible"`, not the bare key — skill
        ;; frontmatter has an unrelated `:compatibility` field, and scanning for
        ;; the key alone flagged `skills_doctor` as a false positive.
                  (let [t (path/join (js/process.cwd) "test")
                        files (->> (fs/readdirSync t)
                                   (filter (fn [f] (.endsWith f ".test.cljs")))
                                   (remove (fn [f] (= f "provider_settings_contract.test.cljs"))))
                        offenders (->> files
                                       (keep (fn [f]
                                               (let [src (fs/readFileSync (path/join t f) "utf8")]
                                                 (when (and (.includes src "createOpenAI")
                                                            (.includes src ":compatibility \"compatible\""))
                                                   f))))
                                       vec)]
                    (-> (expect (clj->js offenders)) (.toEqual (clj->js []))))))))
