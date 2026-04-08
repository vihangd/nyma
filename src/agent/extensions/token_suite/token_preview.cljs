(ns agent.extensions.token-suite.token-preview
  "Live token-count preview widget: debounces editor_change events and
   shows an estimated token count in the footer widget area."
  (:require [agent.pricing :refer [format-tokens]]))

;;; ─── Module state ───────────────────────────────────────────

(def ^:private enabled (atom true))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- update-widget [api tokens]
  (when (and (.-ui api) (.-available (.-ui api)) (.-setWidget (.-ui api)))
    (.setWidget (.-ui api) "token-preview"
      #js [(str "~" (format-tokens tokens) " tokens")]
      "below")))

(defn- clear-widget [api]
  (when (and (.-ui api) (.-available (.-ui api)) (.-clearWidget (.-ui api)))
    (.clearWidget (.-ui api) "token-preview")))

;;; ─── Activation ─────────────────────────────────────────────

(defn activate
  "Subscribe to editor_change, debounce 300 ms, update footer widget."
  [api]
  (reset! enabled true)
  (let [timer (atom nil)

        on-change
        (fn [data _ctx]
          (when @enabled
            (let [text (or (when data (.-text data)) "")]
              ;; Clear debounce timer
              (when @timer (js/clearTimeout @timer))
              (if (= (count text) 0)
                ;; Empty — clear widget immediately
                (clear-widget api)
                ;; Debounce 300 ms then estimate
                (reset! timer
                  (js/setTimeout
                    (fn []
                      (let [tokens (.estimateTokens api text)]
                        (update-widget api tokens)))
                    300))))))]

    ;; Subscribe
    (.on api "editor_change" on-change)

    ;; Register toggle command
    (.registerCommand api "token-preview"
      #js {:description "Toggle live token count preview"
           :handler (fn [_args ctx]
                      (swap! enabled not)
                      (let [msg (if @enabled
                                  "Token preview enabled"
                                  "Token preview disabled")]
                        (when (and ctx (.-ui ctx) (.-available (.-ui ctx)))
                          (.notify (.-ui ctx) msg "info")))
                      (when-not @enabled
                        (clear-widget api)))})

    ;; Return deactivator
    (fn []
      (when @timer (js/clearTimeout @timer))
      (.off api "editor_change" on-change)
      (.unregisterCommand api "token-preview")
      (clear-widget api)
      (reset! enabled true))))
