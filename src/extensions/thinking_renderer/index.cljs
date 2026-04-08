(ns extensions.thinking-renderer.index
  "Enhanced collapsible thinking/reasoning display with token counting.
   Listens to acp_thought (ACP agents) and reasoning_delta (native provider)
   events and renders thinking in a widget with auto-collapse on turn completion.")

;;; ─── State ────────────────────────────────────────────────────

(def thinking-text
  "Accumulated thinking text for the current turn."
  (atom ""))

(def thinking-tokens
  "Estimated token count for accumulated thinking."
  (atom 0))

(def collapsed?
  "Whether the thinking widget is currently collapsed."
  (atom false))

(def last-estimate-ms
  "Timestamp of last token estimation (debounce at 500ms)."
  (atom 0))

;;; ─── Formatting ───────────────────────────────────────────────

(def ^:private max-lines 15)
(def ^:private estimate-interval-ms 500)

(defn- format-k
  "Format a number as 'Nk' for thousands."
  [n]
  (if (and n (> n 0))
    (if (>= n 1000)
      (str (.toFixed (/ n 1000) 1) "k")
      (str n))
    "0"))

(defn- format-expanded
  "Build expanded widget lines: header + last N lines of thinking text."
  [text tokens]
  (let [lines    (.split text "\n")
        total    (.-length lines)
        visible  (if (> total max-lines)
                   (.slice lines (- total max-lines))
                   lines)
        header   (str "💭 Thinking (" (format-k tokens) " tokens)")
        body     (mapv (fn [line] (str "│ " line)) visible)
        ellipsis (when (> total max-lines)
                   [(str "│ ... " (- total max-lines) " lines above")])]
    (into [header] (concat ellipsis body))))

(defn- format-collapsed
  "Build collapsed widget line."
  [tokens]
  [(str "💭 Thinking [" (format-k tokens) " tokens] ▸")])

;;; ─── Widget rendering ─────────────────────────────────────────

(defn- update-widget!
  "Re-render the thinking widget based on current state."
  [api]
  (when (and (.-ui api) (.-available (.-ui api)))
    ;; Check visibility flag
    (let [visible (.getFlag api "visible")]
      (if (and (some? visible) (not visible))
        ;; Hidden — clear widget
        (.clearWidget (.-ui api) "thinking")
        ;; Visible — render based on collapsed state
        (let [text   @thinking-text
              tokens @thinking-tokens]
          (if (empty? text)
            (.clearWidget (.-ui api) "thinking")
            (let [lines (if @collapsed?
                          (format-collapsed tokens)
                          (format-expanded text tokens))]
              (.setWidget (.-ui api) "thinking" (clj->js lines) "above"))))))))

(defn- maybe-estimate-tokens!
  "Estimate tokens for accumulated text, debounced to every 500ms."
  [api]
  (let [now (js/Date.now)]
    (when (> (- now @last-estimate-ms) estimate-interval-ms)
      (reset! last-estimate-ms now)
      (when (.-estimateTokens api)
        (reset! thinking-tokens (.estimateTokens api @thinking-text))))))

;;; ─── Event handlers ───────────────────────────────────────────

(defn- on-acp-thought
  "Handle acp_thought event from ACP agents."
  [api]
  (fn [data _ctx]
    (when-let [text (.-text data)]
      (when (seq text)
        (swap! thinking-text str text)
        (reset! collapsed? false)
        (maybe-estimate-tokens! api)
        (update-widget! api)))))

(defn- on-reasoning-delta
  "Handle reasoning_delta event from native provider (AI SDK)."
  [api]
  (fn [data _ctx]
    (when-let [text (.-delta data)]
      (when (seq text)
        (swap! thinking-text str text)
        (reset! collapsed? false)
        (maybe-estimate-tokens! api)
        (update-widget! api)))))

(defn- on-turn-end
  "Auto-collapse thinking when the response completes."
  [api]
  (fn [_data _ctx]
    (when (seq @thinking-text)
      ;; Final token estimate
      (when (.-estimateTokens api)
        (reset! thinking-tokens (.estimateTokens api @thinking-text)))
      (reset! collapsed? true)
      (update-widget! api))))

(defn- on-turn-start
  "Reset state for a new turn."
  [api]
  (fn [_data _ctx]
    (reset! thinking-text "")
    (reset! thinking-tokens 0)
    (reset! collapsed? false)
    (reset! last-estimate-ms 0)
    (when (and (.-ui api) (.-available (.-ui api)))
      (.clearWidget (.-ui api) "thinking"))))

;;; ─── Activation ───────────────────────────────────────────────

(defn ^:export default
  "Extension activation. Registers flags, wires events, returns deactivator."
  [api]
  ;; Register flags
  (.registerFlag api "active"
    #js {:description "Signal to other extensions that thinking-renderer is active"
         :default     true})
  (.registerFlag api "visible"
    #js {:description "Toggle thinking display visibility"
         :default     true})

  ;; Create event handlers
  (let [h-thought   (on-acp-thought api)
        h-reasoning (on-reasoning-delta api)
        h-turn-end  (on-turn-end api)
        h-turn-start (on-turn-start api)]

    ;; Wire events
    (.on api "acp_thought" h-thought)
    (.on api "reasoning_delta" h-reasoning)
    (.on api "turn_end" h-turn-end)
    (.on api "turn_start" h-turn-start)

    ;; Return deactivator
    (fn []
      (.off api "acp_thought" h-thought)
      (.off api "reasoning_delta" h-reasoning)
      (.off api "turn_end" h-turn-end)
      (.off api "turn_start" h-turn-start)
      ;; Clear widget
      (when (and (.-ui api) (.-available (.-ui api)))
        (.clearWidget (.-ui api) "thinking"))
      ;; Reset state
      (reset! thinking-text "")
      (reset! thinking-tokens 0)
      (reset! collapsed? false))))
