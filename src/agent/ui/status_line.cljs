(ns agent.ui.status-line
  "Segmented status line rendered above the editor. Uses Ink flexbox
   (per R1) with a flex-grow spacer between left and right segment
   groups. Git branch is watched via fs.watch on .git/HEAD; PR info is
   fetched lazily via `gh pr view` with an in-memory TTL cache.

   The status line reads its preset from settings.status-line.preset
   and allows per-segment overrides via settings.status-line.left-segments
   and .right-segments."
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useRef]]
            ["ink" :refer [Box Text]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :refer [exec]]
            [agent.ui.status-line-segments :refer [render-segments token-rate-per-sec]]
            [agent.ui.status-line-presets :refer [get-preset]]
            [agent.ui.status-line-separators :refer [get-separator]]))

;;; ─── Async helpers ──────────────────────────────────────

(defn- exec-capture
  "Wrap child_process.exec in a Promise that resolves to trimmed stdout
   or nil on any non-zero exit."
  [command]
  (js/Promise.
    (fn [resolve _reject]
      (try
        (exec command #js {:timeout 3000 :cwd (js/process.cwd)}
              (fn [err stdout _stderr]
                (if err
                  (resolve nil)
                  (resolve (.trim (or stdout ""))))))
        (catch :default _ (resolve nil))))))

;;; ─── Git watchers ───────────────────────────────────────

(defn- read-git-branch []
  (exec-capture "git rev-parse --abbrev-ref HEAD"))

(defn- read-git-status
  "Parse `git status --porcelain` into {:staged :unstaged :untracked}."
  []
  (-> (exec-capture "git status --porcelain")
      (.then (fn [out]
               (if (or (nil? out) (= out ""))
                 #js {:staged 0 :unstaged 0 :untracked 0}
                 (let [lines (.split out "\n")
                       counts (atom {:staged 0 :unstaged 0 :untracked 0})]
                   (doseq [line lines]
                     (when (>= (count line) 2)
                       (let [x (aget line 0)
                             y (aget line 1)]
                         (cond
                           (and (= x "?") (= y "?"))
                           (swap! counts update :untracked inc)
                           (not= x " ")
                           (swap! counts update :staged inc)
                           (not= y " ")
                           (swap! counts update :unstaged inc)))))
                   #js {:staged    (:staged @counts)
                        :unstaged  (:unstaged @counts)
                        :untracked (:untracked @counts)}))))))

;;; ─── PR fetch with cache ───────────────────────────────

(def ^:private pr-cache (atom {}))
(def ^:private PR-TTL-MS 60000)
(def ^:private pr-inflight (atom #{}))

(defn- fetch-pr-info
  "Run `gh pr view --json number,url` and return {:number :url} or nil."
  [branch]
  (let [now (js/Date.now)
        entry (get @pr-cache branch)]
    (cond
      (and entry (< now (:expires-at entry)))
      (js/Promise.resolve (:value entry))

      (contains? @pr-inflight branch)
      (js/Promise.resolve (get-in @pr-cache [branch :value]))

      :else
      (do
        (swap! pr-inflight conj branch)
        (-> (exec-capture "gh pr view --json number,url")
            (.then (fn [out]
                     (swap! pr-inflight disj branch)
                     (let [parsed (when (seq out)
                                    (try (js/JSON.parse out)
                                         (catch :default _ nil)))
                           val    (when (and parsed (.-number parsed))
                                    {:number (.-number parsed)
                                     :url    (.-url parsed)})]
                       (swap! pr-cache assoc branch
                              {:value val :expires-at (+ now PR-TTL-MS)})
                       val)))
            (.catch (fn [_]
                      (swap! pr-inflight disj branch)
                      nil)))))))

;;; ─── Overflow fit ──────────────────────────────────────

(defn- fit-width
  "Total visual width of rendered segments plus separator glyph widths.
   Used to crudely decide whether to drop right-group segments from the
   right end when the line overflows."
  [items separator]
  (let [sep-len (count (:middle separator))
        n       (count items)]
    (if (zero? n)
      0
      (+ (reduce + (map #(count (:content %)) items))
         (* sep-len (dec n))))))

(defn- fit-segments
  "Trim right-group items from the right end until the combined width of
   (left + separator + right) fits max-width. Returns the fitted right
   group — left is preserved as-is because it's usually more important."
  [left right separator max-width]
  (loop [r (vec right)]
    (let [lw (fit-width left separator)
          rw (fit-width r separator)
          gap (if (and (pos? lw) (pos? rw)) 2 0)
          total (+ lw rw gap)]
      (if (and (pos? (count r)) (> total max-width))
        (recur (vec (butlast r)))
        r))))

;;; ─── Segment rendering ────────────────────────────────

(defn- SegmentGroup
  "Render a group of segment maps as a Box of <Text> children separated
   by the middle separator glyph."
  [{:keys [items separator muted-color]}]
  (let [sep (:middle separator)
        n (count items)]
    #jsx [Box {:flexDirection "row"}
          (for [[i item] (map-indexed vector items)]
            #jsx [Box {:key (:id item) :flexDirection "row"}
                  [Text {:color (:color item)} (:content item)]
                  (when (< i (dec n))
                    #jsx [Text {:color muted-color} sep])])]))

;;; ─── Main component ───────────────────────────────────

(defn- build-context
  "Assemble the context map consumed by every segment render fn from
   the agent state."
  [agent theme]
  (let [state (when-let [s (:state agent)] @s)
        config (:config agent)
        model-id (or (when-let [m (:model config)] (.-modelId m))
                     "unknown")
        token-in  (or (:total-input-tokens state) 0)
        token-out (or (:total-output-tokens state) 0)]
    {:model-id     model-id
     :path         (js/process.cwd)
     :theme        theme
     :token-in     token-in
     :token-out    token-out
     :token-total  (+ token-in token-out)
     :cost-usd     (or (:total-cost state) 0)
     :ctx-used     (or (:context-used state) 0)
     :ctx-window   (or (:context-window state)
                       (when-let [m (:model config)] (.-contextLimit m))
                       0)
     :time-spent-ms 0
     :subagents    (count (:active-executions state))}))

(defn StatusLine
  "Props:
     :agent     agent map (reads state, config)
     :theme     theme map
     :settings  user settings (reads :status-line.preset / overrides)
     :max-width terminal column count"
  [{:keys [agent theme settings max-width]}]
  (let [[git-branch set-git-branch]   (useState nil)
        [git-status set-git-status]   (useState nil)
        [pr-info set-pr-info]         (useState nil)
        [tick set-tick]               (useState 0)
        ;; Sliding window of {:ts :delta-tokens} samples fed from
        ;; "after_provider_request" events. useRef keeps the vector
        ;; between renders without triggering re-renders on push.
        samples-ref                   (useRef #js [])
        sl-settings (get-in settings [:status-line] {})
        preset-name (get sl-settings :preset "default")
        preset      (get-preset preset-name)
        left-ids    (or (get sl-settings :left-segments)
                        (:left-segments preset))
        right-ids   (or (get sl-settings :right-segments)
                        (:right-segments preset))
        sep-style   (or (get sl-settings :separator)
                        (:separator preset))
        separator   (get-separator sep-style)
        muted       (get-in theme [:colors :muted] "#565f89")]

    ;; Initial git branch + status fetch, then poll status every 5s so
    ;; dirty/clean flips during the session stay live without requiring
    ;; a new branch event to fire.
    (useEffect
      (fn []
        (let [refresh-status (fn []
                               (-> (read-git-status)
                                   (.then (fn [s]
                                            (set-git-status
                                              (js->clj s :keywordize-keys true))))))]
          (-> (read-git-branch)
              (.then (fn [b] (set-git-branch (or b nil)))))
          (refresh-status)
          (let [id (js/setInterval refresh-status 5000)]
            (fn [] (js/clearInterval id)))))
      #js [])

    ;; Watch .git/HEAD to detect branch changes
    (useEffect
      (fn []
        (let [head-path (path/join (js/process.cwd) ".git" "HEAD")
              debounce  (atom nil)]
          (try
            (let [watcher (fs/watch head-path
                            (fn [_ _]
                              (when-let [t @debounce] (js/clearTimeout t))
                              (reset! debounce
                                (js/setTimeout
                                  (fn []
                                    (-> (read-git-branch)
                                        (.then (fn [b] (set-git-branch (or b nil))))))
                                  150))))]
              (fn []
                (when-let [t @debounce] (js/clearTimeout t))
                (try (.close watcher) (catch :default _))))
            (catch :default _ js/undefined))))
      #js [])

    ;; Fetch PR info after branch is known
    (useEffect
      (fn []
        (when (seq git-branch)
          (-> (fetch-pr-info git-branch)
              (.then (fn [info] (set-pr-info info)))))
        js/undefined)
      #js [git-branch])

    ;; Subscribe to after_provider_request for token-rate accumulation.
    ;; Each event adds an entry to the samples ref; we prune anything
    ;; older than 120 s to keep the buffer small.
    (useEffect
      (fn []
        (let [events  (:events agent)
              handler (fn [data]
                        (let [usage (.-usage data)
                              input  (or (.-inputTokens usage) 0)
                              output (or (.-outputTokens usage) 0)
                              delta  (+ input output)
                              now    (js/Date.now)
                              buf    (.-current samples-ref)
                              cutoff (- now 120000)]
                          (.push buf #js {:ts now :deltaTokens delta})
                          ;; Prune in-place: drop entries older than cutoff.
                          (while (and (pos? (.-length buf))
                                      (< (.-ts (aget buf 0)) cutoff))
                            (.shift buf))))]
          ((:on events) "after_provider_request" handler)
          (fn [] ((:off events) "after_provider_request" handler))))
      #js [agent])

    ;; Re-render every 5 s so the token-rate (and time-of-day segment)
    ;; update smoothly even when no other state changes.
    (useEffect
      (fn []
        (let [id (js/setInterval (fn [] (set-tick (fn [t] (inc t)))) 5000)]
          (fn [] (js/clearInterval id))))
      #js [])

    (let [samples    (vec (.-current samples-ref))
          token-rate (token-rate-per-sec samples 60000 (js/Date.now))
          ctx (assoc (build-context agent theme)
                     :git-branch git-branch
                     :git-status git-status
                     :pr-info    pr-info
                     :token-rate token-rate)
          left-items  (render-segments left-ids ctx)
          right-items (render-segments right-ids ctx)
          cap         (or max-width 80)
          right-fit   (fit-segments left-items right-items separator cap)]
      #jsx [Box {:flexDirection "row" :flexShrink 0 :paddingX 1}
            [Box {:flexShrink 0}
             [SegmentGroup {:items left-items :separator separator
                            :muted-color muted}]]
            [Box {:flexGrow 1}]  ;; flex spacer
            [Box {:flexShrink 0}
             [SegmentGroup {:items right-fit :separator separator
                            :muted-color muted}]]])))
