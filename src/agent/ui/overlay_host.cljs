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
            [agent.ui.picker-frame :refer [render-frame overlay-max-width
                                           truncate-to truncate-tail two-col-row]]
            [agent.ui.picker-input :refer [dispatch-input]]
            [agent.ui.fuzzy-scorer :refer [fuzzy-filter]]))

;; Overlays sit at the BOTTOM, out of the way of the transcript — borrowed from
;; oh-my-pi, which shows its pickers and its plan-review overlay with
;; `showOverlay(c, "bottom-center", {width: "100%", margin: 0})` and pins its
;; error banner to a fixed region above the input for the same reason.
;;
;; This used to be `{width "60%", anchor "center"}`, and every overlay in the
;; repo inherited it — 23 of 24 call sites pass no options at all, so a
;; permission prompt landed dead centre, covering the transcript the user needs
;; to read to answer it. That was never a decision anyone made; it was the
;; fallthrough.
;;
;; 90% rather than the default 60%: model specs are `provider/org/id` and reach
;; ~50 characters (openrouter/nvidia/nemotron-3-super-120b-a12b:free), so 60% of
;; an 80-col terminal could not show one, let alone its context/price column.
;;
;; Consequence, accepted deliberately: a bottom anchor starts at
;; `availHeight - height` (pi-tui's resolveAnchorRow, tui.js:540), so a tall
;; overlay covers the status bar and the editor — at 24 rows a 70% overlay
;; occupies the bottom 16, leaving 8 rows of transcript above. Every overlay
;; here captures focus, so the editor is inert while one is open and there is
;; nothing to type into; and the transcript is what the user actually needs to
;; see, whether they are answering a permission prompt or reading /help. Giving
;; the big info views their own `center` override would put us back to deciding
;; placement per call site, which is the fallthrough this replaced.
(def default-overlay-options
  #js {:width "90%" :minWidth 40 :maxHeight "70%" :anchor "bottom-center"})

(def valid-anchors
  "pi-tui's nine anchors (dist/tui.d.ts:56). Settings carry no schema — only
   de-duping and case normalization — so an arbitrary string reaches us
   unchecked and must not be handed to pi-tui."
  #{"center" "top-left" "top-center" "top-right"
    "left-center" "right-center"
    "bottom-left" "bottom-center" "bottom-right"})

(defn- size-value
  "A pi-tui SizeValue is a column/row count or a `\"N%\"` string. Anything else
   (nil, a bare number-as-string, junk) yields nil so the default stands."
  [v]
  (cond
    (number? v) v
    (and (string? v) (.endsWith v "%") (not (js/isNaN (js/parseFloat v)))) v
    :else nil))

(defn settings->overlay-options
  "Build pi-tui overlay options from the settings map.

   Settings arrive KEBAB-CASE: `settings/manager.cljs` runs `normalize-keys`
   over loaded JSON, so a user writing `\"maxHeight\"` gets `:max-height` here.
   pi-tui wants `maxHeight`/`minWidth`. This is where that is undone — settings
   are kebab, pi-tui is camel, and the boundary is exactly one function.

   Every field falls back to `default-overlay-options` independently, so a
   partial override (just `:anchor`, say) keeps the rest."
  [settings-map]
  (let [ov     (get-in settings-map [:ui :overlay])
        anchor (get ov :anchor)
        anchor (if (contains? valid-anchors (str anchor))
                 (str anchor)
                 (.-anchor default-overlay-options))
        width  (or (size-value (get ov :width))  (.-width default-overlay-options))
        maxh   (or (size-value (get ov :max-height)) (.-maxHeight default-overlay-options))
        minw   (let [m (get ov :min-width)]
                 (if (number? m) m (.-minWidth default-overlay-options)))]
    #js {:width width :minWidth minw :maxHeight maxh :anchor anchor}))

;; ── Key translation ──────────────────────────────────────────────

;; Built rather than written as literals: a raw ESC byte in source does not
;; survive this repo's formatter, which is how the SGR guard below lost its.
(def ^:private esc (js/String.fromCharCode 27))
(def ^:private paste-start (str esc "[200~"))
(def ^:private paste-end   (str esc "[201~"))
(def ^:private sgr-mouse   (str esc "[<"))

(defn paste-payload
  "Content of a bracketed-paste chunk, or nil when `data` isn't one.

   pi-tui re-wraps pastes in the bracketed-paste markers and hands the whole
   thing to handleInput as ONE string (terminal.js:106). Without this the chunk
   fails the single-character test below and the paste is dropped in silence —
   which is what `/login` does with a pasted API key."
  [data]
  (when (and (string? data) (.startsWith data paste-start))
    (let [body (.slice data (count paste-start))
          end  (.indexOf body paste-end)]
      (if (neg? end) body (.slice body 0 end)))))

(defn printable-char
  "The character(s) `dispatch-input` should treat as typed text, or nil.

   Ctrl+P / Ctrl+N are reported as their bare letter with the ctrl flag set,
   because `dispatch-input` tests `(and (.-ctrl key) (= input \"p\"))`."
  [data]
  (let [pasted (paste-payload data)
        clean  (when pasted
                 (.replace pasted (js/RegExp. "[\\x00-\\x1f\\x7f]" "g") ""))]
    (cond
      (matchesKey data "ctrl+p") "p"
      (matchesKey data "ctrl+n") "n"
      ;; A paste arrives as one chunk; hand back the whole payload with control
      ;; characters stripped, so a multi-line paste can't corrupt the field.
      (and clean (seq clean)) clean
      (and (string? data)
           (= (count data) 1)
           (>= (.charCodeAt data 0) 32)
           (not= (.charCodeAt data 0) 127)) data
      :else nil)))

(defn data->key
  "Build the ink-style key object pickers branch on. Covers the full set the
   components use — tree_viewer and friends navigate with more than up/down,
   and an unmapped key would arrive as an all-false no-op."
  [data]
  #js {:escape     (matchesKey data "escape")
       :return     (or (matchesKey data "enter") (matchesKey data "return"))
       :upArrow    (matchesKey data "up")
       :downArrow  (matchesKey data "down")
       :leftArrow  (matchesKey data "left")
       :rightArrow (matchesKey data "right")
       :pageUp     (matchesKey data "pageUp")
       :pageDown   (matchesKey data "pageDown")
       :home       (matchesKey data "home")
       :end        (matchesKey data "end")
       :backspace  (matchesKey data "backspace")
       :delete     (matchesKey data "delete")
       :tab        (matchesKey data "tab")
       :ctrl       (or (matchesKey data "ctrl+p") (matchesKey data "ctrl+n"))})

(defn close-signal?
  "pi-mono's explicit dismissal signal: `onInput` returning `{close: true}`.
   `tree_viewer` uses this."
  [result]
  (boolean (and result (.-close result))))

(defn escape-key? [data]
  (boolean (matchesKey data "escape")))

(defn select-key? [data]
  (boolean (or (matchesKey data "enter") (matchesKey data "return"))))

(defn should-dismiss?
  "Whether the host should tear down the overlay after this keypress.

   Three sources, because `ui.custom` is fire-and-forget — the picker's
   resolve callback is closed over before the call, so there is no return
   value to await:

   - `{close: true}` from onInput — the explicit signal (tree_viewer).
   - Escape — universal cancel across every component in the repo.
   - Enter — ends single-shot filter pickers built on `dispatch-input`, but
     NOT components that set `keepOpen`, where Enter is a normal interaction
     (tree_viewer folds/unfolds a node with it)."
  [data result keep-open?]
  (or (close-signal? result)
      (escape-key? data)
      (and (not keep-open?) (select-key? data))))

(defn resolve-max-height
  "Rows a component may actually render into, given the overlay options and the
   terminal height. pi-tui resolves `maxHeight` (number or \"N%\") and then
   SLICES the overlay's lines to fit (tui.js `resolveOverlayLayout`), cutting
   from the bottom — and `render-frame` uses a trailing window, so the selected
   row sits near the bottom and is the first thing lost. Components therefore
   need the box height, not the terminal height, to size themselves."
  [options term-rows]
  (let [rows (or term-rows 24)
        mh   (when options (.-maxHeight options))]
    (cond
      (number? mh) (max 1 (min mh rows))
      (and (string? mh) (.endsWith mh "%"))
      (let [pct (js/parseFloat (.slice mh 0 -1))]
        (if (js/isNaN pct)
          rows
          (max 1 (js/Math.floor (* rows (/ pct 100))))))
      :else rows)))

(defn resolve-content-width
  "Columns a component may actually paint, given the overlay options and the
   terminal width — the counterpart to `resolve-max-height`.

   Deliberately does NOT go through `picker-frame/overlay-max-width`: that
   subtracts 6 for an ink Box border + paddingX, chrome pi-tui's overlay never
   draws (`showOverlay` has no border/padding and composites lines as-is). Under
   this host those 6 columns are pure loss — at 80 cols it was handing rows 42
   columns for specs up to 49 characters long."
  [options term-cols]
  (let [cols (or term-cols 80)
        w    (when options (.-width options))
        mw   (when options (.-minWidth options))
        base (cond
               (number? w) w
               (and (string? w) (.endsWith w "%"))
               (let [pct (js/parseFloat (.slice w 0 -1))]
                 (if (js/isNaN pct) cols (js/Math.floor (* cols (/ pct 100)))))
               :else cols)]
    (max 1 (min cols (max base (or mw 0))))))

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
         ;; Drop SGR mouse reports — these pickers are keyboard-only and a
         ;; mouse sequence would otherwise be dispatched as input (oh-my-pi's
         ;; model-picker guards the same way).
         (when-not (and (string? data) (.startsWith data sgr-mouse))
           ;; The onInput RESULT matters: pi-mono components signal dismissal by
           ;; returning {close: true}, so it is forwarded to after-input.
           (let [result (when-let [on-input (.-onInput picker)]
                          (on-input (printable-char data) (data->key data)))]
             (when after-input (after-input data result))))
         nil)

       :invalidate (fn [] nil)})

;; ── Built-in pickers for select / confirm / input ────────────────

(defn- item-label [it]
  (or (.-label it) (.-value it) (str it)))

(defn- item-description [it]
  (str (or (.-description it) "")))

(defn- filter-items
  "Fuzzy, multi-token filter over label + description — so `openrouter llama`
   narrows a ~95-entry catalogue where a plain substring match could not.
   `fuzzy-filter` was already written and tested but only the autocomplete
   registry used it; every picker hand-rolled substring matching."
  [query items]
  (if (empty? query)
    (vec items)
    (vec (fuzzy-filter (vec items) query
                       (fn [it] (str (item-label it) " " (item-description it)))))))

(defn make-select-picker
  "Filter picker over `items` (JS objects with .label/.value/.description).
   `on-resolve` receives the chosen item, or nil on cancel."
  [prompt items on-resolve width-fn]
  (let [filter-text  (atom "")
        selected-idx (atom 0)]
    #js {:render
         (fn [w h]
           (let [;; The host knows the box geometry and passes it in; the `w`
                 ;; fallback keeps the picker usable standalone (tests).
                 cap (if width-fn (width-fn) (overlay-max-width w))
                 ;; render-frame prepends a 4-char focus/scroll indicator.
                 row-w (max 1 (- cap 4))]
             (render-frame
              {:title         (str prompt "  (type to filter, Enter to select, Esc to cancel)")
               :prompt-prefix "> "
               :filter-text   @filter-text
               :items         (filter-items @filter-text items)
               :selected-idx  @selected-idx
               ;; `h` is the rows available INSIDE the overlay box (the host
               ;; resolves maxHeight before calling us), minus render-frame's two
               ;; header lines. A fixed count overflows and pi-tui then slices
               ;; away the trailing rows — i.e. the selected one. No constant cap:
               ;; `h` already bounds it, and capping at 12 wasted a tall terminal
               ;; on a ~95-entry catalogue.
               :max-visible   (max 1 (- (or h 24) 2))
               :max-width     cap
               ;; Two fields with separate budgets, metadata flush right, so the
               ;; `ctx · price` column is not the first thing truncated away.
               :render-item   (fn [it _focused?]
                                (two-col-row (item-label it) (item-description it) row-w))
               :no-match-text "No matches"})))

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
  [prompt placeholder on-resolve width-fn]
  (let [text (atom "")]
    #js {:render
         (fn [w _h]
           (let [cap   (if width-fn (width-fn) (overlay-max-width w))
                 body-cap (max 1 (- cap 2))
                 shown (if (empty? @text) (str (or placeholder "")) @text)
                 ;; Show the TAIL once the value outgrows the line, so typing
                 ;; past the cap keeps showing what's being typed instead of
                 ;; freezing on the first `cap` characters.
                 ;; COLUMNS, not characters: typing CJK or emoji into an
                 ;; input picker put two to four columns on screen for every
                 ;; character this budgeted one for, overflowing the box.
                 ;; truncate-tail does the same head-drop, measured properly.
                 shown (truncate-tail shown body-cap)]
             (str prompt "  (Enter to accept, Esc to cancel)" "\n"
                  "> " shown)))

         :onInput
         (fn [input key]
           (cond
             (.-escape key) (on-resolve nil)
             (.-return key) (on-resolve @text)

             (or (.-backspace key) (.-delete key))
             (swap! text (fn [s] (if (empty? s) "" (subs s 0 (dec (count s))))))

             (.-tab key) nil

             ;; `printable-char` reports ctrl+p/ctrl+n as bare "p"/"n" for
             ;; dispatch-input's navigation; without this guard those chords
             ;; would type a literal letter into a text field.
             (.-ctrl key) nil

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
  [text width-fn]
  (let [scroll (atom 0)]
    #js {:render
         (fn [w h]
           (let [cap     (if width-fn (width-fn) (overlay-max-width w))
                 lines   (.split (str text) "\n")
                 total   (.-length lines)
                 ;; `h` is the box's rows, not the terminal's; reserve one for
                 ;; the range header. Overshooting here got the bottom of every
                 ;; window silently clipped by pi-tui.
                 visible (max 1 (dec (or h 24)))
                 max-top (max 0 (- total visible))
                 top     (min @scroll max-top)
                 window  (.slice lines top (+ top visible))
                 more?   (> total visible)
                 header  (when more?
                           (str "(" (inc top) "-" (min total (+ top visible))
                                " of " total " lines — ↑/↓ scroll, Esc to close)\n"))]
             (reset! scroll top)
             (str header
                  ;; Ellipsis, not a bare slice: this renders /help, /model list
                  ;; and the stats dashboard, and a hard cut lands mid-token with
                  ;; no indication anything was dropped.
                  (str/join "\n" (map (fn [l] (truncate-to (str l) cap)) window)))))

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
   focus; `request-render` schedules a repaint.

   `overlay-options-fn` is a THUNK, not a value: it is called at show time so a
   `/settings` change to `:ui/:overlay` takes effect on the next overlay rather
   than needing a restart. Omitted (tests, non-settings hosts) it falls back to
   `default-overlay-options`."
  [ui tui {:keys [restore-focus request-render overlay-options-fn]}]
  (let [term       (.-terminal tui)
        get-width  (fn [] (or (.-columns term) 80))
        get-height (fn [] (or (.-rows term) 24))
        rerender   (fn [] (when request-render (request-render)))
        refocus    (fn [] (when restore-focus (restore-focus)))

        ;; Resolved per show, so it tracks live settings.
        base-options
        (fn []
          (or (when overlay-options-fn
                (try (overlay-options-fn) (catch :default _ nil)))
              default-overlay-options))

        ;; Show `picker`, returning a 0-arg dismiss fn. `auto-dismiss?`
        ;; closes the overlay on Enter/Escape (the ui.custom contract);
        ;; the promise-returning wrappers dismiss explicitly instead.
        show!
        (fn [picker options auto-dismiss?]
          (let [handle    (atom nil)
                closed    (atom false)
                ;; Multi-step components (tree_viewer) set keepOpen so Enter
                ;; stays a normal interaction rather than a dismissal.
                keep-open? (boolean (.-keepOpen picker))
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
                         ;; Box rows, not terminal rows — see resolve-max-height.
                         :get-height  (fn [] (resolve-max-height options (get-height)))
                         :after-input (fn [data result]
                                        (if (and auto-dismiss?
                                                 (should-dismiss? data result keep-open?))
                                          (done)
                                          (rerender)))})]
            (reset! handle (.showOverlay tui comp (or options (base-options))))
            (rerender)
            done))

        ;; select/confirm/input share this shape: build a picker whose
        ;; resolve callback dismisses the overlay, then hand back a Promise.
        ;; Thunk giving a host-built picker the columns it may actually paint,
        ;; so it stops re-deriving geometry from the terminal width.
        width-fn-for (fn [options]
                       (fn [] (resolve-content-width (or options (base-options))
                                                     (get-width))))

        promised
        (fn [make-picker options signal]
          (js/Promise.
           (fn [resolve _reject]
             (if (and signal (.-aborted signal))
               (resolve nil)
               (let [dismiss  (atom nil)
                     settled  (atom false)
                     finish   (fn [value]
                                (when-not @settled
                                  (reset! settled true)
                                  (when-let [d @dismiss] (d))
                                  (resolve value)))
                     picker   (make-picker finish)]
                 ;; Abort must tear the overlay down too: questionnaire passes
                 ;; `{signal}` per question, and without this an aborted turn
                 ;; left the picker holding keyboard focus with its promise
                 ;; pending until the user hit Esc by hand.
                 (when signal
                   (.addEventListener signal "abort"
                                      (fn [] (finish nil))
                                      #js {:once true}))
                 (reset! dismiss (show! picker (or options (base-options)) false)))))))

        ;; Callers may pass `{overlay: <OverlayOptions>}` to place one picker
        ;; somewhere specific. Nothing in-repo needs to any more: the shared
        ;; default is bottom-anchored, which is what the one previous override
        ;; (/model) wanted.
        overlay-of (fn [opts] (when (and opts (.-overlay opts)) (.-overlay opts)))
        signal-of  (fn [opts] (when (and opts (.-signal opts)) (.-signal opts)))]

    (set! (.-showOverlay ui)
          (fn [content options]
            ;; Every in-repo caller passes a STRING (show-info, stats
            ;; dashboard, tool_dsl); a picker object is also accepted.
            ;; Returns a 0-arg dismiss fn. Enter/Esc close it.
            (let [picker (if (string? content)
                           (make-text-overlay content (width-fn-for options))
                           content)]
              (show! picker (or options (base-options)) true))))

    (set! (.-custom ui)
          (fn [picker options]
            (show! picker (or options (base-options)) true)))

    (set! (.-select ui)
          (fn [prompt items opts]
            (promised (fn [done]
                        (make-select-picker prompt (vec (or items [])) done
                                            (width-fn-for (overlay-of opts))))
                      (overlay-of opts) (signal-of opts))))

    (set! (.-confirm ui)
          (fn [message opts]
            (-> (promised
                 (fn [done]
                   (make-select-picker message
                                       [#js {:value true  :label "Yes"}
                                        #js {:value false :label "No"}]
                                       done
                                       (width-fn-for (overlay-of opts))))
                 (overlay-of opts) (signal-of opts))
                (.then (fn [chosen]
                         (boolean (and chosen (.-value chosen))))))))

    (set! (.-input ui)
          (fn [prompt placeholder opts]
            (promised (fn [done]
                        (make-input-picker prompt placeholder done
                                           (width-fn-for (overlay-of opts))))
                      (overlay-of opts) (signal-of opts))))
    nil))
