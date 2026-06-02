(ns agent.extensions.headroom.compress
  "context_assembly handler — compresses messages via the Headroom proxy.

   Runs at priority 10, below all token_suite handlers (95→70), so Headroom
   receives already-pruned messages and applies ML compression on the residual.

   The 'messages' key is last-writer-wins in context_assembly results
   (events.cljs merge semantics) — returning {:messages compressed} wins.
  "
  (:require ["headroom-ai" :refer [compress]]
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

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Register context_assembly hook at priority 10. Returns a cleanup fn."
  [api config proxy-available?]
  (let [stats      shared/suite-stats
        threshold  (or (:compression-threshold config) 0.5)
        min-tokens (or (:min-tokens-to-compress config) 8000)
        proxy-url  (or (:proxy-url config) "http://localhost:8787")
        algorithms (or (:algorithms config) ["SmartCrusher" "CodeCompressor" "Kompress"])
        warn-once  (atom false)

        handler
        (^:async fn [event _ctx]
          (when-not @proxy-available?
            (when-not @warn-once
              (reset! warn-once true)
              (js/console.warn "[headroom] proxy not reachable — compression skipped"))
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
                        result   (js-await
                                  (compress messages
                                            #js {:proxyUrl   proxy-url
                                                 :model      model-id
                                                 :algorithms (clj->js algorithms)
                                                 :disableCcr true}))]
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
                      (js/console.warn "[headroom] compress error:" (.-message e)))
                    nil))))))]

    (.on api "context_assembly" handler 10)
    (fn [] (.off api "context_assembly" handler))))
