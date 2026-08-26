(ns agent.extensions.custom-provider-local.index
  "Generic local-model provider — register any OpenAI-compatible local
   inference server (ollama, LM Studio, llama.cpp, vLLM, etc.) as a
   Nyma provider without writing code.

   Configuration in settings.json:
     {
       \"local-models\": [
         {
           \"name\":          \"ollama\",           // provider name: --model ollama/<id>
           \"baseUrl\":       \"http://localhost:11434/v1\",
           \"modelId\":       \"qwen2.5-coder\",    // default model for this provider
           \"contextWindow\": 32768,
           \"apiKeyEnv\":     \"OLLAMA_API_KEY\"    // optional; dummy key used if unset
         }
       ]
     }

   Built-in presets (always registered unless overridden or disabled):
     ollama   → http://localhost:11434/v1
     lmstudio → http://localhost:1234/v1

   Usage:  nyma --model ollama/qwen2.5-coder
           nyma --model lmstudio/devstral-small
  "
  (:require [agent.debug :as d]
            [agent.utils.js-interop :as ji]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.utils.credentials :as credentials]
            [agent.utils.toolcall-rescue :as adapter]
            [agent.providers.model-fetch :as model-fetch]
            [agent.utils.reasoning-stream :as rs]))

;; ── Built-in presets ─────────────────────────────────────────────

(def ^:private presets
  [{:name        "ollama"
    :base-url    "http://localhost:11434/v1"
    :api-key-env "OLLAMA_API_KEY"
    :models      [{:id "qwen2.5-coder"    :name "Qwen 2.5 Coder"    :ctx 32768}
                  {:id "qwen3.5-9b"       :name "Qwen 3.5 9B"       :ctx 32768}
                  {:id "devstral"         :name "Devstral"           :ctx 32768}
                  {:id "deepseek-coder"   :name "DeepSeek Coder"     :ctx 16384}
                  {:id "codellama"        :name "Code Llama"         :ctx 16384}]}
   {:name        "lmstudio"
    :base-url    "http://localhost:1234/v1"
    :api-key-env "LMSTUDIO_API_KEY"
    :models      [{:id "devstral-small"   :name "Devstral Small"    :ctx 32768}
                  {:id "qwen3.6-35b-a3b"  :name "Qwen 3.6 35B MoE" :ctx 32768}]}
   ;; oMLX — macOS-native MLX inference server (https://github.com/jundot/omlx)
   ;; Default port 8000; handles Qwen/Mistral tool-call format conversion internally.
   ;; Requires v0.3.10+ for stable Qwen3.6 and no empty-reply bug.
   ;; Model IDs match the alias or directory name used when loading the model in oMLX.
   ;; Context: Qwen3.6-27B native 262K; capped at 131072 (practical with oMLX SSD cache).
   ;; MTP note: oMLX MTP is currently broken for batch>1 (issue #1550); single requests ok.
   {:name        "omlx"
    :base-url    "http://localhost:8000/v1"
    :api-key-env "OMLX_API_KEY"
    ;; oMLX default context cap is 65536. The model supports 262K natively but oMLX
    ;; limits it by KV-cache size — check Admin → Model → Context Length to confirm.
    ;; Override per-model in settings.json "local-models" if you raise the oMLX limit.
    :models      [{:id "Qwen3.6-27B-oQ4-mtp"        :name "Qwen 3.6 27B OptiQ-4bit+MTP"    :ctx 65536}
                  {:id "qwen3.5-9b"                  :name "Qwen 3.5 9B"                    :ctx 32768}
                  {:id "qwen3.6-35b-a3b"             :name "Qwen 3.6 35B MoE"               :ctx 65536}
                  {:id "Qwen3.6-35B-A3B-oQ4-mtp"    :name "Qwen 3.6 35B MoE OptiQ-4bit+MTP" :ctx 65536}
                  {:id "devstral"                    :name "Devstral"                        :ctx 32768}]}])

;; ── Config loading ───────────────────────────────────────────────

(defn- load-settings-models [settings]
  (let [raw (or (get settings "local-models") (get settings :local-models))]
    (when (and raw (not (empty? raw)))
      (if (array? raw)
        (vec raw)
        (ji/js->clj* raw)))))

(defn- js-obj->entry [o]
  {:name          (or (.-name o) (get o "name"))
   :base-url      (or (.-baseUrl o) (aget o "base-url") (get o "baseUrl"))
   :api-key-env   (or (.-apiKeyEnv o) (aget o "api-key-env") (get o "apiKeyEnv") "")
   :rescue-parsing (or (.-rescueParsing o) (aget o "rescue-parsing") (get o "rescueParsing"))
   :think-prefill (or (.-thinkPrefill o) (aget o "think-prefill") (get o "thinkPrefill"))
   :models        (or (.-models o) (get o "models") [])})

(defn- normalize-entry [e]
  (if (map? e) e (js-obj->entry e)))

;; ── Provider registration ────────────────────────────────────────

(defn resolve-key
  "Key for a local entry: env var, then a saved credential, then a placeholder.

   This was the only provider that never consulted credentials.json — relay and
   opencode-zen both do — so a local server that DOES want a key could only be
   reached by exporting an env var. An alias carrying `OMLX_API_KEY=abcd`
   worked while a plain `nyma --model omlx/...` returned `Invalid API key`,
   which is a confusing way to learn that.

   The placeholder stays last: most local servers ignore the key entirely.
   Exposed for tests."
  [entry]
  (let [env-var (:api-key-env entry)
        nm      (str (:name entry))]
    (or (when (seq env-var) (aget js/process.env env-var))
        (when (seq nm) (credentials/read-credential nm))
        "local-no-key")))

(defn- ->js-model [m]
  (let [m (if (map? m) m (ji/js->clj* m))]
    #js {:id            (or (:id m) (get m "id") "")
         :name          (or (:name m) (get m "name") "")
         :contextWindow (or (:ctx m) (:context-window m)
                            (get m "contextWindow") 32768)}))

(defn- create-model-fn [entry get-active-tools]
  (fn [model-id]
    (let [key          (resolve-key entry)
          base-url     (:base-url entry)
          rescue?      (or (:rescue-parsing entry) (get entry "rescueParsing") false)
          prefill?     (or (:think-prefill entry) (get entry "thinkPrefill") false)
          ;; Wrap fetch through reasoning-stream so <think> blocks surface in the UI
          ;; (lift-think rewriter cleans replayed assistant turns; think-prefill
          ;; synthesizes the opener for template-prefilled models), then optionally
          ;; wrap again with rescue parsing for malformed tool calls.
          ;; Orphan-closer lifting is enabled only when the user declared
          ;; thinkPrefill — i.e. asserted this model's template pre-fills <think>.
          ;; Otherwise a turn merely quoting "</think>" would lose its leading text.
          mk-fetch     #(rs/make-fetch (rs/make-lift-think-request-rewriter
                                        (boolean prefill?))
                                       {:think-prefill? (boolean prefill?)})
          base-fetch   (if rescue?
                         (adapter/wrap-fetch-with-rescue (mk-fetch) get-active-tools)
                         (mk-fetch))
          ;; A local server that rejects the placeholder answers 401 and the
          ;; user sees a bare "Invalid API key" — naming neither the provider
          ;; nor the variable to set. Everything needed to say so is right here.
          fetch-fn     (if (= key "local-no-key")
                         (fn [url init]
                           (-> (base-fetch url init)
                               (.then (fn [res]
                                        (if (or (= 401 (.-status res)) (= 403 (.-status res)))
                                          (throw (js/Error.
                                                  (str "No API key for local provider '"
                                                       (:name entry) "' and it rejected the "
                                                       "request. Set "
                                                       (or (:api-key-env entry) "<apiKeyEnv>")
                                                       " in your environment, or run /login "
                                                       (:name entry) " to save one.")))
                                          res)))))
                         base-fetch)]
      (.chat (createOpenAI #js {:apiKey        key
                                :baseURL       base-url
                                :compatibility "compatible"
                                :fetch         fetch-fn})
             model-id))))

(def active-tools-fn
  "Moved to agent.utils.toolcall-rescue so the relay provider can use the same
   rescue; re-exported here because tests and callers already reach for it here."
  adapter/active-tools-fn)

;; ── Server-declared context window ────────────────────────────────
;; A local server knows its own limits; a settings file only knows what someone
;; typed months ago. A vLLM endpoint serving unsloth/Qwen3.8-27B-NVFP4 reports
;; max_model_len 262144 while the settings entry for it said 32768 — nyma sized
;; compaction off the smaller number and started summarising at ~28k for no
;; reason. OpenAI-compatible /v1/models carries max_model_len (vLLM) or
;; context_length (llama.cpp, LM Studio), so ask.

(defn model-window
  "Pure: the context window a /v1/models entry declares, or nil.
   Handles vLLM's max_model_len and the context_length spelling others use.

   Delegates so there is one list of spellings, not two — this and
   model-fetch/entry-window drifted apart the moment either grew a fourth."
  [m]
  (model-fetch/entry-window m))

(defn merge-server-windows
  "Pure: models from settings + entries from /v1/models → models with the
   server's window filled in. An explicit `ctx` in settings always wins; the
   server only supplies what was left unset, and models the server lists but
   settings does not are added."
  [settings-models server-models]
  (let [declared (into {} (map (fn [m] [(str (:id m)) m]) settings-models))
        from-srv (keep (fn [sm]
                         (when-let [id (some-> sm .-id str)]
                           (when (seq id)
                             (let [w (model-window sm)
                                   d (get declared id)]
                               (cond
                                 (and d (or (:ctx d) (:context-window d))) d
                                 d                                          (assoc d :ctx w)
                                 :else {:id id :name id :ctx w})))))
                       (vec (or server-models [])))
        srv-ids  (set (map :id from-srv))]
    (vec (concat from-srv (remove #(contains? srv-ids (str (:id %))) settings-models)))))

(defn discovery-disabled?
  "Local discovery is skipped for the same reasons the relay path skips it: an
   explicit opt-out, or a one-shot run that resolves a single named model from
   the seed list and has no picker to populate."
  []
  (let [off (str (or (aget js/process.env "NYMA_NO_MODEL_DISCOVERY") ""))
        one (str (or (aget js/process.env "NYMA_ONE_SHOT") ""))]
    (or (and (seq off) (not= "0" off) (not= "false" off))
        (= "1" one))))

(defn ^:async fetch-server-models
  "GET <base>/models. Returns the raw data array, or nil — a local endpoint that
   is down must never stop the provider from registering.

   Sends the key. It did not, so any local server requiring auth answered 401,
   `(when (.-ok res))` turned that into nil, and the whole 'context windows from
   the server' path silently did nothing — omlx has never had a window read from
   its server, only the declared one. It appeared to work solely because vllm
   needs no key."
  [base-url api-key]
  (try
    (let [res (js-await (js/fetch (str base-url "/models")
                                  #js {:signal (js/AbortSignal.timeout 4000)
                                       :headers (if (seq (str (or api-key "")))
                                                  #js {"Authorization" (str "Bearer " api-key)}
                                                  #js {})}))]
      (when (.-ok res)
        (let [body (js-await (.json res))]
          (.-data body))))
    (catch :default _e nil)))

(defn- register-entry! [api entry]
  (let [name     (:name entry)
        base-url (:base-url entry)
        models   (mapv ->js-model (:models entry))]
    (when (and name base-url)
      (.registerProvider api name
                         #js {:createModel (create-model-fn entry (active-tools-fn api))
                              :baseUrl     base-url
                              :apiKeyEnv   (or (:api-key-env entry) "")
                              :api         "openai-compatible"
                              :models      (clj->js models)})
      ;; Then ask the server what it actually serves and re-register with real
      ;; windows. Deliberately after the synchronous registration: a slow or
      ;; absent endpoint delays nothing and breaks nothing.
      (-> (if (discovery-disabled?)
            (js/Promise.resolve nil)
            (fetch-server-models base-url (resolve-key entry)))
          (.then (fn [server-models]
                   (when (seq server-models)
                     (let [merged (merge-server-windows (:models entry) server-models)]
                       (when (not= merged (:models entry))
                         (d/info "local-provider"
                                 (str name ": context windows from server")
                                 #js {:models (clj->js (mapv (fn [m] (str (:id m) "=" (:ctx m))) merged))})
                         (.registerProvider api name
                                            #js {:createModel (create-model-fn entry (active-tools-fn api))
                                                 :baseUrl     base-url
                                                 :apiKeyEnv   (or (:api-key-env entry) "")
                                                 :api         "openai-compatible"
                                                 :models      (clj->js (mapv ->js-model merged))}))))))
          (.catch (fn [_e] nil))))))

;; ── Entry point ──────────────────────────────────────────────────

(defn ^:export default [api]
  (let [settings       (try (when (.-getSettings api) (.getSettings api))
                            (catch :default _ nil))
        user-entries   (some-> settings load-settings-models
                               (->> (mapv normalize-entry)))
        ;; Merge: user entries override presets with the same name
        user-names     (set (map :name (or user-entries [])))
        final-presets  (remove #(contains? user-names (:name %)) presets)
        all-entries    (concat final-presets (or user-entries []))
        registered     (atom [])]

    (doseq [entry all-entries]
      (try
        (register-entry! api entry)
        (swap! registered conj (:name entry))
        (catch :default e
          (d/warn "[local-provider] failed to register"
                  (:name entry) "-" (.-message e)))))

    ;; Cleanup
    (fn []
      (doseq [name @registered]
        (try (.unregisterProvider api name)
             (catch :default _ nil))))))
