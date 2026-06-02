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
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.custom-provider-local.toolcall-adapter :as adapter]
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
  {:name        (or (.-name o) (get o "name"))
   :base-url    (or (.-baseUrl o) (.-base-url o) (get o "baseUrl"))
   :api-key-env (or (.-apiKeyEnv o) (.-api-key-env o) (get o "apiKeyEnv") "")
   :models      (or (.-models o) (get o "models") [])})

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
          ;; Wrap fetch through reasoning-stream so <think> blocks surface in the UI,
          ;; then optionally wrap again with rescue parsing for malformed tool calls.
          base-fetch   (if rescue?
                         (adapter/wrap-fetch-with-rescue (rs/make-fetch) get-active-tools)
                         (rs/make-fetch))]
      (.chat (createOpenAI #js {:apiKey        key
                                :baseURL       base-url
                                :compatibility "compatible"
                                :fetch         base-fetch})
             model-id))))

(defn- active-tools-fn
  "Returns a zero-arg fn that resolves the current active tool-name set."
  [api]
  (fn []
    (let [tools (try (.getAllTools api) (catch :default _ nil))]
      (if tools (set (js/Object.keys tools)) #{}))))

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
                              :models      (clj->js models)}))))

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
          (js/console.warn "[local-provider] failed to register"
                           (:name entry) "-" (.-message e)))))

    ;; Cleanup
    (fn []
      (doseq [name @registered]
        (try (.unregisterProvider api name)
             (catch :default _ nil))))))
