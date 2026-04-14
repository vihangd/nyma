(ns gateway.protocols
  "Protocol definitions for the gateway channel system.

   Adapters may be written in ClojureScript (using the defprotocols below)
   or in TypeScript/JavaScript (using plain objects with the documented key shapes).
   Both forms are accepted by gateway.core — the `valid-channel?` and
   `valid-response-context?` validators check only that the required keys exist.

   ─── IChannel (runtime adapter) ─────────────────────────────────────────
   Required keys:
     :name         string   unique channel identifier (e.g. \"telegram\", \"slack\")
     :capabilities set      platform features available e.g. #{:text :typing :threads}
     :start!       fn       (fn [on-message-fn]) → Promise<void>
                            starts the channel; calls on-message-fn for every inbound msg
     :stop!        fn       (fn []) → Promise<void>
                            gracefully shuts down the channel

   on-message-fn receives an inbound-message map:
     {:event-id       string?   optional dedup id (idempotency key)
      :conversation-id string   unique lane key; usually channel + chat_id
      :user-id        string?   sender identifier
      :text           string    message body
      :attachments    [{:url :mime-type :local-path}]?
      :raw            any?      original platform event for advanced adapters}

   ─── IChannelSetup (optional one-time setup) ────────────────────────────
   Required keys:
     :setup! fn  (fn []) → Promise<void>
     Called once by `nyma-gateway setup` for OAuth flows, token entry, etc.

   ─── IChannelConfig (credential validation at startup) ──────────────────
   Required keys:
     :validate-config! fn  (fn [cfg-map]) → Promise<{:valid? bool :error str?}>

   ─── IResponseContext (per-message reply handle) ─────────────────────────
   Required keys:
     :conversation-id  string
     :channel-name     string
     :capabilities     set     platform capabilities mirrored from IChannel
     :send!            fn      (fn [content-map]) → Promise<void>
                               content-map keys: :text :markdown :image-url :file-path
     :stream!          fn      (fn [chunk-str]) → Promise<void>
                               deliver a streaming text delta
     :meta!            fn      (fn [op kw, args map]) → Promise<void>
                               platform operations; ops: :typing-start :typing-stop
                                                          :tool-start :tool-end :done
     :interrupt!       fn      (fn [reason str]) → nil
                               signal that the user interrupted; callers abort the run")

;;; ─── ClojureScript protocols (for CLJS-to-CLJS adapters) ───────────────

(defprotocol IChannel
  "Runtime channel adapter protocol."
  (channel-name     [this])
  (channel-caps     [this])
  (channel-start!   [this on-message])
  (channel-stop!    [this]))

(defprotocol IChannelSetup
  "Optional one-time interactive setup."
  (channel-setup!   [this]))

(defprotocol IChannelConfig
  "Config validation before accepting live traffic."
  (validate-config! [this cfg]))

(defprotocol IResponseContext
  "Per-message reply handle."
  (ctx-send!        [this content-map])
  (ctx-stream!      [this chunk])
  (ctx-meta!        [this op args])
  (ctx-interrupt!   [this reason])
  (ctx-conversation-id [this])
  (ctx-capabilities [this]))

;;; ─── Plain-map validators (for JS/TS adapters) ──────────────────────────

(defn valid-channel?
  "True when `ch` is a map (or JS object) with the required IChannel keys."
  [ch]
  (and (some? ch)
       (or (string? (.-name ch)) (string? (:name ch)))
       (fn? (or (.-start! ch) (:start! ch)))
       (fn? (or (.-stop! ch) (:stop! ch)))))

(defn valid-response-context?
  "True when `ctx` has the required IResponseContext keys."
  [ctx]
  (and (some? ctx)
       (fn? (or (.-send! ctx) (:send! ctx)))
       (fn? (or (.-stream! ctx) (:stream! ctx)))
       (fn? (or (.-meta! ctx) (:meta! ctx)))))

(defn channel-name-str
  "Extract name string from a channel (handles both CLJS maps and JS objects)."
  [ch]
  (or (:name ch) (.-name ch) "unknown"))

(defn channel-capabilities-set
  "Extract capabilities from a channel as a Clojure set."
  [ch]
  (let [raw (or (:capabilities ch) (.-capabilities ch))]
    (cond
      (set? raw)   raw
      (some? raw)  (set (js/Array.from raw))
      :else        #{})))

(defn ctx-fn
  "Extract a function key from a response context (handles maps and JS objects)."
  [ctx k]
  (or (get ctx k)
      (aget ctx (name k))))

(defn make-response-context
  "Construct an IResponseContext-shaped map from component functions."
  [{:keys [conversation-id channel-name capabilities send! stream! meta! interrupt!]}]
  {:conversation-id conversation-id
   :channel-name    channel-name
   :capabilities    (or capabilities #{})
   :send!           send!
   :stream!         stream!
   :meta!           meta!
   :interrupt!      (or interrupt! (fn [_] nil))})
