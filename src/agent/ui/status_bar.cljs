(ns agent.ui.status-bar
  "Pi-tui Component: one-line status bar at the bottom of the screen.

   Renders nyma's built-in status (`nyma │ <model> [streaming|ready]
   N turns`) AND every visible segment registered via
   `agent.ui.status-line-segments/register-segment` whose
   `:auto-append?` is true. Segments fan out left or right based on
   their `:position` and self-hide via `{:visible? false}` when
   their data isn't applicable.

   Each render call walks the registry fresh, so there's no caching
   layer to invalidate when extensions add/remove segments at
   runtime."
  (:require [agent.utils.ansi :refer [fg]]
            ["@earendil-works/pi-tui" :refer [truncateToWidth visibleWidth]]
            [agent.ui.status-line-segments :as segs]
            [agent.debug :as dbg]))

(def ^:private ESC   (js/String.fromCharCode 27))
(def ^:private RESET (str ESC "[0m"))
(def ^:private BOLD  (str ESC "[1m"))
(def ^:private DIM   (str ESC "[2m"))


(defn- render-extension-segments
  "Walk the segment registry, call each auto-append segment's render
   for the given `position` (:left or :right). Returns a vector of
   {:content :color :id} entries that the bar prepends/appends.
   Errors in a render fn are isolated — a misbehaving segment can't
   blank the whole status bar."
  [theme position seg-ctx & [width]]
  (let [reg (segs/segment-registry)]
    (->> (vals reg)
         (filter #(and (:auto-append? %)
                       (= (:position %) position)
                       ;; Narrow terminal: the model name is worth more than
                       ;; the usage numbers. The right half is clamped first,
                       ;; so without this gate usage segments push the model
                       ;; off the left edge at ~70 columns.
                       (not (and width (< width 70) (= :usage (:category %))))))
         (sort-by :id)
         (keep (fn [seg]
                 (try
                   ;; Pass the bar's state, not just the theme. Segments got
                   ;; `{:theme theme}` alone, so every built-in that reads state
                   ;; rendered nothing unconditionally: activity-seg needs
                   ;; :activity and role-seg needs :active-role
                   ;; (status_line_segments.cljs:349-362) — the activity spinner
                   ;; never appeared at all. Extension segments were unaffected
                   ;; only because they close over their own atoms.
                   (let [out ((:render seg) (assoc seg-ctx :theme theme))]
                     (when (:visible? out)
                       {:id      (:id seg)
                        :content (or (:content out) "")
                        :color   (:color out)}))
                   ;; Isolated, but no longer silent: a segment that throws on
                   ;; every frame is indistinguishable from one that has
                   ;; nothing to say, and that hid a broken segment for a whole
                   ;; release. Debug level — this runs at render cadence.
                   (catch :default e
                     (dbg/debug "status-bar"
                                (str "segment " (:id seg) " render failed: "
                                     (or (.-message e) (str e))))
                     nil))))
         vec)))

(defn- format-segment
  "Wrap a segment's content in its color + a leading divider so it
   reads as 'previous │ segment'. Divider color follows the theme
   border so segments don't shout for attention."
  [{:keys [content color]} border]
  (str (fg border) " │ " RESET
       (when color (fg color)) content RESET))

(defn create-status-bar
  "Returns a pi-tui Component that renders a one-line status bar.
   Call .setState({model, role, streaming, turn-count}) to update.

   Auto-appends every registered status-line segment with
   :auto-append? true; segments hide themselves via :visible? false
   when not relevant."
  [theme]
  (let [;; Advances once per frame so the spinner animates; renders are
        ;; frequent while streaming, which is exactly when it is shown.
        frame (atom 0)
        state (atom {:model      "–"
                     :provider   nil
                     :role       nil
                     :streaming  false
                     :turn-count 0})

        primary   (get-in theme [:colors :primary]   "#7aa2f7")
        secondary (get-in theme [:colors :secondary] "#9ece6a")
        muted     (get-in theme [:colors :muted]     "#565f89")
        border    (get-in theme [:colors :border]    "#3b4261")
        warning   (get-in theme [:colors :warning]   "#e0af68")

        bar #js {:render
                 (fn [width]
                   (let [{:keys [model provider streaming turn-count role]} @state
                         ;; Role/mode is shown by the model_roles status SEGMENT
                         ;; (color-coded), not inline here — see
                         ;; model_roles/status_segment.cljs.
                         ;; Provider prefix: dim provider name + slash, then
                         ;; the model id in primary color.
                         ;;
                         ;; Edge cases handled:
                         ;;   - no provider captured → render bare model
                         ;;   - provider == model (e.g. user typed
                         ;;     `/model anthropic` and registry returned the
                         ;;     name as modelId) → render bare model
                         ;;   - model ALREADY carries `<provider>/` (raw
                         ;;     fallback when registry can't resolve, leaves
                         ;;     `config.model` as the literal user spec):
                         ;;     strip the duplicated prefix and render the
                         ;;     clean styled form, not `prov/prov/model`.
                         model-piece-raw (str (or model "–"))
                         already-prefixed? (and (seq (str provider))
                                                (.startsWith model-piece-raw
                                                             (str provider "/")))
                         model-piece (if already-prefixed?
                                       (.slice model-piece-raw
                                               (inc (count (str provider))))
                                       model-piece-raw)
                         show-prov? (and (seq (str provider))
                                         (not= (str provider) model-piece))
                         provider-piece (when show-prov?
                                          (str (fg muted) DIM provider RESET
                                               (fg border) "/" RESET))
                         ;; Built-in left content.
                         core-left (str (fg muted) " nyma " RESET
                                        (fg border) "│" RESET
                                        " "
                                        (or provider-piece "")
                                        (fg primary) model-piece RESET)
                         ;; Built-in right content.
                         core-right (str " "
                                         (if streaming
                                           (str (fg secondary) BOLD "● streaming" RESET)
                                           (str (fg muted) DIM "ready" RESET))
                                         (when (pos? turn-count)
                                           (str (fg border) " │" RESET
                                                (fg muted) DIM " " turn-count " turns" RESET))
                                         " ")
                         ;; Extension auto-append segments.
                         ;; Usage/context numbers refresh once per turn (the
                         ;; store dispatches :usage-updated per run); only the
                         ;; elapsed clock is per-tick, derived from
                         ;; :streaming-since at render so the 100 ms tick moves it.
                         st         @state
                         since      (:streaming-since st)
                         seg-ctx    {:activity        (or streaming (:busy st))
                                     :verb            (:verb st)
                                     :spinner-frame   (swap! frame inc)
                                     :active-role     role
                                     :model           model
                                     :turn-count      turn-count
                                     :cost-usd        (:cost-usd st)
                                     :ctx-used        (:ctx-used st)
                                     :ctx-window      (:ctx-window st)
                                     :token-in        (:token-in st)
                                     :token-out       (:token-out st)
                                     :session-id      (:session-id st)
                                     :time-spent-ms   (when (and since (pos? since))
                                                        (- (js/Date.now) since))}
                         left-segs  (render-extension-segments theme :left seg-ctx width)
                         right-segs (render-extension-segments theme :right seg-ctx width)
                         left  (str core-left
                                    (apply str (map #(format-segment % border) left-segs)))
                         right (str (apply str (map #(format-segment % border) right-segs))
                                    core-right)
                         ;; COLUMNS, not characters. This measured `count` of
                         ;; an ANSI-stripped string while the left half was cut
                         ;; in columns, so the two halves were budgeted in
                         ;; different units. A wide glyph in a segment (segment
                         ;; content is arbitrary extension code) undercounted
                         ;; right-w, inflated left-w, and the concatenation
                         ;; overflowed: measured 85 columns at width 80. The
                         ;; status bar is a base child of the TUI, so unlike
                         ;; the overlay pickers nothing composites it down
                         ;; afterwards — pi-tui throws and the session dies.
                         ;;
                         ;; visibleWidth ignores escapes itself, so the
                         ;; hand-rolled SGR strip goes away with it.
                         ;; The right half is clamped FIRST. `left-w` floors
                         ;; at 0, so on a narrow terminal the left half
                         ;; vanishes and `right` was appended whole — 41
                         ;; columns at width 20. Truncating it first bounds
                         ;; the row by construction.
                         right   (if (> (visibleWidth right) width)
                                   (truncateToWidth right width "\u2026" false)
                                   right)
                         right-w (visibleWidth right)
                         left-w  (max 0 (- width right-w))
                         left-t  (truncateToWidth left left-w "…" false)]
                     [(str left-t right)]))

                 :invalidate (fn [])}]

    (set! (.-setState bar)
          (fn [new-state]
            (when new-state
              (swap! state merge new-state))))

    bar))
