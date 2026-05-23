(ns agent.extensions.custom-provider-minimax.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private provider-name "minimax")
(def ^:private default-base-url "https://api.minimax.io/v1")

;; All MiniMax M2.x share 204,800-token context and are reasoning-capable
;; via the `reasoning_split` extra_body parameter. Highspeed variants run at
;; 100 tps vs 60 tps for standard variants.
(def ^:private models
  [{:id "MiniMax-M2"             :name "MiniMax M2"             :ctx 204800}
   {:id "MiniMax-M2.1"           :name "MiniMax M2.1"           :ctx 204800}
   {:id "MiniMax-M2.1-highspeed" :name "MiniMax M2.1 Highspeed" :ctx 204800}
   {:id "MiniMax-M2.5"           :name "MiniMax M2.5"           :ctx 204800}
   {:id "MiniMax-M2.5-highspeed" :name "MiniMax M2.5 Highspeed" :ctx 204800}
   {:id "MiniMax-M2.7"           :name "MiniMax M2.7"           :ctx 204800}
   {:id "MiniMax-M2.7-highspeed" :name "MiniMax M2.7 Highspeed" :ctx 204800}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx
       :reasoning     true})

(defn resolve-base-url []
  (or (aget js/process.env "MINIMAX_BASE_URL") default-base-url))

(defn- read-credentials-file
  "Read ~/.nyma/credentials.json and return the stored key for this provider.
   The built-in resolve-api-key in registry.cljs only checks env vars, not
   credentials.json — this makes /login minimax actually work without touching
   core code."
  []
  (let [home      (.. js/process -env -HOME)
        cred-path (path/join home ".nyma" "credentials.json")]
    (when (and home (fs/existsSync cred-path))
      (try
        (let [raw    (fs/readFileSync cred-path "utf8")
              parsed (js/JSON.parse raw)]
          (aget parsed provider-name))
        (catch :default _ nil)))))

(defn resolve-api-key []
  (or (aget js/process.env "MINIMAX_API_KEY")
      (read-credentials-file)))

;; ── reasoning_split fetch wrapper ──────────────────────────────
;;
;; MiniMax M2.x emits chain-of-thought via the `reasoning_split: true`
;; extra_body parameter on /v1/chat/completions. @ai-sdk/openai v3.x has
;; no `extraBody` option, so we wrap the fetch to splice the field in
;; just before the request goes out. When the field is present, MiniMax
;; returns reasoning in a separate channel that the AI SDK surfaces as
;; `reasoning-start`/`-delta`/`-end` events (already wired in loop.cljs).
;;
;; If splice fails (non-JSON body, malformed, etc.) we fall through to
;; the original request rather than break the call.

(defn splice-reasoning-split
  "Add `reasoning_split: true` to a JSON body string. Returns the
   modified string, or the original on parse failure."
  [body-str]
  (try
    (let [obj (js/JSON.parse body-str)]
      (when (object? obj)
        (aset obj "reasoning_split" true))
      (js/JSON.stringify obj))
    (catch :default _ body-str)))

(defn wrap-fetch-with-reasoning [base-fetch]
  (fn [url init]
    (let [;; init may be undefined for GET; only POSTs need the splice.
          method (and init (.-method init))
          body   (and init (.-body init))]
      (if (and (= method "POST") (string? body))
        (let [new-body (splice-reasoning-split body)
              new-init (js/Object.assign #js {} init #js {:body new-body})]
          (base-fetch url new-init))
        (base-fetch url init)))))

(defn- create-minimax-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No MiniMax credentials found. Set the MINIMAX_API_KEY "
                   "env var or run /login minimax to save a key. "
                   "Get a key at https://platform.minimax.io/"))))
    ;; .chat() forces the Chat Completions endpoint (/v1/chat/completions).
    ;; Calling the provider directly now routes to the Responses API
    ;; (/v1/responses) which MiniMax does not implement — 404.
    ;;
    ;; The custom :fetch hook splices `reasoning_split: true` into every
    ;; outbound request body so MiniMax emits the chain-of-thought
    ;; channel; without it the model produces only the final answer.
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)
                              :fetch   (wrap-fetch-with-reasoning js/fetch)})
           id)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-minimax-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "MINIMAX_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  ;; Return cleanup function
  (fn []
    (.unregisterProvider api provider-name)))
