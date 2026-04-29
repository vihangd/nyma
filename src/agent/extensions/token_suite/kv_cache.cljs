(ns agent.extensions.token-suite.kv-cache
  "Prompt caching via cache_control breakpoints.

   Two layers:

   1. System-prompt split — splits the system at a stable/dynamic
      boundary (last ## heading or --- in the first 80%) and wraps the
      stable section with provider-specific cacheControl.

   2. Message-level breakpoints — places cache_control on the last
      complete assistant turn (anchor) and optionally every K turns
      back (checkpoint). This keeps multi-turn sessions inside
      Anthropic's 20-block lookback window so cache hits compound
      instead of degrading.

   Provider routing via shared/detect-cache-provider:
     - claude*, anthropic.*  → providerOptions.anthropic.cacheControl
     - gemini*               → providerOptions.google.cacheControl
     - extras via settings    → e.g. minimax via Anthropic-compat

   Anthropic enforces a 4-breakpoint limit per request:
     slot 1: system stable section
     slot 2: last stable assistant turn (anchor)
     slot 3: N-back checkpoint
     slot 4: reserved for future (tool-block caching)"
  (:require [agent.extensions.token-suite.shared :as shared]))

(defn- split-at-stable-boundary
  "Split system prompt into stable (cacheable) and dynamic sections.
   Heuristic: look for the last major separator (---  or ## heading)
   in the first 80% of the text to find the stable/dynamic boundary."
  [system-text]
  (let [len         (count system-text)
        boundary    (js/Math.floor (* len 0.8))
        search-text (.substring system-text 0 boundary)
        last-sep    (max (.lastIndexOf search-text "\n---\n")
                         (.lastIndexOf search-text "\n## "))
        split-idx   (if (> last-sep 100) (+ last-sep 1) boundary)]
    {:stable  (.substring system-text 0 split-idx)
     :dynamic (.substring system-text split-idx)}))

(defn- provider-key
  "Returns the JS key for providerOptions for the given provider keyword."
  [provider]
  (case provider :anthropic "anthropic" :google "google" nil))

(defn- annotate-message!
  "Mutate a JS message in place: add providerOptions.<provider>.cacheControl
   = {type: 'ephemeral'}. Preserves any existing providerOptions for other
   providers."
  [msg provider]
  (when-let [pkey (provider-key provider)]
    (let [existing (or (.-providerOptions msg) #js {})
          ppart    (or (aget existing pkey) #js {})]
      (aset ppart "cacheControl" #js {:type "ephemeral"})
      (aset existing pkey ppart)
      (aset msg "providerOptions" existing))))

(defn- assistant-msg?
  "True when the JS message is an assistant turn with non-empty content.
   Excludes streaming-empty turns and orphaned tool_use messages."
  [msg]
  (and (= "assistant" (.-role msg))
       (let [c (.-content msg)]
         (cond
           (string? c)            (pos? (count c))
           (js/Array.isArray c)   (pos? (.-length c))
           :else                  (some? c)))))

(defn- find-last-stable-anchor
  "Walk messages from the end, return index of the last stable assistant
   turn. Skips empty/streaming messages. Returns nil if none found."
  [messages]
  (let [n (.-length messages)]
    (loop [i (dec n)]
      (cond
        (< i 0)                          nil
        (assistant-msg? (aget messages i)) i
        :else                            (recur (dec i))))))

(defn- find-checkpoint-indices
  "From the anchor going back, every `every-turns` assistant turns place
   a checkpoint. Returns up to `max-checkpoints` indices, ordered nearest-to-anchor first."
  [messages anchor-idx every-turns max-checkpoints]
  (if (or (nil? anchor-idx) (nil? every-turns) (<= every-turns 0) (<= max-checkpoints 0))
    []
    (loop [acc        []
           i          (dec anchor-idx)
           since-last 1]
      (cond
        (or (< i 0) (>= (count acc) max-checkpoints)) acc
        (assistant-msg? (aget messages i))
        (if (>= since-last every-turns)
          (recur (conj acc i) (dec i) 1)
          (recur acc (dec i) (inc since-last)))
        :else (recur acc (dec i) since-last)))))

(defn- annotate-messages!
  "Walk st-config.messages, place cache_control on anchor + checkpoints.
   Mutates the messages array in place. Returns the count of breakpoints
   placed (for stats / testing)."
  [config-obj provider every-turns max-message-breakpoints]
  (let [messages (.-messages config-obj)]
    (if (or (nil? messages) (zero? (.-length messages)))
      0
      (let [anchor (find-last-stable-anchor messages)
            ;; Reserve 1 slot for anchor; remainder for checkpoints.
            ckpt-budget (max 0 (dec max-message-breakpoints))
            checkpoints (find-checkpoint-indices messages anchor every-turns ckpt-budget)
            indices     (cond-> [] anchor (conj anchor) :always (into checkpoints))]
        (doseq [i indices]
          (annotate-message! (aget messages i) provider))
        (count indices)))))

(defn activate [api]
  (let [config            (shared/load-config)
        kv-config         (:kv-cache config)
        prev-hash         (atom nil)
        extra-providers   (or (:extra-providers kv-config) {})
        cache-messages?   (boolean (:cache-messages kv-config))
        every-turns       (or (:checkpoint-every-turns kv-config) 4)
        max-msg-breaks    (or (:max-message-breakpoints kv-config) 2)]

    ;; before_provider_request — priority 100 (runs last so we see the
    ;; final form of messages from any earlier mutators).
    (.on api "before_provider_request"
         (fn [config-obj _ctx]
           (let [model    (.-model config-obj)
                 model-id (str (or (when model (.-modelId model)) model ""))
                 provider (shared/detect-cache-provider model-id extra-providers)
                 system   (.-system config-obj)]

             (when (and provider (:enabled kv-config))

               ;; Layer 1: system prompt split (only when string + above threshold)
               (when (and (string? system)
                          (> (.estimateTokens api system) (:min-system-tokens kv-config)))
                 (let [{:keys [stable dynamic]} (split-at-stable-boundary system)
                       hash         (js/Bun.hash stable)
                       _same-as-prev (= hash @prev-hash)
                       pkey         (provider-key provider)]
                   (reset! prev-hash hash)
                   (aset config-obj "system"
                         #js [#js {:role "system"
                                   :content stable
                                   :providerOptions
                                   (doto #js {}
                                     (aset pkey #js {:cacheControl #js {:type "ephemeral"}}))}
                              #js {:role "system" :content dynamic}])))

               ;; Layer 2: message-level breakpoints (anchor + N-back checkpoint)
               (when cache-messages?
                 (annotate-messages! config-obj provider every-turns max-msg-breaks))))

           ;; Mutations in place — no return value
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
