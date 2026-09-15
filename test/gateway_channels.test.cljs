(ns gateway-channels.test
  "The four built-in gateway channels against fake transports.

   Telegram gets a fake `fetch`; Slack and email get their SDKs replaced with
   `mock.module` (neither package is installed, and the real ones are ES
   classes — which is how the Slack adapter came to call a constructor
   without `new`); the HTTP channel's Bun fetch handler is called directly.
   Nothing here opens a socket. The Telegram case runs the whole gateway —
   registry, allow-list auth, session pool, a scripted model through the real
   loop — so an inbound update is seen to become a turn and a reply."
  (:require ["bun:test" :refer [describe it expect mock]]
            [agent.loop :refer [run]]
            [gateway.core :as core]
            [gateway.session-pool :as sp]
            [gateway.channels.telegram :as tg]
            [gateway.channels.slack :as slack]
            [gateway.channels.http :as http]
            [gateway.channels.email :as email]
            [test-util.agent-harness :refer [make-test-agent]]
            [test-util.mock-model :refer [scripted-model wait-for]]))

(defn- sleep [ms] (js/Promise. (fn [res _] (js/setTimeout res ms))))

(defn- quiet
  "Run `f` with console.log silenced — the gateway narrates every start."
  [f]
  (let [orig (.-log js/console)]
    (set! (.-log js/console) (fn [& _] nil))
    (-> (js/Promise.resolve)
        (.then (fn [] (f)))
        (.finally (fn [] (set! (.-log js/console) orig))))))

;;; ─── Telegram ───────────────────────────────────────────────

(defn- install-fake-telegram!
  "Replace global fetch with a Bot API stand-in. `updates` is an atom holding
   the update objects the next getUpdates hands over (then empties); every
   call is appended to `log` as {:method :body}. Returns a restore fn."
  [updates log]
  (let [orig    (.-fetch js/globalThis)
        counter (atom 0)
        reply   (fn [result]
                  #js {:json (fn [] (js/Promise.resolve #js {:ok true :result result}))})]
    (set! (.-fetch js/globalThis)
          (fn [url opts]
            (let [method (last (.split (str url) "/"))
                  body   (when (and opts (.-body opts)) (js/JSON.parse (.-body opts)))]
              (swap! log conj {:method method :body body})
              (case method
                "getUpdates"  (let [ups @updates]
                                (reset! updates [])
                                (if (seq ups)
                                  (js/Promise.resolve (reply (into-array ups)))
                                  ;; Idle long-poll: don't spin the loop hot.
                                  (.then (sleep 15) (fn [] (reply #js [])))))
                "sendMessage" (js/Promise.resolve (reply #js {:message_id (swap! counter inc)}))
                (js/Promise.resolve (reply #js {}))))))
    (fn [] (set! (.-fetch js/globalThis) orig))))

(defn- tg-update [id chat-id user-id text]
  #js {:update_id id
       :message #js {:chat #js {:id chat-id} :from #js {:id user-id} :text text}})

(defn- calls [log method] (filter #(= method (:method %)) @log))

(defn ^:async test-telegram-through-the-gateway []
  (let [log      (atom [])
        updates  (atom [(tg-update 1 7 42 "hi") (tg-update 2 8 99 "hi too")])
        restore  (install-fake-telegram! updates log)
        {:keys [model]} (scripted-model ["hel" "lo"])
        sessions (atom [])
        ;; The real loop on a scripted model, without loading every extension.
        create-session-fn (fn [opts]
                            (swap! sessions conj opts)
                            (let [agent (make-test-agent {:model model})]
                              (js/Promise.resolve {:agent agent :send (partial run agent)})))
        cfg {:agent    {:model "scripted"}
             :channels [{:type "telegram" :name "tg" :config {:token "TOK"}}]
             :gateway  {:auth      {:allowed-user-ids ["42"]}
                        :streaming {:policy "batch-on-end"}}}]
    (core/register-channel-type! :telegram tg/create-telegram-channel)
    (let [gw (core/create-gateway cfg {:create-session-fn create-session-fn})]
      (try
        (js-await (quiet (fn [] ((:start! gw)))))
        (js-await (wait-for (fn [] (seq (calls log "sendMessage")))))
        ;; A second idle poll must have happened by now — the loop is alive.
        (js-await (wait-for (fn [] (> (count (calls log "getUpdates")) 1))))
        (finally
          (js-await (quiet (fn [] ((:stop! gw)))))
          (restore)))
      ;; Chat 7 (user 42, allowed) became a turn: one session, on a lane
      ;; keyed by chat. Chat 8 never reached the pool.
      (-> (expect (count @sessions)) (.toBe 1))
      (-> (expect (some? (sp/get-entry (:pool gw) "telegram:7"))) (.toBe true))
      (-> (expect (some? (sp/get-entry (:pool gw) "telegram:8"))) (.toBe false)))
    ;; The gateway's own tools were merged into the session's tool map.
    (-> (expect (contains? (:tools (first @sessions)) "send_message")) (.toBe true))
    ;; Typing went out before the reply, and the reply is the model's text,
    ;; posted once, in the channel's parse mode.
    (let [typing (first (calls log "sendChatAction"))
          sent   (calls log "sendMessage")]
      (-> (expect (.-chat_id (:body typing))) (.toBe "7"))
      (-> (expect (.-action (:body typing))) (.toBe "typing"))
      (-> (expect (count sent)) (.toBe 1))
      (-> (expect (.-chat_id (:body (first sent)))) (.toBe "7"))
      (-> (expect (.-text (:body (first sent)))) (.toBe "hello"))
      (-> (expect (.-parse_mode (:body (first sent)))) (.toBe "Markdown")))
    ;; Chat 8 (user 99) was dropped by the allow-list: nothing reached it and
    ;; no session was created for it.
    (-> (expect (boolean (some (fn [c] (= "8" (.-chat_id (:body c))))
                               (concat (calls log "sendMessage") (calls log "sendChatAction")))))
        (.toBe false))))

(describe "telegram channel" (fn []
                               (it "an allowed sender's update becomes a turn and a reply; a disallowed one is dropped"
                                   test-telegram-through-the-gateway)

                               (it "response context: stream! creates then edits one message, send! posts anew"
                                   (^:async fn []
                                     (let [log     (atom [])
                                           restore (install-fake-telegram! (atom []) log)
                                           ctx     (tg/make-response-ctx "TOK" "7" "tg" "HTML")]
                                       (try
                                         (-> (expect (:conversation-id ctx)) (.toBe "telegram:7"))
                                         (-> (expect (contains? (:capabilities ctx) :typing)) (.toBe true))
                                         (js-await ((:stream! ctx) {:text "a"}))
                                         (js-await ((:stream! ctx) {:text "ab"}))
                                         (js-await ((:send! ctx) {:text "standalone"}))
                                         (finally (restore)))
                                       (let [[send edit send2] @log]
                                         (-> (expect (:method send)) (.toBe "sendMessage"))
                                         (-> (expect (.-text (:body send))) (.toBe "a"))
                                         (-> (expect (:method edit)) (.toBe "editMessageText"))
                                         (-> (expect (.-message_id (:body edit))) (.toBe 1))
                                         (-> (expect (.-text (:body edit))) (.toBe "ab"))
                                         (-> (expect (.-parse_mode (:body edit))) (.toBe "HTML"))
                                         (-> (expect (:method send2)) (.toBe "sendMessage"))
                                         (-> (expect (.-text (:body send2))) (.toBe "standalone"))))))

                               (it "converts an update to an inbound message keyed by chat, with a dedup id"
                                   (^:async fn []
                                     (let [m (js-await (tg/update->msg "TOK" (tg-update 9 7 42 "hey")))]
                                       (-> (expect (:event-id m)) (.toBe "tg:9"))
                                       (-> (expect (:conversation-id m)) (.toBe "telegram:7"))
                                       (-> (expect (:user-id m)) (.toBe "42"))
                                       (-> (expect (:text m)) (.toBe "hey")))
                                     (-> (expect (js-await (tg/update->msg "TOK" #js {:update_id 10}))) (.toBeNil))))))

;;; ─── Slack ──────────────────────────────────────────────────

;; Real ES classes, as the SDK ships them: a factory-style fake would have
;; hidden the missing `new`.
(def ^:private slack-box #js {:posts #js [] :updates #js [] :smc nil :tokens #js []})

(def ^:private FakeWebClient
  (js* "class { constructor(token) { ~{}.tokens.push(token); this.n = 0; this.chat = { postMessage: async (o) => { ~{}.posts.push(o); this.n++; return { ok: true, ts: 'ts-' + this.n }; }, update: async (o) => { ~{}.updates.push(o); return { ok: true }; } }; } }"
       slack-box slack-box slack-box))

(def ^:private FakeSocketModeClient
  (js* "class { constructor(opts) { this.opts = opts; this.handlers = {}; this.disconnected = false; ~{}.smc = this; } on(ev, fn) { this.handlers[ev] = fn; } start() { return Promise.resolve(); } disconnect() { this.disconnected = true; return Promise.resolve(); } }"
       slack-box))

(.module mock "@slack/socket-mode" (fn [] #js {:SocketModeClient FakeSocketModeClient}))
(.module mock "@slack/web-api" (fn [] #js {:WebClient FakeWebClient}))

(defn- slack-event [event]
  (let [acked (atom 0)]
    {:acked acked
     :data  #js {:ack  (fn [] (swap! acked inc))
                 :body #js {:event event}}}))

(defn ^:async test-slack-socket-mode []
  (let [ch       (slack/create-slack-channel "sl" {:app-token "xapp-1" :bot-token "xoxb-1"})
        received (atom [])]
    (js-await (quiet (fn [] ((:start! ch) (fn [msg ctx] (swap! received conj {:msg msg :ctx ctx}))))))
    (let [smc     (.-smc slack-box)
          handler (aget (.-handlers smc) "slack_event")
          {:keys [acked data]} (slack-event #js {:type "message" :channel "C1" :user "U7"
                                                 :text "hi" :ts "1.1" :thread_ts "1.0"})]
      (-> (expect (.. smc -opts -appToken)) (.toBe "xapp-1"))
      (-> (expect (js/Array.from (.-tokens slack-box))) (.toContain "xoxb-1"))
      (handler data)
      ;; Slack wants the ack within 3 s — it goes out before any handling.
      (-> (expect @acked) (.toBe 1))
      ;; Bot echoes and subtype events (edits, joins) never become turns.
      (handler (:data (slack-event #js {:type "message" :channel "C1" :bot_id "B1" :text "me"})))
      (handler (:data (slack-event #js {:type "message" :channel "C1" :user "U7"
                                        :subtype "message_changed" :text "edit"})))
      (-> (expect (count @received)) (.toBe 1))
      (let [{:keys [msg ctx]} (first @received)]
        (-> (expect (:event-id msg)) (.toBe "slack:1.1"))
        (-> (expect (:conversation-id msg)) (.toBe "slack:C1:1.0"))
        (-> (expect (:user-id msg)) (.toBe "U7"))
        (-> (expect (:text msg)) (.toBe "hi"))
        (-> (expect (:conversation-id ctx)) (.toBe "slack:C1:1.0"))
        ;; typing-start posts a placeholder that stream! then edits in place.
        (js-await ((:meta! ctx) :typing-start {}))
        (js-await ((:stream! ctx) {:text "hel"}))
        (js-await ((:stream! ctx) {:text "hello"}))
        (js-await ((:send! ctx) {:text "and a new one"}))
        (let [posts   (js/Array.from (.-posts slack-box))
              updates (js/Array.from (.-updates slack-box))]
          (-> (expect (mapv #(.-text %) posts)) (.toEqual ["_Thinking..._" "and a new one"]))
          (-> (expect (.-channel (first posts))) (.toBe "C1"))
          (-> (expect (mapv #(.-text %) updates)) (.toEqual ["hel" "hello"]))
          (-> (expect (mapv #(.-ts %) updates)) (.toEqual ["ts-1" "ts-1"]))))
      (js-await (quiet (fn [] ((:stop! ch)))))
      (-> (expect (.-disconnected smc)) (.toBe true)))))

(describe "slack channel" (fn []
                            (it "constructs the SDK clients, turns a socket-mode message into a turn, and edits its reply in place"
                                test-slack-socket-mode)))

;;; ─── HTTP ───────────────────────────────────────────────────

(defn- job-store []
  (let [jobs (atom {})]
    {:put!   (fn [id r] (swap! jobs assoc id r))
     :get    (fn [id] (get @jobs id))
     :prune! (fn [] nil)}))

(defn- post [path body & [headers]]
  (js/Request. (str "http://gw" path)
               #js {:method "POST"
                    :headers (clj->js (merge {"Content-Type" "application/json"} headers))
                    :body (js/JSON.stringify (clj->js body))}))

(defn- get* [path] (js/Request. (str "http://gw" path)))

(defn- ^:async json-of [resp] (js/JSON.parse (js-await (.text resp))))

(defn- replying
  "on-message-fn that streams two deltas and finishes — the shape the
   gateway loop produces with a :debounce or :batch-on-end policy."
  [received]
  (fn [msg ctx]
    (swap! received conj msg)
    ((:stream! ctx) {:text "hel"})
    ((:stream! ctx) {:text "hello"})
    ((:meta! ctx) :done {})))

(describe "http channel" (fn []
                           (it "POST /message waits for :done and answers with the accumulated text"
                               (^:async fn []
                                 (let [received (atom [])
                                       router   (http/make-router (replying received) "web" 1000 nil (job-store))
                                       resp     (js-await (router (post "/message" {:conversation_id "room-1" :text "hi"
                                                                                    :user_id "u1" :event_id "e1"})))
                                       body     (js-await (json-of resp))]
                                   (-> (expect (.-status resp)) (.toBe 200))
                                   (-> (expect (.-text body)) (.toBe "hello"))
                                   (let [m (first @received)]
                                     (-> (expect (:conversation-id m)) (.toBe "room-1"))
                                     (-> (expect (:user-id m)) (.toBe "u1"))
                                     (-> (expect (:event-id m)) (.toBe "e1"))
                                     (-> (expect (:text m)) (.toBe "hi"))))))

                           (it "a configured secret gates every submission with a Bearer token"
                               (^:async fn []
                                 (let [received (atom [])
                                       router   (http/make-router (replying received) "web" 1000 "s3cret" (job-store))
                                       denied   (js-await (router (post "/message" {:conversation_id "r" :text "hi"})))
                                       ;; Rejected before the message reached the gateway at all.
                                       seen-after-denied (count @received)
                                       allowed  (js-await (router (post "/message" {:conversation_id "r" :text "hi"}
                                                                        {"Authorization" "Bearer s3cret"})))]
                                   (-> (expect (.-status denied)) (.toBe 401))
                                   (-> (expect seen-after-denied) (.toBe 0))
                                   (-> (expect (.-status allowed)) (.toBe 200))
                                   (-> (expect (count @received)) (.toBe 1)))))

                           (it "rejects a body without text or conversation_id, and unknown routes"
                               (^:async fn []
                                 (let [router (http/make-router (replying (atom [])) "web" 1000 nil (job-store))]
                                   (-> (expect (.-status (js-await (router (post "/message" {:conversation_id "r"}))))) (.toBe 400))
                                   (-> (expect (.-status (js-await (router (post "/message" {:text "hi"}))))) (.toBe 400))
                                   (-> (expect (.-status (js-await (router (get* "/nope"))))) (.toBe 404)))))

                           (it "GET /health names the channel"
                               (^:async fn []
                                 (let [router (http/make-router (replying (atom [])) "web" 1000 nil (job-store))
                                       body   (js-await (json-of (js-await (router (get* "/health")))))]
                                   (-> (expect (.-status body)) (.toBe "ok"))
                                   (-> (expect (.-channel body)) (.toBe "web")))))

                           (it "POST /message/async hands out a job id that is pending until :done, then carries the text"
                               (^:async fn []
                                 (let [finish   (atom nil)
                                       on-msg   (fn [_ ctx]
                                                  ((:send! ctx) {:text "first"})
                                                  ((:send! ctx) {:text "second"})
                                                  (reset! finish (fn [] ((:meta! ctx) :done {}))))
                                       router   (http/make-router on-msg "web" 1000 nil (job-store))
                                       submit   (js-await (json-of (js-await (router (post "/message/async" {:conversation_id "r" :text "go"})))))
                                       job-id   (.-job_id submit)
                                       pending  (js-await (router (get* (str "/result/" job-id))))
                                       _        (@finish)
                                       done     (js-await (router (get* (str "/result/" job-id))))
                                       done-body (js-await (json-of done))
                                       missing  (js-await (router (get* "/result/nope")))]
                                   (-> (expect (string? job-id)) (.toBe true))
                                   (-> (expect (.-status pending)) (.toBe 202))
                                   (-> (expect (.-status done)) (.toBe 200))
          ;; send! appends complete messages, one per line.
                                   (-> (expect (.-text done-body)) (.toBe "first\nsecond"))
                                   (-> (expect (.-status missing)) (.toBe 404)))))))

;;; ─── Email ──────────────────────────────────────────────────

(def ^:private mail-sent (atom []))
(def ^:private inbox (atom []))   ;; parsed messages the next poll delivers

(.module mock "imap-simple"
         (fn [] #js {:connect (fn [_cfg]
                                (js/Promise.resolve
                                 #js {:openBox (fn [_] (js/Promise.resolve nil))
                                      :search  (fn [_ _]
                                                 (let [msgs @inbox]
                                                   (reset! inbox [])
                                                   (js/Promise.resolve
                                                    (into-array (map (fn [m] #js {:parts #js [#js {:which "" :body m}]}) msgs)))))
                                      :end     (fn [] nil)}))}))
(.module mock "mailparser"
         ;; The "raw body" our fake IMAP hands over is already the parsed object.
         (fn [] #js {:simpleParser (fn [parsed] (js/Promise.resolve parsed))}))
(.module mock "nodemailer"
         (fn [] #js {:createTransport (fn [_] #js {:sendMail (fn [o] (swap! mail-sent conj o) (js/Promise.resolve #js {}))
                                                   :verify   (fn [] (js/Promise.resolve true))})}))

(defn ^:async test-email-poll-and-reply []
  (reset! mail-sent [])
  (reset! inbox [#js {:from #js {:text "alice@example.com"} :subject "Hi there"
                      :text "question?" :messageId "<abc@example.com>"}])
  (let [ch       (email/create-email-channel "mail" {:imap-host "imap.x" :smtp-host "smtp.x"
                                                     :user "bot@x" :password "pw"
                                                     :poll-ms 600000})
        received (atom [])]
    (js-await (quiet (fn [] ((:start! ch) (fn [msg ctx] (swap! received conj {:msg msg :ctx ctx}))))))
    (try
      (js-await (wait-for (fn [] (seq @received))))
      (let [{:keys [msg ctx]} (first @received)]
        (-> (expect (:event-id msg)) (.toBe "email:<abc@example.com>"))
        ;; Threaded on the root message id, with the address characters
        ;; that would break a lane key replaced.
        (-> (expect (:conversation-id msg)) (.toBe "email:-abc@example.com-"))
        (-> (expect (:user-id msg)) (.toBe "alice@example.com"))
        (-> (expect (:text msg)) (.toBe "question?"))
        ;; Deltas accumulate; one email goes out at :done, and only one even
        ;; though :done can fire more than once per run.
        (js-await ((:stream! ctx) {:text "par"}))
        (js-await ((:stream! ctx) {:text "full reply"}))
        (-> (expect (count @mail-sent)) (.toBe 0))
        (js-await ((:meta! ctx) :done {}))
        (js-await ((:meta! ctx) :done {}))
        (-> (expect (count @mail-sent)) (.toBe 1))
        (let [m (first @mail-sent)]
          (-> (expect (.-to m)) (.toBe "alice@example.com"))
          (-> (expect (.-from m)) (.toBe "bot@x"))
          (-> (expect (.-subject m)) (.toBe "Re: Hi there"))
          (-> (expect (.-text m)) (.toBe "full reply"))
          (-> (expect (.-inReplyTo m)) (.toBe "<abc@example.com>"))
          (-> (expect (.-references m)) (.toContain "<abc@example.com>"))))
      (finally
        (js-await (quiet (fn [] ((:stop! ch)))))))))

(describe "email channel" (fn []
                            (it "polls the mailbox, threads on the root message id, and replies once with Re: subject"
                                test-email-poll-and-reply)

                            (it "a reply in an existing thread lands on the same conversation"
                                (^:async fn []
                                  (reset! inbox [#js {:from #js {:text "bob@example.com"} :subject "Re: Hi there"
                                                      :text "follow-up" :messageId "<def@example.com>"
                                                      :inReplyTo "<abc@example.com>"}])
                                  (let [ch       (email/create-email-channel "mail" {:imap-host "imap.x" :smtp-host "smtp.x"
                                                                                     :user "bot@x" :password "pw"
                                                                                     :poll-ms 600000})
                                        received (atom [])]
                                    (js-await (quiet (fn [] ((:start! ch) (fn [msg _] (swap! received conj msg))))))
                                    (try
                                      (js-await (wait-for (fn [] (seq @received))))
                                      (-> (expect (:conversation-id (first @received))) (.toBe "email:-abc@example.com-"))
                                      (finally
                                        (js-await (quiet (fn [] ((:stop! ch))))))))))))
