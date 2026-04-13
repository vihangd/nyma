(ns agent.ui.welcome
  "Landing screen shown when the message history is empty. Renders a
   small logo, current model info, a tips panel, and up to 3 recent
   sessions. Collapses to a single-column layout on narrow terminals."
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect]]
            ["ink" :refer [Box Text useStdout]]
            [agent.sessions.listing :refer [list-sessions]]
            [agent.utils.time :refer [relative-time]]))

;;; ─── Pure layout helper ─────────────────────────────────

(defn compute-layout
  "Decide whether to render in two columns and how wide each column
   should be for the given terminal width."
  [term-width]
  (let [w (max 0 (or term-width 0))]
    (if (>= w 70)
      (let [left (max 20 (min 26 (js/Math.floor (* w 0.35))))]
        {:dual-column? true
         :left-col     left
         :right-col    (- w left 4)})
      {:dual-column? false
       :left-col     (max 0 (- w 2))
       :right-col    0})))

;;; ─── Static logo ────────────────────────────────────────

(def ^:private logo-lines
  [" _ __  _   _ _ __ ___   __ _ "
   "| '_ \\| | | | '_ ` _ \\ / _` |"
   "| | | | |_| | | | | | | (_| |"
   "|_| |_|\\__, |_| |_| |_|\\__,_|"
   "       |___/"])

;;; ─── Left column ────────────────────────────────────────

(defn- LeftPane [{:keys [agent theme]}]
  (let [primary (get-in theme [:colors :primary] "#7aa2f7")
        muted   (get-in theme [:colors :muted] "#565f89")
        config  (:config agent)
        model-id (or (when-let [m (:model config)] (.-modelId m)) "unknown")
        provider (or (when-let [m (:model config)] (.-provider m)) "")]
    #jsx [Box {:flexDirection "column"}
          (for [[i line] (map-indexed vector logo-lines)]
            #jsx [Text {:key i :color primary} line])
          [Box {:marginTop 1}
           [Text {:bold true} "Welcome to nyma"]]
          [Box [Text {:color muted} (str "model: " model-id)]]
          (when (seq provider)
            #jsx [Box [Text {:color muted} (str "provider: " provider)]])]))

;;; ─── Right column ───────────────────────────────────────

(def ^:private tips
  [["?" "keyboard help"]
   ["/" "slash commands"]
   ["@" "reference files"]
   ["!" "shell mode"]
   ["$" "babashka mode"]])

(defn- TipsPanel [{:keys [theme]}]
  (let [primary (get-in theme [:colors :primary] "#7aa2f7")
        muted   (get-in theme [:colors :muted] "#565f89")]
    #jsx [Box {:flexDirection "column"}
          [Text {:bold true :color primary} "Tips"]
          (for [[i [key desc]] (map-indexed vector tips)]
            #jsx [Box {:key i :flexDirection "row"}
                  [Box {:width 3} [Text {:color primary} key]]
                  [Text {:color muted} desc]])]))

(defn- RecentSessionsPanel [{:keys [sessions theme]}]
  (let [primary (get-in theme [:colors :primary] "#7aa2f7")
        muted   (get-in theme [:colors :muted] "#565f89")]
    #jsx [Box {:flexDirection "column" :marginTop 1}
          [Text {:bold true :color primary} "Recent Sessions"]
          (if (empty? sessions)
            #jsx [Text {:color muted} "  (none yet)"]
            (for [[i s] (map-indexed vector (take 3 sessions))]
              #jsx [Box {:key i :flexDirection "row"}
                    [Box {:width 24}
                     [Text {:color muted :wrap "truncate"}
                      (or (:name s) "(unnamed)")]]
                    [Text {:color muted}
                     (str "  " (relative-time (:modified s)))]]))]))

(defn- RightPane [{:keys [sessions theme]}]
  #jsx [Box {:flexDirection "column"}
        [TipsPanel {:theme theme}]
        [RecentSessionsPanel {:sessions sessions :theme theme}]])

;;; ─── Top-level component ───────────────────────────────

(defn WelcomeScreen
  "Props:
     :agent        agent map
     :theme        theme map
     :sessions-dir path to the sessions directory"
  [{:keys [agent theme sessions-dir]}]
  (let [{:keys [stdout]}                 (useStdout)
        cols                             (or (.-columns stdout) 80)
        {:keys [dual-column?]}           (compute-layout cols)
        [recent-sessions set-sessions]   (useState [])]
    (useEffect
     (fn []
       (when (seq sessions-dir)
         (try
           (set-sessions (list-sessions sessions-dir))
           (catch :default _ (set-sessions []))))
       js/undefined)
     #js [sessions-dir])
    #jsx [Box {:flexDirection "column" :paddingX 2 :paddingY 1
               :flexGrow 1}
          (if dual-column?
            #jsx [Box {:flexDirection "row"}
                  [Box {:flexDirection "column" :marginRight 4}
                   [LeftPane {:agent agent :theme theme}]]
                  [Box {:flexDirection "column"}
                   [RightPane {:sessions recent-sessions :theme theme}]]]
            #jsx [Box {:flexDirection "column"}
                  [LeftPane {:agent agent :theme theme}]
                  [Box {:marginTop 1}
                   [RightPane {:sessions recent-sessions :theme theme}]]])]))
