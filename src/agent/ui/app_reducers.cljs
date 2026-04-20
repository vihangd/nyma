(ns agent.ui.app-reducers
  "Pure helpers extracted from agent.ui.app.

   These live here — not inline in App — so they are reachable from tests
   without mounting React. Two distinct sets of logic are covered:

   1. Tool-execution message-list reducers (`apply-tool-start`,
      `apply-tool-end`, `apply-tool-update`). The App's `on-start`/`on-end`/
      `on-update` event handlers are thin wrappers around these.
   2. Bracketed-paste edit-suppression helpers (`make-guarded-setter`,
      `make-ink-paste-fn`, `make-stdin-paste-handler`). The App wires these
      into React refs and the ink / stdin event buses.

   Neither group touches React or ink directly — all external state
   (refs, setters, schedulers) is passed in, which is also the reason
   tests can stub it trivially.")

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
                     :tool-name (get data :tool-name)
                     :args      (get data :args)
                     :exec-id   (get data :exec-id)
                     :id        (new-id)
                     :verbosity (or (get data :custom-verbosity) verbosity)
                     :max-lines max-lines}
              (get data :custom-one-line-args)  (assoc :custom-one-line-args (get data :custom-one-line-args))
              (get data :custom-status-text)    (assoc :custom-status-text (get data :custom-status-text))
              (get data :custom-icon)           (assoc :custom-icon (get data :custom-icon)))]
    (conj (vec prev) msg)))

(defn apply-tool-end
  "Replace the matching `tool-start` with a `tool-end` built from a
   `tool_execution_end` event payload.

   Critically: the end event payload emitted by middleware does NOT carry
   :args. We copy :args over from the start message we're about to
   replace so downstream consumers — notably `group-messages` and
   `tool-group-label` — can still render a meaningful label (e.g. the
   glob pattern or read path) instead of the literal '?' fallback.

   When no matching start exists (can happen if the tool registered
   lifecycle events out of order), the end message is appended without
   :args."
  [prev data verbosity max-lines]
  (let [prev-v     (vec prev)
        exec-id    (get data :exec-id)
        idx        (find-tool-start-idx prev-v exec-id)
        start-msg  (when idx (nth prev-v idx))
        start-args (when start-msg (:args start-msg))
        start-id   (when start-msg (:id start-msg))
        end-msg    (cond-> {:role      "tool-end"
                            :tool-name (get data :tool-name)
                            :duration  (get data :duration)
                            :result    (get data :result)
                            :exec-id   exec-id
                            :verbosity (or (get data :custom-verbosity) verbosity)
                            :max-lines max-lines}
                     start-id                           (assoc :id start-id)
                     start-args                         (assoc :args start-args)
                     (get data :custom-one-line-result) (assoc :custom-one-line-result (get data :custom-one-line-result))
                     (get data :custom-icon)            (assoc :custom-icon (get data :custom-icon)))]
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
        idx    (find-tool-start-idx prev-v (get data :exec-id))]
    (if (some? idx)
      (assoc-in prev-v [idx :custom-status-text] (str (get data :data)))
      prev-v)))

;;; ─── Bracketed-paste edit suppression ────────────────────

(defn make-guarded-setter
  "Build a wrapper around a React-style setter that drops direct (string)
   setState calls while `block-ref.current` is truthy, but always lets
   functional updates through.

   Functional updates must pass unconditionally because the paste
   state-machine appends its `[paste #N +X lines]` marker using
   `(fn [prev] (str prev marker))` — if we suppressed that, the marker
   would never land and the user would see nothing.

   Direct string updates come from ink-text-input's onChange calls during
   the three-event split of a bracketed paste. Those are the ones we
   want to drop."
  [block-ref set-value]
  (fn [v]
    (if (fn? v)
      (set-value v)
      (when-not (.-current block-ref)
        (set-value v)))))

(defn make-ink-paste-fn
  "Build an 'input' listener for ink's internal_eventEmitter.

   On the paste-start sequence it sets `block-ref.current` to true
   synchronously. On the paste-end sequence it defers clearing via
   `schedule-clear` — the clear must not happen in the same tick as
   TextInput's synchronous useInput handler for that same event, or the
   ref would already be false by the time TextInput tries to append the
   literal '[201~' to the editor value.

   `schedule-clear` should be something like `#(js/setTimeout % 0)` in
   production; tests inject a collector so they can run the pending
   callback manually."
  [block-ref schedule-clear]
  (fn [raw]
    (cond
      (= raw "\u001b[200~")
      (set! (.-current block-ref) true)

      (= raw "\u001b[201~")
      (schedule-clear
       (fn [] (set! (.-current block-ref) false))))))

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

(defn make-stdin-paste-handler
  "Build the process.stdin 'data' listener responsible for running the
   bracketed-paste state machine and appending the `[paste #N]` marker
   to the editor. Short-circuits when an overlay (dialog, picker) is
   active: in that case ink's TextInput receives the paste bytes as
   normal keystrokes and we must not also consume them here."
  [process-fn overlay-ref set-value]
  (fn [data]
    (when-not (.-current overlay-ref)
      (let [result (process-fn data)]
        (when (and (:handled result) (:marker result))
          (set-value (fn [prev] (str (or prev "") (:marker result)))))))))
