(ns agent.ui.app
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useCallback]]
            ["ink" :refer [Box Text render useInput useApp]]
            [agent.loop :refer [run steer]]
            ["./header.jsx" :refer [Header]]
            ["./chat_view.jsx" :refer [ChatView]]
            ["./editor.jsx" :refer [Editor]]
            ["./footer.jsx" :refer [Footer]]
            ["./overlay.jsx" :refer [Overlay]]
            ["./dialogs.jsx" :refer [ConfirmDialog SelectDialog InputDialog]]
            ["./notification.jsx" :refer [Notification]]
            ["./widget_container.jsx" :refer [WidgetContainer]]))

(defn- handle-command [agent text set-overlay]
  (let [parts   (.split (.slice text 1) " ")
        cmd     (first parts)
        args    (rest parts)
        commands @(:commands agent)]
    (when-let [handler (get-in commands [cmd :handler])]
      (handler args #js {:ui (when-let [ext (.-extension-api agent)] (.-ui ext))}))))

(defn ^:async do-submit [agent streaming set-overlay set-streaming set-messages set-steerAcked text]
  ;; Emit "input" event — extensions can intercept, transform, or fully handle
  (let [input-result (js-await
                       ((:emit-collect (:events agent)) "input"
                         #js {:input text :text text}))
        handled      (get input-result "handle")
        transformed  (get input-result "input")
        text         (if (and (some? transformed) (not= transformed text))
                       transformed
                       text)]
    (when-not handled
      (cond
        (.startsWith text "/")
        (js-await (handle-command agent text set-overlay))

        streaming
        (do
          (steer agent {:role "user" :content text})
          (set-steerAcked true)
          (js/setTimeout (fn [] (set-steerAcked false)) 1500))

        :else
        (do
          (set-streaming true)
          (set-messages
            (fn [prev] (conj (vec prev) {:role "user" :content text})))

          ;; fullStream text-delta chunks have `.text` (not `.delta`)
          ;; The AI SDK remaps internal `delta` → `text` in the fullStream transform.
          (let [update-handler
                (fn [chunk]
                  (set-messages
                    (fn [prev]
                      (let [all (vec prev)
                            last-msg (last all)
                            text-delta (.-text chunk)]
                        (if (= (:role last-msg) "assistant")
                          (conj (vec (butlast all))
                                (update last-msg :content str text-delta))
                          (conj all {:role "assistant" :content (or text-delta "")}))))))]
            ((:on (:events agent)) "message_update" update-handler)
            (js-await (run agent text))
            ((:off (:events agent)) "message_update" update-handler)
            (set-streaming false)
            ;; Strip any lingering tool-start messages (frozen spinners)
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
        [custom-header-fn set-custom-header] (useState nil)
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
                             :options (js->clj options)
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
                  (assoc w id {:lines (if (array? lines) (js->clj lines) lines)
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
                      {:role      "tool-start"
                       :tool-name (get data :tool-name)
                       :args      (get data :args)
                       :exec-id   (get data :exec-id)
                       :verbosity verbosity
                       :max-lines max-lines}))))

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
                          end-msg {:role      "tool-end"
                                   :tool-name (get data :tool-name)
                                   :duration  (get data :duration)
                                   :result    (get data :result)
                                   :exec-id   exec-id
                                   :verbosity verbosity
                                   :max-lines max-lines}]
                      (if idx
                        (assoc (vec prev) idx end-msg)
                        (conj (vec prev) end-msg))))))]

          ((:on events) "tool_execution_start" on-start)
          ((:on events) "tool_execution_end" on-end)
          (fn []
            ((:off events) "tool_execution_start" on-start)
            ((:off events) "tool_execution_end" on-end))))
      #js [agent])

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
          (and (.-ctrl key) (= input "p")) nil)))

    ;; Submit handler — useCallback wraps the async fn, returning its Promise
    (let [handle-submit
          (useCallback
            (fn [text]
              (do-submit agent streaming set-overlay set-streaming set-messages set-steerAcked text))
            #js [streaming agent])]

      #jsx [Box {:flexDirection "column" :height "100%"}
            (if custom-header-fn
              (custom-header-fn)
              #jsx [Header {:agent agent :resources resources :theme theme}])
            [WidgetContainer {:widgets widgets :position "above"}]
            [ChatView {:messages messages :theme theme}]
            [WidgetContainer {:widgets widgets :position "below"}]
            (when overlay
              (let [is-transparent (and (some? overlay) (not (string? overlay))
                                       (not (number? overlay)) (.-__transparent overlay))
                    content        (if is-transparent (.-__overlay-content overlay) overlay)]
                #jsx [Overlay {:onClose (fn [] (set-overlay nil))
                               :transparent is-transparent}
                      content]))
            [Editor {:onSubmit   handle-submit
                     :streaming  streaming
                     :steerAcked steerAcked
                     :theme      theme}]
            (when notification
              #jsx [Notification {:message (:message notification)
                                  :level   (:level notification)
                                  :theme   theme}])
            (if custom-footer-fn
              (custom-footer-fn)
              #jsx [Footer {:agent agent :theme theme :statuses statuses}])])))
