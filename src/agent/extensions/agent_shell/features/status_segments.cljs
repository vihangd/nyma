(ns agent.extensions.agent-shell.features.status-segments
  "ACP-specific status line segments. These replace the extension's
   previous ui.setFooter-based rich footer. Each segment reads from the
   shared agent-shell atoms (active-agent, agent-state) and returns
   {:visible? false} when no ACP agent is currently connected, so they
   add zero visual noise to non-ACP sessions.

   Segment ids are prefixed with `acp.` so users can pull them into a
   custom preset without colliding with built-ins."
  (:require [agent.extensions.agent-shell.shared :as shared]))

(defn- active-state []
  (when-let [k @shared/active-agent]
    (get @shared/agent-state k)))

(defn- visible [content color]
  {:content content :color color :visible? true})

(defn- hidden [] {:visible? false})

;;; ─── acp.agent ─────────────────────────────────────────

(defn- acp-agent-segment [{:keys [theme]}]
  (if-let [k @shared/active-agent]
    (visible (str "[" (shared/kw-name k) "]")
             (get-in theme [:colors :primary] "#7aa2f7"))
    (hidden)))

;;; ─── acp.model ────────────────────────────────────────

(defn- acp-model-segment [{:keys [theme]}]
  (if-let [state (active-state)]
    (if-let [model (:model state)]
      (visible (str model) "#bb9af7")
      (hidden))
    (hidden)))

;;; ─── acp.mode ─────────────────────────────────────────

(defn- acp-mode-segment [{:keys [theme]}]
  (if-let [state (active-state)]
    (if-let [mode (:mode state)]
      (visible (str "| " mode)
               (get-in theme [:colors :warning] "#e0af68"))
      (hidden))
    (hidden)))

;;; ─── acp.context ──────────────────────────────────────

(defn- progress-bar [ratio width]
  (let [filled (js/Math.round (* ratio width))
        empty  (max 0 (- width filled))]
    (str (.repeat "\u2588" filled) (.repeat "\u2591" empty))))

(defn- acp-context-segment [{:keys [theme]}]
  (if-let [state (active-state)]
    (let [usage (:usage state)
          used  (:used usage)
          size  (:size usage)
          ratio (when (and used size (> size 0)) (/ used size))]
      (if ratio
        (visible (str "ctx: " (shared/format-k used) "/" (shared/format-k size)
                      " " (js/Math.round (* 100 ratio)) "% "
                      (progress-bar ratio 8))
                 (get-in theme [:colors :muted] "#565f89"))
        (hidden)))
    (hidden)))

;;; ─── acp.cost ─────────────────────────────────────────

(defn- acp-cost-segment [{:keys [theme]}]
  (if-let [state (active-state)]
    (if-let [cost (get-in state [:usage :cost])]
      (visible (str "$" (.toFixed (:amount cost) 2))
               (get-in theme [:colors :secondary] "#9ece6a"))
      (hidden))
    (hidden)))

;;; ─── acp.turn-usage ───────────────────────────────────

(defn- acp-turn-usage-segment [{:keys [theme]}]
  (if-let [state (active-state)]
    (let [tu (:turn-usage state)
          in  (:input-tokens tu)
          out (:output-tokens tu)]
      (if (or in out)
        (visible (str "\u2191" (shared/format-k (or in 0))
                      " \u2193" (shared/format-k (or out 0)))
                 (get-in theme [:colors :muted] "#565f89"))
        (hidden)))
    (hidden)))

;;; ─── Registration ─────────────────────────────────────

(def segments
  "Map of {id → render-fn} for every ACP segment this file contributes."
  {"acp.agent"      acp-agent-segment
   "acp.model"      acp-model-segment
   "acp.mode"       acp-mode-segment
   "acp.context"    acp-context-segment
   "acp.cost"       acp-cost-segment
   "acp.turn-usage" acp-turn-usage-segment})

(defn register-all!
  "Register every ACP segment with the given extension API."
  [api]
  (doseq [[id render-fn] segments]
    (when-let [reg (.-registerStatusSegment api)]
      (reg id #js {:category "acp-agent" :render render-fn}))))
