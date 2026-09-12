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
     read-guard       — oversized file-read trimming with search hint, and
                        read-before-edit enforcement on edit/write
     thinking-budget  — cap thinking tokens; retry without thinking on overflow
     supervisor       — proactive babysitter: escalates to advisor on quality
                        signals, periodic check-ins, pre-commit review
     self-tune        — ACE-style learned playbook: advisor distills worker
                        failures into rules injected on later turns
     respond-tool     — synthetic respond() tool forces structured output mode;
                        prevents bare-text responses on small models (Forge pattern)
  "
  (:require [agent.debug :as d]
            [agent.extensions.small-model.shared          :as shared]
            [agent.extensions.small-model.quality-monitor :as qm]
            [agent.extensions.small-model.profiles        :as profiles]
            [agent.extensions.small-model.evidence        :as evidence]
            [agent.extensions.small-model.read-guard      :as read-guard]
            [agent.extensions.small-model.thinking-budget :as thinking-budget]
            [agent.extensions.small-model.supervisor      :as supervisor]
            [agent.extensions.small-model.self-tune       :as self-tune]
            [agent.extensions.small-model.respond-tool    :as respond-tool]
            [agent.extensions.small-model.context-relief  :as context-relief]
            [agent.extensions.small-model.knowledge-inject :as knowledge-inject]
            [agent.extensions.small-model.finalize-warn    :as finalize-warn]))

(defn ^:export default [api]
  (let [settings (try (when (.-getSettings api) (.getSettings api))
                      (catch :default _ nil))
        config   (shared/load-config (or settings {}))
        state    (shared/make-state)
        cleanups (atom [])]

    ;; Register BEFORE reading. Extensions load before cli's resolve-ext-flags
    ;; runs, so registerFlag is what applies the parsed --ext-* argv value; the
    ;; read used to sit above this call and could only ever see nil, which is
    ;; why --ext-small-model never did anything.
    ;;
    ;; No :default either — an absent flag must read as nil so settings decide.
    ;; A `false` default would hit the (false? flag-val) branch below and
    ;; silently disable an extension enabled via settings.
    (.registerFlag api "small-model"
                   #js {:description "Enable small-model adaptation layer for this session"
                        :type        "boolean"})

    ;; Honour the --ext-small-model boolean flag: true → force-enable,
    ;; explicit false → force-disable, absent → whatever settings said.
    (let [flag-val (.getFlag api "small-model")
          config   (cond
                     (true? flag-val)  (assoc config :enabled true)
                     (false? flag-val) (assoc config :enabled false)
                     :else             config)]

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

      ;; ── Self-tune (ACE-style learned playbook) ───────────────────
      (when (shared/enabled? config :self-tune)
        (swap! cleanups conj (self-tune/activate api config)))

      ;; ── Respond tool ─────────────────────────────────────────────
      (when (shared/enabled? config :respond-tool)
        (swap! cleanups conj (respond-tool/activate api config)))

      ;; ── Knowledge injection ──────────────────────────────────────
      (when (shared/enabled? config :knowledge-inject)
        (swap! cleanups conj (knowledge-inject/activate api config state)))

      ;; ── Finalize warn (premature-completion guard) ───────────────
      (when (shared/enabled? config :finalize-warn)
        (swap! cleanups conj (finalize-warn/activate api config state)))

      ;; ── Context relief ───────────────────────────────────────────
      ;; Always active when the extension is enabled — no sub-toggle needed.
      (swap! cleanups conj (context-relief/activate api config))))

    ;; Return deactivate fn
    (fn []
      (doseq [cleanup @cleanups]
        (when (fn? cleanup)
          (try (cleanup)
               (catch :default e
                 (d/warn "[small-model] cleanup error:" (.-message e)))))))))
