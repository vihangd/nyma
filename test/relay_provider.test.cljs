(ns relay-provider.test
  "Covers the generic remote-gateway registrar and its model discovery."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.pricing :as pricing]
            [agent.providers.catalog :as catalog]
            [agent.providers.model-fetch :as mf]
            [agent.extensions.custom-provider-relay.index :as relay]))

(def ^:private real-home (.. js/process -env -HOME))
(def ^:private real-fetch js/globalThis.fetch)

;; Read at module load, before any test mutates it — otherwise this reads back
;; whatever `restore!` last wrote and proves nothing about the preload.
(def ^:private discovery-guard-at-load
  (aget js/process.env "NYMA_NO_MODEL_DISCOVERY"))

(defn- temp-home! []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-relay-"))]
    (aset js/process.env "HOME" dir)
    dir))

(defn- allow-discovery!
  "Opt back in to network discovery, which scripts/test-preload.mjs disables for
   the suite. Only safe once `fetch` is stubbed."
  []
  (js-delete js/process.env "NYMA_NO_MODEL_DISCOVERY")
  nil)

(defn- restore! []
  (aset js/process.env "HOME" real-home)
  (aset js/globalThis "fetch" real-fetch)
  (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" "1")
  (js-delete js/process.env "YUNWU_API_KEY")
  nil)

;; ── Settings normalization ───────────────────────────────────

(describe "relay/normalize-entry" (fn []
                                    (it "reads camelCase JS settings"
                                        (fn []
                                          (let [e (relay/normalize-entry #js {:name "g" :baseUrl "https://g.test/v1"
                                                                              :apiKeyEnv "G_KEY"})]
                                            (-> (expect (:base-url e)) (.toBe "https://g.test/v1"))
                                            (-> (expect (:api-key-env e)) (.toBe "G_KEY")))))

                                    (it "reads kebab-case JS settings"
                                        (fn []
                                          (let [e (relay/normalize-entry #js {"name" "g" "base-url" "https://g.test/v1"})]
                                            (-> (expect (:base-url e)) (.toBe "https://g.test/v1")))))

                                    (it "defaults api to openai-compatible"
                                        (fn []
                                          (-> (expect (:api (relay/normalize-entry #js {:name "g"})))
                                              (.toBe "openai-compatible"))))

                                    (it "defaults discover to true — the catalogue is the unknown"
                                        (fn []
                                          (-> (expect (:discover (relay/normalize-entry #js {:name "g"}))) (.toBe true))))

                                    (it "honours discover false"
                                        (fn []
                                          (-> (expect (:discover (relay/normalize-entry #js {:name "g" :discover false})))
                                              (.toBe false))))

                                    (it "carries per-model contextWindow overrides"
                                        (fn []
                                          (let [e (relay/normalize-entry
                                                   #js {:name "g" :models #js [#js {:id "m" :contextWindow 4242}]})]
                                            (-> (expect (:context-window (first (:models e)))) (.toBe 4242)))))))

(describe "relay/merge-entries" (fn []
                                  (it "lets a user entry replace a preset of the same name"
                                      (fn []
                                        (let [merged (relay/merge-entries [{:name "yunwu" :base-url "https://preset"}]
                                                                          [{:name "yunwu" :base-url "https://mine"}])]
                                          (-> (expect (count merged)) (.toBe 1))
                                          (-> (expect (:base-url (first merged))) (.toBe "https://mine")))))

                                  (it "keeps presets the user did not override"
                                      (fn []
                                        (let [merged (relay/merge-entries [{:name "a"} {:name "b"}] [{:name "b"}])]
                                          (-> (expect (count merged)) (.toBe 2))
                                          (-> (expect (set (map :name merged))) (.toEqual (set ["a" "b"]))))))))

;; ── Credentials ──────────────────────────────────────────────

(describe "relay credentials" (fn []
                                (afterEach restore!)

                                (it "prefers the env var"
                                    (fn []
                                      (temp-home!)
                                      (aset js/process.env "YUNWU_API_KEY" "sk-env")
                                      (-> (expect (relay/resolve-key {:name "yunwu" :api-key-env "YUNWU_API_KEY"}))
                                          (.toBe "sk-env"))))

                                (it "shares one /login across both protocol variants of a gateway"
                                    (fn []
        ;; yunwu and yunwu-claude are one account; logging in twice would be a
        ;; papercut users would rightly report as a bug.
                                      (let [dir (temp-home!)]
                                        (fs/mkdirSync (path/join dir ".nyma") #js {:recursive true})
                                        (fs/writeFileSync (path/join dir ".nyma" "credentials.json")
                                                          (js/JSON.stringify #js {:yunwu "sk-saved"})))
                                      (-> (expect (relay/resolve-key {:name "yunwu-claude"
                                                                      :credential-name "yunwu"
                                                                      :api-key-env "YUNWU_API_KEY"}))
                                          (.toBe "sk-saved"))))

                                (it "names the shared login in the missing-key error"
                                    (fn []
                                      (-> (expect (relay/missing-key-message {:name "yunwu-claude"
                                                                              :credential-name "yunwu"
                                                                              :api-key-env "YUNWU_API_KEY"}))
                                          (.toContain "/login yunwu"))))

                                (it "never substitutes a placeholder key"
                                    (fn []
                                      (temp-home!)
                                      (-> (expect (relay/resolve-key {:name "yunwu" :api-key-env "YUNWU_API_KEY"}))
                                          (.toBeNil))))))

;; ── Filtering ────────────────────────────────────────────────

(describe "model-fetch/make-filter" (fn []
                                      (it "passes everything when no patterns are given"
                                          (fn []
                                            (let [p (mf/make-filter {})]
                                              (-> (expect (p "anything")) (.toBe true)))))

                                      (it "treats include as an allow-list"
                                          (fn []
                                            (let [p (mf/make-filter {:include ["claude"]})]
                                              (-> (expect (p "claude-opus-5")) (.toBe true))
                                              (-> (expect (p "gpt-5.2")) (.toBe false)))))

                                      (it "subtracts exclude"
                                          (fn []
                                            (let [p (mf/make-filter {:exclude ["embedding"]})]
                                              (-> (expect (p "text-embedding-3")) (.toBe false))
                                              (-> (expect (p "gpt-5.2")) (.toBe true)))))

                                      (it "lets exclude override include"
                                          (fn []
                                            (let [p (mf/make-filter {:include ["claude"] :exclude ["preview"]})]
                                              (-> (expect (p "claude-opus-5")) (.toBe true))
                                              (-> (expect (p "claude-opus-5-preview")) (.toBe false)))))

                                      (it "supports /regex/ patterns"
                                          (fn []
                                            (let [p (mf/make-filter {:include ["/^gpt-5/"]})]
                                              (-> (expect (p "gpt-5.2")) (.toBe true))
                                              (-> (expect (p "not-gpt-5")) (.toBe false)))))

                                      (it "keeps only models the gateway serves over our protocol"
                                          (fn []
        ;; Shapes taken verbatim from a real yunwu /v1/models response.
                                            (let [p (mf/make-filter {:endpoint-types ["openai"]})]
                                              (-> (expect (p {:id "glm-4.7" :endpoints ["openai"]})) (.toBe true))
                                              (-> (expect (p {:id "gemini-2.5-flash" :endpoints ["gemini" "openai"]})) (.toBe true))
                                              (-> (expect (p {:id "BAAI/bge-reranker-v2-m3" :endpoints ["rerank"]})) (.toBe false))
                                              (-> (expect (p {:id "mj_inpaint" :endpoints ["mj动作"]})) (.toBe false))
                                              (-> (expect (p {:id "wan2.5-i2v-preview" :endpoints ["wan视频生成"]})) (.toBe false))
                                              (-> (expect (p {:id "gpt-4o-transcribe" :endpoints ["语音转文字"]})) (.toBe false))
        ;; Supports nothing at all — a dead catalogue entry.
                                              (-> (expect (p {:id "wen-max-2025-01-25" :endpoints []})) (.toBe false)))))

                                      (it "does not filter gateways that don't report endpoint types"
                                          (fn []
        ;; supported_endpoint_types is a New API extension, not standard OpenAI.
        ;; Filtering on an absent field would silently yield an empty catalogue.
                                            (let [p (mf/make-filter {:endpoint-types ["openai"]})]
                                              (-> (expect (p {:id "some-model"})) (.toBe true)))))

                                      (it "matches case-insensitively"
                                          (fn []
                                            (let [p (mf/make-filter {:include ["CLAUDE"]})]
                                              (-> (expect (p "claude-opus-5")) (.toBe true)))))))

;; ── /v1/models parsing ───────────────────────────────────────

(describe "model-fetch/parse-models" (fn []
                                       (it "reads the OpenAI-shaped data array"
                                           (fn []
                                             (let [ms (mf/parse-models #js {:data #js [#js {:id "a" :object "model"}
                                                                                       #js {:id "b"}]})]
                                               (-> (expect (mapv :id ms)) (.toEqual #js ["a" "b"])))))

                                       (it "prefers display_name when present"
                                           (fn []
                                             (let [ms (mf/parse-models #js {:data #js [#js {:id "a" :display_name "Model A"}]})]
                                               (-> (expect (:name (first ms))) (.toBe "Model A")))))

                                       (it "falls back to the id as the display name"
                                           (fn []
                                             (-> (expect (:name (first (mf/parse-models #js {:data #js [#js {:id "a"}]}))))
                                                 (.toBe "a"))))

                                       (it "skips entries without a usable id"
                                           (fn []
                                             (let [ms (mf/parse-models #js {:data #js [#js {:id ""} #js {} #js {:id "ok"}]})]
                                               (-> (expect (count ms)) (.toBe 1)))))

                                       (it "carries supported_endpoint_types through as :endpoints"
                                           (fn []
                                             (let [ms (mf/parse-models
                                                       #js {:data #js [#js {:id "glm-4.7"
                                                                            :supported_endpoint_types #js ["openai"]}]})]
                                               (-> (expect (:endpoints (first ms))) (.toEqual #js ["openai"])))))

                                       (it "leaves :endpoints absent when the gateway omits the field"
                                           (fn []
                                             (-> (expect (:endpoints (first (mf/parse-models #js {:data #js [#js {:id "a"}]}))))
                                                 (.toBeUndefined))))

                                       (it "returns nil for a non-array payload"
                                           (fn []
                                             (-> (expect (mf/parse-models #js {:data "nope"})) (.toBeNil))
                                             (-> (expect (mf/parse-models nil)) (.toBeNil))))))

;; ── Discovery over the network ───────────────────────────────

(defn- stub-fetch! [handler]
  (aset js/globalThis "fetch" handler))

(defn ^:async test-fetch-happy []
  (temp-home!)
  (let [seen (atom nil)]
    (stub-fetch! (fn [url opts]
                   (reset! seen {:url url :opts opts})
                   (js/Response. (js/JSON.stringify #js {:data #js [#js {:id "claude-opus-5"}]})
                                 #js {:status 200
                                      :headers #js {"content-type" "application/json"}})))
    (let [models (js-await (mf/fetch-models "https://g.test/v1" "sk-1"))]
      (-> (expect (mapv :id models)) (.toEqual #js ["claude-opus-5"]))
      ;; Claude Code's discovery contract, borrowed wholesale.
      (-> (expect (:url @seen)) (.toContain "/models?limit=1000"))
      (-> (expect (.-redirect (:opts @seen))) (.toBe "manual"))
      (-> (expect (aget (.-headers (:opts @seen)) "Authorization")) (.toBe "Bearer sk-1")))))

(defn ^:async test-fetch-refuses-redirect []
  (temp-home!)
  (stub-fetch! (fn [_url _opts]
                 (js/Response. "" #js {:status 302})))
  ;; Following it would hand the credential to whoever the gateway names.
  (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "sk-1"))) (.toBeNil)))

(defn ^:async test-fetch-handles-401 []
  (temp-home!)
  (let [calls (atom 0)]
    (stub-fetch! (fn [_url _opts]
                   (swap! calls inc)
                   (js/Response. "" #js {:status 401})))
    (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "bad"))) (.toBeNil))
    ;; No retry: gateways throttle repeated auth failures (yunwu: 120s 429).
    (-> (expect @calls) (.toBe 1))))

(defn ^:async test-fetch-survives-throw []
  (temp-home!)
  (stub-fetch! (fn [_url _opts] (throw (js/Error. "network down"))))
  (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "sk-1"))) (.toBeNil)))

(defn ^:async test-fetch-survives-bad-json []
  (temp-home!)
  (stub-fetch! (fn [_url _opts]
                 (js/Response. "not json" #js {:status 200})))
  (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "sk-1"))) (.toBeNil)))

(describe "model-fetch/fetch-models" (fn []
                                       (afterEach restore!)
                                       (it "fetches, and follows the discovery contract" test-fetch-happy)
                                       (it "refuses to follow a redirect" test-fetch-refuses-redirect)
                                       (it "returns nil on 401 without retrying" test-fetch-handles-401)
                                       (it "returns nil when the request throws" test-fetch-survives-throw)
                                       (it "returns nil on an unparseable body" test-fetch-survives-bad-json)))

;; ── Cache ────────────────────────────────────────────────────

(describe "model-fetch cache" (fn []
                                (afterEach restore!)

                                (it "round-trips through disk"
                                    (fn []
                                      (temp-home!)
                                      (mf/write-cache! "g" [{:id "a" :name "A"}])
                                      (-> (expect (mapv :id (:models (mf/read-cache "g")))) (.toEqual #js ["a"]))))

                                (it "returns nil when absent"
                                    (fn []
                                      (temp-home!)
                                      (-> (expect (mf/read-cache "g")) (.toBeNil))))

                                (it "returns nil on a corrupt cache file rather than throwing"
                                    (fn []
                                      (let [dir (temp-home!)]
                                        (fs/mkdirSync (path/join dir ".nyma" "cache") #js {:recursive true})
                                        (fs/writeFileSync (path/join dir ".nyma" "cache" "models-g.json") "{oops")
                                        (-> (expect (mf/read-cache "g")) (.toBeNil)))))

                                (it "treats a fresh entry as fresh and a day-old one as stale"
                                    (fn []
                                      (let [now (js/Date.now)]
                                        (-> (expect (mf/cache-fresh? {:fetched-at now} now)) (.toBe true))
                                        (-> (expect (mf/cache-fresh? {:fetched-at (- now 90000000)} now)) (.toBe false)))))

                                (it "applies the filter when reading the cache"
                                    (fn []
                                      (temp-home!)
                                      (mf/write-cache! "g" [{:id "claude-opus-5"} {:id "gpt-5.2"}])
                                      (let [got (mf/cached-models "g" (mf/make-filter {:include ["claude"]}))]
                                        (-> (expect (mapv :id (:models got))) (.toEqual #js ["claude-opus-5"])))))))

;; ── Live catalogue refresh ───────────────────────────────────

(defn ^:async test-discovery-updates-catalogue-live []
  (temp-home!)
  (allow-discovery!)
  (aset js/process.env "YUNWU_API_KEY" "sk-test")
  (aset js/globalThis "fetch"
        (fn [_url _opts]
          (js/Response. (js/JSON.stringify
                         #js {:data #js [#js {:id "claude-opus-5"}
                                         #js {:id "claude-newly-launched"}]})
                        #js {:status 200
                             :headers #js {"content-type" "application/json"}})))
  (let [agent   (create-agent {:model "test" :system-prompt "x"})
        api     (create-extension-api agent "relay")
        cleanup ((aget relay "default") api)
        specs   (fn []
                  (set (map :spec (catalog/list-all-models
                                   ((:list (:provider-registry agent)))
                                   (:context-window (:model-registry agent))))))]
    ;; Seeded synchronously — the provider is usable before discovery lands.
    (-> (expect (contains? (specs) "yunwu-claude/claude-opus-5")) (.toBe true))
    (-> (expect (contains? (specs) "yunwu-claude/claude-newly-launched")) (.toBe false))
    ;; Let the background refresh settle.
    (js-await (js/Promise. (fn [res] (js/setTimeout res 50))))
    ;; Re-registering is enough: the registry is a plain assoc and the catalogue
    ;; reads :models at call time, so /model sees this without a restart.
    (-> (expect (contains? (specs) "yunwu-claude/claude-newly-launched")) (.toBe true))
    (cleanup)))

(defn ^:async test-discovery-failure-keeps-seed []
  (temp-home!)
  (allow-discovery!)
  (aset js/process.env "YUNWU_API_KEY" "sk-test")
  (aset js/globalThis "fetch" (fn [_url _opts] (js/Response. "" #js {:status 500})))
  (let [agent   (create-agent {:model "test" :system-prompt "x"})
        api     (create-extension-api agent "relay")
        cleanup ((aget relay "default") api)]
    (js-await (js/Promise. (fn [res] (js/setTimeout res 50))))
    ;; A gateway that can't answer must degrade to the seed list, not to nothing.
    (let [specs (set (map :spec (catalog/list-all-models
                                 ((:list (:provider-registry agent)))
                                 (:context-window (:model-registry agent)))))]
      (-> (expect (contains? specs "yunwu-claude/claude-opus-5")) (.toBe true)))
    (cleanup)))

(defn ^:async test-discovery-off-by-default-in-tests []
  ;; Guards the guard: several test files load every built-in extension, so if
  ;; the preload ever stops applying, a machine with a real YUNWU_API_KEY
  ;; exported would start making live third-party calls during `bun test`.
  (-> (expect discovery-guard-at-load) (.toBe "1"))
  (temp-home!)
  (aset js/process.env "YUNWU_API_KEY" "sk-test")
  (let [calls (atom 0)]
    (aset js/globalThis "fetch"
          (fn [_url _opts] (swap! calls inc) (js/Response. "" #js {:status 200})))
    (let [agent   (create-agent {:model "test" :system-prompt "x"})
          api     (create-extension-api agent "relay")
          cleanup ((aget relay "default") api)]
      (js-await (js/Promise. (fn [res] (js/setTimeout res 50))))
      (-> (expect @calls) (.toBe 0))
      (cleanup))))

(describe "relay discovery" (fn []
                              (afterEach restore!)
                              (it "a background refresh updates the catalogue without a restart"
                                  test-discovery-updates-catalogue-live)
                              (it "a failed refresh leaves the seed list registered"
                                  test-discovery-failure-keeps-seed)
                              (it "makes no network calls under the suite's default guard"
                                  test-discovery-off-by-default-in-tests)))

;; ── Extension registration ───────────────────────────────────

(defn- boot []
  (let [agent (create-agent {:model "test" :system-prompt "x"})
        api   (create-extension-api agent "relay")]
    {:agent agent :api api :cleanup ((aget relay "default") api)}))

(defn- provider-names [agent]
  (set (js/Object.keys (or ((:list (:provider-registry agent))) #js {}))))

(describe "relay extension registration" (fn []
                                           (beforeEach (fn [] (temp-home!) (reset! pricing/unpriced-providers #{}) nil))
                                           (afterEach restore!)

                                           (it "registers both yunwu presets"
                                               (fn []
                                                 (let [{:keys [agent cleanup]} (boot)]
                                                   (-> (expect (contains? (provider-names agent) "yunwu")) (.toBe true))
                                                   (-> (expect (contains? (provider-names agent) "yunwu-claude")) (.toBe true))
                                                   (cleanup))))

                                           (it "marks gateways unpriced so relayed ids don't inherit vendor rates"
                                               (fn []
                                                 (let [{:keys [cleanup]} (boot)]
                                                   (-> (expect (contains? @pricing/unpriced-providers "yunwu-claude")) (.toBe true))
                                                   (-> (expect (pricing/lookup-cost "yunwu-claude/claude-opus-5")) (.toBeNil))
                                                   (cleanup))))

                                           (it "resolves a real context window for a relayed vendor id"
                                               (fn []
                                                 (let [{:keys [agent cleanup]} (boot)
                                                       models (catalog/list-all-models ((:list (:provider-registry agent)))
                                                                                       (:context-window (:model-registry agent)))
                                                       opus   (first (filter (fn [m] (= "yunwu-claude/claude-opus-5" (:spec m))) models))]
          ;; The relay's /v1/models says nothing about size; the vendor entry does.
                                                   (-> (expect (:context-window opus)) (.toBe 1000000))
                                                   (cleanup))))

                                           (it "builds an anthropic model against the gateway base URL"
                                               (fn []
                                                 (aset js/process.env "YUNWU_API_KEY" "sk-test")
                                                 (let [{:keys [agent cleanup]} (boot)
                                                       model ((:resolve (:provider-registry agent)) "yunwu-claude" "claude-opus-5")]
          ;; anthropic.messages is what makes cache_control reach the wire.
                                                   (-> (expect (.-provider model)) (.toBe "anthropic.messages"))
                                                   (cleanup))))

                                           (it "builds an openai-compatible model for the default variant"
                                               (fn []
                                                 (aset js/process.env "YUNWU_API_KEY" "sk-test")
                                                 (let [{:keys [agent cleanup]} (boot)
                                                       model ((:resolve (:provider-registry agent)) "yunwu" "gpt-5.2")]
                                                   (-> (expect (.-provider model)) (.toBe "openai.chat"))
                                                   (cleanup))))

                                           (it "throws a friendly error, not a raw 401, when no key is configured"
                                               (fn []
                                                 (let [{:keys [agent cleanup]} (boot)]
                                                   (-> (expect (fn [] ((:resolve (:provider-registry agent)) "yunwu" "gpt-5.2")))
                                                       (.toThrow #"/login yunwu"))
                                                   (cleanup))))))
