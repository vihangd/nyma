(ns agent.ui.chat-pane
  "Pi-tui Component for the chat message list.

   Implements the Component interface: { render(width): string[], invalidate() }.
   Extra API:
     .appendChunk(delta)    — append text delta to the last assistant message
     .pushMessage(msg)      — push a complete message (any role)
     .setMessages(msgs)     — replace the full message list"
  (:require [agent.ui.chat-renderer :as cr]))

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn create-chat-pane
  "Create and return a pi-tui Component that renders the chat history."
  [theme]
  (let [messages  (atom [])
        md-caches (atom {})   ;; msg-id → atom holding incremental-render cache

        ensure-cache!
        (fn [id]
          (when (and id (not (get @md-caches id)))
            (swap! md-caches assoc id (atom nil))))

        get-cache
        (fn [msg]
          (when (= "assistant" (:role msg))
            (let [id (:id msg)]
              (ensure-cache! id)
              (get @md-caches id))))

        pane
        #js {:render
             (fn [width]
               (let [msgs (vec @messages)
                     n    (count msgs)
                     out  (atom [])]
                 (doseq [[i msg] (map-indexed vector msgs)]
                   (swap! out into
                          (cr/render-message {:msg      msg
                                              :width    width
                                              :theme    theme
                                              :md-cache (get-cache msg)}))
                   (when (< i (dec n))
                     (swap! out conj "")))
                 (to-array @out)))

             :invalidate
             (fn []
               (doseq [[_ c] @md-caches] (reset! c nil)))}]

    (set! (.-appendChunk pane)
          (fn [delta]
            (swap! messages
                   (fn [msgs]
                     (let [v    (vec msgs)
                           tail (last v)]
                       (if (= "assistant" (:role tail))
                         (update v (dec (count v)) update :content str delta)
                         (conj v {:role "assistant" :content (or delta "") :id (new-id)})))))))

    (set! (.-pushMessage pane)
          (fn [msg]
            (let [msg-with-id (if (:id msg) msg (assoc msg :id (new-id)))]
              (swap! messages conj msg-with-id))))

    (set! (.-replaceMessage pane)
          (fn [pred-fn new-msg]
            (swap! messages
                   (fn [msgs]
                     (let [v (vec msgs)
                           i (first (keep-indexed (fn [idx m] (when (pred-fn m) idx)) v))]
                       (if (some? i)
                         (assoc v i new-msg)
                         (conj v new-msg)))))))

    (set! (.-setMessages pane)
          (fn [new-msgs]
            (reset! messages (vec new-msgs))))

    (set! (.-getMessages pane)
          (fn [] @messages))

    pane))
