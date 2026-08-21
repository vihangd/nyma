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
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.custom-provider-local.toolcall-adapter :as adapter]
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
        (js->clj raw :keywordize-keys true)))))

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

(defn- resolve-key [entry]
  ;; Local servers typically don't require an API key.
  ;; Try env var; fall back to a placeholder (openai-compatible ignores it).
  (let [env-var (:api-key-env entry)]
    (or (when (seq env-var) (aget js/process.env env-var))
        "local-no-key")))

(defn- ->js-model [m]
  (let [m (if (map? m) m (js->clj m :keywordize-keys true))]
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
                         (mk-fetch))]
      (.chat (createOpenAI #js {:apiKey        key
                                :baseURL       base-url
                                :compatibility "compatible"
                                :fetch         base-fetch})
             model-id))))

(defn active-tools-fn
  "Returns a zero-arg fn that resolves the current active tool-name set.

   `getAllTools` hands back `(clj->js (keys …))` — an ARRAY of names
   (extensions.cljs:45). `Object.keys` on an array returns \"0\", \"1\", \"2\" …,
   so every rescued tool call failed the `(contains? available tool-name)`
   check in toolcall_adapter and the rescue silently produced nothing — on
   exactly the local models it exists to support."
  [api]
  (fn []
    (let [tools (try (.getAllTools api) (catch :default _ nil))]
      (cond
        (nil? tools)    #{}
        (array? tools)  (set (map str (vec tools)))
        (object? tools) (set (js/Object.keys tools))
        :else           #{}))))

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

(defn ^:async fetch-server-models
  "GET <base>/models. Returns the raw data array, or nil — a local endpoint that
   is down must never stop the provider from registering."
  [base-url]
  (try
    (let [res (js-await (js/fetch (str base-url "/models")
                                  #js {:signal (js/AbortSignal.timeout 4000)}))]
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
      (-> (fetch-server-models base-url)
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
