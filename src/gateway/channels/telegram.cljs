(ns gateway.channels.telegram
  "Telegram Bot API channel adapter.

   Uses long-polling (getUpdates) — no webhook server required, works behind NAT.
   Supports text messages and file attachments (downloaded to local temp files).

   Config keys:
     :token       string  Bot API token from @BotFather (required)
     :parse-mode  string  'Markdown' or 'HTML' (default: 'Markdown')
     :timeout     int     Long-poll timeout in seconds (default: 30)

   Capabilities: #{:text :typing :attachments}

   Streaming strategy: Telegram allows editing existing messages, so streaming
   updates are delivered by editing the bot's reply in-place. Recommended
   gateway streaming policy: :debounce or :throttle (Telegram rate-limits edits
   to ~1/second per chat)."
  (:require ["node:fs/promises" :as fsp]
            ["node:path" :as path]
            ["node:os" :as os]
            [gateway.protocols :as proto]))

;;; ─── Telegram HTTP helpers ────────────────────────────────────────────

(defn- api-url [token method]
  (str "https://api.telegram.org/bot" token "/" method))

(defn ^:async telegram-call
  "Call a Telegram Bot API method. Returns the parsed JSON response or throws."
  [token method params]
  (let [resp (js-await
              (js/fetch (api-url token method)
                        #js {:method  "POST"
                             :headers #js {"Content-Type" "application/json"}
                             :body    (js/JSON.stringify (clj->js params))}))
        body (js-await (.json resp))]
    (if (.-ok body)
      body
      (throw (js/Error. (str "Telegram API error [" method "]: "
                             (.-description body)))))))

(defn ^:async telegram-send
  "Send a text message. Returns message_id on success."
  [token chat-id text parse-mode]
  (let [resp (js-await
              (telegram-call token "sendMessage"
                             {:chat_id    chat-id
                              :text       (or text "")
                              :parse_mode (or parse-mode "Markdown")}))]
    (.. resp -result -message_id)))

(defn ^:async telegram-edit
  "Edit an existing message. Swallows 'message not modified' errors (idempotent)."
  [token chat-id message-id text parse-mode]
  (try
    (js-await
     (telegram-call token "editMessageText"
                    {:chat_id    chat-id
                     :message_id message-id
                     :text       (or text "")
                     :parse_mode (or parse-mode "Markdown")}))
    (catch :default e
      ;; Telegram returns 400 when text hasn't changed — not a real error
      (when-not (.includes (.-message e) "message is not modified")
        (js/console.warn "[telegram] Edit error:" (.-message e))))))

(defn ^:async telegram-typing
  "Send a typing chat action."
  [token chat-id]
  (try
    (js-await (telegram-call token "sendChatAction"
                             {:chat_id chat-id :action "typing"}))
    (catch :default e
      (js/console.warn "[telegram] Typing error:" (.-message e)))))

;;; ─── Response context ─────────────────────────────────────────────────

(defn make-response-ctx
  "Build an IResponseContext for one Telegram message.
   Streaming works by editing the same message in-place."
  [token chat-id channel-name parse-mode]
  (let [msg-id (atom nil)]   ;; nil until first send/stream creates a message
    (proto/make-response-context
     {:conversation-id (str "telegram:" chat-id)
      :channel-name    channel-name
      :capabilities    #{:text :typing}

       ;; send! — always posts a new message (used for tool-result follow-ups etc.)
      :send!
      (fn [content]
        (let [text (or (:text content) (:markdown content) "")]
          (.. (telegram-send token chat-id text parse-mode)
              (then (fn [id] (reset! msg-id id))))))

       ;; stream! — edits existing message (called by streaming policy with accumulated text)
      :stream!
      (fn [content]
        (let [text (or (:text content) "")]
          (if @msg-id
            (telegram-edit token chat-id @msg-id text parse-mode)
             ;; First chunk — create the message first
            (.. (telegram-send token chat-id text parse-mode)
                (then (fn [id] (reset! msg-id id)))))))

       ;; meta! — typing indicator and lifecycle signals
      :meta!
      (fn [op _args]
        (case op
          :typing-start (telegram-typing token chat-id)
          :done         (js/Promise.resolve nil)   ;; message already updated
          :tool-start   (telegram-typing token chat-id)
          (js/Promise.resolve nil)))

      :interrupt! (fn [_] nil)})))

;;; ─── Attachment handling ──────────────────────────────────────────────

(defn ^:async download-attachment
  "Download a Telegram file to a temp directory. Returns local path."
  [token file-id]
  (try
    (let [file-resp (js-await
                     (telegram-call token "getFile" {:file_id file-id}))
          file-path (.. file-resp -result -file_path)
          url       (str "https://api.telegram.org/file/bot" token "/" file-path)
          dl-resp   (js-await (js/fetch url))
          buf       (js-await (.arrayBuffer dl-resp))
          filename  (path/basename file-path)
          tmp-path  (path/join (.tmpdir os) (str "nyma-tg-" (js/Date.now) "-" filename))]
      (js-await (.writeFile fsp tmp-path (js/Buffer.from buf)))
      tmp-path)
    (catch :default e
      (js/console.warn "[telegram] Attachment download failed:" (.-message e))
      nil)))

(defn ^:async extract-attachments
  "Return a vector of attachment maps from a Telegram message object."
  [token msg-obj]
  (let [attachments (atom [])]
    ;; Photo — take the largest size
    (when-let [photos (.-photo msg-obj)]
      (let [largest (aget photos (dec (.-length photos)))
            local   (js-await (download-attachment token (.-file_id largest)))]
        (when local
          (swap! attachments conj {:local local :mime-type "image/jpeg"}))))
    ;; Document
    (when-let [doc (.-document msg-obj)]
      (let [local (js-await (download-attachment token (.-file_id doc)))]
        (when local
          (swap! attachments conj {:local local :mime-type (or (.-mime_type doc) "application/octet-stream")}))))
    @attachments))

;;; ─── Update → message conversion ─────────────────────────────────────

(defn ^:async update->msg
  "Convert a Telegram update object to a gateway inbound-message map.
   Returns nil for non-message updates (channel posts, callbacks, etc.)."
  [token update-obj]
  (let [msg (.-message update-obj)]
    (when msg
      (let [chat-id  (str (.. msg -chat -id))
            user-id  (str (or (and (.-from msg) (.. msg -from -id)) "unknown"))
            text     (or (.-text msg) (.-caption msg) "")
            atts     (js-await (extract-attachments token msg))]
        {:event-id        (str "tg:" (.-update_id update-obj))
         :conversation-id (str "telegram:" chat-id)
         :user-id         user-id
         :text            text
         :attachments     atts
         :raw             update-obj
         :_chat-id        chat-id}))))   ;; internal — used by response-ctx factory

;;; ─── Long-polling loop ────────────────────────────────────────────────

(defn ^:async poll-loop
  "Long-poll getUpdates and dispatch each message to on-message-fn.
   Runs until polling-active atom becomes false."
  [token poll-timeout-secs channel-name parse-mode on-message-fn polling-active]
  (let [offset (atom 0)]
    (loop []
      (when @polling-active
        (try
          (let [resp (js-await
                      (telegram-call token "getUpdates"
                                     {:offset  @offset
                                      :timeout poll-timeout-secs
                                      :allowed_updates ["message"]}))
                updates (js/Array.from (.-result resp))]
            (doseq [upd updates]
              (reset! offset (inc (.-update_id upd)))
              (let [msg-map (js-await (update->msg token upd))]
                (when (and msg-map (seq (:text msg-map)))
                  (let [chat-id  (:_chat-id msg-map)
                        resp-ctx (make-response-ctx token chat-id channel-name parse-mode)
                        clean-msg (dissoc msg-map :_chat-id)]
                    ;; Fire-and-forget — each message runs concurrently across conversations
                    (on-message-fn clean-msg resp-ctx))))))
          (catch :default e
            (when @polling-active
              (js/console.error "[telegram] Poll error:" (.-message e))
              ;; Back off briefly before retrying
              (js-await (js/Promise. (fn [res _] (js/setTimeout res 5000)))))))
        (recur)))))

;;; ─── Channel factory ──────────────────────────────────────────────────

(defn create-telegram-channel
  "Factory function for the Telegram channel adapter.
   Registered with gateway.core as channel type :telegram."
  [channel-name cfg]
  (let [token       (or (:token cfg) (.-token cfg))
        parse-mode  (or (:parse-mode cfg) "Markdown")
        poll-secs   (or (:timeout cfg) 30)
        polling-active (atom false)]

    (when-not token
      (throw (js/Error. (str "[telegram] Missing token for channel '" channel-name "'"))))

    {:name         channel-name
     :capabilities #{:text :typing :attachments}

     :start!
     (fn [on-message-fn]
       (reset! polling-active true)
       (js/console.log (str "[telegram:" channel-name "] Starting long-poll..."))
       ;; poll-loop runs asynchronously — we don't await it here
       (poll-loop token poll-secs channel-name parse-mode on-message-fn polling-active)
       (js/Promise.resolve nil))

     :stop!
     (fn []
       (reset! polling-active false)
       (js/console.log (str "[telegram:" channel-name "] Stopped"))
       (js/Promise.resolve nil))

     :setup!
     (fn []
       (js/console.log (str "\nTelegram channel: " channel-name))
       (if token
         (do
           (js/console.log "  Token: configured")
           (.. (telegram-call token "getMe" {})
               (then (fn [resp]
                       (js/console.log
                        (str "  Bot username: @" (.. resp -result -username)))
                       (js/console.log "  Connection: OK")))
               (catch (fn [e]
                        (js/console.error "  Connection: FAILED —" (.-message e))))))
         (js/console.error "  Token: NOT SET (set TELEGRAM_BOT_TOKEN)")))}))
