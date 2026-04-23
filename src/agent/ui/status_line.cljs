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
            [agent.ui.status-line-segments :refer [render-segments token-rate-per-sec auto-append-ids ACTIVITY-VERBS]]
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

;;; ─── setState bailout helper ────────────────────────────

(defn set-if-changed!
  "Call `setter` only when `prev` and `next` differ by content (not ref).
   React's state setter bails out on primitive === equality, but CLJS
   maps and freshly-constructed JS objects never ref-compare equal — so
   pollers that return a fresh value every tick end up re-rendering even
   when the semantic content is unchanged. This guards the producer side.

   Exported (public) so it is reachable from the status_line test suite
   without mounting React. Pure — tested directly."
  ([setter prev next] (set-if-changed! setter prev next =))
  ([setter prev next equals?]
   (when-not (equals? prev next)
     (setter next))))

;;; ─── Git watchers ───────────────────────────────────────

(defn- read-git-branch []
  (exec-capture "git rev-parse --abbrev-ref HEAD"))

(defn- read-git-status
  "Parse `git status --porcelain` into {:staged :unstaged :untracked}."
  []
  (-> (exec-capture "git status --porcelain")
      (.then (fn [out]
               (if (or (nil? out) (= out ""))
                 {:staged 0 :unstaged 0 :untracked 0}
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
                   @counts))))))

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
     :subagents    (count (:active-executions state))
     :active-role  (or (:active-role state) "default")}))

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
        ;; Activity state — drives the "activity" segment (spinner +
        ;; rotating verb). Only on while the agent is working, so the
        ;; status-line is idle (no re-renders, no scroll-snap) when
        ;; nothing is happening. See status_line_segments/activity-seg.
        [activity set-activity]       (useState false)
        [spinner-frame set-spinner-frame] (useState 0)
        [verb-idx set-verb-idx]       (useState 0)
        ;; Sliding window of {:ts :delta-tokens} samples fed from
        ;; "after_provider_request" events. useRef keeps the vector
        ;; between renders without triggering re-renders on push.
        samples-ref                   (useRef #js [])
        sl-settings (get-in settings [:status-line] {})
        preset-name (get sl-settings :preset "default")
        preset      (get-preset preset-name)
        ;; Extensions can mark their segments as :auto-append? to have
        ;; them rendered without being in the user's preset. We merge
        ;; them onto the configured ids here so the rest of the pipeline
        ;; (fit-segments, render-segments) doesn't need to know about it.
        base-left   (or (get sl-settings :left-segments)
                        (:left-segments preset))
        base-right  (or (get sl-settings :right-segments)
                        (:right-segments preset))
        left-ids    (concat base-left (auto-append-ids :left base-left))
        right-ids   (concat base-right (auto-append-ids :right base-right))
        sep-style   (or (get sl-settings :separator)
                        (:separator preset))
        separator   (get-separator sep-style)
        muted       (get-in theme [:colors :muted] "#565f89")]

    ;; Initial git branch + status fetch, then poll status every 5s so
    ;; dirty/clean flips during the session stay live without requiring
    ;; a new branch event to fire.
    ;;
    ;; IMPORTANT: `read-git-status` resolves to a FRESHLY-CONSTRUCTED map
    ;; every tick — even when the working tree is unchanged. Calling
    ;; `(set-git-status new-map)` unconditionally would trigger a React
    ;; re-render every 5 s, which in scrollback-mode snaps the terminal
    ;; scroll back to the cursor (makes scrollback unreadable while
    ;; idle). Same vector as the separate 5 s token-rate tick we already
    ;; removed.
    ;;
    ;; Fix: use functional-setState + content equality. When the new
    ;; value is `=` to prev, return prev (same reference), which makes
    ;; React's `Object.is` bailout skip the re-render. For branch, we
    ;; still need the guard too — comparing nil to "main" via = works
    ;; fine and avoids an unrelated re-render on the second poll.
    (useEffect
     (fn []
       (let [refresh-status
             (fn []
               (-> (read-git-status)
                   (.then (fn [s]
                            (set-git-status
                             (fn [prev] (if (= prev s) prev s)))))))]
         (-> (read-git-branch)
             (.then (fn [b]
                      (set-git-branch
                       (fn [prev]
                         (let [next (or b nil)]
                           (if (= prev next) prev next)))))))
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
                                                    (.then (fn [b]
                                                             (set-git-branch
                                                              (fn [prev]
                                                                (let [next (or b nil)]
                                                                  (if (= prev next) prev next))))))))
                                              150))))]
             (fn []
               (when-let [t @debounce] (js/clearTimeout t))
               (try (.close watcher) (catch :default _))))
           (catch :default _ js/undefined))))
     #js [])

    ;; Fetch PR info after branch is known. fetch-pr-info is cached, so
    ;; repeat calls with the same branch return equal content — use the
    ;; same bailout pattern so we don't re-render on a cache hit.
    (useEffect
     (fn []
       (when (seq git-branch)
         (-> (fetch-pr-info git-branch)
             (.then (fn [info]
                      (set-pr-info
                       (fn [prev] (if (= prev info) prev info)))))))
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

    ;; Deliberately NO periodic re-render here. Any stdout write from Ink
    ;; re-rendering snaps the terminal scroll back to the bottom, which
    ;; makes scrollback unreadable in scrollback-mode. Token-rate updates
    ;; whenever a new after_provider_request event arrives (samples-ref
    ;; push → status_line re-computes from the ref on next render). That's
    ;; plenty — idle users get to scroll without being yanked down.

    ;; Activity tracking — subscribe to agent lifecycle events to know
    ;; when the agent is working. The spinner/verb timers below ONLY
    ;; run while active, so the status line re-renders at 80 ms during
    ;; work and at 0 Hz while idle. Safe (scroll-snap-wise) because the
    ;; status line is a fixed 1-row region — its re-render clears and
    ;; writes exactly 1 row, no terminal scroll is triggered.
    (useEffect
     (fn []
       (let [events  (:events agent)
             on-start (fn [_]
                        ;; Pick a random starting verb so each turn
                        ;; feels fresh — otherwise the user sees
                        ;; "Thinking" every single time.
                        (set-verb-idx
                         (js/Math.floor (* (js/Math.random)
                                           (count ACTIVITY-VERBS))))
                        (set-spinner-frame 0)
                        (set-activity true))
             on-end   (fn [_] (set-activity false))]
         ((:on events) "agent_start" on-start)
         ((:on events) "agent_end"   on-end)
         (fn []
           ((:off events) "agent_start" on-start)
           ((:off events) "agent_end"   on-end))))
     #js [agent])

    ;; Spinner frame advance — 80 ms tick, only while active.
    (useEffect
     (fn []
       (if activity
         (let [id (js/setInterval
                   (fn [] (set-spinner-frame (fn [f] (inc f))))
                   80)]
           (fn [] (js/clearInterval id)))
         js/undefined))
     #js [activity])

    ;; Verb rotation — every ~2.5 s, pick the next whimsy verb.
    (useEffect
     (fn []
       (if activity
         (let [id (js/setInterval
                   (fn [] (set-verb-idx (fn [i] (inc i))))
                   2500)]
           (fn [] (js/clearInterval id)))
         js/undefined))
     #js [activity])

    (let [samples    (vec (.-current samples-ref))
          token-rate (token-rate-per-sec samples 60000 (js/Date.now))
          verb       (nth ACTIVITY-VERBS
                          (mod verb-idx (count ACTIVITY-VERBS)))
          ctx (assoc (build-context agent theme)
                     :git-branch    git-branch
                     :git-status    git-status
                     :pr-info       pr-info
                     :token-rate    token-rate
                     :activity      activity
                     :spinner-frame spinner-frame
                     :verb          verb)
          left-items  (render-segments left-ids ctx)
          right-items (render-segments right-ids ctx)
          cap         (or max-width 80)
          right-fit   (fit-segments left-items right-items separator cap)]
      #jsx [Box {:flexDirection "column" :flexShrink 0}
            [Text {:color muted} (.repeat "─" cap)]
            [Box {:flexDirection "row" :flexShrink 0 :paddingX 1}
             [Box {:flexShrink 0}
              [SegmentGroup {:items left-items :separator separator
                             :muted-color muted}]]
             [Box {:flexGrow 1}]
             [Box {:flexShrink 0}
              [SegmentGroup {:items right-fit :separator separator
                             :muted-color muted}]]]])))
