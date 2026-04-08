(ns agent.extensions.agent-shell.agents.registry
  "Agent definitions — command, args, features, mode mappings.
   Supports three-tier merge: builtin < config < dynamic (extension-registered)."
  (:require [clojure.string :as str]))

(defn- ->key
  "Normalize a key to plain string format (Squint keywords compile to plain strings)."
  [s]
  (let [s (str s)]
    ;; Strip leading colon if present (from literal ":keyword" strings)
    (if (str/starts-with? s ":")
      (subs s 1)
      s)))

;;; ─── Built-in agents ──────────────────────────────────────────

(def builtin-agents
  {:claude
   {:name           "Claude Code"
    :command        "npx"
    :args           ["@agentclientprotocol/claude-agent-acp"]
    :features       #{:plan-mode :model-switch :sessions :thinking :subagents :mcp :cost}
    :modes          {:plan      "plan"
                     :yolo      "bypassPermissions"
                     :approve   "default"
                     :auto-edit "acceptEdits"
                     :auto      "auto"}
    :model-config-id "model"
    :init-mode       nil}

   :gemini
   {:name           "Gemini CLI"
    :command        "gemini"
    :args           ["--acp"]
    :features       #{:plan-mode :model-switch :sessions :thinking :subagents :cost}
    :modes          {:plan      "plan"
                     :yolo      "yolo"
                     :approve   "default"
                     :auto-edit "auto_edit"}
    :model-config-id "model"
    :init-mode       nil}

   :opencode
   {:name           "OpenCode"
    :command        "opencode"
    :args           ["acp"]
    :features       #{:model-switch :sessions :cost}
    :modes          {:approve "default"}
    :model-config-id "model"
    :model-method    :set_model
    :init-mode       nil}

   :qwen
   {:name           "Qwen Code"
    :command        "qwen"
    :args           ["--acp"]
    :features       #{:model-switch :cost}
    :modes          {:yolo    "yolo"
                     :approve "default"}
    :model-config-id "model"
    :init-mode       "yolo"}

   :goose
   {:name           "Goose"
    :command        "goose"
    :args           ["acp"]
    :features       #{:model-switch :cost}
    :modes          {:auto    "auto"
                     :approve "approve"}
    :model-config-id "model"
    :init-mode       nil}

   :kiro
   {:name           "Kiro"
    :command        "kiro"
    :args           ["--acp"]
    :features       #{:model-switch :sessions :cost}
    :modes          {:approve "default"}
    :model-config-id "model"
    :init-mode       nil}})

;; Backward compat alias — tests and some callers reference registry/agents directly
(def agents builtin-agents)

;;; ─── Dynamic state ────────────────────────────────────────────

(def config-agents
  "Agents loaded from .nyma/settings.json."
  (atom {}))

(def dynamic-agents
  "Agents registered at runtime by extensions."
  (atom {}))

(def merged-registry
  "Computed merge of builtin + config + dynamic agents."
  (atom builtin-agents))

;;; ─── Config parsing ───────────────────────────────────────────

(defn- normalize-features
  "Convert features from config format (array of strings) to keyword set."
  [features]
  (cond
    (set? features) features
    (vector? features) (set (map ->key features))
    (seq? features) (set (map ->key features))
    (array? features) (set (map keyword (vec features)))
    :else #{}))

(defn- normalize-modes
  "Convert modes map: ensure keys are keywords, values are strings."
  [modes]
  (if (map? modes)
    (reduce-kv (fn [acc k v] (assoc acc (->key k) (str v))) {} modes)
    {}))

(defn- normalize-agent-config
  "Normalize an agent config from settings.json into internal format."
  [config]
  (when (and config (:command config))
    (cond-> {:command        (:command config)
             :args           (or (:args config) [])
             :features       (normalize-features (or (:features config) []))
             :modes          (normalize-modes (or (:modes config) {}))
             :model-config-id (or (:model-config-id config) "model")
             :init-mode       (:init-mode config)}
      (:name config)         (assoc :name (:name config))
      (:model-method config) (assoc :model-method (->key (:model-method config))))))

;;; ─── Registry operations ──────────────────────────────────────

(defn- recompute-merge!
  "Recompute the merged registry from all three tiers."
  []
  (reset! merged-registry
    (merge builtin-agents @config-agents @dynamic-agents)))

(defn refresh!
  "Reload config agents from settings and recompute the merged registry.
   config is the agent-shell config map (from shared/load-config)."
  [config]
  (let [raw-agents (:agents config)]
    (if (and raw-agents (map? raw-agents))
      (let [parsed (reduce-kv
                     (fn [acc k v]
                       (let [key (->key k)]
                         (if-let [normalized (normalize-agent-config v)]
                           (assoc acc key (assoc normalized :name (or (:name normalized) (->key key))))
                           acc)))
                     {} raw-agents)]
        (reset! config-agents parsed))
      (reset! config-agents {})))
  (recompute-merge!))

(defn register-agent!
  "Register a dynamic agent (from an extension). Returns true on success, false on invalid config."
  [agent-key agent-config]
  (let [key (->key agent-key)]
    (if-let [normalized (normalize-agent-config agent-config)]
      (do (swap! dynamic-agents assoc key
                 (assoc normalized :name (or (:name normalized) (->key key))))
          (recompute-merge!)
          true)
      false)))

(defn unregister-agent!
  "Remove a dynamically registered agent."
  [agent-key]
  (let [key (->key agent-key)]
    (swap! dynamic-agents dissoc key)
    (recompute-merge!)))

;;; ─── Public API (backward compatible) ─────────────────────────

(defn get-agent
  "Get agent definition by key. Reads from merged registry.
   config parameter accepted for backward compat but overrides are now
   handled by the three-tier merge."
  [agent-key & [_config]]
  (get @merged-registry agent-key))

(defn list-agents
  "List all available agents as [{:key :claude :name \"Claude Code\"} ...]."
  []
  (mapv (fn [[k v]] {:key k :name (:name v)}) @merged-registry))

(defn reset-dynamic!
  "Reset dynamic agents (for testing / deactivation)."
  []
  (reset! dynamic-agents {})
  (reset! config-agents {})
  (recompute-merge!))
