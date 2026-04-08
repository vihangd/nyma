(ns ext-thinking-renderer.test
  "Tests for the thinking-renderer extension."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [extensions.thinking-renderer.index :as ext]))

;;; ─── Mock API ─────────────────────────────────────────────────

(defn- make-mock-api []
  (let [flags          (atom {})
        event-handlers (atom {})
        widget-calls   (atom [])
        clear-calls    (atom [])
        estimate-calls (atom [])]
    #js {:registerFlag  (fn [name opts]
                          (swap! flags assoc name opts))
         :getFlag       (fn [name]
                          (when-let [f (get @flags name)]
                            (let [v (.-value f)]
                              (if (some? v) v (.-default f)))))
         :on            (fn [evt handler _priority]
                          (swap! event-handlers update evt (fnil conj []) handler))
         :off           (fn [evt handler]
                          (swap! event-handlers update evt
                            (fn [hs] (filterv #(not= % handler) (or hs [])))))
         :estimateTokens (fn [text]
                            (swap! estimate-calls conj text)
                            ;; Deterministic: 1 token per 4 chars
                            (js/Math.ceil (/ (.-length text) 4)))
         :ui            #js {:available true
                             :setWidget (fn [id lines pos]
                                          (swap! widget-calls conj {:id id :lines (vec lines) :pos pos}))
                             :clearWidget (fn [id]
                                            (swap! clear-calls conj id))}
         :_flags        flags
         :_events       event-handlers
         :_widgets      widget-calls
         :_clears       clear-calls
         :_estimates    estimate-calls}))

(defn- emit [api event data]
  (doseq [h (get @(.-_events api) event [])]
    (h data nil)))

(defn- last-widget-lines [api]
  "Get the lines from the last setWidget call."
  (when-let [last-call (last @(.-_widgets api))]
    (:lines last-call)))

;;; ─── Setup ────────────────────────────────────────────────────

(beforeEach
  (fn []
    (reset! ext/thinking-text "")
    (reset! ext/thinking-tokens 0)
    (reset! ext/collapsed? false)
    (reset! ext/last-estimate-ms 0)))

;;; ─── Activation ───────────────────────────────────────────────

(describe "thinking-renderer:activation" (fn []
  (it "returns a deactivator function"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default ext) api)]
        (-> (expect (fn? deact)) (.toBe true)))))

  (it "registers active flag with default true"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)
            f   (get @(.-_flags api) "active")]
        (-> (expect (.-default f)) (.toBe true)))))

  (it "registers visible flag with default true"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)
            f   (get @(.-_flags api) "visible")]
        (-> (expect (.-default f)) (.toBe true)))))

  (it "wires acp_thought event handler"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (-> (expect (pos? (count (get @(.-_events api) "acp_thought" [])))) (.toBe true)))))

  (it "wires reasoning_delta, turn_start, and turn_end handlers"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (-> (expect (pos? (count (get @(.-_events api) "reasoning_delta" [])))) (.toBe true))
        (-> (expect (pos? (count (get @(.-_events api) "turn_start" [])))) (.toBe true))
        (-> (expect (pos? (count (get @(.-_events api) "turn_end" [])))) (.toBe true)))))))

;;; ─── Deactivation ─────────────────────────────────────────────

(describe "thinking-renderer:deactivation" (fn []
  (it "removes all event handlers on deactivate"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default ext) api)]
        (deact)
        (-> (expect (count (get @(.-_events api) "acp_thought" []))) (.toBe 0))
        (-> (expect (count (get @(.-_events api) "reasoning_delta" []))) (.toBe 0))
        (-> (expect (count (get @(.-_events api) "turn_start" []))) (.toBe 0))
        (-> (expect (count (get @(.-_events api) "turn_end" []))) (.toBe 0)))))

  (it "clears the thinking widget on deactivate"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default ext) api)]
        ;; Trigger some thinking first
        (emit api "acp_thought" #js {:text "some thought"})
        (reset! (.-_clears api) [])
        (deact)
        (-> (expect (some #(= % "thinking") @(.-_clears api))) (.toBe true)))))))

;;; ─── ACP thinking accumulation ────────────────────────────────

(describe "thinking-renderer:acp-accumulation" (fn []
  (it "renders widget after single acp_thought chunk"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "acp_thought" #js {:text "Let me think..."})
        (-> (expect (pos? (count @(.-_widgets api)))) (.toBe true))
        ;; Widget lines should contain the thinking text
        (let [lines (last-widget-lines api)]
          (-> (expect (some #(.includes % "Let me think") lines)) (.toBe true))))))

  (it "accumulates multiple chunks into widget"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "acp_thought" #js {:text "First. "})
        (emit api "acp_thought" #js {:text "Second."})
        ;; Last widget render should contain both texts
        (let [lines     (last-widget-lines api)
              all-text  (.join (clj->js lines) "\n")]
          (-> (expect (.includes all-text "First.")) (.toBe true))
          (-> (expect (.includes all-text "Second.")) (.toBe true))))))

  (it "calls estimateTokens with accumulated text"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "acp_thought" #js {:text "Hello world"})
        (-> (expect (pos? (count @(.-_estimates api)))) (.toBe true)))))))

;;; ─── Native reasoning accumulation ────────────────────────────

(describe "thinking-renderer:reasoning-accumulation" (fn []
  (it "renders widget on reasoning_delta"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "reasoning_delta" #js {:delta "Reasoning about this..."})
        (-> (expect (pos? (count @(.-_widgets api)))) (.toBe true))
        (let [lines     (last-widget-lines api)
              all-text  (.join (clj->js lines) "\n")]
          (-> (expect (.includes all-text "Reasoning about this")) (.toBe true))))))

  (it "mixed acp_thought and reasoning_delta both accumulate"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "acp_thought" #js {:text "ACP thought. "})
        (emit api "reasoning_delta" #js {:delta "Native reasoning."})
        (let [lines     (last-widget-lines api)
              all-text  (.join (clj->js lines) "\n")]
          (-> (expect (.includes all-text "ACP thought.")) (.toBe true))
          (-> (expect (.includes all-text "Native reasoning.")) (.toBe true))))))))

;;; ─── Auto-collapse ────────────────────────────────────────────

(describe "thinking-renderer:auto-collapse" (fn []
  (it "collapses widget on turn_end"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "acp_thought" #js {:text "Thinking..."})
        (emit api "turn_end" nil)
        ;; Last widget call should be collapsed (single line with ▸)
        (let [lines (last-widget-lines api)]
          (-> (expect (= (count lines) 1)) (.toBe true))
          (-> (expect (.includes (first lines) "▸")) (.toBe true))))))

  (it "resets state on turn_start and clears widget"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        ;; Simulate a complete turn
        (emit api "acp_thought" #js {:text "Old thinking"})
        (emit api "turn_end" nil)
        ;; New turn
        (reset! (.-_clears api) [])
        (emit api "turn_start" nil)
        ;; Widget should have been cleared
        (-> (expect (some #(= % "thinking") @(.-_clears api))) (.toBe true))
        ;; New thought should start fresh (no "Old thinking" text)
        (emit api "acp_thought" #js {:text "New thought"})
        (let [lines     (last-widget-lines api)
              all-text  (.join (clj->js lines) "\n")]
          (-> (expect (.includes all-text "New thought")) (.toBe true))
          ;; Old text should NOT be present
          (-> (expect (.includes all-text "Old thinking")) (.toBe false))))))))

;;; ─── Flag control ─────────────────────────────────────────────

(describe "thinking-renderer:flag-control" (fn []
  (it "visible=false clears widget instead of rendering"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        ;; Set visible flag to false
        (let [f (get @(.-_flags api) "visible")]
          (set! (.-value f) false))
        ;; Emit thought — should clear widget not render it
        (reset! (.-_clears api) [])
        (reset! (.-_widgets api) [])
        (emit api "acp_thought" #js {:text "Invisible thought"})
        ;; Widget should have been cleared, not rendered with content
        (-> (expect (some #(= % "thinking") @(.-_clears api))) (.toBe true))
        ;; No widget setWidget calls
        (-> (expect (count @(.-_widgets api))) (.toBe 0)))))

  (it "visible=true renders widget normally"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        ;; Default visible=true
        (emit api "acp_thought" #js {:text "Visible thought"})
        (let [lines     (last-widget-lines api)
              all-text  (.join (clj->js lines) "\n")]
          (-> (expect (.includes all-text "Visible thought")) (.toBe true))))))))

;;; ─── Edge cases ───────────────────────────────────────────────

(describe "thinking-renderer:edge-cases" (fn []
  (it "ignores empty/nil thought text"
    (fn []
      (let [api     (make-mock-api)
            _       ((.-default ext) api)
            initial (count @(.-_widgets api))]
        (emit api "acp_thought" #js {:text ""})
        (emit api "acp_thought" #js {:text nil})
        (emit api "acp_thought" #js {})
        ;; No widget calls should have been made
        (-> (expect (= (count @(.-_widgets api)) initial)) (.toBe true)))))

  (it "widget header shows token count"
    (fn []
      (let [api (make-mock-api)
            _   ((.-default ext) api)]
        (emit api "acp_thought" #js {:text "Some thinking content here"})
        ;; Header (first line) should contain "tokens"
        (let [lines (last-widget-lines api)]
          (-> (expect (.includes (first lines) "tokens")) (.toBe true))))))))
