(ns gateway.core
  "Gateway facade — wires channels, session pool, auth pipeline, and agent sessions
   into a running gateway.

   ─── Lifecycle ────────────────────────────────────────────────────────────

   1. `create-gateway`  — validates config, creates pool + pipelines, wires
                          built-in auth checks
   2. `start-gateway!`  — calls channel.start! on each channel, begins accepting traffic
   3. `stop-gateway!`   — calls channel.stop! on each channel, drains in-flight work

   ─── Channel registry ─────────────────────────────────────────────────────

   Channels are plain maps / JS objects with the IChannel shape (see gateway.protocols).
   Built-in adapters (telegram, slack, http) live in gateway.channels.*.
   Third-party adapters can be registered via `register-channel-type!`.

   ─── create-session injection ─────────────────────────────────────────────

   `create-gateway` accepts `:create-session-fn` so callers can inject
   `agent.modes.sdk/create-session` without creating a circular dependency.
   The gateway entry point (gateway.entry) provides this."
  (:require [clojure.string :as str]
            [gateway.config :as config]
            [gateway.protocols :as proto]
            [gateway.session-pool :as pool]
            [gateway.pipelines :as pipelines]
            [gateway.loop :as gloop]))

;;; ─── Channel type registry ────────────────────────────────────────────

(def ^:private channel-registry (atom {}))

(defn register-channel-type!
  "Register a channel factory for a type keyword.
   Factory signature: (fn [channel-name config-map]) → IChannel-shaped map."
  [type-kw factory-fn]
  (swap! channel-registry assoc type-kw factory-fn))

(defn build-channel
  "Instantiate a channel from a config entry using the registered factory.
   Returns nil and logs a warning if the type is unknown."
  [channel-cfg]
  ;; Squint: keywords are strings, so registry keys and config :type are both strings.
  (let [type-kw (:type channel-cfg)
        factory (get @channel-registry type-kw)]
    (if factory
      (factory (:name channel-cfg) (:config channel-cfg))
      (do
        (js/console.warn
         (str "[gateway] Unknown channel type '" type-kw
              "' — skipping. Register with register-channel-type!"))
        nil))))

;;; ─── Built-in auth checks ─────────────────────────────────────────────

(defn- make-allowlist-check
  "Return a check-fn that denies messages from users/channels not on the allow-lists.
   Empty lists mean no restriction on that axis."
  [allowed-user-ids allowed-channel-ids]
  (fn [req]
    (let [uid (or (.-userId req) (:user-id req))
          cid (or (.-channelName req) (:channel-name req))]
      (cond
        (and (seq allowed-user-ids)
             uid
             (not (contains? (set allowed-user-ids) uid)))
        {:allow? false :reason (str "User " uid " not on allow-list")}

        (and (seq allowed-channel-ids)
             cid
             (not (contains? (set allowed-channel-ids) cid)))
        {:allow? false :reason (str "Channel " cid " not on allow-list")}

        :else {:allow? true}))))

;;; ─── Gateway creation ─────────────────────────────────────────────────

(defn create-gateway
  "Create a gateway instance from a loaded config map.

   Required in opts:
     :create-session-fn  — agent.modes.sdk/create-session (injected by entry point)

   Optional in opts:
     :channel-overrides  — {channel-name → IChannel} to bypass registry (for testing)

   Returns a gateway map:
     :channels          — vec of instantiated IChannel maps
     :pool              — session pool
     :auth-pipeline     — auth pipeline
     :approval-pipeline — approval pipeline
     :agent-opts        — agent creation opts derived from config
     :streaming-policy  — streaming policy keyword or map
     :state             — atom<:created|:running|:stopped>
     :start!            — (fn []) → Promise<void>
     :stop!             — (fn []) → Promise<void>"
  [cfg opts]
  (let [{:keys [create-session-fn channel-overrides]} opts

        ;; Validate
        vr (config/validate-config cfg)
        _  (when-not (:valid? vr)
             (throw (js/Error.
                     (str "Invalid gateway config: "
                          (str/join "; " (:errors vr))))))

        ;; Channels
        channels (->> (:channels cfg)
                      (map (fn [ch-cfg]
                             (or (get (or channel-overrides {}) (:name ch-cfg))
                                 (build-channel ch-cfg))))
                      (filter some?)
                      vec)
        _  (when (empty? channels)
             (throw (js/Error. "No channels could be instantiated")))

        ;; Session pool
        the-pool (pool/create-session-pool
                  (config/session-pool-opts-from-config cfg))

        ;; Agent opts and streaming policy derived from config
        agent-opts       (config/agent-opts-from-config cfg)
        streaming-policy (config/streaming-policy-from-config cfg)

        ;; Pipelines
        auth-pipeline     (pipelines/create-auth-pipeline)
        approval-pipeline (pipelines/create-approval-pipeline)

        ;; Register allow-list auth check when config specifies one
        auth-cfg (get-in cfg [:gateway :auth])
        _ (when auth-cfg
            (let [allowed-users    (or (:allowed-user-ids auth-cfg) [])
                  allowed-channels (or (:allowed-channels auth-cfg) [])]
              (when (or (seq allowed-users) (seq allowed-channels))
                ((:add! auth-pipeline)
                 (make-allowlist-check allowed-users allowed-channels)))))

        state       (atom :created)
        maint-timer (atom nil)

        handle-opts {:create-session-fn create-session-fn
                     :agent-opts        agent-opts
                     :streaming-policy  streaming-policy}]

    {:channels          channels
     :pool              the-pool
     :auth-pipeline     auth-pipeline
     :approval-pipeline approval-pipeline
     :agent-opts        agent-opts
     :streaming-policy  streaming-policy
     :state             state

     :start!
     (fn []
       (reset! state :running)
       (js/console.log (str "[gateway] Starting " (count channels) " channel(s)..."))
       ;; Maintenance timer: prune dedup cache + evict idle sessions every 10 min
       (reset! maint-timer
               (js/setInterval
                (fn []
                  (pool/prune-dedup! the-pool)
                  (pool/evict-idle! the-pool))
                600000))
       ;; Start each channel — channels call wire-and-run for every inbound message
       (js/Promise.all
        (clj->js
         (mapv (fn [ch]
                 (let [ch-name  (proto/channel-name-str ch)
                       start-fn (or (:start! ch) (.-start! ch))]
                   (js/console.log (str "[gateway] Starting channel: " ch-name))
                     ;; Provide wire-and-run as the on-message callback so channel
                     ;; adapters don't need to import gateway.loop directly.
                   (start-fn (fn [msg response-ctx]
                               (gloop/wire-and-run
                                the-pool auth-pipeline
                                msg response-ctx handle-opts)))))
               channels))))

     :stop!
     (fn []
       (reset! state :stopped)
       (when @maint-timer
         (js/clearInterval @maint-timer)
         (reset! maint-timer nil))
       (js/console.log "[gateway] Stopping channels...")
       (js/Promise.all
        (clj->js
         (mapv (fn [ch]
                 (let [stop-fn (or (:stop! ch) (.-stop! ch))]
                   (.. (stop-fn)
                       (catch (fn [e]
                                (js/console.error
                                 (str "[gateway] Error stopping "
                                      (proto/channel-name-str ch) ":") e))))))
               channels))))}))

(defn ^:async start-gateway!
  "Load config from `config-path`, create and start a gateway. Returns the gateway map.

   `create-session-fn` must be `agent.modes.sdk/create-session`.

   Example:
     (start-gateway! \"gateway.json\" create-session)"
  [config-path create-session-fn]
  (let [cfg (js-await (config/load-config config-path))
        gw  (create-gateway cfg {:create-session-fn create-session-fn})]
    (js-await ((:start! gw)))
    gw))

(defn ^:async stop-gateway!
  "Stop a running gateway and wait for all channels to shut down."
  [gateway]
  (js-await ((:stop! gateway)))
  (reset! (:state gateway) :stopped))

(defn gateway-stats
  "Return a runtime statistics map for monitoring."
  [gateway]
  {:state    @(:state gateway)
   :channels (mapv proto/channel-name-str (:channels gateway))
   :pool     (pool/pool-stats (:pool gateway))})
