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
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]
            [agent.extensions.claude-hook-bridge.events.common :as common]))

(def ^:private bridge-priority 200)

(defn- pre-payload [api evt-ctx]
  (js/Object.assign
   (common/base api "PreCompact")
   ;; CC's PreCompact input: `custom_instructions` is what the user
   ;; passed to /compact, null when nothing (and always null for auto).
   #js {:trigger             (or (.-trigger evt-ctx) "auto")
        :custom_instructions (or (.-customInstructions evt-ctx) nil)}))

(defn- post-payload [api data]
  (js/Object.assign
   (common/base api "PostCompact")
   #js {:trigger         (or (.-trigger data) "auto")
        ;; CC's PostCompact input; nyma's `compact` event carries :summary.
        :compact_summary (str (or (.-summary data) ""))
        ;; nyma extra.
        :tokens_removed  (or (.-tokensRemoved data) 0)}))

(defn register!
  [{:keys [api hooks-atom cwd]}]
  (let [pre-handler
        (^:async fn [evt-ctx]
          (let [trigger (str (or (.-trigger evt-ctx) "auto"))
                merged  (js-await
                         (dispatch/dispatch
                          {:hooks-map     @hooks-atom
                           :event-name    "PreCompact"
                           :discriminator trigger
                           :stdin-payload (pre-payload api evt-ctx)
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
              :stdin-payload (post-payload api data)
              :cwd           cwd
              :api           api})))]

    (.on api "before_compact" pre-handler bridge-priority)
    (.on api "compact" post-handler bridge-priority)

    (fn []
      (.off api "before_compact" pre-handler)
      (.off api "compact" post-handler))))
