(ns agent.extensions.model-roles.status-segment
  "Status-line segments for the two orthogonal axes:
     role segment — the active MODEL role (deep/fast/commit/custom), muted;
                    hidden for the default role.
     mode segment — the active permission MODE as a glyph + name, color-coded
                    so a permissive one is visually obvious (`⏵⏵ full-auto`);
                    'default' shows muted as `○ default`.

   The mode always shows, so the quiet state is still an answer; the default
   role is the one piece of the line that said nothing a user acts on.
   Glyphs come from the status line's one table (`segs/glyph`), so the nerd
   and ascii sets apply here too.

   The render context the status bar passes is sparse ({:theme} only), so the
   segments cannot read agent state from it — `register!` closes over `role-fn`
   / `mode-fn` thunks that read live state at render time (the same trick the
   mcp_client segments use with captured atoms)."
  (:require [agent.ui.status-line-segments :as segs]))

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
   Every mode reads as glyph + name (`✎ accept-edits`); the three non-default
   ones are color-coded, 'default' is muted (`○ default`); an EMPTY string is
   hidden (no data, not a default).
   Colors resolve from the theme, falling back to fixed hexes. Exposed for
   tests."
  [mode & [theme]]
  (let [m (str mode)
        c (case m
            "plan"         (themed theme :info color-plan)
            "accept-edits" (themed theme :warning color-accept)
            "full-auto"    (themed theme :error color-auto)
            "default"      (themed theme :muted color-role)
            nil)]
    (if c
      {:content (segs/labelled (keyword (str "mode-" m)) m) :color c :visible? true}
      {:visible? false})))

(defn render-role
  "Pure: a model-role string (+ optional theme) → a status segment map. Renders
   the role name muted (`◆ deep`); 'default' and an EMPTY string are hidden.
   Exposed for tests."
  [role & [theme]]
  (let [r (str role)]
    (cond
      (or (= r "") (= r "default") (= r ":default")) {:visible? false}
      :else {:content (segs/labelled :role r) :color (themed theme :muted color-role) :visible? true})))

(defn render-escalated
  "Pure: the escalated 'provider/model' spec (or nil) → a status segment map.
   Shows the MODEL, not a role name: escalation overrides config.model without
   touching :active-role, so the role badge would keep showing the old role."
  [spec & [theme]]
  (let [s (str (or spec ""))]
    (if (= s "")
      {:visible? false}
      (let [i    (.lastIndexOf s "/")
            name (if (pos? i) (.substring s (inc i)) s)]
        {:content (segs/labelled :escalated name)
         :color   (themed theme :warning color-accept)
         :visible? true}))))

(defn register!
  "Register the role + mode segments. `role-fn`/`mode-fn` are thunks returning
   the current model role / permission mode at render time. The render ctx
   carries {:theme}, threaded into the renderers for theme-aware colors.
   Returns a no-arg cleanup thunk."
  [api role-fn mode-fn & [esc-fn]]
  (when-let [reg (.-registerStatusSegment api)]
    (reg "model-roles.role"
         #js {:category   "role"
              :autoAppend true
              :position   "left"
              :render     (fn [ctx] (render-role (role-fn) (:theme ctx)))})
    (reg "model-roles.escalated"
         #js {:category   "role"
              :autoAppend true
              :position   "left"
              :render     (fn [ctx] (render-escalated (when esc-fn (esc-fn)) (:theme ctx)))})
    (reg "model-roles.mode"
         #js {:category   "mode"
              :autoAppend true
              :position   "left"
              :render     (fn [ctx] (render-mode (mode-fn) (:theme ctx)))}))
  (fn []
    (when-let [unreg (.-unregisterStatusSegment api)]
      (try (unreg "model-roles.role") (catch :default _ nil))
      (try (unreg "model-roles.mode") (catch :default _ nil))
      (try (unreg "model-roles.escalated") (catch :default _ nil)))))
