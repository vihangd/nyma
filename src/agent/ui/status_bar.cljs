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
  (:require ["@mariozechner/pi-tui" :refer [truncateToWidth]]
            [agent.ui.status-line-segments :as segs]))

(def ^:private ESC   (js/String.fromCharCode 27))
(def ^:private RESET (str ESC "[0m"))
(def ^:private BOLD  (str ESC "[1m"))
(def ^:private DIM   (str ESC "[2m"))

(defn- fg [hex]
  (let [r (js/parseInt (.slice hex 1 3) 16)
        g (js/parseInt (.slice hex 3 5) 16)
        b (js/parseInt (.slice hex 5 7) 16)]
    (str ESC "[38;2;" r ";" g ";" b "m")))

(defn- render-extension-segments
  "Walk the segment registry, call each auto-append segment's render
   for the given `position` (:left or :right). Returns a vector of
   {:content :color :id} entries that the bar prepends/appends.
   Errors in a render fn are isolated — a misbehaving segment can't
   blank the whole status bar."
  [theme position]
  (let [reg (segs/segment-registry)]
    (->> (vals reg)
         (filter #(and (:auto-append? %)
                       (= (:position %) position)))
         (sort-by :id)
         (keep (fn [seg]
                 (try
                   (let [out ((:render seg) {:theme theme})]
                     (when (:visible? out)
                       {:id      (:id seg)
                        :content (or (:content out) "")
                        :color   (:color out)}))
                   (catch :default _e nil))))
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
  (let [state (atom {:model      "–"
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
                   (let [{:keys [model provider streaming turn-count]} @state
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
                         left-segs  (render-extension-segments theme :left)
                         right-segs (render-extension-segments theme :right)
                         left  (str core-left
                                    (apply str (map #(format-segment % border) left-segs)))
                         right (str (apply str (map #(format-segment % border) right-segs))
                                    core-right)
                         right-w (count (.replace right (js/RegExp. (str ESC "\\[[0-9;]*m") "g") ""))
                         left-w  (max 0 (- width right-w))
                         left-t  (truncateToWidth left left-w "…" false)]
                     [(str left-t right)]))

                 :invalidate (fn [])}]

    (set! (.-setState bar)
          (fn [new-state]
            (when new-state
              (swap! state merge new-state))))

    bar))
