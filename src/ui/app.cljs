(ns agent.ui.app
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect useCallback]]
            ["ink" :refer [Box Text render useInput useApp]]
            [agent.loop :refer [run steer]]
            [agent.ui.header :refer [Header]]
            [agent.ui.chat-view :refer [ChatView]]
            [agent.ui.editor :refer [Editor]]
            [agent.ui.footer :refer [Footer]]
            [agent.ui.overlay :refer [Overlay]]))

(defn- handle-command [agent text set-overlay]
  (let [parts   (.split (.slice text 1) " ")
        cmd     (first parts)
        args    (rest parts)
        commands @(:commands agent)]
    (when-let [handler (get-in commands [cmd :handler])]
      (handler args #js {:ui (.-ui (.-extension-api agent))}))))

(defn App [{:keys [agent session resources]}]
  (let [[messages set-messages]   (useState [])
        [streaming set-streaming] (useState false)
        [overlay set-overlay]     (useState nil)
        [theme set-theme]         (useState (.-theme resources))
        app                       (useApp)]

    ;; Wire extension UI hooks
    (useEffect
      (fn []
        (let [ui #js {}]
          (set! (.-showOverlay ui)
            (fn [content] (set-overlay content)))
          (set! (.-setWidget ui)
            (fn [_position _component] nil))
          (set! (.-confirm ui)
            (fn [msg]
              (js/Promise.
                (fn [resolve]
                  (set-overlay
                    #jsx [:div {:key "confirm"}
                          [:p msg]
                          [:button {:onClick (fn [] (set-overlay nil) (resolve true))} "Yes"]
                          [:button {:onClick (fn [] (set-overlay nil) (resolve false))} "No"]])))))
          (when (.-extension-api agent)
            (set! (.-ui (.-extension-api agent)) ui)))
        js/undefined)
      #js [])

    ;; Global keyboard handler
    (useInput
      (fn [input key]
        (cond
          (.-escape key) (when streaming ((:abort agent)))
          (and (.-ctrl key) (= input "l")) (set-overlay "model-switcher")
          (and (.-ctrl key) (= input "p")) nil)))

    ;; Submit handler
    (let [handle-submit
          (useCallback
            (fn ^:async [text]
              (cond
                (.startsWith text "/")
                (js-await (handle-command agent text set-overlay))

                streaming
                (steer agent {:role "user" :content text})

                :else
                (do
                  (set-streaming true)
                  (set-messages
                    (fn [prev] (conj (vec prev) {:role "user" :content text})))

                  ((:on (:events agent)) "message_update"
                    (fn [chunk]
                      (set-messages
                        (fn [prev]
                          (let [all (vec prev)
                                last-msg (last all)]
                            (if (= (:role last-msg) "assistant")
                              (conj (vec (butlast all))
                                    (update last-msg :content str (.-textDelta chunk)))
                              (conj all {:role "assistant" :content (or (.-textDelta chunk) "")})))))))

                  (js-await (run agent text))
                  (set-streaming false))))
            #js [streaming agent])]

      #jsx [Box {:flexDirection "column" :height "100%"}
            [Header {:agent agent :resources resources :theme theme}]
            [ChatView {:messages messages :theme theme}]
            (when overlay
              [Overlay {:onClose (fn [] (set-overlay nil))}
               overlay])
            [Editor {:onSubmit  handle-submit
                     :streaming streaming
                     :theme     theme}]
            [Footer {:agent agent :theme theme}]])))
