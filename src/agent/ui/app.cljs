(ns agent.ui.app
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useCallback useRef]]
            ["ink" :refer [Box Text render useInput useApp useStdout useStdin]]
            [agent.loop :refer [run steer]]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.keybinding-registry :as kbr]
            [agent.ui.bracketed-paste :as paste]
            [agent.ui.app-reducers :as reducers]
            [agent.ui.autocomplete-provider :as ac]
            [agent.ui.editor-bash :as editor-bash]
            [agent.ui.editor-eval :as editor-eval]
            ["./chat_view.jsx" :refer [ChatView]]
            ["./scrollback.mjs" :refer [commit_to_scrollback_BANG_
                                        committable_past_turn]]
            ["./editor.jsx" :refer [Editor]]
            ["./footer.jsx" :refer [Footer]]
            ["./overlay.jsx" :refer [Overlay]]
            ["./dialogs.jsx" :refer [ConfirmDialog SelectDialog InputDialog]]
            ["./notification.jsx" :refer [Notification]]
            ["./widget_container.jsx" :refer [WidgetContainer]]
            ["./help_overlay.jsx" :refer [HelpOverlay]]
            ["./status_line.jsx" :refer [StatusLine]]
            ["./welcome.jsx" :refer [WelcomeScreen]]
            ["./mention_picker.mjs" :refer [create-picker]]
            [agent.debug :as dbg]))

(defn- handle-command [agent text set-overlay set-messages]
  (let [parts    (.split (.slice text 1) " ")
        cmd      (first parts)
        args     (rest parts)
        commands @(:commands agent)]
    (when-let [entry (resolve-command commands cmd)]
      ;; ctx: `:ui` stays for notification/overlay use; `:set-messages`
      ;; and `:append-message` are the new handles commands need to
      ;; push rendered output into the chat view (e.g. /bash running
      ;; through the same path as editor bash mode).
      (let [h (:handler entry)]
        (h args #js {:ui            (when-let [ext (.-extension-api agent)] (.-ui ext))
                     :agent         agent
                     :set-messages  set-messages
                     :append-message (fn [msg]
                                       (set-messages
                                        (fn [prev] (conj (vec prev) (assoc msg :id (new-id))))))})))))

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn- add-user-msg!
  "Add user message to chat. Called synchronously to batch with Editor clear."
  [set-messages text]
  (dbg/dbg "[add-user-msg!] text:" (.slice (str text) 0 200)
           "| stack:" (.-stack (js/Error.)))
  (set-messages (fn [prev] (conj (vec prev) {:role "user" :content text :id (new-id)}))))

(defn- add-assistant-chunk!
  "Append a text delta to the last assistant message, or create one.
   Uses in-place tail update (O(1)) rather than butlast+conj (O(n))."
  [set-messages text-delta]
  (set-messages
   (fn [prev]
     (let [all      (vec prev)
           tail-idx (dec (count all))
           last-msg (when (>= tail-idx 0) (nth all tail-idx))]
       (when (not= (:role last-msg) "assistant")
         (dbg/dbg "[add-assistant-chunk!] last-msg role is" (:role last-msg)
                  "— creating new assistant message"))
       (if (= (:role last-msg) "assistant")
         (update all tail-idx update :content str text-delta)
         (conj all {:role "assistant" :content (or text-delta "") :id (new-id)}))))))

(defn ^:async do-submit [agent streaming set-overlay set-streaming set-messages set-steerAcked set-editor-hidden text]
  ;; Expand bracketed-paste markers to their original content before the
  ;; prompt is handed to extensions or the model.
  (let [paste-handler (when-let [ph (.-__paste-handler agent)] ph)
        pastes-map    (when paste-handler @(:pastes paste-handler))
        text          (if pastes-map
                        (paste/expand-paste-markers text pastes-map)
                        text)
        _ (dbg/dbg "[do-submit] raw-text:" (.slice (str text) 0 200))
        ;; Emit "input" event — extensions can intercept, transform, or fully handle
        input-result (js-await
                      (let [ec (:emit-collect (:events agent))]
                        (ec "input" #js {:input text :text text})))
        handled      (get input-result "handle")
        transformed  (get input-result "input")
        _ (when (and (some? transformed) (not= transformed text))
            (dbg/dbg "[do-submit] input-event rewrote text to:"
                     (.slice (str transformed) 0 200)))
        text         (if (and (some? transformed) (not= transformed text))
                       transformed
                       text)]
    (cond
      ;; ── Extension handled (ACP agent shell streaming) ──
      (and handled (get input-result "subscribe"))
      (let [subscribe (get input-result "subscribe")]
        ;; Hide Editor — prompt text is prefixed in the response stream
        (set-editor-hidden true)
        (set-streaming true)
        (js-await (subscribe set-messages))
        (set-streaming false)
        (set-editor-hidden false))

      (and handled (get input-result "response"))
      (let [response (get input-result "response")]
        (when (> (count (str response)) 0)
          (set-messages
           (fn [prev] (conj (vec prev) {:role "assistant" :content response :id (new-id)})))))

      handled
      nil  ;; handled but no response or subscribe

      ;; ── Editor bash mode ──
      ;; `!cmd` runs through bash_suite and appends the output to
      ;; the conversation; `!!cmd` does the same but tags the
      ;; message :local-only so agent.context/build-context strips
      ;; it before the next LLM turn. Must sit BEFORE the slash arm
      ;; so `!git log --pretty=/foo` routes here, not to the
      ;; command handler.
      (let [parsed (editor-bash/parse-bash-input text)]
        (not= :not-bash (:kind parsed)))
      (let [{:keys [kind command]} (editor-bash/parse-bash-input text)
            hidden? (= kind :run-hidden)]
        (set-streaming true)
        (add-user-msg! set-messages (str (if hidden? "!!" "!") command))
        (let [result  (js-await (editor-bash/run-bash! agent command))
              content (editor-bash/format-bash-output result)
              msg     (cond-> {:role      "assistant"
                               :kind      :bash
                               :content   content
                               :command   command
                               :stdout    (:stdout result)
                               :stderr    (:stderr result)
                               :exit-code (:exit-code result)
                               :blocked?  (:blocked? result)
                               :reason    (:reason result)
                               :id        (new-id)}
                        hidden? (assoc :local-only true))]
          (set-messages (fn [prev] (conj (vec prev) msg)))
          (set-streaming false)))

      ;; ── Editor eval mode ──
      ;; `$expr` spawns `bb -e` and appends the output; `$$expr`
      ;; same but :local-only. Ungated — bb is a language runtime
      ;; and the bash_suite chain doesn't apply. If a future phase
      ;; adds an eval-mode security extension, wire it in
      ;; `editor-eval/run-eval!` via before_tool_call, not here.
      (let [parsed (editor-eval/parse-eval-input text)]
        (not= :not-eval (:kind parsed)))
      (let [{:keys [kind expr]} (editor-eval/parse-eval-input text)
            hidden? (= kind :eval-hidden)]
        (set-streaming true)
        (add-user-msg! set-messages (str (if hidden? "$$" "$") expr))
        (let [result  (js-await (editor-eval/run-eval! expr (:events agent)))
              content (editor-eval/format-eval-output result)
              msg     (cond-> {:role         "assistant"
                               :kind         :eval
                               :content      content
                               :expr         expr
                               :stdout       (:stdout result)
                               :stderr       (:stderr result)
                               :exit-code    (:exit-code result)
                               :unavailable? (:unavailable? result)
                               :install-hint (:install-hint result)
                               :id           (new-id)}
                        hidden? (assoc :local-only true))]
          (set-messages (fn [prev] (conj (vec prev) msg)))
          (set-streaming false)))

      ;; ── Slash command ──
      (.startsWith text "/")
      (js-await (handle-command agent text set-overlay set-messages))

      ;; ── Steer (mid-stream follow-up) ──
      streaming
      (do
        (steer agent {:role "user" :content text})
        (set-steerAcked true)
        (js/setTimeout (fn [] (set-steerAcked false)) 1500))

      ;; ── Normal LLM prompt ──
      :else
      (let [;; emit-collect input_submit — extensions can cancel or transform text
            submit-result (js-await
                           (let [ec (:emit-collect (:events agent))]
                             (ec "input_submit" #js {:text text :timestamp (js/Date.now)})))
            cancelled     (get submit-result "cancel")
            text          (let [t (or (get submit-result "text") text)]
                            (when (not= t text)
                              (dbg/dbg "[do-submit] input_submit rewrote text to:"
                                       (.slice (str t) 0 200)))
                            t)]
        (when-not cancelled
          (set-streaming true)
          (add-user-msg! set-messages text)
          (let [update-handler
                (fn [chunk] (add-assistant-chunk! set-messages (.-text chunk)))]
            ((:on (:events agent)) "message_update" update-handler)
            (js-await (run agent text))
            ((:off (:events agent)) "message_update" update-handler)
            (set-streaming false)
            ;; Convert any orphaned tool-start messages (those that never got
            ;; a matching tool-end, e.g. due to abort/error) to synthetic
            ;; tool-end messages in place. Preserves the stable :id so items
            ;; already committed to Static scrollback are not removed.
            (set-messages
             (fn [prev]
               (mapv (fn [msg]
                       (if (= (:role msg) "tool-start")
                         (assoc msg :role "tool-end"
                                :result "(cancelled)"
                                :duration 0)
                         msg))
                     prev)))))))))

(defn with-dismissal
  "Attach timeout and/or abort-signal auto-dismiss to a dialog.
   dismiss-fn should call (set-overlay nil) and (resolve val).
   Returns a cleanup function that clears timer and removes signal listener."
  [dismiss-fn opts]
  (let [timer-id (atom nil)
        sig-ref  (atom nil)
        cleanup  (fn []
                   (when-let [t @timer-id]
                     (js/clearTimeout t)
                     (reset! timer-id nil))
                   (when-let [[signal handler] @sig-ref]
                     (.removeEventListener signal "abort" handler)
                     (reset! sig-ref nil)))]
    (when (and opts (.-timeout opts))
      (reset! timer-id
              (js/setTimeout (fn [] (cleanup) (dismiss-fn nil)) (.-timeout opts))))
    (when (and opts (.-signal opts))
      (let [handler (fn [] (cleanup) (dismiss-fn nil))]
        (reset! sig-ref [(.-signal opts) handler])
        (.addEventListener (.-signal opts) "abort" handler #js {:once true})))
    cleanup))

(defn App [{:keys [agent session resources]}]
  (let [[messages set-messages]             (useState [])
        [streaming set-streaming]         (useState false)
        [overlay set-overlay]             (useState nil)
        [theme set-theme]                 (useState (.-theme resources))
        [steerAcked set-steerAcked]       (useState false)
        [notification set-notification]   (useState nil)
        [statuses set-statuses]           (useState {})
        [widgets set-widgets]             (useState {})
        [custom-footer-fn set-custom-footer] (useState nil)
        [editor-value set-editor-value]   (useState "")
        [editor-hidden set-editor-hidden] (useState false)
        [custom-header-fn set-custom-header] (useState nil)
        ;; Autocomplete-picker cancellation memory. When the user
        ;; presses Escape on a picker opened by the autocomplete
        ;; useEffect, we stash the editor-value that triggered it so
        ;; the effect doesn't immediately reopen the picker on the
        ;; next render (same editor-value still matches the trigger).
        ;; Cleared as soon as the editor-value changes to anything
        ;; else, so typing more resumes normal completion.
        dismissed-trigger-ref             (useRef nil)
        ;; Ref mirror of (some? overlay). Updated synchronously via
        ;; useEffect([overlay]) so the stdin paste handler (a stale closure
        ;; from the agent useEffect) can read current overlay state without
        ;; re-registering its data listener on every render.
        overlay-active-ref                (useRef false)
        ;; Ref used to block ink-text-input from appending paste-marker
        ;; fragments ("[200~", "[201~") to the editor value.  ink's
        ;; input-parser splits a bracketed paste into three separate events
        ;; and use-input.js delivers them to all useInput handlers including
        ;; ink-text-input's onChange.  Those are direct setState calls; the
        ;; last one ("[201~") overwrites our functional marker update in the
        ;; same React batch.  We set this ref true on "[200~" and clear it
        ;; (via setTimeout 0, after TextInput's handler for "[201~" fires) so
        ;; the guarded setter below suppresses all three paste-fragment updates.
        ink-paste-block-ref               (useRef false)
        ;; Destructure internal_eventEmitter from ink's StdinContext so we can
        ;; prependListener on the shared event bus before ink-text-input's
        ;; useInput fires for each paste fragment.
        {:keys [internal_eventEmitter]}   (useStdin)
        ;; Guarded wrapper: drop direct-setState calls while a paste is in
        ;; flight (ink-paste-block-ref is true).  Functional updates (fn [prev]
        ;; …) are always allowed through — our marker append uses those and
        ;; must never be suppressed.  See app_reducers/make-guarded-setter.
        set-editor-value-guarded          (reducers/make-guarded-setter
                                           ink-paste-block-ref set-editor-value)
        ;; Bumped whenever the on-resolve callback externally sets
        ;; the editor text (picker commits). Used as the React key
        ;; on the Editor subtree so ink-text-input remounts with a
        ;; fresh cursorOffset = newValue.length — otherwise the
        ;; cursor stays wherever it was before the picker opened
        ;; (after the trailing '/' for a slash completion), which
        ;; looks broken.
        [editor-remount-key set-editor-remount-key] (useState 0)
        ;; `write` is Ink's writeToStdout — clears the dynamic region,
        ;; writes to scrollback, then restores the dynamic region below.
        ;; Used by scrollback module to commit finalized messages.
        {:keys [stdout write]}            (useStdout)
        [term-rows set-term-rows]         (useState (or (.-rows stdout) 24))
        layout-handlers                   (useRef #js [])
        app                               (useApp)
        ;; Convenience helper — fire-and-forget emit on the agent's main event bus.
        ;; Defined here so all closures below (showOverlay, keybinding, autocomplete)
        ;; can share the same null-safe wrapper.
        emit!                             (fn [event data]
                                            (when-let [e (:emit (:events agent))]
                                              (e event data)))]

    ;; ── scrollback-mode commit sweep ───────────────────────────────
    ;; When :scrollback-mode is ON in settings, commit PAST TURNS to
    ;; terminal scrollback via Ink's writeToStdout. The CURRENT turn
    ;; (from the last user message onwards) stays in React state and
    ;; renders live in the chat region — this is what the user actually
    ;; sees at any given moment. Only when a NEW user message arrives
    ;; does the previous turn get committed to scrollback.
    ;;
    ;; This matches Claude Code / Codex UX: the latest exchange is
    ;; always in the visible chat region; older turns are in terminal
    ;; scrollback (scroll up to see them).
    ;;
    ;; Committed messages stay in React state with `:committed true` so
    ;; `agent.context/build-context` (which reads `:messages` from the
    ;; agent state atom) sees the full conversation for LLM context.
    ;; ChatView filters them out of the dynamic region to avoid double
    ;; rendering (scrollback + live).
    ;;
    ;; Turn boundary: the index of the last `user` message. Everything
    ;; BEFORE that index is a past turn and eligible to commit.
    ;; Everything FROM that index onwards is the current turn.
    ;;
    ;; Idempotent: committing flips :committed flags, which causes the
    ;; effect to re-fire; the second run sees no uncommitted past-turn
    ;; messages and returns early.
    (useEffect
     (fn []
       (let [sm       (:settings resources)
             flag-on? (boolean (when sm (:scrollback-mode ((:get sm)))))]
         (when flag-on?
           ;; committable-past-turn is pure — see scrollback.cljs — and
           ;; directly tested so the turn-boundary rules don't regress
           ;; in the integration-only render path.
           (let [to-commit (committable_past_turn messages)
                 br        (when-let [br (:block-renderers agent)] @br)
                 columns   (or (.-columns stdout) 80)]
             (when (seq to-commit)
               (doseq [msg to-commit]
                 (commit_to_scrollback_BANG_
                  #js {:write write
                       :message msg
                       :theme theme
                       :block-renderers br
                       :columns columns}))
               (set-messages
                (fn [prev]
                  (let [to-commit-ids (set (map :id to-commit))]
                    (mapv (fn [m]
                            (if (contains? to-commit-ids (:id m))
                              (assoc m :committed true)
                              m))
                          prev))))))))
       js/undefined)
     #js [messages])

    ;; Wire extension UI hooks
    (useEffect
     (fn []
       (let [ui #js {:available true}]
         (set! (.-showOverlay ui)
               (fn [content & [opts]]
                 (if (and opts (.-transparent opts))
                   (set-overlay #js {:__overlay-content content :__transparent true})
                   (set-overlay content))
                 (emit! "overlay_open" {:overlay-type "custom"})))
         (set! (.-setWidget ui)
               (fn [_position _component] nil))
         (set! (.-confirm ui)
               (fn [first-arg & rest-args]
              ;; Supports: (confirm msg), (confirm msg opts), (confirm title msg), (confirm title msg opts)
                 (let [has-title (and (seq rest-args) (string? (first rest-args)))
                       title     (when has-title first-arg)
                       msg       (if has-title (first rest-args) first-arg)
                       opts      (if has-title (second rest-args) (first rest-args))]
                   (js/Promise.
                    (fn [resolve]
                      (let [dismiss (fn [val]
                                      (emit! "overlay_dismiss" {:overlay-type "confirm"})
                                      (set-overlay nil)
                                      (resolve val))
                            cleanup (with-dismissal dismiss opts)]
                        (set-overlay
                         #jsx [ConfirmDialog
                               {:title title
                                :message msg
                                :on-confirm (fn [] (cleanup) (dismiss true))
                                :on-cancel  (fn [] (cleanup) (dismiss false))}])
                        (emit! "overlay_open" {:overlay-type "confirm"})))))))
         (set! (.-select ui)
               (fn [title options opts]
                 (js/Promise.
                  (fn [resolve]
                    (let [dismiss (fn [val]
                                    (emit! "overlay_dismiss" {:overlay-type "select"})
                                    (set-overlay nil)
                                    (resolve val))
                          cleanup (with-dismissal dismiss opts)]
                      (set-overlay
                       #jsx [SelectDialog
                             {:title title
                              :options options
                              :on-select (fn [choice] (cleanup) (dismiss choice))
                              :on-cancel (fn [] (cleanup) (dismiss nil))
                              :theme theme}])
                      (emit! "overlay_open" {:overlay-type "select"}))))))
         (set! (.-input ui)
               (fn [title placeholder opts]
                 (js/Promise.
                  (fn [resolve]
                    (let [dismiss (fn [val]
                                    (emit! "overlay_dismiss" {:overlay-type "input"})
                                    (set-overlay nil)
                                    (resolve val))
                          cleanup (with-dismissal dismiss opts)]
                      (set-overlay
                       #jsx [InputDialog
                             {:title title
                              :placeholder (or placeholder "")
                              :on-submit (fn [text] (cleanup) (dismiss text))
                              :on-cancel (fn [] (cleanup) (dismiss nil))
                              :theme theme}])
                      (emit! "overlay_open" {:overlay-type "input"}))))))
         (set! (.-notify ui)
               (fn [msg level]
                 (set-notification {:message msg :level (or level "info")})
                 (js/setTimeout (fn [] (set-notification nil)) 3000)))
         (set! (.-setStatus ui)
               (fn [id text]
                 (set-statuses
                  (fn [prev]
                    (if text
                      (assoc prev id text)
                      (dissoc prev id))))))
          ;; Widget system
         (set! (.-setWidget ui)
               (fn [id lines & [pos priority]]
                 (set-widgets
                  (fn [w]
                    (assoc w id {:lines (if (array? lines) (vec lines) lines)
                                 :position (or pos "below")
                                 :priority (or priority 0)})))))
         (set! (.-clearWidget ui)
               (fn [id]
                 (set-widgets (fn [w] (dissoc w id)))))
          ;; Custom footer/header
         (set! (.-setFooter ui)
               (fn [factory] (set-custom-footer (fn [] factory))))
         (set! (.-setHeader ui)
               (fn [factory] (set-custom-header (fn [] factory))))
          ;; Terminal title (OSC escape)
         (set! (.-setTitle ui)
               (fn [title]
                 (let [esc (js/String.fromCharCode 27)
                       bel (js/String.fromCharCode 7)]
                   (.write (.-stdout js/process) (str esc "]0;" title bel)))))
          ;; Custom component overlay — returns Promise that resolves when closed
         (set! (.-custom ui)
               (fn [component]
                 (js/Promise.
                  (fn [resolve]
                  ;; Attach resolve so Overlay/CustomComponentAdapter can call it
                    (when (and (some? component) (not (string? component)) (not (number? component)))
                      (set! (.-__resolve component) resolve))
                    (set-overlay component)
                    (emit! "overlay_open" {:overlay-type "custom"})))))
          ;; Raw terminal input
         (set! (.-onTerminalInput ui)
               (fn [handler]
                 (.on (.-stdin js/process) "data" handler)
              ;; Return cleanup function
                 (fn [] (.off (.-stdin js/process) "data" handler))))
          ;; Layout info for extensions
         (set! (.-getTerminalSize ui)
               (fn []
                 #js {:rows    (or (.-rows stdout) 24)
                      :columns (or (.-columns stdout) 80)}))
          ;; Layout change subscription
         (set! (.-onLayoutChange ui)
               (fn [handler]
                 (.push (.-current layout-handlers) handler)
              ;; Return cleanup function
                 (fn []
                   (let [handlers (.-current layout-handlers)
                         idx      (.indexOf handlers handler)]
                     (when (>= idx 0)
                       (.splice handlers idx 1))))))
          ;; Editor value accessors for extensions (prompt history, etc.)
         (set! (.-setEditorValue ui) (fn [text] (set-editor-value (or text ""))))
         (set! (.-getEditorValue ui) (fn [] editor-value))
         (when (.-extension-api agent)
           (set! (.-ui (.-extension-api agent)) ui)))
       js/undefined)
     #js [])

    ;; Subscribe to tool execution events for inline visibility
    (useEffect
     (fn []
       (let [events    (:events agent)
             settings  (when-let [sm (:settings resources)] ((:get sm)))
             verbosity (or (:tool-display settings) "collapsed")
             max-lines (or (:tool-display-max-lines settings) 500)

             on-start   (fn [data]
                          (set-messages
                           (fn [prev]
                             (reducers/apply-tool-start prev data verbosity max-lines))))

             on-end     (fn [data]
                          (set-messages
                           (fn [prev]
                             (reducers/apply-tool-end prev data verbosity max-lines))))

             on-update  (fn [data]
                          (set-messages
                           (fn [prev]
                             (reducers/apply-tool-update prev data))))]

         ((:on events) "tool_execution_start" on-start)
         ((:on events) "tool_execution_end" on-end)
         ((:on events) "tool_execution_update" on-update)
         (fn []
           ((:off events) "tool_execution_start" on-start)
           ((:off events) "tool_execution_end" on-end)
           ((:off events) "tool_execution_update" on-update))))
     #js [agent])

    ;; Track terminal resize for fixed-height layout + notify extensions
    (useEffect
     (fn []
       (let [handler (fn []
                       (let [rows (or (.-rows stdout) 24)
                             cols (or (.-columns stdout) 80)]
                         (set-term-rows rows)
                         (doseq [h (.-current layout-handlers)]
                           (try (h #js {:rows rows :columns cols})
                                (catch :default _)))))]
         (.on stdout "resize" handler)
         (fn [] (.off stdout "resize" handler))))
     #js [stdout])

    ;; Bracketed paste: enable terminal mode, install raw stdin listener, and
    ;; attach the handler to `agent` so do-submit can expand markers.
    ;; Also registers a prependListener on ink's internal_eventEmitter so we
    ;; can set ink-paste-block-ref before ink-text-input's useInput fires for
    ;; each of the three paste events ("[200~", body, "[201~") that ink's
    ;; input-parser emits.  Without this guard those events reach TextInput's
    ;; onChange as direct-setState calls; the last one overwrites our
    ;; functional marker update in the same React batch, showing "[201~"
    ;; instead of "[paste #N +X lines]".
    (useEffect
     (fn []
       (paste/enable-bracketed-paste)
       (let [handler      (paste/create-paste-handler)
             process-fn   (:process handler)
             ;; Skips while any overlay is active (see make-stdin-paste-handler).
             stdin-fn     (reducers/make-stdin-paste-handler
                           process-fn overlay-active-ref set-editor-value)
             ;; Blocks ink-text-input's direct onChange calls during a paste
             ;; (see make-ink-paste-fn for the three-event race explanation).
             ink-paste-fn (reducers/make-ink-paste-fn
                           ink-paste-block-ref
                           (fn [cb] (js/setTimeout cb 0)))]
         (set! (.-__paste-handler agent) handler)
         (.on (.-stdin js/process) "data" stdin-fn)
         (when internal_eventEmitter
           (.prependListener internal_eventEmitter "input" ink-paste-fn))
         (fn []
           (.off (.-stdin js/process) "data" stdin-fn)
           (when internal_eventEmitter
             (.removeListener internal_eventEmitter "input" ink-paste-fn))
           (paste/disable-bracketed-paste)
           (set! (.-__paste-handler agent) js/undefined))))
     #js [agent internal_eventEmitter])

    ;; Keep overlay-active-ref in sync so the stdin paste handler (a closure
    ;; that cannot re-capture `overlay` state directly) can skip paste
    ;; processing while any dialog or picker is shown. We do NOT toggle
    ;; terminal bracketed-paste mode here — doing so creates a race: the
    ;; re-enable write to stdout arrives after readline has already started
    ;; processing the next paste, causing the ESC[201~ end marker to leak
    ;; into the editor as the literal string "[201~".  Keeping the terminal
    ;; always in bracketed-paste mode and filtering in software is simpler
    ;; and race-free.
    (useEffect
     (fn []
       (set! (.-current overlay-active-ref) (some? overlay))
       js/undefined)
     #js [overlay])

    ;; Emit editor_change so extensions can react to typed text (e.g. token preview)
    (useEffect
     (fn []
       (when-let [emit (:emit (:events agent))]
         (emit "editor_change" {:text editor-value}))
       js/undefined)
     #js [editor-value])

    ;; Clear the dismissed-trigger ref the moment the editor text
    ;; moves off the value the user cancelled on. Without this, a
    ;; user who dismissed the picker at "/cl" and then typed "ear"
    ;; would not see the picker come back even though "/clear" is
    ;; a fresh trigger state.
    (useEffect
     (fn []
       (when (and (.-current dismissed-trigger-ref)
                  (not= editor-value (.-current dismissed-trigger-ref)))
         (set! (.-current dismissed-trigger-ref) nil))
       js/undefined)
     #js [editor-value])

    ;; General autocomplete trigger detection. Runs whenever the editor
    ;; text changes: detects slash/at/path triggers, invokes
    ;; complete-all on the autocomplete registry, opens a picker overlay
    ;; when any provider returns results.
    (useEffect
     (fn []
       (when (and (ac/should-open-picker?
                   editor-value
                   {:overlay?        (some? overlay)
                    :streaming?      streaming
                    :editor-hidden?  editor-hidden
                    :dismissed-value (.-current dismissed-trigger-ref)})
                  (:autocomplete-registry agent))
         (-> (ac/complete-all (:autocomplete-registry agent) editor-value)
             (.then
              (fn [items]
                (when (and items (pos? (count items)))
                  (let [item-array (clj->js (vec items))
                        ;; Snapshot editor-value into the closure so
                        ;; async resolution doesn't race with later
                        ;; edits — the user's intent at picker-open
                        ;; time is the authority for replacement.
                        trigger-text editor-value
                        picker (create-picker item-array ""
                                              (fn [selected]
                                                (set-overlay nil)
                                                (if selected
                                                  (do
                                                    (emit! "autocomplete_select"
                                                           {:value (.-value selected)
                                                            :label (.-label selected)
                                                            :query trigger-text})
                                                    (set-editor-value
                                                     (ac/replace-trigger-token
                                                      trigger-text
                                                      (.-value selected)))
                                                    ;; Force ink-text-input to
                                                    ;; remount so its internal
                                                    ;; cursorOffset resets to the
                                                    ;; end of the new value.
                                                    (set-editor-remount-key inc))
                                                  ;; Dismissed: remember this
                                                  ;; trigger-text so the outer
                                                  ;; useEffect doesn't
                                                  ;; immediately reopen.
                                                  (do
                                                    (emit! "autocomplete_close"
                                                           {:query trigger-text :reason "escape"})
                                                    (set! (.-current dismissed-trigger-ref)
                                                          trigger-text)))))]
                    (set-overlay picker)
                    (emit! "autocomplete_open"
                           {:query trigger-text :item-count (count items)})))))
             (.catch (fn [_] nil))))
       js/undefined)
     #js [editor-value overlay streaming editor-hidden])

    ;; Global keyboard handler — routes through keybinding-registry so
    ;; user overrides in ~/.nyma/keybindings.json take effect.
    (useInput
     (fn [input key]
       (let [reg @(:keybinding-registry agent)]
         (cond
           (kbr/matches? reg input key "app.interrupt")
           (do
             (emit! "keybinding_activated" {:action-id "app.interrupt" :input input})
             (when streaming ((:abort agent))))

           (kbr/matches? reg input key "app.help")
            ;; Open help overlay only when editor is empty — matches oh-my-pi UX.
           (when (and (empty? editor-value) (not streaming))
             (emit! "keybinding_activated" {:action-id "app.help" :input input})
             (set-overlay
              #jsx [HelpOverlay {:registry reg
                                 :shortcuts (when-let [s (:shortcuts agent)] @s)
                                 :theme theme
                                 :onClose (fn [] (set-overlay nil))}]))

           (kbr/matches? reg input key "app.model.show")
           (let [_ (emit! "keybinding_activated" {:action-id "app.model.show" :input input})
                 m        (:model (:config agent))
                 model-id (cond
                            (nil? m)    "unknown"
                            (string? m) m
                            :else       (or (.-modelId m) "unknown"))]
             (set-overlay
              #jsx [Box {:flexDirection "column"}
                    [Text {:bold true} "Current Model"]
                    [Text (str "  " model-id)]
                    [Box {:marginTop 1}
                     [Text {:color "#565f89"} "Press ESC to close"]]])))))
     #js {:isActive (not (some? overlay))})

    (let [handle-submit
          (useCallback
           (fn [text]
             (set-editor-value "")
             (-> (do-submit agent streaming set-overlay set-streaming set-messages set-steerAcked set-editor-hidden text)
                 (.catch (fn [e]
                           (set-streaming false)
                           (set-editor-hidden false)
                           (when (and (.-extension-api agent) (.-ui (.-extension-api agent)))
                             (.notify (.-ui (.-extension-api agent))
                                      (str "Error: " (.-message e)) "error"))))))
           #js [streaming agent])]

      ;; The startup header banner is printed ONCE to stdout before Ink
      ;; mounts (see agent.modes.interactive + agent.ui.scrollback/
      ;; print-header-banner!). This matches Claude Code's pattern: the
      ;; banner is normal terminal output — it scrolls up with the rest
      ;; of the conversation as content accumulates. No Static, no
      ;; pinning, no repaint. Extension-provided custom headers still
      ;; go through the dynamic region below.
      #jsx [Box {:flexDirection "column" :height (max 1 (dec term-rows))}
            (when-let [custom-header (when custom-header-fn (custom-header-fn))]
              #jsx [Box {:flexShrink 0}
                    (if (string? custom-header)
                      #jsx [Box {:paddingX 1 :justifyContent "space-between"
                                 :borderStyle "round" :borderColor "#7aa2f7"}
                            [Text {:color "#7aa2f7" :bold true} custom-header]]
                      custom-header)])
            [WidgetContainer {:widgets widgets :position "above"}]
            [Box {:flexGrow 1 :flexShrink 1
                  :flexDirection "column" :overflow "hidden"}
             (if (and (empty? messages) (not streaming))
               #jsx [WelcomeScreen {:agent agent :theme theme
                                    :sessions-dir (:sessions-dir resources)}]
               ;; scrollback-mode flows from settings :scrollback-mode.
               ;; OFF (default): current Static-based rendering. ON:
               ;; in-flight-only rendering; commits go to scrollback.
               #jsx [ChatView {:messages messages :theme theme
                               :streaming streaming
                               :scrollback-mode (boolean
                                                 (when-let [sm (:settings resources)]
                                                   (:scrollback-mode ((:get sm)))))
                               :block-renderers (when-let [br (:block-renderers agent)]
                                                  @br)}])]
            [WidgetContainer {:widgets widgets :position "below"}]
            ;; Status line sits directly above the editor (oh-my-pi style,
            ;; and consistent with Claude Code / Codex / Gemini CLI).
            [StatusLine {:agent    agent
                         :theme    theme
                         :settings (when-let [sm (:settings resources)]
                                     ((:get sm)))
                         :max-width (or (.-columns stdout) 80)}]
            (when overlay
              (let [is-transparent (and (some? overlay) (not (string? overlay))
                                        (not (number? overlay)) (.-__transparent overlay))
                    content        (if is-transparent (.-__overlay-content overlay) overlay)]
                #jsx [Box {:position "absolute"
                           :flexDirection "column"
                           :width "100%" :height "100%"
                           :justifyContent "center"
                           :alignItems "center"}
                      [Overlay {:onClose (fn [] (set-overlay nil))
                                :transparent is-transparent}
                       content]]))
            ;; :key changes (bumped by `set-editor-remount-key`) force
            ;; ink-text-input to remount with a fresh cursorOffset at
            ;; the end of the new value. Used after picker commits
            ;; externally set editor-value — without the remount, the
            ;; cursor stays where it was before the picker opened
            ;; (e.g. right after the '/' on a slash completion).
            [Editor {:key            editor-remount-key
                     :onSubmit       handle-submit
                     :editorValue    editor-value
                     :setEditorValue set-editor-value-guarded
                     :hidden         editor-hidden
                     :overlay        (some? overlay)
                     :streaming  streaming
                     :steerAcked steerAcked
                     :theme      theme}]
            (when notification
              #jsx [Box {:flexShrink 0}
                    [Notification {:message (:message notification)
                                   :level   (:level notification)
                                   :theme   theme}]])
            (let [custom-footer (when custom-footer-fn (custom-footer-fn))]
              (if custom-footer
                #jsx [Box {:flexShrink 0}
                      (if (string? custom-footer)
                        #jsx [Box {:paddingX 1 :justifyContent "space-between"}
                              [Text {:color "#7aa2f7"} custom-footer]
                              [Text {:color "#565f89"} "nyma v0.1.0"]]
                        custom-footer)]
                #jsx [Footer {:agent agent :theme theme :statuses statuses}]))])))
