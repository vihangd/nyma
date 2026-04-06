(ns agent.extensions.token-suite.kv-cache
  (:require [agent.extensions.token-suite.shared :as shared]))

(defn- split-at-stable-boundary
  "Split system prompt into stable (cacheable) and dynamic sections.
   Heuristic: look for the last major separator (---  or ## heading)
   in the first 80% of the text to find the stable/dynamic boundary."
  [system-text]
  (let [len        (count system-text)
        boundary   (js/Math.floor (* len 0.8))
        ;; Search backwards from 80% mark for a section separator
        search-text (.substring system-text 0 boundary)
        last-sep    (max (.lastIndexOf search-text "\n---\n")
                         (.lastIndexOf search-text "\n## "))
        split-idx   (if (> last-sep 100) (+ last-sep 1) boundary)]
    {:stable  (.substring system-text 0 split-idx)
     :dynamic (.substring system-text split-idx)}))

(defn activate [api]
  (let [config     (shared/load-config)
        kv-config  (:kv-cache config)
        prev-hash  (atom nil)]

    ;; before_provider_request — priority 100 (runs first)
    (.on api "before_provider_request"
      (fn [config-obj _ctx]
        (let [model    (.-model config-obj)
              model-id (str (or (when model (.-modelId model)) model ""))
              system   (.-system config-obj)]

          ;; Only for Claude models with string system prompts above threshold
          (when (and (shared/is-claude-model? model-id)
                     (string? system)
                     (:enabled kv-config)
                     (> (.estimateTokens api system) (:min-system-tokens kv-config)))

            (let [{:keys [stable dynamic]} (split-at-stable-boundary system)
                  ;; Fingerprint stable section to detect changes
                  hash (js/Bun.hash stable)
                  same-as-prev (= hash @prev-hash)]
              (reset! prev-hash hash)

              ;; Restructure system prompt into cache-annotated sections
              (aset config-obj "system"
                #js [#js {:role "system"
                          :content stable
                          :providerOptions
                          #js {:anthropic #js {:cacheControl #js {:type "ephemeral"}}}}
                     #js {:role "system"
                          :content dynamic}]))))

        ;; Return nil — mutations in place
        nil)
      100)

    ;; after_provider_request — track cache metrics
    (.on api "after_provider_request"
      (fn [event _ctx]
        (let [cached (.-cachedTokens event)]
          (swap! shared/suite-stats update :kv-cache
            (fn [s] (-> s
                        (update :turns inc)
                        (update :cached-tokens + (or cached 0))
                        (update :cache-hits + (if (and cached (pos? cached)) 1 0))))))))

    ;; Return deactivate
    (fn []
      (reset! prev-hash nil))))
