(ns gateway.channels.http
  "Generic HTTP webhook channel adapter using Bun's built-in HTTP server.

   Exposes two endpoints:
     POST /message       — submit a message, wait for the full response (sync)
     POST /message/async — submit a message, return immediately with a job-id,
                           poll GET /result/:job-id for the response

   Request body (JSON):
     { \"conversation_id\": \"room-123\",   // required
       \"text\":            \"Hello\",       // required
       \"user_id\":         \"user-1\",      // optional
       \"event_id\":        \"evt-abc\" }    // optional (dedup)

   Sync response (200):
     { \"text\": \"Agent reply\" }

   Async submit response (202):
     { \"job_id\": \"<uuid>\" }

   Async result response (200 / 202 pending / 404 expired):
     { \"status\": \"done\", \"text\": \"...\" }
     { \"status\": \"pending\" }
     { \"status\": \"not_found\" }

   Config keys:
     :port       int     HTTP port (default: 3000)
     :host       string  Bind address (default: '0.0.0.0')
     :timeout-ms int     Max ms to wait for sync response (default: 120000)
     :secret     string  Optional Bearer token for auth (validates Authorization header)

   Capabilities: #{:text}"
  (:require [gateway.protocols :as proto]))

;;; ─── Async job store ──────────────────────────────────────────────────

(defn- create-job-store
  "Simple in-memory store for async job results.
   Entries expire after ttl-ms (default 5 minutes)."
  [& [ttl-ms]]
  (let [jobs   (atom {})
        ttl    (or ttl-ms 300000)]
    {:put!   (fn [job-id result]
               (swap! jobs assoc job-id
                      {:result result :expires (+ (js/Date.now) ttl)}))
     :get    (fn [job-id]
               (let [entry (get @jobs job-id)]
                 (when (and entry (> (:expires entry) (js/Date.now)))
                   (:result entry))))
     :prune! (fn []
               (let [now (js/Date.now)]
                 (swap! jobs (fn [m]
                               (into {} (filter (fn [[_ v]] (> (:expires v) now)) m))))))}))

;;; ─── Response context ─────────────────────────────────────────────────

(defn make-response-ctx
  "Build an IResponseContext that accumulates the agent's response into a promise.
   resolve-fn is called with the final text when the agent finishes."
  [conversation-id channel-name resolve-fn]
  (let [buf (atom "")]
    (proto/make-response-context
     {:conversation-id conversation-id
      :channel-name    channel-name
      :capabilities    #{:text}

       ;; send! — append a complete message to the buffer
      :send!
      (fn [content]
        (let [text (or (:text content) "")]
          (when (seq text)
            (swap! buf (fn [b] (if (seq b) (str b "\n" text) text)))))
        (js/Promise.resolve nil))

       ;; stream! — overwrite buffer with latest accumulated text (streaming policy handles debounce)
      :stream!
      (fn [content]
        (let [text (or (:text content) "")]
          (reset! buf text))
        (js/Promise.resolve nil))

       ;; meta! :done signals the agent finished — resolve the waiting HTTP response
      :meta!
      (fn [op _args]
        (when (= op :done)
          (resolve-fn @buf))
        (js/Promise.resolve nil))

      :interrupt!
      (fn [_]
        (resolve-fn @buf)
        nil)})))

;;; ─── Request parsing ──────────────────────────────────────────────────

(defn ^:async parse-request
  "Parse and validate an inbound HTTP request body."
  [req]
  (try
    (let [body (js-await (.json req))
          cid  (or (.-conversation_id body) (.-conversationId body))
          text (.-text body)]
      (cond
        (not cid)  {:error "conversation_id is required" :status 400}
        (not text) {:error "text is required" :status 400}
        :else      {:ok    true
                    :msg   {:event-id        (or (.-event_id body) nil)
                            :conversation-id (str cid)
                            :user-id         (or (.-user_id body) "http-user")
                            :text            (str text)
                            :attachments     []
                            :raw             body}}))
    (catch :default _
      {:error "Invalid JSON body" :status 400})))

(defn- auth-ok?
  "Return true if the request passes optional Bearer token auth."
  [req secret]
  (if (nil? secret)
    true
    (let [auth (or (.get (.-headers req) "authorization") "")
          expected (str "Bearer " secret)]
      (= auth expected))))

(defn- json-response
  "Build a JSON Response with the given status and body map."
  [status body-map]
  (js/Response.
   (js/JSON.stringify (clj->js body-map))
   #js {:status  status
        :headers #js {"Content-Type" "application/json"}}))

;;; ─── Request handlers ─────────────────────────────────────────────────

(defn ^:async handle-sync
  "Handle POST /message — wait for the full agent response before replying."
  [req on-message-fn channel-name timeout-ms secret]
  (if-not (auth-ok? req secret)
    (json-response 401 {:error "Unauthorized"})
    (let [parsed (js-await (parse-request req))]
      (if (:error parsed)
        (json-response (:status parsed) {:error (:error parsed)})
        ;; Deferred pattern: capture resolve/reject outside the executor so
        ;; the response-ctx's resolve-fn can call them without a self-reference.
        (let [msg       (:msg parsed)
              cid       (:conversation-id msg)
              res-atom  (atom nil)
              rej-atom  (atom nil)
              result-promise (js/Promise.
                              (fn [res rej]
                                (reset! res-atom res)
                                (reset! rej-atom rej)))
              tms      (or timeout-ms 120000)
              timer    (js/setTimeout
                        (fn [] (when @rej-atom
                                 ((@rej-atom) (js/Error. "timeout"))))
                        tms)
              resolve-fn (fn [text]
                           (js/clearTimeout timer)
                           (when @res-atom ((@res-atom) text)))
              resp-ctx (make-response-ctx cid channel-name resolve-fn)]
          (on-message-fn msg resp-ctx)
          (try
            (let [text (js-await result-promise)]
              (json-response 200 {:text (or text "")}))
            (catch :default e
              (if (.includes (.-message e) "timeout")
                (json-response 504 {:error "Agent timed out"})
                (json-response 500 {:error "Internal error"})))))))))

(defn ^:async handle-async-submit
  "Handle POST /message/async — enqueue message, return job-id immediately."
  [req on-message-fn channel-name job-store secret]
  (if-not (auth-ok? req secret)
    (json-response 401 {:error "Unauthorized"})
    (let [parsed (js-await (parse-request req))]
      (if (:error parsed)
        (json-response (:status parsed) {:error (:error parsed)})
        (let [msg     (:msg parsed)
              cid     (:conversation-id msg)
              job-id  (str (js/Date.now) "-" (.toString (js/Math.random) 36))
              resolve (fn [text] ((:put! job-store) job-id {:status "done" :text (or text "")}))]
          (let [resp-ctx (make-response-ctx cid channel-name resolve)]
            (on-message-fn msg resp-ctx))
          (json-response 202 {:job_id job-id}))))))

(defn handle-async-result
  "Handle GET /result/:job-id — return job result or pending status."
  [job-id job-store]
  (let [result ((:get job-store) job-id)]
    (cond
      (nil? result)              (json-response 404 {:status "not_found"})
      (= (:status result) "done") (json-response 200 {:status "done" :text (:text result)})
      :else                      (json-response 202 {:status "pending"}))))

;;; ─── Router ───────────────────────────────────────────────────────────

(defn make-router
  "Return a Bun fetch handler that routes HTTP requests to the right handler."
  [on-message-fn channel-name timeout-ms secret job-store]
  (fn [req]
    (let [url    (js/URL. (.-url req))
          path   (.-pathname url)
          method (.-method req)]
      (cond
        ;; Health check
        (and (= method "GET") (= path "/health"))
        (json-response 200 {:status "ok" :channel channel-name})

        ;; Sync message submission
        (and (= method "POST") (= path "/message"))
        (handle-sync req on-message-fn channel-name timeout-ms secret)

        ;; Async message submission
        (and (= method "POST") (= path "/message/async"))
        (handle-async-submit req on-message-fn channel-name job-store secret)

        ;; Async result polling
        (and (= method "GET") (.startsWith path "/result/"))
        (let [job-id (.slice path 8)]   ;; strip "/result/"
          (handle-async-result job-id job-store))

        ;; 404 for everything else
        :else
        (json-response 404 {:error "Not found"})))))

;;; ─── Channel factory ──────────────────────────────────────────────────

(defn create-http-channel
  "Factory function for the HTTP webhook channel adapter.
   Registered with gateway.core as channel type :http."
  [channel-name cfg]
  (let [port       (or (:port cfg) 3000)
        host       (or (:host cfg) "0.0.0.0")
        timeout-ms (or (:timeout-ms cfg) 120000)
        secret     (or (:secret cfg) nil)
        server-atom (atom nil)
        job-store  (create-job-store)
        ;; Prune expired jobs every 10 minutes
        prune-timer (atom nil)]

    {:name         channel-name
     :capabilities #{:text}

     :start!
     (fn [on-message-fn]
       (let [handler (make-router on-message-fn channel-name timeout-ms secret job-store)
             server  (js/Bun.serve
                      #js {:port    port
                           :host    host
                           :fetch   handler})]
         (reset! server-atom server)
         (reset! prune-timer
                 (js/setInterval (:prune! job-store) 600000))
         (js/console.log
          (str "[http:" channel-name "] Listening on http://" host ":" port))
         (js/Promise.resolve nil)))

     :stop!
     (fn []
       (when @prune-timer
         (js/clearInterval @prune-timer)
         (reset! prune-timer nil))
       (when @server-atom
         (js/console.log (str "[http:" channel-name "] Stopping server"))
         (.stop @server-atom))
       (js/Promise.resolve nil))

     :setup!
     (fn []
       (js/console.log (str "\nHTTP channel: " channel-name))
       (js/console.log (str "  Port:    " port))
       (js/console.log (str "  Host:    " host))
       (js/console.log (str "  Auth:    " (if secret "Bearer token configured" "none (open)")))
       (js/console.log "  Endpoints:")
       (js/console.log "    POST /message         sync request-response")
       (js/console.log "    POST /message/async   async submit → job_id")
       (js/console.log "    GET  /result/:job_id  poll for async result")
       (js/console.log "    GET  /health          healthcheck")
       (js/Promise.resolve nil))}))
