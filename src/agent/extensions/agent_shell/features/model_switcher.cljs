(ns agent.extensions.agent-shell.features.model-switcher
  "The /model command — show fuzzy picker, list models, or switch model directly.
   Supports session/set_config_option and session/set_model dispatch per agent."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.ui.model-picker :as picker]
            [clojure.string :as str]))

(defn- notify [api msg & [level]]
  (if (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))
    nil))

;;; ─── Model method dispatch ────────────────────────────────────

(defn- send-model-change
  "Send the appropriate model-change RPC based on agent's :model-method.
   Returns a promise."
  [conn agent-def model-value]
  (let [sid    @(:session-id conn)
        method (or (:model-method agent-def) :set_config_option)]
    (case method
      :set_model
      (client/send-request conn (client/next-id conn) "session/set_model"
        {:sessionId sid :modelId model-value})
      ;; default: session/set_config_option
      (client/send-request conn (client/next-id conn) "session/set_config_option"
        {:sessionId sid
         :configId (or (:model-config-id agent-def) "model")
         :value model-value}))))

(defn- switch-model!
  "Send the model change request and update state on success."
  [api model-value]
  (let [agent-key @shared/active-agent
        conn      (get @shared/connections agent-key)
        agent-def (get registry/agents agent-key)]
    (if (and conn agent-key)
      (-> (send-model-change conn agent-def model-value)
          (.then (fn [_]
                   (shared/update-agent-state! agent-key :model model-value)
                   (notify api (str "Model changed to: " model-value))))
          (.catch (fn [e]
                    (notify api (str "Model switch failed: " (.-message e)) "error"))))
      (notify api "No agent connected" "error"))))

;;; ─── Model filtering ──────────────────────────────────────────

(defn- get-model-filter
  "Load model-filter patterns from config for the given agent.
   Returns a seq of substring patterns, or nil if not set."
  [agent-key]
  (let [config (shared/load-config)]
    (get-in config [:agents agent-key :model-filter])))

(defn apply-model-filter
  "Filter models to those matching ANY of the filter patterns (case-insensitive substring).
   Returns all models if no filter patterns set."
  [models filter-patterns]
  (if (seq filter-patterns)
    (filterv (fn [m]
               (let [id (str/lower-case (or (:id m) ""))]
                 (some #(str/includes? id (str/lower-case (str %))) filter-patterns)))
             models)
    models))

(defn- get-filtered-models
  "Get the agent's models with config filter applied."
  [agent-key]
  (let [all-models  (or (shared/get-agent-state agent-key :models) [])
        filter-pats (get-model-filter agent-key)]
    (apply-model-filter all-models filter-pats)))

;;; ─── /model list ──────────────────────────────────────────────

(defn- show-model-list
  "Display available models as text in the UI."
  [api]
  (let [agent-key @shared/active-agent]
    (if-not agent-key
      (notify api "No agent connected" "error")
      (let [models  (get-filtered-models agent-key)
            current (shared/get-agent-state agent-key :model)]
        (if (empty? models)
          (notify api "No models available from agent")
          (notify api (str "Models (" (count models) "):\n"
                       (str/join "\n"
                         (mapv (fn [m]
                                 (str (if (= (:id m) current) " * " "   ")
                                      (:id m)
                                      (when (not= (:id m) (:display m))
                                        (str "  (" (:display m) ")"))))
                               models)))))))))

;;; ─── /model (picker) ──────────────────────────────────────────

(defn- show-model-picker
  "Open the fuzzy model picker overlay."
  [api]
  (let [agent-key @shared/active-agent]
    (if-not agent-key
      (notify api "No agent connected" "error")
      (let [models (get-filtered-models agent-key)]
        (if (empty? models)
          ;; No model list available — fall back to text input
          (if (and (.-ui api) (.-input (.-ui api)))
            (-> (.input (.-ui api) "Enter model name" "model-id")
                (.then (fn [value]
                         (when (and value (seq value))
                           (switch-model! api value)))))
            (notify api "No models available. Use /agent-shell__model <model-id> to switch directly."))
          ;; Show fuzzy picker
          (when (and (.-ui api) (.-custom (.-ui api)))
            (.custom (.-ui api)
              (picker/create-picker models
                (fn [selected-id]
                  (if selected-id
                    (switch-model! api selected-id)
                    (notify api "Model selection cancelled")))))))))))

;;; ─── Activation ───────────────────────────────────────────────

(defn activate
  "Register the /model command."
  [api]
  (.registerCommand api "model"
    #js {:description "Show, list, or change agent model"
         :handler (fn [args _ctx]
                    (let [first-arg (first args)]
                      (cond
                        (= first-arg "list") (show-model-list api)
                        (seq args)           (switch-model! api (str/join " " args))
                        :else                (show-model-picker api))))})

  (fn []
    (.unregisterCommand api "model")))
