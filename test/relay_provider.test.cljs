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
    (let [models (js-await (mf/fetch-models "https://g.test/v1" "sk-1" nil))]
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
  (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "sk-1" nil))) (.toBeNil)))

(defn ^:async test-fetch-handles-401 []
  (temp-home!)
  (let [calls (atom 0)]
    (stub-fetch! (fn [_url _opts]
                   (swap! calls inc)
                   (js/Response. "" #js {:status 401})))
    (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "bad" nil))) (.toBeNil))
    ;; No retry: gateways throttle repeated auth failures (yunwu: 120s 429).
    (-> (expect @calls) (.toBe 1))))

(defn ^:async test-fetch-survives-throw []
  (temp-home!)
  (stub-fetch! (fn [_url _opts] (throw (js/Error. "network down"))))
  (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "sk-1" nil))) (.toBeNil)))

(defn ^:async test-fetch-survives-bad-json []
  (temp-home!)
  (stub-fetch! (fn [_url _opts]
                 (js/Response. "not json" #js {:status 200})))
  (-> (expect (js-await (mf/fetch-models "https://g.test/v1" "sk-1" nil))) (.toBeNil)))

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

;; ── Per-model protocol dispatch ──────────────────────────────
;; Endpoint types below are verbatim from a live yunwu catalogue.

(describe "relay/pick-protocol" (fn []
                                  (let [oai {:api "openai-compatible"}]

                                    (it "uses /chat when the model serves the chat endpoint"
                                        (fn []
                                          (-> (expect (relay/pick-protocol oai ["openai" "openai-response"])) (.toBe :chat))
                                          (-> (expect (relay/pick-protocol oai ["openai"])) (.toBe :chat))))

                                    (it "uses /responses for models that ONLY serve openai-response"
                                        (fn []
          ;; gpt-5.4, gpt-5-pro, gpt-5-codex, gpt-5.1-codex-max are all like this.
          ;; Sending them to /chat/completions is a 404.
                                          (-> (expect (relay/pick-protocol oai ["openai-response"])) (.toBe :responses))))

                                    (it "falls back to the entry's api when the gateway reports no endpoints"
                                        (fn []
                                          (-> (expect (relay/pick-protocol oai nil)) (.toBe :chat))
                                          (-> (expect (relay/pick-protocol {:api "openai-responses"} nil)) (.toBe :responses))
                                          (-> (expect (relay/pick-protocol {:api "anthropic"} nil)) (.toBe :anthropic))))

                                    (it "keeps an anthropic entry on the Messages API regardless of endpoints"
                                        (fn []
          ;; Claude ids advertise [anthropic, openai]; caching only survives the
          ;; former, so the entry's api must win here.
                                          (-> (expect (relay/pick-protocol {:api "anthropic"} ["anthropic" "openai"]))
                                              (.toBe :anthropic)))))))

(describe "relay preset filtering against a real catalogue" (fn []
                                                              (let [ids-kept (fn [entry models]
                                                                               (let [p (mf/make-filter entry)]
                                                                                 (mapv :id (filterv p models))))
        ;; A representative slice of the live response.
                                                                    catalogue [{:id "claude-opus-5"          :endpoints ["anthropic" "openai"]}
                                                                               {:id "claude-fable-5"         :endpoints ["anthropic" "openai"]}
                                                                               {:id "gpt-5.2"                :endpoints ["openai" "openai-response"]}
                                                                               {:id "gpt-5.4"                :endpoints ["openai-response"]}
                                                                               {:id "glm-4.7"                :endpoints ["openai"]}
                                                                               {:id "gpt-5.6-terra-ultra"    :endpoints []}
                                                                               {:id "gpt-5-all"              :endpoints []}
                                                                               {:id "BAAI/bge-reranker-v2-m3" :endpoints ["rerank"]}
                                                                               {:id "mj_inpaint"             :endpoints ["mj动作"]}]]

                                                                (it "the yunwu preset keeps chat and responses models, drops the rest"
                                                                    (fn []
                                                                      (let [kept (ids-kept {:endpoint-types ["openai" "openai-response"]
                                                                                            :exclude ["claude"]}
                                                                                           catalogue)]
                                                                        (-> (expect kept) (.toEqual #js ["gpt-5.2" "gpt-5.4" "glm-4.7"])))))

                                                                (it "drops entries that declare no endpoint at all"
                                                                    (fn []
          ;; gpt-5-all and gpt-5.6-terra-ultra are listed but unusable.
                                                                      (let [kept (ids-kept {:endpoint-types ["openai" "openai-response"]} catalogue)]
                                                                        (-> (expect (some #{"gpt-5-all"} kept)) (.toBeUndefined)))))

                                                                (it "the yunwu-claude preset keeps exactly the anthropic-capable models"
                                                                    (fn []
                                                                      (let [kept (ids-kept {:endpoint-types ["anthropic"]} catalogue)]
                                                                        (-> (expect kept) (.toEqual #js ["claude-opus-5" "claude-fable-5"])))))

                                                                (it "keeps claude models off the openai variant, where caching would die"
                                                                    (fn []
                                                                      (let [kept (ids-kept {:endpoint-types ["openai" "openai-response"]
                                                                                            :exclude ["claude"]}
                                                                                           catalogue)]
                                                                        (-> (expect (some (fn [i] (.includes i "claude")) kept)) (.toBeUndefined))))))))

(defn ^:async test-discovered-endpoints-drive-protocol []
  (temp-home!)
  (allow-discovery!)
  (aset js/process.env "YUNWU_API_KEY" "sk-test")
  (aset js/globalThis "fetch"
        (fn [_url _opts]
          (js/Response. (js/JSON.stringify
                         #js {:data #js [#js {:id "gpt-5.2"
                                              :supported_endpoint_types #js ["openai" "openai-response"]}
                                         #js {:id "gpt-5.4"
                                              :supported_endpoint_types #js ["openai-response"]}]})
                        #js {:status 200
                             :headers #js {"content-type" "application/json"}})))
  (let [agent   (create-agent {:model "test" :system-prompt "x"})
        api     (create-extension-api agent "relay")
        cleanup ((aget relay "default") api)
        resolve-model (fn [id] ((:resolve (:provider-registry agent)) "yunwu" id))]
    (js-await (js/Promise. (fn [res] (js/setTimeout res 50))))
    ;; The endpoint types discovered at runtime, not the entry's `api`, decide
    ;; which wire protocol each model speaks.
    (-> (expect (.-provider (resolve-model "gpt-5.2"))) (.toBe "openai.chat"))
    (-> (expect (.-provider (resolve-model "gpt-5.4"))) (.toBe "openai.responses"))
    (cleanup)))

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
                                  test-discovery-off-by-default-in-tests)
                              (it "discovered endpoint types decide each model's wire protocol"
                                  test-discovered-endpoints-drive-protocol)))

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

;; ── Catalogs that carry windows and prices ───────────────────
;;
;; New API says nothing but ids, so relay used to invent nothing and every
;; discovered model fell to the 100k default. Velona's gateway surface and
;; OpenRouter's both publish real numbers; these fixtures are trimmed from live
;; responses, and the two pricing dialects below are the whole reason
;; `entry-cost` reads key names rather than guessing from magnitude.

(def ^:private velona-payload
  #js {:data #js {:models #js [#js {:id "qwen/qwen3.8-27b"
                                    :name "Qwen: Qwen3.8 27B"
                                    :type "text"
                                    :context_window 1000000
                                    :capabilities #js ["streaming" "text"]
                                    :pricing #js {:input_per_1m_usd 0.45
                                                  :output_per_1m_usd 3.2}}
                               #js {:id "poolside/laguna-s-2.1:free"
                                    :name "Poolside: Laguna S 2.1 (free)"
                                    :type "text"
                                    :context_window 262144
                                    :pricing #js {:input_per_1m_usd 0.0
                                                  :output_per_1m_usd 0.0}}
                               #js {:id "google/lyria-3-pro-preview"
                                    :name "Lyria 3 Pro"
                                    :type "image"
                                    :context_window 8192}]}})

(def ^:private openrouter-payload
  #js {:data #js [#js {:id "z-ai/glm-5.3"
                       :name "Z.AI: GLM 5.3"
                       :context_length 1048576
                       ;; USD per TOKEN, as strings.
                       :pricing #js {:prompt "0.0000015" :completion "0.000006"}}]})

(describe "model-fetch/parse-models — richer catalogues" (fn []

  (it "unwraps Velona's data.models and reads window + USD/1M pricing"
      (fn []
        (let [ms (mf/parse-models velona-payload)
              m  (first ms)]
          (-> (expect (count ms)) (.toBe 3))
          (-> (expect (:id m)) (.toBe "qwen/qwen3.8-27b"))
          (-> (expect (:context-window m)) (.toBe 1000000))
          (-> (expect (:cost m)) (.toEqual #js {:input 0.45 :output 3.2}))
          (-> (expect (:type m)) (.toBe "text")))))

  (it "keeps a declared price of zero rather than dropping it"
      (fn []
        ;; 0 is a real rate, and `or`-chains have eaten it before.
        (-> (expect (:cost (second (mf/parse-models velona-payload))))
            (.toEqual #js {:input 0 :output 0}))))

  (it "scales OpenRouter's per-token string pricing to USD per 1M"
      (fn []
        (let [m (first (mf/parse-models openrouter-payload))]
          (-> (expect (:context-window m)) (.toBe 1048576))
          ;; "0.0000015"/token = $1.50 per 1M. A missing x1e6 or a missing
          ;; parseFloat both show up right here, as money.
          (-> (expect (:input (:cost m))) (.toBeCloseTo 1.5 6))
          (-> (expect (:output (:cost m))) (.toBeCloseTo 6.0 6)))))

  (it "leaves the bare OpenAI shape exactly as it was"
      (fn []
        ;; New API, and Velona's OWN /v1/models, carry ids and nothing else.
        ;; The change has to be purely additive for them.
        (let [m (first (mf/parse-models
                        #js {:data #js [#js {:id "a" :object "model" :owned_by "x"}]}))]
          (-> (expect (:id m)) (.toBe "a"))
          (-> (expect (:name m)) (.toBe "a"))
          (-> (expect (contains? m :context-window)) (.toBe false))
          (-> (expect (contains? m :cost)) (.toBe false)))))

  (it "ignores a negative price sentinel"
      (fn []
        ;; OpenRouter publishes "-1" for variable/auto-routed pricing. Scaled
        ;; up that is -$1,000,000 per 1M — and under merge-declared it would
        ;; beat a hand-declared cost and show as a negative dollar rate.
        (-> (expect (contains? (first (mf/parse-models
                                       #js {:data #js [#js {:id "a" :pricing #js {:prompt "-1" :completion "-1"}}]}))
                               :cost))
            (.toBe false))))

  (it "drops a half-priced entry rather than reporting half a price"
      (fn []
        (-> (expect (contains? (first (mf/parse-models
                                       #js {:data #js [#js {:id "a" :pricing #js {:prompt "0.000001"}}]}))
                               :cost))
            (.toBe false))))))

(describe "model-fetch/make-filter — :paid-only" (fn []

  (it "drops a model the catalog prices at zero on both sides"
      (fn []
        ;; Velona serves its free tier ONLY from the native inference/run
        ;; surface; /v1 answers a zero-priced id with model_not_supported.
        ;; Listing them offers models that cannot run.
        (let [p (mf/make-filter {:paid-only true})]
          (-> (expect (p {:id "z-ai/glm-5.2:free" :cost {:input 0 :output 0}})) (.toBe false))
          ;; The marker is the price, not the suffix — several free ids carry none.
          (-> (expect (p {:id "stealth/ox-alpha" :cost {:input 0 :output 0}})) (.toBe false))
          (-> (expect (p {:id "qwen/qwen3.8-27b" :cost {:input 0.45 :output 3.2}})) (.toBe true)))))

  (it "keeps a model whose price is unknown"
      (fn []
        ;; Same absence rule as :types — a gateway reporting no pricing must
        ;; not be filtered down to nothing.
        (-> (expect ((mf/make-filter {:paid-only true}) {:id "a"})) (.toBe true))))

  (it "filters nothing when :paid-only is absent"
      (fn []
        (-> (expect ((mf/make-filter {}) {:id "a" :cost {:input 0 :output 0}})) (.toBe true))))))

(describe "model-fetch/make-filter — :types" (fn []

  (it "keeps only the declared types when asked"
      (fn []
        (let [p  (mf/make-filter {:types ["text"]})
              ms (mf/parse-models velona-payload)]
          (-> (expect (mapv :id (filterv p ms)))
              (.toEqual #js ["qwen/qwen3.8-27b" "poolside/laguna-s-2.1:free"])))))

  (it "keeps a model that declares no type at all"
      (fn []
        ;; Same rule as :endpoint-types — a gateway that doesn't report the
        ;; field must not be filtered down to nothing.
        (-> (expect ((mf/make-filter {:types ["text"]}) {:id "a"})) (.toBe true))))

  (it "filters nothing when :types is absent"
      (fn []
        (-> (expect ((mf/make-filter {}) {:id "a" :type "image"})) (.toBe true))))))

(describe "model-fetch cache — fields discovery learns to carry" (fn []
  (afterEach restore!)

  (it "round-trips a window, a cost and a type"
      (fn []
        ;; read-cache is a whitelist. A field written but not read back is
        ;; right on run one and wrong on every run after — and the cache is
        ;; what a session reads before any refresh lands.
        (temp-home!)
        (mf/write-cache! "g" [{:id "a" :name "A" :context-window 262144
                               :cost {:input 0.45 :output 3.2} :type "text"}])
        (let [m (first (:models (mf/read-cache "g")))]
          (-> (expect (:context-window m)) (.toBe 262144))
          (-> (expect (:cost m)) (.toEqual #js {:input 0.45 :output 3.2}))
          (-> (expect (:type m)) (.toBe "text")))))))

;; ── catalogUrl override ──────────────────────────────────────

(defn ^:async test-catalog-url-same-origin []
  (temp-home!)
  (let [seen (atom nil)]
    (stub-fetch! (fn [url opts]
                   (reset! seen {:url url :opts opts})
                   (js/Response. (js/JSON.stringify velona-payload)
                                 #js {:status 200
                                      :headers #js {"content-type" "application/json"}})))
    (let [ms (js-await (mf/fetch-models "https://velona.in/v1" "sk-1"
                                        "https://velona.in/gateway/v1/models"))]
      (-> (expect (:url @seen)) (.toBe "https://velona.in/gateway/v1/models"))
      ;; Same origin as the provider, so the key still travels.
      (-> (expect (aget (.-headers (:opts @seen)) "Authorization")) (.toBe "Bearer sk-1"))
      (-> (expect (:context-window (first ms))) (.toBe 1000000)))))

(defn ^:async test-catalog-url-off-origin-refused []
  (temp-home!)
  (let [called (atom 0)]
    (stub-fetch! (fn [_url _opts]
                   (swap! called inc)
                   (js/Response. (js/JSON.stringify
                                  #js {:data #js [#js {:id "claude-opus-5" :context_length 4096}]})
                                 #js {:status 200
                                      :headers #js {"content-type" "application/json"}})))
    ;; catalogUrl comes from settings, so an off-origin one is two problems:
    ;; it would be handed this provider's key, AND whatever it returns would be
    ;; registered as this provider's windows and prices. Withholding the key
    ;; closes only the first. Refuse the request outright.
    (-> (expect (js-await (mf/fetch-models "https://velona.in/v1" "sk-1"
                                           "https://evil.test/gateway/v1/models")))
        (.toBeNil))
    (-> (expect @called) (.toBe 0))))

(defn ^:async test-catalog-url-still-refuses-redirect []
  (temp-home!)
  (stub-fetch! (fn [_url _opts] (js/Response. "" #js {:status 302})))
  (-> (expect (js-await (mf/fetch-models "https://velona.in/v1" "sk-1"
                                         "https://velona.in/gateway/v1/models")))
      (.toBeNil)))

(describe "model-fetch/fetch-models — catalogUrl" (fn []
  (afterEach restore!)
  (it "fetches the override verbatim and keeps the key on-origin" test-catalog-url-same-origin)
  (it "refuses an off-origin catalog outright" test-catalog-url-off-origin-refused)
  (it "still refuses a redirect on the override path" test-catalog-url-still-refuses-redirect)))

;; ── merge-declared precedence ────────────────────────────────

(describe "relay/merge-declared" (fn []

  (it "lets a discovered window beat a declared one"
      (fn []
        ;; Otherwise the handful of ids anyone bothers to declare — exactly the
        ;; ids they use most — stay pinned to a hand-typed number forever.
        (let [got (relay/merge-declared
                   [{:id "a" :name "A" :context-window 1000000}]
                   {"a" {:id "a" :context-window 32768}})]
          (-> (expect (:context-window (first got))) (.toBe 1000000)))))

  (it "keeps a declared window when discovery supplies none"
      (fn []
        ;; The yunwu case: New API reports no size, so the key is absent and
        ;; the settings entry is the only source of a real number.
        (let [got (relay/merge-declared
                   [{:id "a" :name "A"}]
                   {"a" {:id "a" :context-window 200000 :cost {:input 1 :output 2}}})]
          (-> (expect (:context-window (first got))) (.toBe 200000))
          (-> (expect (:cost (first got))) (.toEqual #js {:input 1 :output 2})))))

  (it "never lets a declared name override the discovered one"
      (fn []
        (let [got (relay/merge-declared [{:id "a" :name "Discovered"}]
                                        {"a" {:id "a" :name "Declared"}})]
          (-> (expect (:name (first got))) (.toBe "Discovered")))))))

;; ── The velona preset ────────────────────────────────────────

(describe "relay velona preset" (fn []
  (afterEach restore!)

  (it "registers as a gateway, so its prices stay its own"
      (fn []
        (temp-home!)
        (let [agent (create-agent {:model "test" :system-prompt "test"})
              api   (create-extension-api agent)
              _     ((.-default relay) api)
              entry ((:get (:provider-registry agent)) "velona")]
          (-> (expect (some? entry)) (.toBe true))
          (-> (expect (fn? (:create-model entry))) (.toBe true))
          ;; Velona carries other vendors' ids. Without this, a velona model's
          ;; cost falls back to the first-party rate the user is not paying.
          (-> (expect (contains? @pricing/unpriced-providers "velona")) (.toBe true)))))

  (it "seeds real context windows, not the 100k default"
      (fn []
        (temp-home!)
        (let [agent (create-agent {:model "test" :system-prompt "test"})
              api   (create-extension-api agent)
              _     ((.-default relay) api)]
          ;; Two slashes: `velona` + `qwen/qwen3.8-27b`. The qualified key is
          ;; the only one a gateway writes.
          (-> (expect ((:context-window (:model-registry agent)) "velona/qwen/qwen3.8-27b"))
              (.toBe 1000000)))))

  (it "points discovery at the surface that actually carries the numbers"
      (fn []
        ;; velona.in/v1/models lists ids and nothing else; the gateway surface
        ;; carries windows and prices. Same origin, so the key still goes.
        (let [v (first (filterv (fn [e] (= "velona" (:name e))) relay/presets))]
          (-> (expect (:catalog-url v)) (.toBe "https://velona.in/gateway/v1/models"))
          (-> (expect (mf/same-origin? (:catalog-url v) (:base-url v))) (.toBe true))
          (-> (expect (:types v)) (.toEqual #js ["text"])))))

  (it "offers no model that /v1 refuses to serve"
      (fn []
        ;; Velona's free tier 404s on the OpenAI surface. A seed entry that
        ;; cannot run is a broken suggestion in the picker.
        (let [v    (first (filterv (fn [e] (= "velona" (:name e))) relay/presets))
              free (filterv (fn [m] (= 0 (:input (:cost m)))) (:models v))]
          (-> (expect (:paid-only v)) (.toBe true))
          (-> (expect (count free)) (.toBe 0)))))

  (it "keeps the id that reasons about tools and then calls none"
      (fn []
        ;; qwen/qwen3.6-35b-a3b ends its turn with finish_reason=stop, no
        ;; tool_calls, no content. Measured, and the reason the user saw an
        ;; empty bubble. Excluded rather than shipped as an agent model.
        (let [v (first (filterv (fn [e] (= "velona" (:name e))) relay/presets))
              p (mf/make-filter v)]
          (-> (expect (p {:id "qwen/qwen3.6-35b-a3b" :type "text" :cost {:input 0.14 :output 1.0}}))
              (.toBe false))
          (-> (expect (p {:id "qwen/qwen3.8-27b" :type "text" :cost {:input 0.45 :output 3.2}}))
              (.toBe true)))))))

(describe "relay/normalize-entry — catalogUrl and types" (fn []

  (it "reads both spellings from a settings entry"
      (fn []
        (let [e (relay/normalize-entry #js {"name" "v"
                                            "catalogUrl" "https://x.test/c"
                                            "types" #js ["text"]})]
          (-> (expect (:catalog-url e)) (.toBe "https://x.test/c"))
          (-> (expect (:types e)) (.toEqual #js ["text"])))
        (let [e (relay/normalize-entry #js {"name" "v" "catalog-url" "https://y.test/c"})]
          (-> (expect (:catalog-url e)) (.toBe "https://y.test/c")))))))

(describe "relay/filtered-out-warning" (fn []

  (it "explains an explicitly requested id that the catalog filter drops"
      (fn []
        ;; The filter shapes the model LIST only. `--model velona/<id>` goes
        ;; straight to create-model, so an excluded id still runs — and the
        ;; reason it was excluded is precisely that it fails silently.
        (let [v (first (filterv (fn [e] (= "velona" (:name e))) relay/presets))
              w (relay/filtered-out-warning v "qwen/qwen3.6-35b-a3b")]
          (-> (expect (some? w)) (.toBe true))
          (-> (expect w) (.toContain "excluded"))
          (-> (expect w) (.toContain "velona")))))

  (it "says nothing about a model the provider does list"
      (fn []
        (let [v (first (filterv (fn [e] (= "velona" (:name e))) relay/presets))]
          (-> (expect (relay/filtered-out-warning v "qwen/qwen3.8-27b")) (.toBeNil))
          (-> (expect (relay/filtered-out-warning v "z-ai/glm-5.3")) (.toBeNil)))))

  (it "says nothing for an entry with no filters at all"
      (fn []
        (-> (expect (relay/filtered-out-warning {:name "plain"} "anything")) (.toBeNil))))))
