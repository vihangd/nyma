(ns agent.extensions.claude-hook-bridge.events.compact
  "PreCompact and PostCompact hook events.

   nyma:  `before_compact` → PreCompact (matcher = manual|auto)
          we synthesize PostCompact ourselves when the compact event
          completes (nyma already emits a `compact` event after the
          summary is appended).

   Outbound:
     - PreCompact decision \"block\"  → set evt-ctx.skip = true so
       sessions/compaction.cljs aborts the compaction. Note: requires
       nyma's compact() to honor a skip flag — we add it as part of
       this work.
     - PostCompact: observational only."
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]))

(def ^:private bridge-priority 200)

(defn- pre-payload [evt-ctx]
  #js {:session_id      "session"
       :transcript_path ""
       :cwd             (js/process.cwd)
       :hook_event_name "PreCompact"
       :trigger         (or (.-trigger evt-ctx) "auto")})

(defn- post-payload [data]
  #js {:session_id      "session"
       :transcript_path ""
       :cwd             (js/process.cwd)
       :hook_event_name "PostCompact"
       :trigger         (or (.-trigger data) "auto")
       :tokens_removed  (or (.-tokensRemoved data) 0)})

(defn register!
  [{:keys [api hooks-atom cwd]}]
  (let [events (.-events api)

        pre-handler
        (fn [evt-ctx]
          (let [trigger (str (or (.-trigger evt-ctx) "auto"))
                merged  (js-await
                         (dispatch/dispatch
                          {:hooks-map     @hooks-atom
                           :event-name    "PreCompact"
                           :discriminator trigger
                           :stdin-payload (pre-payload evt-ctx)
                           :cwd           cwd
                           :api           api}))]
            (when (and merged (:decision-block? merged))
              ;; Signal sessions/compaction.cljs to bail.
              (when evt-ctx
                (try (aset evt-ctx "skip" true) (catch :default _e nil))
                (try (aset evt-ctx "skipReason" (or (:decision-block-reason merged)
                                                    "blocked by hook"))
                     (catch :default _e nil))))))

        post-handler
        (fn [data]
          (let [trigger (str (or (.-trigger data) "auto"))]
            (dispatch/dispatch
             {:hooks-map     @hooks-atom
              :event-name    "PostCompact"
              :discriminator trigger
              :stdin-payload (post-payload data)
              :cwd           cwd
              :api           api})))]

    ((:on events) "before_compact" pre-handler bridge-priority)
    ((:on events) "compact" post-handler bridge-priority)

    (fn []
      ((:off events) "before_compact" pre-handler)
      ((:off events) "compact" post-handler))))
