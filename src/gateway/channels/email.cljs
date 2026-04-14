(ns gateway.channels.email
  "Email channel adapter using imap-simple (IMAP polling) + nodemailer (SMTP).

   Polls a mailbox for unseen messages at a configurable interval, dispatches
   them through the gateway, and sends agent replies back as a single email.
   Both SDKs are loaded lazily on first use.

   Config keys:
     :imap-host   string  IMAP server hostname (required)
     :imap-port   int     IMAP port (default: 993)
     :smtp-host   string  SMTP server hostname (required)
     :smtp-port   int     SMTP port (default: 587)
     :user        string  Email login / address (required)
     :password    string  Password or app-specific password (required)
     :mailbox     string  Mailbox folder to monitor (default: 'INBOX')
     :from        string  Sender address for replies (defaults to :user)
     :poll-ms     int     Poll interval in ms (default: 30000)
     :tls?        bool    Use TLS for IMAP (default: true)
     :smtp-secure bool    SMTP port uses implicit TLS (default: false → STARTTLS)

   Capabilities: #{:text}

   Streaming strategy: batch-on-end — agent reply is accumulated and sent as
   a single email when the agent signals :done.

   Install dependencies:
     bun add imap-simple nodemailer mailparser"
  (:require [gateway.protocols :as proto]))

;;; ─── Lazy SDK loading ─────────────────────────────────────────────────

(def ^:private sdk-cache (atom nil))

(defn ^:async load-email-sdk
  "Dynamically import imap-simple, nodemailer, and mailparser.
   Returns {:connect :createTransport :simpleParser} or throws with install hint."
  []
  (if @sdk-cache
    (js/Promise.resolve @sdk-cache)
    (try
      (let [imap-mod (js-await (js/import "imap-simple"))
            smtp-mod (js-await (js/import "nodemailer"))
            mail-mod (js-await (js/import "mailparser"))
            result   {:connect         (.-connect imap-mod)
                      :createTransport (.-createTransport smtp-mod)
                      :simpleParser    (.-simpleParser mail-mod)}]
        (reset! sdk-cache result)
        result)
      (catch :default e
        (throw (js/Error.
                (str "[email] Failed to load email SDKs. "
                     "Run: bun add imap-simple nodemailer mailparser\n"
                     "Original: " (.-message e))))))))

;;; ─── SMTP helper ──────────────────────────────────────────────────────

(defn ^:async smtp-send-reply
  "Send an email reply via nodemailer transporter.
   reply-opts: {:from :to :subject :text} + optional {:in-reply-to :references}."
  [transporter reply-opts]
  (try
    (let [{:keys [from to subject text in-reply-to references]} reply-opts
          opts (cond-> {:from from :to to :subject subject :text text}
                 in-reply-to (assoc :inReplyTo in-reply-to)
                 references  (assoc :references references))]
      (js-await (.sendMail transporter (clj->js opts))))
    (catch :default e
      (js/console.warn "[email] SMTP send error:" (.-message e))
      nil)))

;;; ─── Subject threading ────────────────────────────────────────────────

(defn- reply-subject
  "Return subject prefixed with 'Re: ' if not already present."
  [subject]
  (let [s (or subject "(no subject)")]
    (if (re-find #"(?i)^re:\s" s) s (str "Re: " s))))

;;; ─── Response context ─────────────────────────────────────────────────

(defn make-response-ctx
  "Build an IResponseContext that accumulates the agent reply in a buffer and
   sends it as a single email when :done fires (batch-on-end).
   reply-opts: map of :from :to :subject and optional :in-reply-to :references."
  [transporter reply-opts conversation-id channel-name]
  (let [buf    (atom "")
        sent?  (atom false)
        flush! (fn []
                 (when (and (seq @buf) (not @sent?))
                   (reset! sent? true)
                   (smtp-send-reply transporter (assoc reply-opts :text @buf))))]
    (proto/make-response-context
     {:conversation-id conversation-id
      :channel-name    channel-name
      :capabilities    #{:text}

       ;; send! — post a complete message immediately
      :send!
      (fn [content]
        (let [text (or (:text content) "")]
          (when (seq text)
            (reset! buf text)
            (flush!))))

       ;; stream! — accumulate; deferred to :done so we send one email, not many
      :stream!
      (fn [content]
        (reset! buf (or (:text content) ""))
        (js/Promise.resolve nil))

       ;; meta! :done — flush the accumulated reply
      :meta!
      (fn [op _args]
        (when (= op :done) (flush!))
        (js/Promise.resolve nil))

      :interrupt!
      (fn [_]
        (flush!)
        nil)})))

;;; ─── IMAP message parsing ─────────────────────────────────────────────

(defn ^:async parse-imap-msg
  "Parse one imap-simple message object via mailparser.
   Returns {:from :subject :text :msg-id :in-reply-to :references} or nil."
  [msg-obj simple-parser]
  (try
    (let [parts    (js/Array.from (.-parts msg-obj))
          ;; bodies: [''] fetches the full RFC 822 source as which = \"\"
          raw-part (some (fn [p] (when (= (.-which p) "") p)) parts)]
      (when raw-part
        (let [parsed      (js-await (simple-parser (.-body raw-part)))
              from-text   (some-> (.-from parsed) .-text)
              subject     (or (.-subject parsed) "(no subject)")
              text        (or (.-text parsed) "")
              msg-id      (or (.-messageId parsed) "")
              in-reply-to (or (.-inReplyTo parsed) nil)
              refs        (.-references parsed)
              references  (cond
                            (nil? refs)                nil
                            (string? refs)             refs
                            :else (.join (clj->js (js/Array.from refs)) " "))]
          (when (seq from-text)
            {:from        from-text
             :subject     subject
             :text        text
             :msg-id      msg-id
             :in-reply-to in-reply-to
             :references  references}))))
    (catch :default e
      (js/console.warn "[email] Parse error:" (.-message e))
      nil)))

;;; ─── IMAP poll cycle ──────────────────────────────────────────────────

(defn ^:async poll-once
  "Open a fresh IMAP connection, fetch all UNSEEN messages (marking them seen),
   parse and dispatch each to on-message-fn, then close the connection.
   A fresh connection per poll is simpler than keeping an idle IMAP connection
   alive across long-poll intervals."
  [connect-fn imap-config mailbox simple-parser transporter from-addr
   channel-name on-message-fn]
  (let [conn (js-await (connect-fn (clj->js imap-config)))]
    (try
      (js-await (.openBox conn mailbox))
      (let [msgs (js/Array.from
                  (js-await
                   (.search conn
                            (clj->js ["UNSEEN"])
                            #js {:bodies   (clj->js [""])
                                 :markSeen true})))]
        (doseq [msg msgs]
          (let [parsed (js-await (parse-imap-msg msg simple-parser))]
            (when (and parsed (seq (:text parsed)))
              (let [{:keys [from subject text msg-id in-reply-to references]} parsed
                    ;; Thread by the root message-id (in-reply-to → msg-id)
                    thread-root (or in-reply-to msg-id)
                    conv-id     (str "email:"
                                     (-> (or thread-root "unknown")
                                         (.replace #"[<> ]" "-")))
                    reply-opts  {:from        from-addr
                                 :to          from
                                 :subject     (reply-subject subject)
                                 :in-reply-to msg-id
                                 :references  (str (or references "") " " msg-id)}
                    msg-map     {:event-id        (str "email:" msg-id)
                                 :conversation-id conv-id
                                 :user-id         from
                                 :text            text
                                 :attachments     []
                                 :raw             msg}
                    resp-ctx    (make-response-ctx transporter reply-opts
                                                   conv-id channel-name)]
                (on-message-fn msg-map resp-ctx))))))
      (finally
        (.end conn)))))

;;; ─── Channel factory ──────────────────────────────────────────────────

(defn create-email-channel
  "Factory function for the email channel adapter.
   Registered with gateway.core as channel type :email."
  [channel-name cfg]
  (let [imap-host   (or (:imap-host cfg) (.-imapHost cfg))
        imap-port   (or (:imap-port cfg) 993)
        smtp-host   (or (:smtp-host cfg) (.-smtpHost cfg))
        smtp-port   (or (:smtp-port cfg) 587)
        user        (or (:user cfg) (.-user cfg))
        password    (or (:password cfg) (.-password cfg))
        mailbox     (or (:mailbox cfg) "INBOX")
        from-addr   (or (:from cfg) (.-from cfg) user)
        poll-ms     (or (:poll-ms cfg) 30000)
        tls?        (if (false? (:tls? cfg)) false true)
        smtp-secure (or (:smtp-secure cfg) false)
        active?     (atom false)
        timer-atom  (atom nil)]

    (when-not imap-host
      (throw (js/Error. (str "[email] Missing imap-host for '" channel-name "'"))))
    (when-not smtp-host
      (throw (js/Error. (str "[email] Missing smtp-host for '" channel-name "'"))))
    (when-not user
      (throw (js/Error. (str "[email] Missing user for '" channel-name "'"))))
    (when-not password
      (throw (js/Error. (str "[email] Missing password for '" channel-name "'"))))

    {:name         channel-name
     :capabilities #{:text}

     :start!
     (fn [on-message-fn]
       (.. (load-email-sdk)
           (then
            (fn [sdk]
              (let [connect-fn    (:connect sdk)
                    simple-parser (:simpleParser sdk)
                    transporter   ((:createTransport sdk)
                                   #js {:host   smtp-host
                                        :port   smtp-port
                                        :secure smtp-secure
                                        :auth   #js {:user user :pass password}})
                    imap-config   {:imap {:user        user
                                          :password    password
                                          :host        imap-host
                                          :port        imap-port
                                          :tls         tls?
                                          :authTimeout 10000}}
                    do-poll       (fn []
                                    (when @active?
                                      (.. (poll-once connect-fn imap-config mailbox
                                                     simple-parser transporter
                                                     from-addr channel-name on-message-fn)
                                          (catch (fn [e]
                                                   (js/console.error
                                                    "[email] Poll error:" (.-message e)))))))]
                (reset! active? true)
                (js/console.log
                 (str "[email:" channel-name "] Monitoring " mailbox
                      " on " imap-host " every " (/ poll-ms 1000) "s"))
                ;; Poll immediately, then on interval
                (do-poll)
                (reset! timer-atom (js/setInterval do-poll poll-ms))
                (js/Promise.resolve nil))))))

     :stop!
     (fn []
       (reset! active? false)
       (when @timer-atom
         (js/clearInterval @timer-atom)
         (reset! timer-atom nil))
       (js/console.log (str "[email:" channel-name "] Stopped"))
       (js/Promise.resolve nil))

     :setup!
     (fn []
       (js/console.log (str "\nEmail channel: " channel-name))
       (js/console.log (str "  IMAP:     " imap-host ":" imap-port
                            " (tls=" tls? ")"))
       (js/console.log (str "  SMTP:     " smtp-host ":" smtp-port
                            " (secure=" smtp-secure ")"))
       (js/console.log (str "  User:     " user))
       (js/console.log (str "  From:     " from-addr))
       (js/console.log (str "  Mailbox:  " mailbox))
       (js/console.log (str "  Poll:     " (/ poll-ms 1000) "s"))
       (.. (load-email-sdk)
           (then
            (fn [sdk]
              (let [transporter ((:createTransport sdk)
                                 #js {:host   smtp-host
                                      :port   smtp-port
                                      :secure smtp-secure
                                      :auth   #js {:user user :pass password}})]
                (.. (.verify transporter)
                    (then (fn [_] (js/console.log "  SMTP:     OK")))
                    (catch (fn [e]
                             (js/console.error
                              "  SMTP:     FAILED —" (.-message e))))))))
           (catch (fn [e]
                    (js/console.error "  SDK load failed:" (.-message e))))))}))
