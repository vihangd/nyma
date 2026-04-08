(ns token-preview.test
  "Tests for the token-preview sub-extension of token-suite."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [clojure.string :as str]
            [agent.extensions.token-suite.token-preview :as token-preview]))

;;; ─── Mock API ─────────────────────────────────────────────────

(defn- make-mock-api []
  (let [registered-commands (atom {})
        notifications       (atom [])
        event-handlers      (atom {})
        widget-state        (atom {})
        ;; each estimateTokens call is recorded
        estimate-calls      (atom [])]
    #js {:ui                #js {:available   true
                                 :notify      (fn [msg _level] (swap! notifications conj msg))
                                 :setWidget   (fn [id lines _pos]
                                                (swap! widget-state assoc id (vec lines)))
                                 :clearWidget (fn [id]
                                                (swap! widget-state dissoc id))}
         :estimateTokens    (fn [text]
                              (swap! estimate-calls conj text)
                              ;; Simple mock: 1 token per 4 chars
                              (js/Math.ceil (/ (count text) 4)))
         :registerCommand   (fn [name opts]
                              (swap! registered-commands assoc name opts))
         :unregisterCommand (fn [name]
                              (swap! registered-commands dissoc name))
         :on                (fn [event handler]
                              (swap! event-handlers update event (fnil conj []) handler))
         :off               (fn [event handler]
                              (swap! event-handlers update event
                                (fn [hs] (vec (remove #(= % handler) hs)))))
         :_commands         registered-commands
         :_notifications    notifications
         :_handlers         event-handlers
         :_widgets          widget-state
         :_estimates        estimate-calls}))

(defn- fire-event [api event data]
  (doseq [h (get @(.-_handlers api) event [])]
    (h data nil)))

;;; ─── State reset ──────────────────────────────────────────────

(beforeEach (fn [] nil))
(afterEach  (fn [] nil))

;;; ─── Group 1: Activation ──────────────────────────────────────

(describe "token-preview:activation" (fn []
  (it "subscribes to editor_change event on activate"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        (-> (expect (seq (get @(.-_handlers api) "editor_change"))) (.toBeTruthy))
        (deact))))

  (it "registers /token-preview command"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        (-> (expect (contains? @(.-_commands api) "token-preview")) (.toBe true))
        (deact))))

  (it "deactivator unregisters command and clears listener"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        (deact)
        (-> (expect (contains? @(.-_commands api) "token-preview")) (.toBe false))
        (-> (expect (empty? (get @(.-_handlers api) "editor_change"))) (.toBe true)))))))

;;; ─── Group 2: Token estimation ────────────────────────────────

(describe "token-preview:estimation" (fn []
  (it "editor_change with text triggers estimateTokens"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        ;; Fire event synchronously — debounce timer won't have fired yet,
        ;; but we can verify estimation via the setTimeout callback
        (fire-event api "editor_change" #js {:text "hello world"})
        ;; Use Promise to wait past debounce
        (js/Promise.
          (fn [resolve _]
            (js/setTimeout
              (fn []
                (-> (expect (pos? (count @(.-_estimates api)))) (.toBe true))
                (resolve nil))
              400))))))

  (it "widget content starts with ~"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        (fire-event api "editor_change" #js {:text "some text here"})
        (js/Promise.
          (fn [resolve _]
            (js/setTimeout
              (fn []
                (let [widget-lines (get @(.-_widgets api) "token-preview")]
                  (-> (expect (some #(str/starts-with? % "~") widget-lines)) (.toBeTruthy)))
                (resolve nil))
              400))))))))

;;; ─── Group 3: Empty text ──────────────────────────────────────

(describe "token-preview:empty-text" (fn []
  (it "empty text clears widget without estimating"
    (fn []
      ;; Pre-set a widget value
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        ;; First set a widget
        (fire-event api "editor_change" #js {:text "some text"})
        (js/Promise.
          (fn [resolve _]
            (js/setTimeout
              (fn []
                ;; Now clear with empty text
                (fire-event api "editor_change" #js {:text ""})
                ;; Widget should be gone immediately (no debounce for empty)
                (-> (expect (nil? (get @(.-_widgets api) "token-preview"))) (.toBe true))
                (resolve nil))
              400))))))))

;;; ─── Group 4: Toggle ─────────────────────────────────────────

(describe "token-preview:toggle" (fn []
  (it "/token-preview command toggles and notifies"
    (fn []
      (let [api     (make-mock-api)
            deact   (token-preview/activate api)
            handler (.-handler (get @(.-_commands api) "token-preview"))]
        ;; Default is enabled; toggle off
        (handler #js [] #js {:ui (.-ui api)})
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "disabled")) (.toBe true)))
        ;; Toggle back on
        (swap! (.-_notifications api) empty)
        (handler #js [] #js {:ui (.-ui api)})
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "enabled")) (.toBe true)))
        (deact))))

  (it "when disabled, editor_change does not update widget"
    (fn []
      (let [api     (make-mock-api)
            deact   (token-preview/activate api)
            handler (.-handler (get @(.-_commands api) "token-preview"))]
        ;; Disable
        (handler #js [] #js {:ui (.-ui api)})
        ;; Fire change
        (fire-event api "editor_change" #js {:text "lots of text here"})
        (js/Promise.
          (fn [resolve _]
            (js/setTimeout
              (fn []
                ;; Widget should not have been set
                (-> (expect (nil? (get @(.-_widgets api) "token-preview"))) (.toBe true))
                (resolve nil))
              400))))))))

;;; ─── Group 5: Deactivation ────────────────────────────────────

(describe "token-preview:deactivation" (fn []
  (it "deactivator clears widget"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        (fire-event api "editor_change" #js {:text "hello"})
        (js/Promise.
          (fn [resolve _]
            (js/setTimeout
              (fn []
                (deact)
                (-> (expect (nil? (get @(.-_widgets api) "token-preview"))) (.toBe true))
                (resolve nil))
              400))))))

  (it "deactivator removes event listener"
    (fn []
      (let [api   (make-mock-api)
            deact (token-preview/activate api)]
        (deact)
        (-> (expect (empty? (get @(.-_handlers api) "editor_change"))) (.toBe true)))))))
