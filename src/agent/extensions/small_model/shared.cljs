(ns agent.extensions.small-model.shared
  "Shared config loading, defaults, and state for the small-model extension."
  (:require [agent.utils.js-interop :as ji]))

;; ── Default configuration ───────────────────────────────────────

(def default-config
  {;; Master enable (off by default — zero behaviour change unless explicitly on)
   :enabled false

   ;; Per-module toggles
   :quality-monitor {:enabled       true
                     :max-turns      40   ; stop the loop before context-ceiling
                     :no-progress-streak 3 ; consecutive do-nothing turns → restart
                     :adaptive-temperature true}
   :profiles        {:enabled true
                     ;; "provider/model" → {contextLimit thinking temperature resultCap allowedTools editStrategy}
                     ;; Example for oMLX + Qwen3.6-27B-oQ4-mtp (see custom_provider_local README):
                     ;;   "omlx/Qwen3.6-27B-oQ4-mtp" → {:thinking "low" :temperature 0.15
                     ;;                                  :allowedTools [...] :editStrategy "patch"}
                     :model-profiles {}}
   :evidence        {:enabled     true
                     :max-snippets 20
                     :max-snippet-chars 1024}
   :read-guard      {:enabled  false
                     :max-lines 60}
   :thinking-budget {:enabled     false
                     :max-tokens  8000
                     :retry-without-thinking true}
   :supervisor      {:enabled         false
                     :every-n-turns   8
                     :max-interventions 3
                     :pre-commit      true}
   :respond-tool    {:enabled false}

   ;; Self-tune: distill worker failures into a learned playbook (ACE-style
   ;; online delta updates via the advisor lead). Off by default.
   :self-tune       {:enabled         false
                     :max-lessons     20
                     :min-failures    2
                     :max-reflections 3
                     :reflect-on      ["quality-signal" "verify-fail"]}

   ;; Inject top-K task-relevant knowledge cards from .nyma/knowledge/*.md.
   :knowledge-inject {:enabled      false
                      :dir          "knowledge"
                      :top-k        1
                      :threshold    2.0
                      :token-budget 800}
   ;; Nudge the model when it stops with the task apparently unfinished.
   :finalize-warn   {:enabled    false
                     :max-nudges 2}})

;; ── Config loading ──────────────────────────────────────────────

(defn load-config
  "Merge user settings (\"small-model\" key) over defaults.
   Accepts the raw settings map from api.getSettings()."
  [settings]
  (let [raw (or (get settings "small-model") (get settings :small-model))]
    (if-not raw
      default-config
      ;; Shallow merge per sub-key; user values win.
      (reduce (fn [acc k]
                (let [user-val (or (get raw k) (get raw (str k)))]
                  (if (and user-val (map? (get acc k)))
                    (update acc k merge (if (map? user-val) user-val
                                            (ji/js->clj* user-val)))
                    (if (some? user-val)
                      (assoc acc k user-val)
                      acc))))
              default-config
              (keys default-config)))))

(defn enabled?
  "True iff the extension is enabled and the module toggle is on."
  [config module-key]
  (and (:enabled config)
       (:enabled (get config module-key))))

;; ── Per-session state atom (extension-internal) ─────────────────

(defn make-state []
  (atom {:turn-count     0
         :no-progress    0
         :last-tool-sigs #{}    ; tool-name+args hashes this turn
         :all-tool-sigs  #{}    ; cumulative across turns
         :tool-result-fps {}    ; sig → result fingerprint, so a repeat whose
                                ; OUTPUT changed is progress, not a loop
         :evidence       []
         :interventions  0}))

;; ── Helpers ──────────────────────────────────────────────────────

(defn result-fingerprint
  "Cheap identity for a tool result — length plus a bounded head/tail slice.
   Enough to tell \"the same command returned the same thing\" from \"the same
   command returned something new\" without holding whole outputs. Pure."
  [result]
  (let [s (str (or result ""))
        n (count s)]
    (if (<= n 240)
      (str n ":" s)
      (str n ":" (subs s 0 120) "…" (subs s (- n 120))))))

(defn tool-call-sig
  "Simple signature for a tool call — used to detect exact repeats."
  [tool-name args]
  (str tool-name ":" (try (js/JSON.stringify args)
                          (catch :default _ (str args)))))
