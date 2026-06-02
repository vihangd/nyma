(ns agent.extensions.small-model.index
  "Small-model mode — opt-in adaptation layer for small/local LLMs.

   Everything is additive and off by default.  Enable via:
     settings.json  →  {\"small-model\": {\"enabled\": true, ...}}
     CLI flag       →  --ext-small-model

   Each module is independently toggleable.  With enabled:false (the
   default) no hooks are wired and behaviour is identical to baseline.

   Modules:
     quality-monitor  — empty turns, hallucinated tools, repeat loops,
                        turn budget (context-ceiling guard); escalating nudges
     profiles         — per-model tuning (thinking, temperature, allowedTools,
                        editStrategy)
     evidence         — EvidenceAdd/Get/List tools that survive compaction
     read-guard       — oversized file-read trimming with search hint
     thinking-budget  — cap thinking tokens; retry without thinking on overflow
     supervisor       — proactive babysitter: escalates to advisor on quality
                        signals, periodic check-ins, pre-commit review
     respond-tool     — synthetic respond() tool forces structured output mode;
                        prevents bare-text responses on small models (Forge pattern)
  "
  (:require [agent.extensions.small-model.shared          :as shared]
            [agent.extensions.small-model.quality-monitor :as qm]
            [agent.extensions.small-model.profiles        :as profiles]
            [agent.extensions.small-model.evidence        :as evidence]
            [agent.extensions.small-model.read-guard      :as read-guard]
            [agent.extensions.small-model.thinking-budget :as thinking-budget]
            [agent.extensions.small-model.supervisor      :as supervisor]
            [agent.extensions.small-model.respond-tool    :as respond-tool]
            [agent.extensions.small-model.context-relief  :as context-relief]))

(defn ^:export default [api]
  (let [settings (try (when (.-getSettings api) (.getSettings api))
                      (catch :default _ nil))
        config   (shared/load-config (or settings {}))
        ;; Honour the --ext-small-model boolean flag (registered below)
        ;; to allow per-session enable without touching settings.json.
        flag-val (.getFlag api "small-model")
        ;; Merge: flag true → force-enable; flag false (explicit) → force-disable
        config   (cond
                   (true? flag-val)  (assoc config :enabled true)
                   (false? flag-val) (assoc config :enabled false)
                   :else             config)

        state    (shared/make-state)
        cleanups (atom [])]

    ;; Register the --ext-small-model flag (must be before resolve-ext-flags runs)
    (.registerFlag api "small-model"
                   #js {:description "Enable small-model adaptation layer for this session"
                        :type        "boolean"
                        :default     false})

    (when (:enabled config)

      ;; ── Quality monitor ──────────────────────────────────────────
      (when (shared/enabled? config :quality-monitor)
        (swap! cleanups conj (qm/activate api config state)))

      ;; ── Per-model profiles ───────────────────────────────────────
      (when (shared/enabled? config :profiles)
        (swap! cleanups conj (profiles/activate api config)))

      ;; ── Evidence store ───────────────────────────────────────────
      (when (shared/enabled? config :evidence)
        (swap! cleanups conj (evidence/activate api config state)))

      ;; ── Read guard ───────────────────────────────────────────────
      (when (shared/enabled? config :read-guard)
        (swap! cleanups conj (read-guard/activate api config)))

      ;; ── Thinking budget ──────────────────────────────────────────
      (when (shared/enabled? config :thinking-budget)
        (swap! cleanups conj (thinking-budget/activate api config)))

      ;; ── Supervisor ───────────────────────────────────────────────
      (when (shared/enabled? config :supervisor)
        (swap! cleanups conj (supervisor/activate api config state)))

      ;; ── Respond tool ─────────────────────────────────────────────
      (when (shared/enabled? config :respond-tool)
        (swap! cleanups conj (respond-tool/activate api config)))

      ;; ── Context relief ───────────────────────────────────────────
      ;; Always active when the extension is enabled — no sub-toggle needed.
      (swap! cleanups conj (context-relief/activate api config)))

    ;; Return deactivate fn
    (fn []
      (doseq [cleanup @cleanups]
        (when (fn? cleanup)
          (try (cleanup)
               (catch :default e
                 (js/console.warn "[small-model] cleanup error:" (.-message e)))))))))
