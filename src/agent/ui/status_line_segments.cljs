(ns agent.ui.status-line-segments
  "Segment registry + 18 built-in segments for the status line.

   A segment is a map of {:id :category :render} where :render is a
   function taking a context map and returning {:content :color :visible?}
   (or nil to hide the segment entirely).

   Context shape:
     {:model-id :path :git-branch
      :git-status     {:staged :unstaged :untracked}
      :pr-info        {:number :url}
      :token-in :token-out :token-total :token-rate
      :cost-usd :ctx-used :ctx-window :time-spent-ms
      :session-id :hostname :cache-read :cache-write
      :subagents      int
      :theme          theme-map}

   All segments should degrade gracefully when their data is missing —
   return {:visible? false} rather than rendering empty noise."
  (:require ["node:path" :as path]
            ["node:os" :as os]))

;;; ─── Registry ───────────────────────────────────────────

(def ^:private registry (atom {}))

(defn register-segment [id segment]
  (swap! registry assoc (str id)
         (assoc segment :id (str id)))
  @registry)

(defn unregister-segment [id]
  (swap! registry dissoc (str id))
  @registry)

(defn get-segment [id]
  (get @registry (str id)))

(defn segment-registry []
  @registry)

(defn auto-append-ids
  "Return segment ids marked `:auto-append? true` for the given position
   (`:left` or `:right`), excluding any ids already present in `preset-ids`.

   Used by StatusLine to let extensions inject their own segments without
   requiring the user to hand-edit their preset. Segments that aren't
   relevant at the moment self-hide via `{:visible? false}`, so this is
   safe to call unconditionally."
  [position preset-ids]
  (let [existing (set preset-ids)]
    (->> @registry
         (filter (fn [[_ seg]]
                   (and (:auto-append? seg)
                        (= (:position seg) position))))
         (map first)
         (remove existing)
         sort
         vec)))

(defn reset-registry!
  "Test helper — clears the registry and reinstalls built-ins."
  []
  (reset! registry {}))

;;; ─── Context usage tiers ────────────────────────────────

(def ^:private PCT-WARNING 50)
(def ^:private PCT-PURPLE  70)
(def ^:private PCT-ERROR   90)
(def ^:private TOK-WARNING 150000)
(def ^:private TOK-PURPLE  270000)
(def ^:private TOK-ERROR   500000)

(defn context-usage-level
  "Return :ok :warning :purple :error based on token count and window.

   A level fires when EITHER the percentage OR the absolute token count
   crosses its threshold, so small models still warn at high pct and
   large windows still warn at high absolute counts."
  [ctx-used ctx-window]
  (let [used (or ctx-used 0)
        pct  (if (and ctx-window (pos? ctx-window))
               (* 100.0 (/ used ctx-window))
               0)]
    (cond
      (or (>= used TOK-ERROR)   (>= pct PCT-ERROR))   :error
      (or (>= used TOK-PURPLE)  (>= pct PCT-PURPLE))  :purple
      (or (>= used TOK-WARNING) (>= pct PCT-WARNING)) :warning
      :else :ok)))

(defn- context-color
  "Map a context-usage-level to the resolved theme hex."
  [level theme]
  (case level
    :error   (get-in theme [:colors :context-error]   "#f7768e")
    :purple  (get-in theme [:colors :context-purple]  "#bb9af7")
    :warning (get-in theme [:colors :context-warning] "#e0af68")
    (get-in theme [:colors :context-ok] "#9ece6a")))

;;; ─── Token rate (sliding window) ───────────────────────

(defn- sample-ts [s] (or (get s :ts) (get s "ts") 0))
(defn- sample-delta [s]
  (or (get s :delta-tokens) (get s "deltaTokens") 0))

(defn token-rate-per-sec
  "Compute tokens/second from a sequence of sample maps over the
   trailing `window-ms` milliseconds.

   Samples are {:ts :delta-tokens}. `now` is passed in to keep the
   function pure. Returns 0 when there are fewer than 2 samples in the
   window or when the span is zero."
  [samples window-ms now]
  (let [window (or window-ms 60000)
        n0     (or now 0)
        cutoff (- n0 window)
        in-win (filter (fn [s] (>= (sample-ts s) cutoff))
                       (or samples []))
        ordered (sort-by sample-ts in-win)
        n       (count ordered)]
    (if (< n 2)
      0
      (let [first-ts (sample-ts (first ordered))
            last-ts  (sample-ts (last ordered))
            span-ms  (- last-ts first-ts)
            ;; Sum deltas from samples after the first — the first acts
            ;; as the baseline timestamp for the window.
            total    (reduce (fn [acc s] (+ acc (sample-delta s)))
                             0
                             (rest ordered))]
        (if (pos? span-ms)
          (* 1000.0 (/ total span-ms))
          0)))))

;;; ─── Formatting helpers ─────────────────────────────────

(defn- compact-number
  "Render a number as a short string: 12345 → 12.3k, 2_345_678 → 2.3M."
  [n]
  (let [n (or n 0)]
    (cond
      (>= n 1000000) (str (.toFixed (/ n 1000000) 1) "M")
      (>= n 1000)    (str (.toFixed (/ n 1000) 1) "k")
      :else          (str n))))

(defn- format-cost [n]
  (if (or (nil? n) (zero? n))
    "$0.00"
    (str "$" (.toFixed n 2))))

(defn- format-duration-ms [ms]
  (let [m (or ms 0)
        secs   (js/Math.floor (/ m 1000))
        mins   (js/Math.floor (/ secs 60))
        hrs    (js/Math.floor (/ mins 60))]
    (cond
      (>= hrs 1)  (str hrs "h" (mod mins 60) "m")
      (>= mins 1) (str mins "m" (mod secs 60) "s")
      :else       (str secs "s"))))

(defn- shorten-path
  "Collapse $HOME to ~ and keep only the last two path segments when
   deeper than that."
  [p]
  (if (or (nil? p) (= p ""))
    ""
    (let [home (try (os/homedir) (catch :default _ ""))
          p    (if (and (seq home) (.startsWith p home))
                 (str "~" (.slice p (count home)))
                 p)
          parts (.split p "/")
          n     (.-length parts)]
      (if (<= n 3)
        p
        (str ".../"
             (aget parts (- n 2))
             "/"
             (aget parts (- n 1)))))))

(defn- format-time-of-day []
  (let [d (js/Date.)
        h (.getHours d)
        m (.getMinutes d)]
    (str (when (< h 10) "0") h ":"
         (when (< m 10) "0") m)))

;;; ─── Built-in segments ──────────────────────────────────

(defn- visible [content color]
  {:content content :color color :visible? true})

(defn- hidden [] {:visible? false})

(defn- model-seg [{:keys [model-id theme]}]
  (if (seq model-id)
    (visible (str "" model-id)
             (get-in theme [:colors :primary] "#7aa2f7"))
    (hidden)))

(defn- path-seg [{:keys [path theme]}]
  (if (seq path)
    (visible (shorten-path path)
             (get-in theme [:colors :muted] "#565f89"))
    (hidden)))

(defn- git-seg [{:keys [git-branch git-status theme]}]
  (if (seq git-branch)
    (let [{:keys [staged unstaged untracked]} (or git-status {})
          dirty? (or (pos? (or staged 0))
                     (pos? (or unstaged 0))
                     (pos? (or untracked 0)))
          suffix (if dirty? "*" "")
          color  (if dirty?
                   (get-in theme [:colors :warning] "#e0af68")
                   (get-in theme [:colors :secondary] "#9ece6a"))]
      (visible (str " " git-branch suffix) color))
    (hidden)))

(defn- pr-seg [{:keys [pr-info theme]}]
  (if (and pr-info (:number pr-info))
    (visible (str "#" (:number pr-info))
             (get-in theme [:colors :primary] "#7aa2f7"))
    (hidden)))

(defn- subagents-seg [{:keys [subagents theme]}]
  (let [n (or subagents 0)]
    (if (pos? n)
      (visible (str "sub:" n)
               (get-in theme [:colors :warning] "#e0af68"))
      (hidden))))

(defn- token-in-seg [{:keys [token-in theme]}]
  (let [n (or token-in 0)]
    (if (pos? n)
      (visible (str "↑" (compact-number n))
               (get-in theme [:colors :muted] "#565f89"))
      (hidden))))

(defn- token-out-seg [{:keys [token-out theme]}]
  (let [n (or token-out 0)]
    (if (pos? n)
      (visible (str "↓" (compact-number n))
               (get-in theme [:colors :muted] "#565f89"))
      (hidden))))

(defn- token-total-seg [{:keys [token-total theme]}]
  (let [n (or token-total 0)]
    (if (pos? n)
      (visible (str "tok:" (compact-number n))
               (get-in theme [:colors :muted] "#565f89"))
      (hidden))))

(defn- token-rate-seg [{:keys [token-rate theme]}]
  (let [r (or token-rate 0)]
    (if (pos? r)
      (visible (str (.toFixed r 0) " t/s")
               (get-in theme [:colors :muted] "#565f89"))
      (hidden))))

(defn- cost-seg [{:keys [cost-usd theme]}]
  (visible (format-cost (or cost-usd 0))
           (get-in theme [:colors :secondary] "#9ece6a")))

(defn- context-pct-seg [{:keys [ctx-used ctx-window theme]}]
  (if (and ctx-window (pos? ctx-window))
    (let [pct (js/Math.floor (* 100 (/ (or ctx-used 0) ctx-window)))
          lvl (context-usage-level ctx-used ctx-window)]
      (visible (str "ctx:" pct "%") (context-color lvl theme)))
    (hidden)))

(defn- context-total-seg [{:keys [ctx-used theme]}]
  (if (pos? (or ctx-used 0))
    (visible (compact-number ctx-used)
             (get-in theme [:colors :muted] "#565f89"))
    (hidden)))

(defn- time-spent-seg [{:keys [time-spent-ms theme]}]
  (if (pos? (or time-spent-ms 0))
    (visible (format-duration-ms time-spent-ms)
             (get-in theme [:colors :muted] "#565f89"))
    (hidden)))

(defn- time-seg [{:keys [theme]}]
  (visible (format-time-of-day)
           (get-in theme [:colors :muted] "#565f89")))

(defn- session-seg [{:keys [session-id theme]}]
  (if (seq session-id)
    (visible (str "ses:" (.slice (str session-id) 0 6))
             (get-in theme [:colors :muted] "#565f89"))
    (hidden)))

(defn- hostname-seg [{:keys [hostname theme]}]
  (let [h (or hostname (try (os/hostname) (catch :default _ "")))]
    (if (seq h)
      (visible h (get-in theme [:colors :muted] "#565f89"))
      (hidden))))

(defn- cache-read-seg [{:keys [cache-read theme]}]
  (if (pos? (or cache-read 0))
    (visible (str "c↑" (compact-number cache-read))
             (get-in theme [:colors :muted] "#565f89"))
    (hidden)))

(defn- cache-write-seg [{:keys [cache-write theme]}]
  (if (pos? (or cache-write 0))
    (visible (str "c↓" (compact-number cache-write))
             (get-in theme [:colors :muted] "#565f89"))
    (hidden)))

;;; ─── Activity (spinner + rotating verb) ────────────────────
;;;
;;; Single source of motion for the whole UI. Shown only while the
;;; agent is actively working (streaming / tool running); hidden at
;;; idle so the status-line stops re-rendering and the terminal
;;; scroll position can be held still.
;;;
;;; Lives in the status-line (a fixed 1-row region) by design —
;;; replaces the previous per-component Spinners in ReasoningBlock
;;; and ToolStartStatus, which leaked lines into terminal scrollback
;;; whenever the dynamic region touched the viewport bottom (each
;;; 80 ms tick's trailing `\n` scrolled the top line out of
;;; log-update's erase reach). A 1-row region can't overflow, so the
;;; same tick here is cosmetic, not catastrophic.
;;;
;;; Context keys read:
;;;   :activity      bool — is the agent working right now?
;;;   :spinner-frame int  — index into SPINNER-FRAMES; rotated by
;;;                         StatusLine's useEffect at ~80 ms cadence.
;;;   :verb          str  — current whimsy verb; rotated by the same
;;;                         useEffect at ~2500 ms cadence.

(def SPINNER-FRAMES
  "Same 10-frame dots spinner as ink-spinner's 'dots' style, inlined
   so we don't keep the package just for this one glyph list."
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(def ACTIVITY-VERBS
  "Whimsy verbs shown alongside the spinner. Rotated every few
   seconds while the agent is working. Keeping the list inside the
   segments module (rather than a user-facing setting) means adding
   or trimming a verb is a one-line change with no migration cost."
  ["Thinking"     "Pondering"   "Musing"       "Cogitating"
   "Contemplating" "Reasoning"  "Ruminating"   "Deliberating"
   "Reflecting"   "Brewing"     "Conjuring"    "Untangling"
   "Crafting"     "Noodling"    "Scheming"     "Divining"])

(defn- role-seg [{:keys [active-role theme]}]
  (if (and (seq active-role) (not= active-role "default"))
    (visible (str "[" active-role "]")
             (get-in theme [:colors :warning] "#e0af68"))
    (hidden)))

(defn- activity-seg [{:keys [activity spinner-frame verb theme]}]
  (if activity
    (let [frame (nth SPINNER-FRAMES
                     (mod (or spinner-frame 0) (count SPINNER-FRAMES)))
          v     (or verb (first ACTIVITY-VERBS))]
      (visible (str frame " " v "…")
               (get-in theme [:colors :warning] "#e0af68")))
    (hidden)))

;;; ─── Install built-ins ──────────────────────────────────

(def builtin-segments
  [["model"         :agent    model-seg]
   ["role"          :agent    role-seg]
   ["path"          :workspace path-seg]
   ["git"           :workspace git-seg]
   ["pr"            :workspace pr-seg]
   ["subagents"     :agent    subagents-seg]
   ["token-in"      :usage    token-in-seg]
   ["token-out"     :usage    token-out-seg]
   ["token-total"   :usage    token-total-seg]
   ["token-rate"    :usage    token-rate-seg]
   ["cost"          :usage    cost-seg]
   ["context-pct"   :usage    context-pct-seg]
   ["context-total" :usage    context-total-seg]
   ["time-spent"    :session  time-spent-seg]
   ["time"          :session  time-seg]
   ["session"       :session  session-seg]
   ["hostname"      :workspace hostname-seg]
   ["cache-read"    :usage    cache-read-seg]
   ["cache-write"   :usage    cache-write-seg]
   ["activity"      :agent    activity-seg]])

;; Activity segment auto-appends so users see the spinner + verb
;; without having to edit their preset. Position :left places it
;; alongside model / path / git on the left of the status line.
(def ^:private auto-append-spec
  {"activity" :left
   "role"     :left})

(defn install-builtins!
  "Install every built-in into the module-level registry. Safe to call
   multiple times."
  []
  (doseq [[id cat render] builtin-segments]
    (let [base    {:category cat :render render}
          with-ap (if-let [pos (get auto-append-spec id)]
                    (assoc base :auto-append? true :position pos)
                    base)]
      (register-segment id with-ap))))

;; Register built-ins at module load.
(install-builtins!)

;;; ─── Layout helpers ─────────────────────────────────────

(defn render-segments
  "Invoke the :render fn of each segment id in `ids` against `context`.
   Returns a vector of {:id :content :color} for visible segments only."
  [ids context]
  (->> ids
       (keep (fn [id]
               (when-let [seg (get-segment id)]
                 (when-let [r ((:render seg) context)]
                   (when (:visible? r)
                     {:id (str id)
                      :content (:content r)
                      :color   (:color r)})))))
       vec))
