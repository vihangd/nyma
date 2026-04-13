(ns agent.ui.app
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useCallback useRef]]
            ["ink" :refer [Box Text render useInput useApp useStdout]]
            [agent.loop :refer [run steer]]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.keybinding-registry :as kbr]
            [agent.ui.bracketed-paste :as paste]
            [agent.ui.autocomplete-provider :as ac]
            [agent.ui.editor-bash :as editor-bash]
            [agent.ui.editor-eval :as editor-eval]
            ["./header.jsx" :refer [Header]]
            ["./chat_view.jsx" :refer [ChatView]]
            ["./editor.jsx" :refer [Editor]]
            ["./footer.jsx" :refer [Footer]]
            ["./overlay.jsx" :refer [Overlay]]
            ["./dialogs.jsx" :refer [ConfirmDialog SelectDialog InputDialog]]
            ["./notification.jsx" :refer [Notification]]
            ["./widget_container.jsx" :refer [WidgetContainer]]
            ["./help_overlay.jsx" :refer [HelpOverlay]]
            ["./status_line.jsx" :refer [StatusLine]]
            ["./welcome.jsx" :refer [WelcomeScreen]]
            ["./mention_picker.mjs" :refer [create-picker]]))

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
      ((:handler entry) args
                        #js {:ui            (when-let [ext (.-extension-api agent)] (.-ui ext))
                             :agent         agent
                             :set-messages  set-messages
                             :append-message (fn [msg]
                                               (set-messages
                                                (fn [prev] (conj (vec prev) msg))))}))))

(defn- add-user-msg!
  "Add user message to chat. Called synchronously to batch with Editor clear."
  [set-messages text]
  (set-messages (fn [prev] (conj (vec prev) {:role "user" :content text}))))

(defn- add-assistant-chunk!
  "Append a text delta to the last assistant message, or create one."
  [set-messages text-delta]
  (set-messages
   (fn [prev]
     (let [all      (vec prev)
           last-msg (last all)]
       (if (= (:role last-msg) "assistant")
         (conj (vec (butlast all))
               (update last-msg :content str text-delta))
         (conj all {:role "assistant" :content (or text-delta "")}))))))

(defn ^:async do-submit [agent streaming set-overlay set-streaming set-messages set-steerAcked set-editor-hidden text]
  ;; Expand bracketed-paste markers to their original content before the
  ;; prompt is handed to extensions or the model.
  (let [paste-handler (when-let [ph (.-__paste-handler agent)] ph)
        pastes-map    (when paste-handler @(:pastes paste-handler))
        text          (if pastes-map
                        (paste/expand-paste-markers text pastes-map)
                        text)
        ;; Emit "input" event — extensions can intercept, transform, or fully handle
        input-result (js-await
                      ((:emit-collect (:events agent)) "input"
                                                       #js {:input text :text text}))
        handled      (get input-result "handle")
        transformed  (get input-result "input")
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
           (fn [prev] (conj (vec prev) {:role "assistant" :content response})))))

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
                               :reason    (:reason result)}
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
                               :install-hint (:install-hint result)}
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
                           ((:emit-collect (:events agent)) "input_submit"
                                                            #js {:text text :timestamp (js/Date.now)}))
            cancelled     (get submit-result "cancel")
            text          (or (get submit-result "text") text)]
        (when-not cancelled
          (set-streaming true)
          (add-user-msg! set-messages text)
          (let [update-handler
                (fn [chunk] (add-assistant-chunk! set-messages (.-text chunk)))]
            ((:on (:events agent)) "message_update" update-handler)
            (js-await (run agent text))
            ((:off (:events agent)) "message_update" update-handler)
            (set-streaming false)
            (set-messages (fn [prev] (vec (remove #(= (:role %) "tool-start") prev))))))))))

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
        ;; Bumped whenever the on-resolve callback externally sets
        ;; the editor text (picker commits). Used as the React key
        ;; on the Editor subtree so ink-text-input remounts with a
        ;; fresh cursorOffset = newValue.length — otherwise the
        ;; cursor stays wherever it was before the picker opened
        ;; (after the trailing '/' for a slash completion), which
        ;; looks broken.
        [editor-remount-key set-editor-remount-key] (useState 0)
        {:keys [stdout]}                  (useStdout)
        [term-rows set-term-rows]         (useState (or (.-rows stdout) 24))
        layout-handlers                   (useRef #js [])
        app                               (useApp)]

    ;; Wire extension UI hooks
    (useEffect
     (fn []
       (let [ui #js {:available true}]
         (set! (.-showOverlay ui)
               (fn [content & [opts]]
                 (if (and opts (.-transparent opts))
                   (set-overlay #js {:__overlay-content content :__transparent true})
                   (set-overlay content))))
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
                      (let [dismiss (fn [val] (set-overlay nil) (resolve val))
                            cleanup (with-dismissal dismiss opts)]
                        (set-overlay
                         #jsx [ConfirmDialog
                               {:title title
                                :message msg
                                :on-confirm (fn [] (cleanup) (dismiss true))
                                :on-cancel  (fn [] (cleanup) (dismiss false))}])))))))
         (set! (.-select ui)
               (fn [title options opts]
                 (js/Promise.
                  (fn [resolve]
                    (let [dismiss (fn [val] (set-overlay nil) (resolve val))
                          cleanup (with-dismissal dismiss opts)]
                      (set-overlay
                       #jsx [SelectDialog
                             {:title title
                              :options options
                              :on-select (fn [choice] (cleanup) (dismiss choice))
                              :on-cancel (fn [] (cleanup) (dismiss nil))
                              :theme theme}]))))))
         (set! (.-input ui)
               (fn [title placeholder opts]
                 (js/Promise.
                  (fn [resolve]
                    (let [dismiss (fn [val] (set-overlay nil) (resolve val))
                          cleanup (with-dismissal dismiss opts)]
                      (set-overlay
                       #jsx [InputDialog
                             {:title title
                              :placeholder (or placeholder "")
                              :on-submit (fn [text] (cleanup) (dismiss text))
                              :on-cancel (fn [] (cleanup) (dismiss nil))
                              :theme theme}]))))))
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
                    (set-overlay component)))))
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

             on-start
             (fn [data]
               (set-messages
                (fn [prev]
                  (conj (vec prev)
                        (cond-> {:role      "tool-start"
                                 :tool-name (get data :tool-name)
                                 :args      (get data :args)
                                 :exec-id   (get data :exec-id)
                                 :verbosity (or (get data :custom-verbosity) verbosity)
                                 :max-lines max-lines}
                          (get data :custom-one-line-args)  (assoc :custom-one-line-args (get data :custom-one-line-args))
                          (get data :custom-status-text)    (assoc :custom-status-text (get data :custom-status-text))
                          (get data :custom-icon)           (assoc :custom-icon (get data :custom-icon)))))))

             on-end
             (fn [data]
               (set-messages
                (fn [prev]
                  (let [exec-id (get data :exec-id)
                        idx     (reduce-kv
                                 (fn [_ i msg]
                                   (when (and (= (:role msg) "tool-start")
                                              (= (:exec-id msg) exec-id))
                                     (reduced i)))
                                 nil (vec prev))
                        end-msg (cond-> {:role      "tool-end"
                                         :tool-name (get data :tool-name)
                                         :duration  (get data :duration)
                                         :result    (get data :result)
                                         :exec-id   exec-id
                                         :verbosity (or (get data :custom-verbosity) verbosity)
                                         :max-lines max-lines}
                                  (get data :custom-one-line-result) (assoc :custom-one-line-result (get data :custom-one-line-result))
                                  (get data :custom-icon)            (assoc :custom-icon (get data :custom-icon)))]
                    (if idx
                      (assoc (vec prev) idx end-msg)
                      (conj (vec prev) end-msg))))))

             on-update
             (fn [data]
               (set-messages
                (fn [prev]
                  (let [exec-id (get data :exec-id)
                        idx     (reduce-kv
                                 (fn [_ i msg]
                                   (when (and (= (:role msg) "tool-start")
                                              (= (:exec-id msg) exec-id))
                                     (reduced i)))
                                 nil (vec prev))]
                    (if idx
                      (assoc-in (vec prev) [idx :custom-status-text] (str (get data :data)))
                      prev)))))]

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
    (useEffect
     (fn []
       (paste/enable-bracketed-paste)
       (let [handler     (paste/create-paste-handler)
             process-fn  (:process handler)
             stdin-fn    (fn [data]
                           (let [result (process-fn data)]
                             (when (and (:handled result) (:marker result))
                                ;; Append the marker to the editor value so
                                ;; the user sees '[paste #N +X lines]' where
                                ;; they would have seen the raw content.
                               (set-editor-value
                                (fn [prev] (str (or prev "") (:marker result)))))))]
         (set! (.-__paste-handler agent) handler)
         (.on (.-stdin js/process) "data" stdin-fn)
         (fn []
           (.off (.-stdin js/process) "data" stdin-fn)
           (paste/disable-bracketed-paste)
           (set! (.-__paste-handler agent) js/undefined))))
     #js [agent])

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
                                                  (set! (.-current dismissed-trigger-ref)
                                                        trigger-text))))]
                    (set-overlay picker)))))
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
           (when streaming ((:abort agent)))

           (kbr/matches? reg input key "app.help")
            ;; Open help overlay only when editor is empty — matches oh-my-pi UX.
           (when (and (empty? editor-value) (not streaming))
             (set-overlay
              #jsx [HelpOverlay {:registry reg
                                 :shortcuts (when-let [s (:shortcuts agent)] @s)
                                 :theme theme
                                 :onClose (fn [] (set-overlay nil))}]))

           (kbr/matches? reg input key "app.model.show")
           (let [model-id (or (.-modelId (:model (:config agent))) "unknown")]
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

      #jsx [Box {:flexDirection "column" :height (max 1 (dec term-rows))}
            (let [custom-header (when custom-header-fn (custom-header-fn))]
              (if custom-header
                #jsx [Box {:flexShrink 0}
                      (if (string? custom-header)
                        #jsx [Box {:paddingX 1 :justifyContent "space-between"
                                   :borderStyle "round" :borderColor "#7aa2f7"}
                              [Text {:color "#7aa2f7" :bold true} custom-header]]
                        custom-header)]
                #jsx [Header {:agent agent :resources resources :theme theme}]))
            [WidgetContainer {:widgets widgets :position "above"}]
            [Box {:flexGrow 1 :flexShrink 1 :overflow "hidden"
                  :flexDirection "column"}
             (if (and (empty? messages) (not streaming))
               #jsx [WelcomeScreen {:agent agent :theme theme
                                    :sessions-dir (:sessions-dir resources)}]
               #jsx [ChatView {:messages messages :theme theme
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
                     :setEditorValue set-editor-value
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
