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

(def clamp-line
  "Re-exported from chat-renderer, which owns the width contract. Kept here so
   the pane's own boundary check reads locally."
  cr/clamp-line)

(defn create-chat-pane
  "Create and return a pi-tui Component that renders the chat history.

   `theme` is a theme map or a thunk returning one. Interactive mode passes
   the thunk so `/theme` applies to the next frame; a closed-over map would
   have pinned the colours at construction."
  [theme]
  (let [theme-now (if (fn? theme) theme (fn [] theme))
        messages  (atom [])
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

        ;; Rendered lines per message, keyed on the MESSAGE OBJECT.
        ;;
        ;; pi-tui calls every child's render from scratch on every frame
        ;; (tui.js:84-90) and requests a frame on every keystroke, so without
        ;; this each keypress re-rendered the entire transcript: measured at
        ;; 66ms for 900 messages against a 16ms frame budget, which is why
        ;; typing got slower the longer a session ran. Markdown was ~10x the
        ;; rest per message — md/lexer re-tokenised the full text of every
        ;; finished assistant message 60 times a second (the existing md-cache
        ;; only skips highlighting of non-final blocks, never the lex).
        ;;
        ;; Safe because finished messages are immutable: add-chunk! rebuilds
        ;; only the LAST message and `(vec msgs)` copies the array, not its
        ;; elements, so completed messages keep object identity across renders.
        ;; The streaming tail is a fresh object per chunk, so it always misses
        ;; and always re-renders — which is what we want.
        ;;
        ;; A WeakMap rather than an id-keyed map on purpose: /resume reassigns
        ;; every message a fresh id, which is exactly how md-caches leaks its
        ;; whole contents today. Entries here die with the messages.
        line-cache (atom (js/WeakMap.))

        render-msg
        (fn [msg width]
          (let [hit (.get @line-cache msg)]
            (if (and hit (= (.-width hit) width))
              (.-lines hit)
              (let [lines (cr/render-message {:msg      msg
                                              :width    width
                                              :theme    (theme-now)
                                              :md-cache (get-cache msg)})]
                ;; Width is part of the key: a resize must re-wrap, not serve
                ;; lines measured for the old terminal.
                ;; WeakMap keys must be objects; squint compiles a CLJS map to
                ;; one, but guard rather than throw if a caller ever passes a
                ;; primitive.
                (try (.set @line-cache msg #js {:width width :lines lines})
                     (catch :default _ nil))
                lines))))

        pane
        #js {:render
             (fn [width]
               (let [msgs (vec @messages)
                     n    (count msgs)
                     ;; Accumulate into a mutable JS array. This was
                     ;; `(swap! out into ...)` on an atom holding a vector —
                     ;; one fresh immutable vector per message, so building the
                     ;; output was O(total lines²) and cost 11ms at 900 messages
                     ;; even with every message's lines already cached. The
                     ;; per-message memo alone left that in place.
                     out  #js []]
                 (doseq [[i msg] (map-indexed vector msgs)]
                   (doseq [l (render-msg msg width)]
                     (.push out l))
                   (when (< i (dec n))
                     (.push out "")))
                 ;; Clamping happens once, in width_guard, which wraps every
                 ;; child and so cannot be bypassed by adding a component and
                 ;; forgetting it. This used to re-measure every line a second
                 ;; time (and render-message a third).
                 out))

             :invalidate
             (fn []
               ;; Drops the markdown caches; the line cache keys off the same
               ;; messages, so clear it too or the next frame serves lines
               ;; built from the caches just thrown away.
               (reset! line-cache (js/WeakMap.))
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
