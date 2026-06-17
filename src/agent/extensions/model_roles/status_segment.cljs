(ns agent.extensions.model-roles.status-segment
  "Status-line segments for the two orthogonal axes:
     role segment — the active MODEL role (deep/fast/commit/custom), muted;
                    'default' is hidden.
     mode segment — the active permission MODE, color-coded so a permissive one
                    is visually obvious (Claude's `⏵⏵ accept edits on` analog);
                    'default' is hidden.
   Both show together, so e.g. `fast` + `⏵⏵ full-auto` coexist.

   The render context the status bar passes is sparse ({:theme} only), so the
   segments cannot read agent state from it — `register!` closes over `role-fn`
   / `mode-fn` thunks that read live state at render time (the same trick the
   mcp_client segments use with captured atoms).")

;; Fallback hexes (Tokyo-Night), used when the theme omits the key.
(def ^:private color-plan   "#7aa2f7")  ;; blue
(def ^:private color-accept "#e0af68")  ;; yellow
(def ^:private color-auto   "#f7768e")  ;; red
(def ^:private color-role   "#9aa5ce")  ;; muted (plain model role)

(defn- themed
  "theme[:colors][k] or the fallback hex (so a custom theme recolors the badge,
   like the mcp_client / status_line segments)."
  [theme k fallback]
  (or (get-in theme [:colors k]) fallback))

(defn render-mode
  "Pure: a permission-mode string (+ optional theme) → a status segment map.
   Modes get a color-coded glyph badge; 'default' (or empty) is hidden. Colors
   resolve from the theme, falling back to fixed hexes. Exposed for tests."
  [mode & [theme]]
  (case (str mode)
    "plan"         {:content "◯ plan"         :color (themed theme :info color-plan)      :visible? true}
    "accept-edits" {:content "✎ accept-edits" :color (themed theme :warning color-accept) :visible? true}
    "full-auto"    {:content "⏵⏵ full-auto"   :color (themed theme :error color-auto)     :visible? true}
    {:visible? false}))

(defn render-role
  "Pure: a model-role string (+ optional theme) → a status segment map. Renders
   the role name muted; 'default' (or empty) is hidden. Exposed for tests."
  [role & [theme]]
  (let [r (str role)]
    (if (or (= r "default") (= r ""))
      {:visible? false}
      {:content r :color (themed theme :muted color-role) :visible? true})))

(defn register!
  "Register the role + mode segments. `role-fn`/`mode-fn` are thunks returning
   the current model role / permission mode at render time. The render ctx
   carries {:theme}, threaded into the renderers for theme-aware colors.
   Returns a no-arg cleanup thunk."
  [api role-fn mode-fn]
  (when-let [reg (.-registerStatusSegment api)]
    (reg "model-roles.role"
         #js {:category   "role"
              :autoAppend true
              :position   "left"
              :render     (fn [ctx] (render-role (role-fn) (:theme ctx)))})
    (reg "model-roles.mode"
         #js {:category   "mode"
              :autoAppend true
              :position   "left"
              :render     (fn [ctx] (render-mode (mode-fn) (:theme ctx)))}))
  (fn []
    (when-let [unreg (.-unregisterStatusSegment api)]
      (try (unreg "model-roles.role") (catch :default _ nil))
      (try (unreg "model-roles.mode") (catch :default _ nil)))))
