(ns agent.ui.app
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useCallback useRef]]
            ["ink" :refer [Box Text render useInput useApp useStdout]]
            [agent.loop :refer [run steer]]
            [agent.commands.resolver :refer [resolve-command]]
            ["./header.jsx" :refer [Header]]
            ["./chat_view.jsx" :refer [ChatView]]
            ["./editor.jsx" :refer [Editor]]
            ["./footer.jsx" :refer [Footer]]
            ["./overlay.jsx" :refer [Overlay]]
            ["./dialogs.jsx" :refer [ConfirmDialog SelectDialog InputDialog]]
            ["./notification.jsx" :refer [Notification]]
            ["./widget_container.jsx" :refer [WidgetContainer]]
            ["./mention_picker.mjs" :refer [create-picker]]))

(defn- handle-command [agent text set-overlay]
  (let [parts   (.split (.slice text 1) " ")
        cmd     (first parts)
        args    (rest parts)
        commands @(:commands agent)]
    (when-let [entry (resolve-command commands cmd)]
      ((:handler entry) args #js {:ui (when-let [ext (.-extension-api agent)] (.-ui ext))}))))

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
  ;; Emit "input" event — extensions can intercept, transform, or fully handle
  (let [input-result (js-await
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

      ;; ── Slash command ──
      (.startsWith text "/")
      (js-await (handle-command agent text set-overlay))

      ;; ── Steer (mid-stream follow-up) ──
      streaming
      (do
        (steer agent {:role "user" :content text})
        (set-steerAcked true)
        (js/setTimeout (fn [] (set-steerAcked false)) 1500))

      ;; ── Normal LLM prompt ──
      :else
      (do
        (set-streaming true)
        (add-user-msg! set-messages text)
        (let [update-handler
              (fn [chunk] (add-assistant-chunk! set-messages (.-text chunk)))]
          ((:on (:events agent)) "message_update" update-handler)
          (js-await (run agent text))
          ((:off (:events agent)) "message_update" update-handler)
          (set-streaming false)
          (set-messages (fn [prev] (vec (remove #(= (:role %) "tool-start") prev)))))))))

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
            (fn [id lines & [pos]]
              (set-widgets
                (fn [w]
                  (assoc w id {:lines (if (array? lines) (vec lines) lines)
                               :position (or pos "below")})))))
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

    ;; Emit editor_change so extensions can react to typed text (e.g. token preview)
    (useEffect
      (fn []
        (when-let [emit (:emit (:events agent))]
          (emit "editor_change" {:text editor-value}))
        js/undefined)
      #js [editor-value])

    ;; @-mention detection: when user types @, show file picker overlay
    (useEffect
      (fn []
        (when (and (not overlay) (not streaming) (not editor-hidden)
                   editor-value (.endsWith editor-value "@"))
          (let [providers (when-let [mp (:mention-providers agent)] @mp)]
            (when (and providers (seq providers))
              ;; Gather results from the first provider's search
              (let [provider (first (vals providers))
                    search   (:search provider)]
                (when search
                  (-> (search "")
                      (.then (fn [items]
                               (when (and items (pos? (.-length items)))
                                 (let [picker (create-picker (vec items) ""
                                                (fn [selected]
                                                  (set-overlay nil)
                                                  (when selected
                                                    ;; Replace trailing @ with selected value
                                                    (let [base (subs editor-value 0 (dec (count editor-value)))]
                                                      (set-editor-value (str base (.-value selected) " "))))))]
                                   (set-overlay picker)))))
                      (.catch (fn [_] nil))))))))
        js/undefined)
      #js [editor-value overlay streaming editor-hidden])

    ;; Global keyboard handler
    (useInput
      (fn [input key]
        (cond
          (.-escape key) (when streaming ((:abort agent)))
          (and (.-ctrl key) (= input "l"))
          (let [model-id (or (.-modelId (:model (:config agent))) "unknown")]
            (set-overlay
              #jsx [Box {:flexDirection "column"}
                    [Text {:bold true} "Current Model"]
                    [Text (str "  " model-id)]
                    [Box {:marginTop 1}
                     [Text {:color "#565f89"} "Press ESC to close"]]]))
          (and (.-ctrl key) (= input "p")) nil))
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
             [ChatView {:messages messages :theme theme
                        :block-renderers (when-let [br (:block-renderers agent)]
                                           @br)}]]
            [WidgetContainer {:widgets widgets :position "below"}]
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
            [Editor {:onSubmit       handle-submit
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
