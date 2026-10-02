(ns agent.extensions.headroom.compress
  "context_assembly handler — compresses messages via the Headroom proxy.

   Runs at priority 10, below all token_suite handlers (95→70), so Headroom
   receives already-pruned messages and applies ML compression on the residual.

   The 'messages' key is last-writer-wins in context_assembly results
   (events.cljs merge semantics) — returning {:messages compressed} wins.
  "
  (:require [agent.debug :as d]
            ["headroom-ai" :refer [compress]]
            [agent.extensions.headroom.shared :as shared]
            [clojure.string :as str]))

;; ── Helpers ──────────────────────────────────────────────────────

(defn- current-model-id [event]
  (try
    (str (or (some-> event .-tokenBudget .-model) "unknown"))
    (catch :default _ "unknown")))

(defn- tokens-used [event]
  (try (or (some-> event .-tokenBudget .-tokensUsed) 0)
       (catch :default _ 0)))

(defn- context-window [event]
  (try (or (some-> event .-tokenBudget .-contextWindow) 100000)
       (catch :default _ 100000)))

(defn- input-budget [event]
  (try (let [b (some-> event .-tokenBudget .-inputBudget)]
         (when (and (number? b) (pos? b)) b))
       (catch :default _ nil)))

(def valid-modes
  "The pipeline modes `POST /v1/compress` accepts. Unset means the proxy's
   default marker-free pipeline; the proxy answers 400 to anything else
   (headroom-ai `CompressMode`), so an unknown value is dropped here with a
   warning rather than turned into a failed request every assembly."
  #{"ccr" "lossy_inline" "lossless_then_lossy"})

(defn resolve-mode
  "config → the `config.mode` to send, or nil for the proxy default. Pure.

   `:algorithms` and `:disable-ccr` are the two keys this replaces. They named
   an option surface headroom-ai never had — `proxyUrl`, `algorithms` and
   `disableCcr` appear nowhere in the package, so both settings were inert and
   the algorithm list was decided entirely by the proxy. CCR is now a pipeline
   mode instead of a flag, and off by default, so the one legacy spelling worth
   honouring is an explicit `disable-ccr: false`, which asked for CCR on."
  [config]
  (let [m (some-> (:mode config) str)]
    (cond
      (contains? valid-modes m) m
      (seq m)                   ::invalid
      (false? (:disable-ccr config)) "ccr"
      :else                     nil)))

(defn compress-opts
  "The options object handed to `headroom-ai`'s `compress`. Pure.

   Exported and pure because the bug this replaced was invisible from the
   outside: the old call passed `proxyUrl`, `algorithms` and `disableCcr`, none
   of which the package has ever read, and nothing asserted the option NAMES.
   A test over the returned object is the only thing that catches that class."
  [{:keys [base-url model token-budget mode]}]
  (let [o #js {:baseUrl base-url :model model}]
    ;; Absent, not zero/null: the proxy reads a missing key as its default.
    (when (and (number? token-budget) (pos? token-budget))
      (aset o "tokenBudget" token-budget))
    (when (and mode (string? mode))
      (aset o "config" #js {:mode mode}))
    o))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Register context_assembly hook at priority 10. Returns a cleanup fn."
  [api config proxy-available?]
  (let [stats      shared/suite-stats
        threshold  (or (:compression-threshold config) 0.5)
        min-tokens (or (:min-tokens-to-compress config) 8000)
        proxy-url  (or (:proxy-url config) "http://localhost:8787")
        ;; `baseUrl`, not `proxyUrl` — see the call site. The nyma-facing
        ;; setting keeps the name `proxy-url`, which is accurate.
        mode       (let [m (resolve-mode config)]
                     (when (= m ::invalid)
                       (d/warn "headroom"
                               (str "unknown headroom.mode " (pr-str (:mode config))
                                    " — using the proxy default. Valid: "
                                    (str/join ", " (sort valid-modes)))))
                     (when (string? m) m))
        warn-once  (atom false)

        handler
        (^:async fn [event _ctx]
          (when-not @proxy-available?
            (when-not @warn-once
              (reset! warn-once true)
              (d/warn "[headroom] proxy not reachable — compression skipped"))
            nil)

          (when @proxy-available?
            (let [used   (tokens-used event)
                  window (context-window event)
                  ratio  (if (pos? window) (/ used window) 0)]
              (if (or (< used min-tokens) (< ratio threshold))
                (do (swap! stats update :skipped inc)
                    nil)
                (try
                  (let [messages (.-messages event)
                        model-id (current-model-id event)
                        ;; `baseUrl` is the option headroom-ai reads. The
                        ;; previous `proxyUrl` was ignored, and the package's
                        ;; own default happens to be this same localhost:8787
                        ;; — so a DEFAULT setup worked and a configured proxy
                        ;; was silently bypassed, while `probe-proxy` went on
                        ;; reporting the configured one healthy.
                        opts     (compress-opts
                                  {:base-url     proxy-url
                                   :model        model-id
                                   ;; "compress to fit within this limit",
                                   ;; which is what this handler is for.
                                   :token-budget (input-budget event)
                                   :mode         mode})
                        result   (js-await (compress messages opts))]
                    (when result
                      (let [saved (or (.-tokensSaved result) 0)
                            r     (or (.-compressionRatio result) 1.0)]
                        (swap! stats update :calls inc)
                        (swap! stats update :tokens-saved + saved)
                        (swap! stats assoc :compression-ratio r))
                      #js {:messages (.-messages result)}))
                  (catch :default e
                    (swap! stats update :errors inc)
                    (when-not @warn-once
                      (reset! warn-once true)
                      (d/warn "[headroom] compress error:" (.-message e)))
                    nil))))))]

    (.on api "context_assembly" handler 10)
    (fn [] (.off api "context_assembly" handler))))
