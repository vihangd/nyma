(ns agent.ui.status-bar
  "Pi-tui Component: one-line status bar at the bottom of the screen."
  (:require ["@mariozechner/pi-tui" :refer [truncateToWidth]]))

(def ^:private ESC   (js/String.fromCharCode 27))
(def ^:private RESET (str ESC "[0m"))
(def ^:private BOLD  (str ESC "[1m"))
(def ^:private DIM   (str ESC "[2m"))

(defn- fg [hex]
  (let [r (js/parseInt (.slice hex 1 3) 16)
        g (js/parseInt (.slice hex 3 5) 16)
        b (js/parseInt (.slice hex 5 7) 16)]
    (str ESC "[38;2;" r ";" g ";" b "m")))

(defn create-status-bar
  "Returns a pi-tui Component that renders a one-line status bar.
   Call .setState({model, role, streaming, turn-count}) to update."
  [theme]
  (let [state (atom {:model      "–"
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
                   (let [{:keys [model role streaming turn-count]} @state
                         role-str  (when (and (seq (str role))
                                              (not= (str role) "default"))
                                     (str (fg warning) "[" role "]" RESET " "))
                         left  (str (fg muted) " nyma " RESET
                                    (fg border) "│" RESET
                                    " " (or role-str "")
                                    (fg primary) (or model "–") RESET)
                         right (str " "
                                    (if streaming
                                      (str (fg secondary) BOLD "● streaming" RESET)
                                      (str (fg muted) DIM "ready" RESET))
                                    (when (pos? turn-count)
                                      (str (fg border) " │" RESET
                                           (fg muted) DIM " " turn-count " turns" RESET))
                                    " ")
                         ;; Truncate left side to leave room for right side
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
