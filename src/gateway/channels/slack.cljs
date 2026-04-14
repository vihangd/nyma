(ns gateway.channels.slack
  "Slack channel adapter using Socket Mode.

   Receives events over a persistent WebSocket connection — no public HTTPS
   endpoint required. Uses @slack/socket-mode and @slack/web-api, which are
   auto-installed on first use if not already present.

   Config keys:
     :app-token   string  xapp-... Socket Mode app token (required)
     :bot-token   string  xoxb-... Bot user OAuth token (required)

   Capabilities: #{:text :typing :threads}

   Streaming strategy: Slack supports updating messages (chat.update), so
   streaming deltas are delivered by editing the bot's reply in-place.
   Recommended gateway streaming policy: :debounce (rate-limit ~50 writes/min)."
  (:require [gateway.protocols :as proto]))

;;; ─── Lazy SDK loading ─────────────────────────────────────────────────

(def ^:private sdk-cache (atom nil))

(defn ^:async load-slack-sdk
  "Dynamically import @slack/socket-mode and @slack/web-api.
   Returns {:SocketModeClient :WebClient} or throws with install hint."
  []
  (if @sdk-cache
    (js/Promise.resolve @sdk-cache)
    (try
      (let [sm-mod (js-await (js/import "@slack/socket-mode"))
            wa-mod (js-await (js/import "@slack/web-api"))
            result {:SocketModeClient (.-SocketModeClient sm-mod)
                    :WebClient        (.-WebClient wa-mod)}]
        (reset! sdk-cache result)
        result)
      (catch :default e
        (throw (js/Error.
                (str "[slack] Failed to load Slack SDK. "
                     "Run: bun add @slack/socket-mode @slack/web-api\n"
                     "Original: " (.-message e))))))))

;;; ─── Web API helpers ──────────────────────────────────────────────────

(defn ^:async slack-post
  "Post a new message. Returns the timestamp (ts) of the posted message."
  [web-client channel-id text]
  (try
    (let [resp (js-await
                (.postMessage (.-chat web-client)
                              #js {:channel channel-id :text text}))]
      (when (.-ok resp) (.-ts resp)))
    (catch :default e
      (js/console.warn "[slack] Post error:" (.-message e))
      nil)))

(defn ^:async slack-update
  "Update an existing message in-place."
  [web-client channel-id ts text]
  (try
    (js-await
     (.update (.-chat web-client)
              #js {:channel channel-id :ts ts :text text}))
    (catch :default e
      (js/console.warn "[slack] Update error:" (.-message e)))))

;;; ─── Response context ─────────────────────────────────────────────────

(defn make-response-ctx
  "Build an IResponseContext for one Slack message."
  [web-client channel-id thread-ts channel-name]
  (let [reply-ts (atom nil)]
    (proto/make-response-context
     {:conversation-id (str "slack:" channel-id
                            (when thread-ts (str ":" thread-ts)))
      :channel-name    channel-name
      :capabilities    #{:text :typing :threads}

       ;; send! — post a new standalone message
      :send!
      (fn [content]
        (slack-post web-client channel-id (or (:text content) "")))

       ;; stream! — edit existing bot reply in-place (or create on first call)
      :stream!
      (fn [content]
        (let [text (or (:text content) "")]
          (if @reply-ts
            (slack-update web-client channel-id @reply-ts text)
            (.. (slack-post web-client channel-id text)
                (then (fn [ts] (reset! reply-ts ts)))))))

      :meta!
      (fn [op _args]
        (case op
           ;; Post a placeholder; the first stream! will edit it
          :typing-start
          (.. (slack-post web-client channel-id "_Thinking..._")
              (then (fn [ts] (reset! reply-ts ts) nil)))
          (js/Promise.resolve nil)))

      :interrupt! (fn [_] nil)})))

;;; ─── Channel factory ──────────────────────────────────────────────────

(defn create-slack-channel
  "Factory function for the Slack Socket Mode channel adapter.
   Registered with gateway.core as channel type :slack."
  [channel-name cfg]
  (let [app-token   (or (:app-token cfg) (.-appToken cfg))
        bot-token   (or (:bot-token cfg) (.-botToken cfg))
        client-atom (atom nil)
        smc-atom    (atom nil)]

    (when-not app-token
      (throw (js/Error. (str "[slack] Missing app-token for '" channel-name "'"))))
    (when-not bot-token
      (throw (js/Error. (str "[slack] Missing bot-token for '" channel-name "'"))))

    {:name         channel-name
     :capabilities #{:text :typing :threads}

     :start!
     (fn [on-message-fn]
       (.. (load-slack-sdk)
           (then (fn [sdk]
                   (let [web-client ((:WebClient sdk) bot-token)
                         smc        ((:SocketModeClient sdk) #js {:appToken app-token})]
                     (reset! client-atom web-client)
                     (reset! smc-atom smc)

                     ;; All Slack events arrive on "slack_event".
                     ;; Acknowledge immediately (Slack requires ACK within 3s).
                     (.on smc "slack_event"
                          (fn [data]
                            ;; ACK first, then handle
                            (when (.-ack data) ((.ack data)))
                            (let [body  (.-body data)
                                  event (when body (.-event body))]
                              (when (and event
                                         (= (.-type event) "message")
                                         (not (.-bot_id event))      ;; skip bot msgs
                                         (not (.-subtype event))     ;; skip edits, joins
                                         (seq (.-text event)))
                                (let [channel-id (.-channel event)
                                      user-id    (or (.-user event) "unknown")
                                      thread-ts  (.-thread_ts event)
                                      ts         (.-ts event)
                                      msg-map    {:event-id        (str "slack:" ts)
                                                  :conversation-id (str "slack:" channel-id
                                                                        (when thread-ts
                                                                          (str ":" thread-ts)))
                                                  :user-id         user-id
                                                  :text            (.-text event)
                                                  :attachments     []
                                                  :raw             data}
                                      resp-ctx   (make-response-ctx
                                                  web-client channel-id thread-ts channel-name)]
                                  (on-message-fn msg-map resp-ctx))))))

                     (js/console.log
                      (str "[slack:" channel-name "] Connecting via Socket Mode..."))
                     (.start smc))))))

     :stop!
     (fn []
       (when @smc-atom
         (js/console.log (str "[slack:" channel-name "] Disconnecting..."))
         (.disconnect @smc-atom))
       (js/Promise.resolve nil))

     :setup!
     (fn []
       (js/console.log (str "\nSlack channel: " channel-name))
       (.. (load-slack-sdk)
           (then (fn [sdk]
                   (let [web-client ((:WebClient sdk) bot-token)]
                     (.. (.test (.-auth web-client) #js {})
                         (then (fn [resp]
                                 (js/console.log
                                  (str "  Bot:        " (.-user resp)
                                       " (" (.-user_id resp) ")\n"
                                       "  Team:       " (.-team resp) "\n"
                                       "  Connection: OK"))))
                         (catch (fn [e]
                                  (js/console.error
                                   "  Connection: FAILED —" (.-message e))))))))
           (catch (fn [e]
                    (js/console.error "  SDK load failed:" (.-message e))))))}))
