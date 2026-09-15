(ns agent.ui.app-reducers
  "Pure reducers for the interactive TUI state.

   These live here — not inline in modes/interactive — so they are reachable from tests
   without mounting a TUI: the tool-execution message-list reducers
   (`apply-tool-start`, `apply-tool-end`, `apply-tool-update`), which the
   mode's `on-start`/`on-end`/`on-update` event handlers are thin wrappers
   around, plus `make-submit-guard`.

   A set of bracketed-paste edit-suppression helpers used to live here too
   (`make-guarded-setter`, `make-ink-paste-fn`, `make-stdin-paste-handler`),
   left over from the ink UI. They were dead: pi-tui's Editor collapses a
   >10-line paste into its own `[paste #1 +50 lines]` marker (pi-tui README,
   \"Large paste handling\"), so nothing wired them up and nothing should.")

;;; ─── Tool execution reducers ─────────────────────────────

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn find-tool-start-idx
  "Return the vector index of the `tool-start` message whose :exec-id
   matches id, or nil if none exists."
  [prev-v id]
  (loop [i 0]
    (if (< i (count prev-v))
      (let [msg (nth prev-v i)]
        (if (and (= (:role msg) "tool-start")
                 (= (:exec-id msg) id))
          i
          (recur (inc i))))
      nil)))

(defn apply-tool-start
  "Append a `tool-start` message built from a `tool_execution_start` event
   payload. Extension-supplied `custom-*` fields are copied when present so
   the renderer can show a specialized header."
  [prev data verbosity max-lines]
  (let [msg (cond-> {:role      "tool-start"
                     :tool-name (get data :toolName)
                     :args      (get data :args)
                     :exec-id   (get data :execId)
                     :id        (new-id)
                     :verbosity (or (get data :customVerbosity) verbosity)
                     :max-lines max-lines
                     :expanded  (= "expanded" (or (get data :customVerbosity) verbosity))}
              (get data :customOneLineArgs)  (assoc :custom-one-line-args (get data :customOneLineArgs))
              (get data :customStatusText)    (assoc :custom-status-text (get data :customStatusText))
              (get data :customIcon)           (assoc :custom-icon (get data :customIcon)))]
    (conj (vec prev) msg)))

(defn apply-tool-end
  "Replace the matching `tool-start` with a `tool-end` built from a
   `tool_execution_end` event payload.

   The end payload now carries :args too, but we still prefer the :args
   copied from the start message we're about to replace so downstream
   consumers — notably `group-messages` and `tool-group-label` — render
   a meaningful label (e.g. the glob pattern or read path) even if a
   future emit drops it.

   When no matching start exists (can happen if the tool registered
   lifecycle events out of order), the end message is appended without
   :args."
  [prev data verbosity max-lines]
  (let [prev-v     (vec prev)
        exec-id    (get data :execId)
        idx        (find-tool-start-idx prev-v exec-id)
        start-msg  (when idx (nth prev-v idx))
        ;; Prefer the start message's args; fall back to the end payload's —
        ;; covers out-of-order lifecycle events (end with no matching start).
        start-args (or (when start-msg (:args start-msg))
                       (get data :args))
        start-id   (when start-msg (:id start-msg))
        end-msg    (cond-> {:role      "tool-end"
                            :tool-name (get data :toolName)
                            :duration  (get data :duration)
                            :result    (get data :result)
                            :exec-id   exec-id
                            :verbosity (or (get data :customVerbosity) verbosity)
                            :max-lines max-lines
                            ;; The renderer reads this; the setting decides the
                            ;; default and ctrl+o flips it. A flip made while
                            ;; the tool was still running lives on the start
                            ;; message; carry it over, or finishing undoes it.
                            ;; Untouched, the end payload's own verbosity wins.
                            :expanded  (if (and start-msg
                                                (some? (:expanded start-msg))
                                                (not= (:expanded start-msg)
                                                      (= "expanded" (:verbosity start-msg))))
                                         (:expanded start-msg)
                                         (= "expanded" (or (get data :customVerbosity) verbosity)))}
                     start-id                          (assoc :id start-id)
                     start-args                         (assoc :args start-args)
                     ;; The end payload has no customOneLineArgs — only the
                     ;; start event carries it — so without this the finished
                     ;; line drops back to the generic arg preview.
                     (and start-msg (:custom-one-line-args start-msg))
                     (assoc :custom-one-line-args (:custom-one-line-args start-msg))
                     ;; middleware emits :isError; without copying it the
                     ;; renderer had no way to tell a failure from a success
                     ;; and drew "✓" on both.
                     (get data :isError)             (assoc :is-error true)
                     (get data :customOneLineResult) (assoc :custom-one-line-result (get data :customOneLineResult))
                     (get data :customIcon)            (assoc :custom-icon (get data :customIcon)))]
    (if (some? idx)
      (assoc prev-v idx end-msg)
      (conj prev-v end-msg))))

(defn apply-tool-update
  "Handle a `tool_execution_update` event: find the matching in-flight
   `tool-start` and update its :custom-status-text. If no match,
   prev is returned unchanged (not an error — updates can arrive
   after the end event flushes the start message)."
  [prev data]
  (let [prev-v (vec prev)
        idx    (find-tool-start-idx prev-v (get data :execId))]
    (if (some? idx)
      (assoc-in prev-v [idx :custom-status-text] (str (get data :data)))
      prev-v)))

(defn tool-toggle-expanded
  "Flip `:expanded` on the LAST tool message (ctrl+o). Returns prev unchanged
   when there is no tool message.

   The message map is REPLACED, not mutated: chat-pane's line cache is a
   WeakMap keyed on the message object, so a fresh object is what makes the
   next frame re-render that entry."
  [prev]
  (let [v   (vec prev)
        idx (last (keep-indexed (fn [i m]
                                  (when (contains? #{"tool-start" "tool-end"} (:role m)) i))
                                v))]
    (if (some? idx)
      (update v idx (fn [m] (assoc m :expanded (not (:expanded m)))))
      v)))

(defn make-submit-guard
  "Wrap submit-fn so that:
   - empty text is rejected immediately (returns nil, no side effects)
   - concurrent calls are blocked via submit-lock-ref until the previous
     call's Promise settles  (prevents key-repeat / double-Enter duplication)
   submit-lock-ref must have a `.current` property (a React useRef object or
   a plain JS object like #js {:current false})."
  [submit-lock-ref submit-fn]
  (fn [text]
    (when (and (pos? (.-length (str text)))
               (not (.-current submit-lock-ref)))
      (set! (.-current submit-lock-ref) true)
      (-> (submit-fn text)
          (.then  (fn [_] (set! (.-current submit-lock-ref) false)))
          (.catch (fn [e]
                    (set! (.-current submit-lock-ref) false)
                    (throw e)))))))
