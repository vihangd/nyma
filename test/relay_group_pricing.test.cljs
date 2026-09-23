(ns relay-group-pricing.test
  "New API billing groups. `Kiro-Claude-1` and `Codex-Gpt-1` on openlux are
   not models: they are upstream routes with a price multiplier, bound to the
   TOKEN when it is minted. So a group is a provider entry with its own key,
   and its prices come from the relay's public pricing sheet — the one place
   the group's rates are stated."
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

(defn- temp-home! []
  (aset js/process.env "HOME" (fs/mkdtempSync (path/join (os/tmpdir) "nyma-relay-group-"))))

(defn- restore! []
  (aset js/process.env "HOME" real-home)
  (aset js/globalThis "fetch" real-fetch)
  (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" "1")
  (js-delete js/process.env "OPENLUX_KIRO_API_KEY")
  nil)

;; A slice of the live sheet (2026-09-23): the ratios are the real ones.
(def ^:private sheet
  #js {:group_ratio #js {"Kiro-Claude-1" 0.08824 "Codex-Gpt-1" 0.03677 "Anthropic-Claude-1" 1.1}
       :data #js [#js {:model_name "claude-sonnet-5" :quota_type 0 :available true
                       :model_ratio 1 :completion_ratio 5 :cache_ratio 0.1 :cache_creation_5m_ratio 1.25
                       :enable_groups #js ["Anthropic-Claude-1" "Kiro-Claude-1"]}
                  #js {:model_name "claude-haiku-4-5-20251001" :quota_type 0 :available true
                       :model_ratio 0.5 :completion_ratio 5 :cache_ratio 0.1 :cache_creation_ratio 1.25
                       :enable_groups #js ["Kiro-Claude-1"]}
                  ;; official route only — a Kiro token is not served this one
                  #js {:model_name "claude-opus-4-1" :quota_type 0 :available true
                       :model_ratio 7.5 :completion_ratio 5
                       :enable_groups #js ["Anthropic-Claude-1"]}
                  ;; per-call image row: not token-priced
                  #js {:model_name "gpt-image-2" :quota_type 1 :model_price 0.12 :model_ratio 0 :completion_ratio 0
                       :enable_groups #js ["Kiro-Claude-1"]}
                  ;; flagged unavailable
                  #js {:model_name "claude-dead" :quota_type 0 :available false :model_ratio 1 :completion_ratio 5
                       :enable_groups #js ["Kiro-Claude-1"]}
                  #js {:model_name "gpt-5.6-terra" :quota_type 0 :available true
                       :model_ratio 1 :completion_ratio 6 :cache_ratio 0.1
                       :enable_groups #js ["Codex-Gpt-1"]}]})

(describe "model-fetch:group-costs" (fn []
                                      (it "prices a group's models from the ratios: input = model × group × 2"
                                          (fn []
                                            (let [c (mf/group-costs sheet "Kiro-Claude-1")
                                                  s (get c "claude-sonnet-5")]
                                              (-> (expect (:input s)) (.toBeCloseTo 0.17648 5))
                                              (-> (expect (:output s)) (.toBeCloseTo 0.8824 4))
                                              (-> (expect (:cache-read s)) (.toBeCloseTo 0.017648 6))
                                              (-> (expect (:cache-write s)) (.toBeCloseTo 0.2206 4))
          ;; the older cache_creation_ratio spelling works too
                                              (-> (expect (:cache-write (get c "claude-haiku-4-5-20251001"))) (.toBeCloseTo 0.1103 4)))))

                                      (it "keeps only the rows the group serves, token-priced and available"
                                          (fn []
                                            (let [c (mf/group-costs sheet "Kiro-Claude-1")]
                                              (-> (expect (sort (keys c))) (.toEqual (clj->js ["claude-haiku-4-5-20251001" "claude-sonnet-5"])))
                                              (-> (expect (contains? c "claude-opus-4-1")) (.toBe false))
                                              (-> (expect (contains? c "gpt-image-2")) (.toBe false))
                                              (-> (expect (contains? c "claude-dead")) (.toBe false)))))

                                      (it "another group, another price for what it serves"
                                          (fn []
                                            (let [c (mf/group-costs sheet "Codex-Gpt-1")]
                                              (-> (expect (sort (keys c))) (.toEqual (clj->js ["gpt-5.6-terra"])))
                                              (-> (expect (:input (get c "gpt-5.6-terra"))) (.toBeCloseTo 0.07354 5))
                                              (-> (expect (:cache-write (get c "gpt-5.6-terra"))) (.toBeUndefined)))))

                                      (it "nil when the sheet does not know the group, or is missing"
                                          (fn []
                                            (-> (expect (mf/group-costs sheet "No-Such-Group")) (.toBeNil))
                                            (-> (expect (mf/group-costs nil "Kiro-Claude-1")) (.toBeNil))
                                            (-> (expect (mf/group-costs #js {:data "nope"} "Kiro-Claude-1")) (.toBeNil))))

                                      (it "pricing-url is the origin's /api/pricing, not under /v1"
                                          (fn []
                                            (-> (expect (mf/pricing-url "https://api.openlux.ai/v1")) (.toBe "https://api.openlux.ai/api/pricing"))
                                            (-> (expect (mf/pricing-url "https://g.test/v1/")) (.toBe "https://g.test/api/pricing"))
                                            (-> (expect (mf/pricing-url "not a url")) (.toBeNil))))

  (it "pricing-url keeps a mount prefix: only the version segment comes off"
      (fn []
        (-> (expect (mf/pricing-url "https://gw.example.com/newapi/v1")) (.toBe "https://gw.example.com/newapi/api/pricing"))
        (-> (expect (mf/pricing-url "https://gw.example.com/newapi/v2/")) (.toBe "https://gw.example.com/newapi/api/pricing"))))

  (it "a null row in the sheet or the catalogue is skipped, not thrown on"
      (fn []
        (let [bad #js {:group_ratio #js {"G" 1} :data #js [nil #js {:model_name "m" :quota_type 0 :model_ratio 1 :completion_ratio 2 :enable_groups #js ["G"]}]}]
          (-> (expect (sort (keys (mf/group-costs bad "G")))) (.toEqual (clj->js ["m"]))))
        (-> (expect (mapv :id (mf/parse-models #js {:data #js [nil #js {:id "x"}]}))) (.toEqual (clj->js ["x"])))))

  (it "the cache key carries the group, so changing it is a miss not a stale hit"
      (fn []
        (-> (expect (relay/cache-key {:name "openlux-kiro" :group "Kiro-Claude-1"})) (.toBe "openlux-kiro@Kiro-Claude-1"))
        (-> (expect (relay/cache-key {:name "openlux"})) (.toBe "openlux"))))))

(describe "relay:group presets" (fn []
                                  (it "ship openlux-kiro and openlux-codex on their own credentials"
                                      (fn []
                                        (let [by-name (into {} (map (fn [p] [(:name p) p]) relay/presets))
                                              kiro    (get by-name "openlux-kiro")
                                              codex   (get by-name "openlux-codex")]
                                          (-> (expect (:group kiro)) (.toBe "Kiro-Claude-1"))
                                          (-> (expect (:group codex)) (.toBe "Codex-Gpt-1"))
          ;; a shared openlux token would be a token in the wrong group
                                          (doseq [p [kiro codex]]
                                            (-> (expect (:credential-name p)) (.toBeUndefined))
                                            (-> (expect (seq (:credential-fallbacks p))) (.toBeFalsy))
                                            (-> (expect (:api-key-env p)) (.not.toBe "OPENLUX_API_KEY"))))))

                                  (it "normalize-entry reads group in both casings, blank means none"
                                      (fn []
                                        (-> (expect (:group (relay/normalize-entry #js {:name "g" :group "Kiro-Claude-1"}))) (.toBe "Kiro-Claude-1"))
                                        (-> (expect (:group (relay/normalize-entry #js {"name" "g" "group" ""}))) (.toBeNil))
                                        (-> (expect (:group (relay/normalize-entry #js {:name "g"}))) (.toBeNil))))))

(defn- boot []
  (let [agent (create-agent {:model "test" :system-prompt "x"})
        api   (create-extension-api agent "relay")]
    {:agent agent :api api :cleanup ((aget relay "default") api)}))

(defn- specs [agent]
  (set (map :spec (catalog/list-all-models
                   ((:list (:provider-registry agent)))
                   (:context-window (:model-registry agent))))))

(defn- settle [] (js/Promise. (fn [res] (js/setTimeout res 60))))

(defn- json-response [body]
  (js/Response. (js/JSON.stringify body)
                #js {:status 200 :headers #js {"content-type" "application/json"}}))

(defn- stub-fetch!
  "Route by URL: the catalogue and the pricing sheet are different endpoints."
  [models-body pricing-body]
  (aset js/globalThis "fetch"
        (fn [url _opts]
          (let [u (str url)]
            (cond
              (.includes u "/api/pricing") (if pricing-body
                                             (json-response pricing-body)
                                             (js/Response. "" #js {:status 500}))
              :else (json-response models-body))))))

(defn ^:async test-group-discovery-prices-and-narrows []
  (temp-home!)
  (js-delete js/process.env "NYMA_NO_MODEL_DISCOVERY")
  (aset js/process.env "OPENLUX_KIRO_API_KEY" "sk-kiro")
  ;; A group token's /v1/models lists the WHOLE catalogue…
  (stub-fetch! #js {:data #js [#js {:id "claude-sonnet-5" :supported_endpoint_types #js ["anthropic" "openai"]}
                               #js {:id "claude-haiku-4-5-20251001" :supported_endpoint_types #js ["anthropic" "openai"]}
                               #js {:id "claude-opus-4-1" :supported_endpoint_types #js ["anthropic" "openai"]}]}
               sheet)
  (let [{:keys [agent cleanup]} (boot)]
    ;; …seeds are priced before any network call
    (-> (expect (pricing/lookup-cost "openlux-kiro/claude-sonnet-5")) (.toEqual (clj->js [0.176 0.882 0.018 0.221])))
    (js-await (settle))
    ;; the sheet narrows the list to what the group serves
    (-> (expect (contains? (specs agent) "openlux-kiro/claude-haiku-4-5-20251001")) (.toBe true))
    (-> (expect (contains? (specs agent) "openlux-kiro/claude-opus-4-1")) (.toBe false))
    ;; and prices it — discovery wins over the seed's hand-typed number
    (let [[i o cr cw] (pricing/lookup-cost "openlux-kiro/claude-sonnet-5")]
      (-> (expect i) (.toBeCloseTo 0.17648 5))
      (-> (expect o) (.toBeCloseTo 0.8824 4))
      (-> (expect cr) (.toBeCloseTo 0.017648 6))
      (-> (expect cw) (.toBeCloseTo 0.2206 4)))
    ;; the cache carries the price, so the next start reads it before refresh
    (let [cached (:models (mf/read-cache "openlux-kiro@Kiro-Claude-1"))
          s      (first (filter #(= "claude-sonnet-5" (:id %)) cached))]
      (-> (expect (:input (:cost s))) (.toBeCloseTo 0.17648 5))
      (-> (expect (:cache-read (:cost s))) (.toBeCloseTo 0.017648 6)))
    (cleanup)))

(defn ^:async test-group-without-sheet-degrades-to-plain-discovery []
  (temp-home!)
  (js-delete js/process.env "NYMA_NO_MODEL_DISCOVERY")
  (aset js/process.env "OPENLUX_KIRO_API_KEY" "sk-kiro")
  (stub-fetch! #js {:data #js [#js {:id "claude-sonnet-5" :supported_endpoint_types #js ["anthropic"]}
                               #js {:id "claude-opus-4-1" :supported_endpoint_types #js ["anthropic"]}]}
               nil)
  (let [{:keys [agent cleanup]} (boot)]
    (js-await (settle))
    ;; no sheet → whole (endpoint-filtered) catalogue, seed prices only
    (-> (expect (contains? (specs agent) "openlux-kiro/claude-opus-4-1")) (.toBe true))
    (-> (expect (pricing/lookup-cost "openlux-kiro/claude-sonnet-5")) (.toEqual (clj->js [0.176 0.882 0.018 0.221])))
    (-> (expect (pricing/lookup-cost "openlux-kiro/claude-opus-4-1")) (.toBeNil))
    (cleanup)))

(describe "relay:group discovery" (fn []
                                    (beforeEach (fn [] (reset! pricing/unpriced-providers #{}) nil))
                                    (afterEach restore!)
                                    (it "prices the catalogue from the sheet and keeps only the group's models"
                                        test-group-discovery-prices-and-narrows)
                                    (it "a missing sheet degrades to ordinary discovery, never to nothing"
                                        test-group-without-sheet-degrades-to-plain-discovery)))
