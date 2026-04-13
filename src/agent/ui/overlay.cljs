(ns agent.ui.overlay
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useCallback]]
            ["ink" :refer [Box Text useInput useStdout]]))

;;; ─── Key mapping ────────────────────────────────────────────

(defn- map-key
  "Convert Ink's (input, key) pair to a pi-mono compatible key string.
   Covers all keys used by pi-mono extensions (snake, doom, modal-editor, etc.)."
  [input key]
  (cond
    (.-escape key)    "escape"
    (.-return key)    "enter"
    (.-tab key)       "tab"
    (.-upArrow key)   "up"
    (.-downArrow key) "down"
    (.-leftArrow key) "left"
    (.-rightArrow key) "right"
    (.-backspace key) "backspace"
    (.-delete key)    "delete"
    (= input " ")     "space"
    :else             input))

;;; ─── CustomComponentAdapter ─────────────────────────────────

(defn- call-render
  "Safely call component.render(w, h) and normalize the result to a string."
  [component width height]
  (if-let [render-fn (.-render component)]
    (try
      (let [rendered (render-fn width height)]
        (cond
          (nil? rendered)   ""
          (array? rendered) (.join rendered "\n")
          :else             (str rendered)))
      (catch :default e
        (str "[Render error] " (.-message e))))
    ""))

(defn CustomComponentAdapter
  "Adapter that bridges pi-mono's {render, onInput, interval} object interface
   into an Ink component.

   Supported component properties:
     .render(width, height)      → string | string[]  (text output)
     .onInput(input, key)        → {close: true, value?: any} | Promise<same> | void
                                  Called with Ink's (input, key) pair. Pickers
                                  typically branch on (.-escape key), (.-return key),
                                  etc. When no onInput is defined, ESC closes by
                                  default.
     .interval                   → number (ms) for automatic re-rendering (e.g. games)
     .dispose()                  → cleanup called when component unmounts

   The adapter exposes .invalidate() on the component for manual re-render triggers."
  [{:keys [component onClose onResolve]}]
  (let [{:keys [stdout]}      (useStdout)
        width                 (or (.-columns stdout) 80)
        height                (or (.-rows stdout) 24)

        ;; Compute initial output synchronously so first frame has content
        [tick set-tick]       (useState 0)
        [output set-output]   (useState (fn [] (call-render component width height)))

        ;; invalidate triggers a re-render by bumping tick
        invalidate (useCallback
                    (fn [] (set-tick (fn [t] (inc t))))
                    #js [])]

    ;; Expose invalidate to the component + set up interval timer
    (useEffect
     (fn []
       (set! (.-invalidate component) invalidate)
        ;; If component has .interval, set up automatic re-rendering
       (if-let [ms (.-interval component)]
         (let [id (js/setInterval invalidate ms)]
           (fn []
             (js/clearInterval id)
             (when-let [dispose (.-dispose component)]
               (try (dispose) (catch :default _)))))
          ;; Cleanup: call dispose on unmount
         (fn []
           (when-let [dispose (.-dispose component)]
             (try (dispose) (catch :default _))))))
     #js [component invalidate])

    ;; Re-render when dimensions or tick changes (not on mount — already computed)
    (useEffect
     (fn []
       (set-output (call-render component width height))
       js/undefined)
     #js [width height component tick])

    ;; Forward keyboard input to component.onInput
    (useInput
     (fn [input key]
       (let [key-str (map-key input key)]
         (if-let [on-input (.-onInput component)]
            ;; Component has its own input handler — pass Ink's (input, key)
            ;; pair so pickers can destructure key.-escape / key.-return /
            ;; key.-upArrow etc. Passing only a mapped key-str would cause
            ;; them to throw TypeError on the undefined second argument.
           (try
             (let [result (.call on-input component input key)]
                ;; Handle async (Promise) results
               (if (and result (.-then result))
                 (-> result
                     (.then (fn [r]
                              (if (and r (.-close r))
                                (do (when onResolve (onResolve (.-value r)))
                                    (onClose))
                                (invalidate))))
                     (.catch (fn [e]
                               (js/console.error "[nyma] onInput async error:" e))))
                  ;; Sync result
                 (if (and result (.-close result))
                   (do (when onResolve (onResolve (.-value result)))
                       (onClose))
                    ;; Not closing — component may have mutated internal
                    ;; state (filter text, selection index, etc.). Force
                    ;; a re-render so the next frame reflects that state.
                   (invalidate))))
             (catch :default e
               (js/console.error "[nyma] onInput error:" e)))
            ;; No onInput handler — ESC closes by default
           (when (= key-str "escape")
             (when onResolve (onResolve nil))
             (onClose))))))

    ;; Paint every cell of the picker output. ink's <Box> doesn't
    ;; fill its interior, so without a backgroundColor on the Text
    ;; itself the chat view underneath bleeds through — the overlay
    ;; looks semi-transparent and is hard to read. Paired with
    ;; picker-frame's pad-lines, every row is a uniform-width block
    ;; with a solid background.
    #jsx [Box {:flexDirection  "column"
               :backgroundColor "#1a1b26"}
          [Text {:backgroundColor "#1a1b26"} output]]))

;;; ─── Editor adapter ─────────────────────────────────────────

(defn- EditorAdapter
  "Adapter for pi-mono's {type: 'editor', initialValue, language} pattern.
   Renders a simple multi-line text display with the initial value."
  [{:keys [component onClose onResolve]}]
  (let [[value set-value] (useState (or (.-initialValue component) ""))]
    (useInput
     (fn [input key]
       (cond
         (.-escape key)
         (do (when onResolve (onResolve nil))
             (onClose))
         (.-return key)
         (do (when onResolve (onResolve value))
             (onClose)))))

    #jsx [Box {:flexDirection "column"}
          [Text {:bold true} (str "Editor" (when-let [lang (.-language component)]
                                             (str " (" lang ")")))]
          [Box {:borderStyle "single" :borderColor "#3b4261"
                :paddingLeft 1 :paddingRight 1 :marginTop 1}
           [Text value]]
          [Box {:marginTop 1}
           [Text {:color "#565f89"} "Enter accept  Esc cancel"]]]))

;;; ─── Detection helpers ──────────────────────────────────────

(defn- custom-render-obj?
  "True if children is a plain object with a .render method (pi-mono pattern)."
  [children]
  (and (some? children)
       (not (string? children))
       (not (number? children))
       (.-render children)))

(defn- editor-obj?
  "True if children is a plain object with type='editor' (pi-mono editor pattern)."
  [children]
  (and (some? children)
       (not (string? children))
       (not (number? children))
       (= (.-type children) "editor")))

;;; ─── Overlay ────────────────────────────────────────────────

(defn Overlay [{:keys [onClose children transparent]}]
  (let [is-custom   (custom-render-obj? children)
        is-editor   (editor-obj? children)
        ;; Extract the resolve callback attached by ui.custom() in app.cljs
        on-resolve  (when (and (some? children) (not (string? children)) (not (number? children)))
                      (.-__resolve children))]

    ;; Always call useInput (Rules of Hooks) — handler is conditional
    (useInput
     (fn [_input key]
       (when (and (not is-custom) (not is-editor) (.-escape key))
         (when on-resolve (on-resolve nil))
         (onClose))))

    #jsx [Box {:flexDirection "column"
               :borderStyle (if transparent "none" "round")
               :borderColor (when-not transparent "#7aa2f7")
               ;; Solid background so the chat view underneath doesn't
               ;; bleed through the gaps. ink's <Box> doesn't fill its
               ;; interior natively — the fill comes from the children
               ;; + padding cells getting the same backgroundColor.
               :backgroundColor (when-not transparent "#1a1b26")
               :paddingX (if transparent 0 2)
               :paddingY (if transparent 0 1)
               :width (if transparent "100%" "60%")
               :alignSelf "center"}
          (cond
            ;; String/number → render as text
            (or (string? children) (number? children))
            #jsx [Text (str children)]

            ;; Pi-compat: {type: "editor"} → EditorAdapter
            is-editor
            #jsx [EditorAdapter {:component children :onClose onClose :onResolve on-resolve}]

            ;; Pi-compat: object with .render method → CustomComponentAdapter
            is-custom
            #jsx [CustomComponentAdapter {:component children :onClose onClose :onResolve on-resolve}]

            ;; React element → render directly
            :else
            children)]))
