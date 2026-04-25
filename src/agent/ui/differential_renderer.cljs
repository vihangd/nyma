(ns agent.ui.differential-renderer
  "Pi-mono style differential renderer — drop-in for Ink's log-update interface.

   Instead of erasing N lines + rewriting everything each frame:
     1. Diffs new output lines against the previous frame.
     2. Moves cursor to firstChanged, overwrites only firstChanged..lastChanged.
     3. Wraps every write in ANSI 2026 synchronized output to prevent tearing.

   Note: squint strips \\u001b from string literals, so ESC must be built via
   String.fromCharCode(27) and all ANSI sequences constructed from it.

   Based on https://github.com/badlogic/pi-mono/blob/main/packages/tui/src/tui.ts")

;; ESC char — squint strips \u001b from string literals so build it via charCode
(def ^:private ESC (js/String.fromCharCode 27))
(def ^:private BSU (str ESC "[?2026h"))   ;; Begin synchronized update (ANSI 2026)
(def ^:private ESU (str ESC "[?2026l"))   ;; End synchronized update
(def ^:private CLEAR-SCR (str ESC "[2J" ESC "[H" ESC "[3J"))
(def ^:private HIDE-CUR (str ESC "[?25l"))
(def ^:private SHOW-CUR (str ESC "[?25h"))
(def ^:private ERASE-LINE (str ESC "[2K"))

(defn- cur-up   [n] (str ESC "[" n "A"))
(defn- cur-down [n] (str ESC "[" n "B"))

(defn- split-lines
  "Split on newline; strip the trailing empty element from a trailing newline."
  [s]
  (let [parts (.split s "\n")]
    (if (.endsWith s "\n") (.slice parts 0 -1) parts)))

(defn- find-first-changed [lines prev-lines max-n n-new n-old]
  (loop [i 0]
    (cond
      (>= i max-n) -1
      (not= (if (< i n-new) (aget lines i) "")
            (if (< i n-old) (aget prev-lines i) "")) i
      :else (recur (inc i)))))

(defn- find-last-changed [lines prev-lines max-n n-new n-old]
  (loop [i (dec max-n)]
    (cond
      (< i 0) -1
      (not= (if (< i n-new) (aget lines i) "")
            (if (< i n-old) (aget prev-lines i) "")) i
      :else (recur (dec i)))))

(defn ^:export createDifferential [stream]
  (let [state (atom {:prev-lines  #js []
                     :prev-output ""
                     :cursor-row  0
                     :hidden      false
                     :prev-width  0})

        reset-state!
        (fn []
          (swap! state assoc :prev-output "" :prev-lines #js [] :cursor-row 0))

        full-render!
        (fn [lines clear?]
          (let [buf #js [BSU]]
            (when clear? (.push buf CLEAR-SCR))
            (dotimes [i (.-length lines)]
              (when (pos? i) (.push buf "\r\n"))
              (.push buf (aget lines i)))
            (.push buf ESU)
            (.write stream (.join buf ""))
            (swap! state assoc
                   :cursor-row (max 0 (dec (.-length lines)))
                   :prev-lines lines)))

        render
        (fn [s]
          (when-not (:hidden @state)
            (.write stream HIDE-CUR)
            (swap! state assoc :hidden true))
          (let [w              (or (.-columns stream) 80)
                prev-width     (:prev-width @state)
                width-changed? (and (pos? prev-width) (not= prev-width w))]
            (swap! state assoc :prev-width w)
            (let [prev-output (:prev-output @state)
                  prev-lines  (:prev-lines @state)
                  cursor-row  (:cursor-row @state)
                  lines       (split-lines s)]
              (cond
                (and (= s prev-output) (not width-changed?))
                false

                (or (zero? (.-length prev-lines)) width-changed?)
                (do (swap! state assoc :prev-output s)
                    (full-render! lines width-changed?)
                    true)

                :else
                (let [n-new (.-length lines)
                      n-old (.-length prev-lines)
                      max-n (max n-new n-old)
                      fc    (find-first-changed lines prev-lines max-n n-new n-old)
                      lc    (find-last-changed lines prev-lines max-n n-new n-old)]
                  (if (neg? fc)
                    false
                    (let [re    (min lc (dec n-new))
                          delta (- fc cursor-row)
                          buf   #js [BSU]]
                      (cond (pos? delta)  (.push buf (cur-down delta))
                            (neg? delta)  (.push buf (cur-up (- delta))))
                      (.push buf "\r")
                      (loop [i fc]
                        (when (<= i re)
                          (when (> i fc) (.push buf "\r\n"))
                          (.push buf ERASE-LINE)
                          (.push buf (aget lines i))
                          (recur (inc i))))
                      (let [final-row
                            (if (> n-old n-new)
                              (let [extra (- n-old n-new)]
                                (dotimes [_ extra]
                                  (.push buf "\r\n")
                                  (.push buf ERASE-LINE))
                                (.push buf (cur-up extra))
                                (dec n-new))
                              re)]
                        (.push buf ESU)
                        (.write stream (.join buf ""))
                        (swap! state assoc
                               :prev-output s
                               :cursor-row final-row
                               :prev-lines lines)
                        true))))))))

        _ (set! (.-clear render)
                (fn []
                  (let [prev-lines (:prev-lines @state)
                        cursor-row (:cursor-row @state)
                        n          (.-length prev-lines)]
                    (when (pos? n)
                      (let [buf #js [BSU]]
                        (when (pos? cursor-row)
                          (.push buf (cur-up cursor-row)))
                        (.push buf "\r")
                        (dotimes [i n]
                          (when (pos? i) (.push buf "\r\n"))
                          (.push buf ERASE-LINE))
                        (when (> n 1) (.push buf (cur-up (dec n))))
                        (.push buf ESU)
                        (.write stream (.join buf ""))
                        (reset-state!))))))

        _ (set! (.-done render)
                (fn []
                  (.write stream SHOW-CUR)
                  (reset-state!)
                  (swap! state assoc :hidden false)))

        _ (set! (.-reset render) reset-state!)
        _ (set! (.-sync render) (fn [_] (reset-state!)))
        _ (set! (.-setCursorPosition render) (fn [_]))
        _ (set! (.-isCursorDirty render) (fn [] false))
        _ (set! (.-willRender render)
                (fn [s] (not= s (:prev-output @state))))]

    render))
