(ns agent.ui.chat-pane
  "Pi-tui Component for the chat message list.

   Implements the Component interface: { render(width): string[], invalidate() }.
   Extra API:
     .appendChunk(delta)    — append text delta to the last assistant message
     .pushMessage(msg)      — push a complete message (any role)
     .setMessages(msgs)     — replace the full message list"
  (:require ["@mariozechner/pi-tui" :refer [visibleWidth truncateToWidth]]
            [agent.ui.chat-renderer :as cr]))

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn clamp-line
  "Truncate one rendered line to `width`, measured the way pi-tui measures it.

   pi-tui THROWS on any line wider than the terminal, from inside its own
   render timer — it calls stop() first, so the exception escapes with the
   terminal already torn down and takes the session with it. There is no error
   hook and no strict-width opt-out, so this boundary is the only place nyma
   can defend itself.

   Deliberately uses pi-tui's `visibleWidth` rather than our `string-width`:
   agreeing with the function that judges us is the entire point. The two
   disagree on tabs, and we are pinned to a release missing three months of
   grapheme-width, OSC-8 and CRLF fixes, so more disagreements are likely.

   This does NOT fix the tab crash — that overflow appears later, when pi-tui
   composites an overlay over an already-conforming line (see
   `ansi/expand-tabs`). It downgrades the *rest* of the class from a dead
   session to a short line."
  [line width]
  (if (and (number? width) (pos? width) (> (visibleWidth line) width))
    (truncateToWidth line width)
    line))

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
                 ;; Last line of defence before pi-tui: never hand it a line it
                 ;; would throw on.
                 (to-array (mapv (fn [l] (clamp-line l width)) @out))))

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
