(ns gateway.entry
  "nyma-gateway CLI entry point.

   Usage:
     nyma-gateway [--config=<path>]          Start the gateway (default: gateway.json)
     nyma-gateway setup [--config=<path>]    Run interactive channel setup flows
     nyma-gateway validate [--config=<path>] Validate config and exit
     nyma-gateway --help                     Show this help

   Environment variables required by channels are specified in gateway.json using
   the `${VAR_NAME}` interpolation syntax (see gateway.config for schema)."
  (:require [gateway.core :as core]
            [gateway.config :as config]
            [gateway.tools :as tools]
            [gateway.channels.telegram :as telegram-ch]
            [gateway.channels.slack :as slack-ch]
            [gateway.channels.http :as http-ch]
            [gateway.channels.email :as email-ch]
            [agent.modes.sdk :refer [create-session]]))

;;; ─── Register built-in channel adapters ──────────────────────────────
;; Done at module load time so all four types are available before
;; any config is parsed. Third-party adapters call register-channel-type!
;; from their own entry points.

(core/register-channel-type! :telegram telegram-ch/create-telegram-channel)
(core/register-channel-type! :slack    slack-ch/create-slack-channel)
(core/register-channel-type! :http     http-ch/create-http-channel)
(core/register-channel-type! :email    email-ch/create-email-channel)

(defn- parse-args
  "Parse process.argv into a command + options map."
  []
  (let [args (vec (.slice (.-argv js/process) 2))
        command (if (and (seq args) (not (.startsWith (first args) "-")))
                  (first args)
                  "start")
        rest-args (if (and (seq args) (not (.startsWith (first args) "-")))
                    (vec (rest args))
                    args)
        config-path (some (fn [a]
                            (cond
                              (.startsWith a "--config=") (.slice a 9)
                              (= a "--config") nil  ;; next arg handled separately
                              :else nil))
                          rest-args)
        config-path (or config-path
                        (let [idx (.indexOf (clj->js rest-args) "--config")]
                          (when (>= idx 0)
                            (aget (clj->js rest-args) (inc idx)))))]
    {:command     command
     :config-path config-path
     :help?       (some #(or (= % "--help") (= % "-h")) args)}))

(defn- print-help []
  (js/console.log
   "nyma-gateway — channel gateway for the nyma agent\n
Usage:
  nyma-gateway [--config=<path>]           Start gateway (default: gateway.json)
  nyma-gateway setup [--config=<path>]     Run interactive channel setup flows
  nyma-gateway validate [--config=<path>]  Validate config file and exit
  nyma-gateway --help                      Show this help

Config file (gateway.json) example:
  {
    \"agent\":    { \"model\": \"claude-sonnet-4-6\", \"modes\": [\"gateway\"] },
    \"channels\": [{ \"type\": \"telegram\", \"name\": \"bot\",
                    \"config\": { \"token\": \"${TELEGRAM_BOT_TOKEN}\" } }]
  }

See docs/gateway.md for the full schema and channel adapter guide."))

(defn ^:async run-setup
  "Run the setup flow for each channel that implements IChannelSetup."
  [cfg]
  (let [channels (:channels cfg)]
    (if (empty? channels)
      (js/console.log "No channels configured.")
      (do
        (js/console.log (str "Running setup for " (count channels) " channel(s)..."))
        (doseq [ch-cfg channels]
          (let [ch (core/build-channel ch-cfg)]
            (if (nil? ch)
              (js/console.warn (str "  Skipping unknown channel type: " (:type ch-cfg)))
              (let [setup-fn (or (:setup! ch) (.-setup! ch))]
                (if (fn? setup-fn)
                  (do
                    (js/console.log (str "\n─── " (:name ch-cfg) " ───"))
                    (js-await (setup-fn)))
                  (js/console.log (str "  " (:name ch-cfg) ": no setup required")))))))
        (js/console.log "\nSetup complete.")))))

(defn ^:async run-validate
  "Validate the config file and print a summary."
  [cfg]
  (let [result (config/validate-config cfg)]
    (if (:valid? result)
      (do
        (js/console.log "✓ Config is valid")
        (js/console.log (str "  Channels: " (count (:channels cfg))))
        (js/console.log (str "  Model:    " (get-in cfg [:agent :model])))
        (js/console.log (str "  Policy:   " (get-in cfg [:gateway :session :policy] "persistent")))
        0)
      (do
        (js/console.error "✗ Config errors:")
        (doseq [e (:errors result)]
          (js/console.error (str "  • " e)))
        1))))

(defn ^:async run-gateway
  "Start the gateway and block until SIGINT/SIGTERM."
  [cfg config-path]
  ;; Register gateway tool metadata so filters work correctly
  (tools/register-tool-metadata!)

  ;; create-session is injected here — gateway.core never imports agent.modes.sdk
  (let [gw (core/create-gateway cfg {:create-session-fn create-session})]
    (js-await ((:start! gw)))

    (js/console.log "Gateway running. Press Ctrl+C to stop.")

    ;; Block until signal
    (js-await
     (js/Promise.
      (fn [_resolve _reject]
        (let [shutdown (fn [sig]
                         (js/console.log (str "\n[gateway] Received " sig ", shutting down..."))
                         (.. ((:stop! gw))
                             (then (fn []
                                     (js/console.log "[gateway] Stopped.")
                                     (js/process.exit 0)))
                             (catch (fn [e]
                                      (js/console.error "[gateway] Stop error:" e)
                                      (js/process.exit 1)))))]
          (.on js/process "SIGINT"  (fn [] (shutdown "SIGINT")))
          (.on js/process "SIGTERM" (fn [] (shutdown "SIGTERM")))))))))

(defn ^:async main []
  (let [{:keys [command config-path help?]} (parse-args)]
    (when help?
      (print-help)
      (.exit js/process 0))

    (let [cfg-path (or config-path "gateway.json")
          exit-code
          (try
            (let [cfg (js-await (config/load-config cfg-path))]
              (case command
                "setup"    (do (js-await (run-setup cfg)) 0)
                "validate" (js-await (run-validate cfg))
                "start"    (do (js-await (run-gateway cfg cfg-path)) 0)
                (do
                  (js/console.error (str "Unknown command: " command))
                  (print-help)
                  1)))
            (catch :default e
              (js/console.error (str "[gateway] Fatal error: " (.-message e)))
              (when (.-stack e)
                (js/console.error (.-stack e)))
              1))]
      (.exit js/process (or exit-code 0)))))

;; ── Entry ──────────────────────────────────────────────────────────────
(main)
