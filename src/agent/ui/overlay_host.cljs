(ns agent.ui.overlay-host
  "Backs `api.ui`'s overlay surface with pi-tui's native overlay stack.

   nyma declares `showOverlay`/`select`/`confirm`/`input`/`custom` on the
   extension UI object (agent.extensions) but the interactive TUI never
   assigned them, so every picker in the repo fell through to its text
   fallback. pi-tui already ships the machinery — `TUI.showOverlay` returns
   an `OverlayHandle` with anchoring, focus capture and hide/show — so this
   namespace is just the adapter between the two contracts.

   Two impedance mismatches it resolves:

   1. **Component shape.** nyma pickers are pi-mono style
      `#js {:render (fn [w h] -> string) :onInput (fn [input key]) :dispose}`.
      pi-tui wants `{render(width) -> string[], handleInput?(data), invalidate()}`.
   2. **Key events.** pi-tui hands `handleInput` the raw terminal bytes;
      `agent.ui.picker-input/dispatch-input` expects an ink-style
      `(input, key)` pair. `matchesKey` from pi-tui does the decoding.

   Width note: pickers compute their own row cap with
   `picker-frame/overlay-max-width`, which expects the TERMINAL width and
   subtracts the overlay's 60% + chrome itself. pi-tui passes the overlay's
   already-narrowed inner width to `render`, so we deliberately hand the
   picker the terminal width instead — otherwise the 60% would be applied
   twice and every picker would render as a sliver."
  (:require ["@mariozechner/pi-tui" :refer [matchesKey]]
            [clojure.string :as str]
            [agent.ui.picker-frame :refer [render-frame overlay-max-width]]
            [agent.ui.picker-input :refer [dispatch-input]]))

;; Matches the 60% assumed by `picker-frame/overlay-max-width`.
(def default-overlay-options
  #js {:width "60%" :minWidth 30 :maxHeight "70%" :anchor "center"})

;; Quick pickers (model switcher) sit at the bottom, out of the way of the
;; transcript — borrowed from oh-my-pi's model-picker, which shows its
;; non-fullscreen picker with `{ anchor: "bottom-center" }`.
(def bottom-overlay-options
  #js {:width "60%" :minWidth 30 :maxHeight "50%" :anchor "bottom-center"})

;; ── Key translation ──────────────────────────────────────────────

(defn printable-char
  "The character `dispatch-input` should treat as typed text, or nil.

   Ctrl+P / Ctrl+N are reported as their bare letter with the ctrl flag set,
   because `dispatch-input` tests `(and (.-ctrl key) (= input \"p\"))`."
  [data]
  (cond
    (matchesKey data "ctrl+p") "p"
    (matchesKey data "ctrl+n") "n"
    (and (string? data)
         (= (count data) 1)
         (>= (.charCodeAt data 0) 32)
         (not= (.charCodeAt data 0) 127)) data
    :else nil))

(defn data->key
  "Build the ink-style key object `dispatch-input` branches on."
  [data]
  #js {:escape    (matchesKey data "escape")
       :return    (or (matchesKey data "enter") (matchesKey data "return"))
       :upArrow   (matchesKey data "up")
       :downArrow (matchesKey data "down")
       :backspace (matchesKey data "backspace")
       :delete    (matchesKey data "delete")
       :tab       (matchesKey data "tab")
       :ctrl      (or (matchesKey data "ctrl+p") (matchesKey data "ctrl+n"))})

(defn resolving-key?
  "True for the keys that end a picker's life. Every picker nyma ships is a
   single-shot filter picker built on `dispatch-input`, which only calls
   on-select (Enter) or on-cancel (Escape) — so the host can dismiss on
   these without the picker needing to signal completion. `ui.custom` is
   fire-and-forget (the resolve callback is closed over before the call),
   so there is no other dismissal signal available."
  [data]
  (or (matchesKey data "enter")
      (matchesKey data "return")
      (matchesKey data "escape")))

;; ── Component adapter ────────────────────────────────────────────

(defn adapt-component
  "Wrap a pi-mono `{render, onInput, dispose}` picker as a pi-tui Component.
   `get-width`/`get-height` are thunks returning TERMINAL dimensions (see the
   ns docstring); `after-input` runs with the raw data after each keypress."
  [picker {:keys [get-width get-height after-input]}]
  #js {:render
       (fn [width]
         (let [w (or (when get-width (get-width)) width)
               h (or (when get-height (get-height)) 24)
               s ((.-render picker) w h)]
           (.split (str s) "\n")))

       :handleInput
       (fn [data]
         (when-let [on-input (.-onInput picker)]
           (on-input (printable-char data) (data->key data)))
         (when after-input (after-input data))
         nil)

       :invalidate (fn [] nil)})

;; ── Built-in pickers for select / confirm / input ────────────────

(defn- item-label [it]
  (or (.-label it) (.-value it) (str it)))

(defn- item-description [it]
  (str (or (.-description it) "")))

(defn- filter-items [query items]
  (if (empty? query)
    (vec items)
    (let [q (str/lower-case query)]
      (filterv (fn [it]
                 (str/includes?
                  (str/lower-case (str (item-label it) " " (item-description it)))
                  q))
               items))))

(defn make-select-picker
  "Filter picker over `items` (JS objects with .label/.value/.description).
   `on-resolve` receives the chosen item, or nil on cancel."
  [prompt items on-resolve]
  (let [filter-text  (atom "")
        selected-idx (atom 0)]
    #js {:render
         (fn [w _h]
           (render-frame
            {:title         (str prompt "  (type to filter, Enter to select, Esc to cancel)")
             :prompt-prefix "> "
             :filter-text   @filter-text
             :items         (filter-items @filter-text items)
             :selected-idx  @selected-idx
             :max-visible   12
             :max-width     (overlay-max-width w)
             :render-item   (fn [it _focused?]
                              (let [d (item-description it)]
                                (str (item-label it)
                                     (when (seq d) (str "  — " d)))))
             :no-match-text "No matches"}))

         :onInput
         (dispatch-input
          {:filter-text-atom  filter-text
           :selected-idx-atom selected-idx
           :filtered-fn       (fn [] (filter-items @filter-text items))
           :on-select         (fn [it] (on-resolve it))
           :on-cancel         (fn [] (on-resolve nil))})

         :dispose (fn [] nil)}))

(defn make-input-picker
  "Single-line text entry. `on-resolve` receives the string, or nil on cancel.

   Deliberately not built on `render-frame` — that renders a windowed list
   and this is one line. Width is still capped so a long value can't overflow
   the overlay box; pi-tui wraps over-wide rows, and a wrapped row throws off
   the differential renderer's line accounting."
  [prompt placeholder on-resolve]
  (let [text (atom "")]
    #js {:render
         (fn [w _h]
           (let [cap   (overlay-max-width w)
                 shown (if (empty? @text) (str (or placeholder "")) @text)
                 body  (str "> " shown)]
             (str prompt "  (Enter to accept, Esc to cancel)" "\n"
                  (subs body 0 (min (count body) cap)))))

         :onInput
         (fn [input key]
           (cond
             (.-escape key) (on-resolve nil)
             (.-return key) (on-resolve @text)

             (or (.-backspace key) (.-delete key))
             (swap! text (fn [s] (if (empty? s) "" (subs s 0 (dec (count s))))))

             (.-tab key) nil

             (and input (= (count input) 1))
             (swap! text str input)))

         :dispose (fn [] nil)}))

(defn make-text-overlay
  "Scrollable read-only text overlay.

   `showOverlay` in the pi-mono contract takes a STRING, not a component —
   every caller in the repo passes text (`builtins/show-info`,
   `stats_dashboard`, the `tool_dsl` macro). Long content (a stats dashboard)
   would otherwise overflow the box, so this windows the lines and scrolls
   with the arrow keys; Enter/Esc dismiss via the host's auto-dismiss."
  [text]
  (let [scroll (atom 0)]
    #js {:render
         (fn [w h]
           (let [cap     (overlay-max-width w)
                 lines   (.split (str text) "\n")
                 total   (.-length lines)
                 visible (max 3 (- (min (or h 24) 30) 4))
                 max-top (max 0 (- total visible))
                 top     (min @scroll max-top)
                 window  (.slice lines top (+ top visible))
                 more?   (> total visible)
                 header  (when more?
                           (str "(" (inc top) "-" (min total (+ top visible))
                                " of " total " lines — ↑/↓ scroll, Esc to close)\n"))]
             (reset! scroll top)
             (str header
                  (str/join "\n"
                            (map (fn [l] (subs (str l) 0 (min (count (str l)) cap)))
                                 window)))))

         :onInput
         (fn [_input key]
           (cond
             (.-upArrow key)   (swap! scroll (fn [n] (max 0 (dec n))))
             (.-downArrow key) (swap! scroll inc)))

         :dispose (fn [] nil)}))

;; ── Installation ─────────────────────────────────────────────────

(defn install!
  "Assign the overlay-backed `api.ui` slots on `ui`, driven by `tui`.

   `restore-focus` is called after every dismissal so the editor regains
   focus; `request-render` schedules a repaint."
  [ui tui {:keys [restore-focus request-render]}]
  (let [term       (.-terminal tui)
        get-width  (fn [] (or (.-columns term) 80))
        get-height (fn [] (or (.-rows term) 24))
        rerender   (fn [] (when request-render (request-render)))
        refocus    (fn [] (when restore-focus (restore-focus)))

        ;; Show `picker`, returning a 0-arg dismiss fn. `auto-dismiss?`
        ;; closes the overlay on Enter/Escape (the ui.custom contract);
        ;; the promise-returning wrappers dismiss explicitly instead.
        show!
        (fn [picker options auto-dismiss?]
          (let [handle (atom nil)
                closed (atom false)
                done   (fn []
                         (when-not @closed
                           (reset! closed true)
                           (when-let [h @handle] (.hide h))
                           (when-let [d (.-dispose picker)] (d))
                           (refocus)
                           (rerender)))
                comp   (adapt-component
                        picker
                        {:get-width   get-width
                         :get-height  get-height
                         :after-input (fn [data]
                                        (if (and auto-dismiss? (resolving-key? data))
                                          (done)
                                          (rerender)))})]
            (reset! handle (.showOverlay tui comp (or options default-overlay-options)))
            (rerender)
            done))

        ;; select/confirm/input share this shape: build a picker whose
        ;; resolve callback dismisses the overlay, then hand back a Promise.
        promised
        (fn [make-picker options]
          (js/Promise.
           (fn [resolve _reject]
             (let [dismiss (atom nil)
                   picker  (make-picker (fn [value]
                                          (when-let [d @dismiss] (d))
                                          (resolve value)))]
               (reset! dismiss (show! picker (or options default-overlay-options) false))))))

        ;; Callers may pass `{overlay: <OverlayOptions>}` to place the picker —
        ;; /model uses `bottom-overlay-options` so the transcript stays visible.
        overlay-of (fn [opts] (when (and opts (.-overlay opts)) (.-overlay opts)))]

    (set! (.-showOverlay ui)
          (fn [content options]
            ;; Every in-repo caller passes a STRING (show-info, stats
            ;; dashboard, tool_dsl); a picker object is also accepted.
            ;; Returns a 0-arg dismiss fn. Enter/Esc close it.
            (let [picker (if (string? content)
                           (make-text-overlay content)
                           content)]
              (show! picker (or options default-overlay-options) true))))

    (set! (.-custom ui)
          (fn [picker options]
            (show! picker (or options default-overlay-options) true)))

    (set! (.-select ui)
          (fn [prompt items opts]
            (promised (fn [done]
                        (make-select-picker prompt (vec (or items [])) done))
                      (overlay-of opts))))

    (set! (.-confirm ui)
          (fn [message opts]
            (-> (promised
                 (fn [done]
                   (make-select-picker message
                                       [#js {:value true  :label "Yes"}
                                        #js {:value false :label "No"}]
                                       done))
                 (overlay-of opts))
                (.then (fn [chosen]
                         (boolean (and chosen (.-value chosen))))))))

    (set! (.-input ui)
          (fn [prompt placeholder opts]
            (promised (fn [done]
                        (make-input-picker prompt placeholder done))
                      (overlay-of opts))))
    nil))
