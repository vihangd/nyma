(ns integration.extension-ui.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Box Text]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            ["../agent/ui/overlay.jsx" :refer [Overlay]]))

(afterEach (fn [] (cleanup)))

;;; ─── Helpers ────────────────────────────────────────────

(defn wire-ui-hooks
  "Simulates what App.useEffect does — populates the extension API's UI hooks.
   Returns an atom that captures the overlay content set via showOverlay."
  [base-api]
  (let [overlay-state (atom nil)
        ui (.-ui base-api)]
    (set! (.-available ui) true)
    (set! (.-showOverlay ui)
      (fn [content] (reset! overlay-state content)))
    (set! (.-confirm ui)
      (fn [msg]
        (js/Promise.
          (fn [resolve]
            (reset! overlay-state
              #jsx [Box {:flexDirection "column"}
                    [Text msg]
                    [Box {:marginTop 1 :gap 2}
                     [Text "[Y]es"]
                     [Text "[N]o"]]])
            ;; Auto-resolve for testing
            (resolve true)))))
    overlay-state))

;;; ─── showOverlay ────────────────────────────────────────

(describe "extension showOverlay" (fn []
  (it "sets overlay state with string content"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            overlay-state (wire-ui-hooks base-api)
            scoped        (create-scoped-api base-api "test-ext" #{:all})]
        (.showOverlay (.-ui scoped) "hello from extension")
        (-> (expect @overlay-state) (.toBe "hello from extension")))))

  (it "string overlay content renders in Overlay without crashing"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            overlay-state (wire-ui-hooks base-api)
            scoped        (create-scoped-api base-api "test-ext" #{:all})]
        (.showOverlay (.-ui scoped) "extension panel")
        (let [{:keys [lastFrame]} (render
                                    #jsx [Overlay {:onClose (fn [])} @overlay-state])]
          (-> (expect (lastFrame)) (.toContain "extension panel"))))))

  (it "JSX overlay content renders in Overlay without crashing"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            overlay-state (wire-ui-hooks base-api)
            scoped        (create-scoped-api base-api "test-ext" #{:all})
            panel         #jsx [Box {:flexDirection "column"}
                                [Text {:bold true} "Custom Panel"]
                                [Text "Details here"]]]
        (.showOverlay (.-ui scoped) panel)
        (let [{:keys [lastFrame]} (render
                                    #jsx [Overlay {:onClose (fn [])} @overlay-state])]
          (-> (expect (lastFrame)) (.toContain "Custom Panel"))
          (-> (expect (lastFrame)) (.toContain "Details here"))))))

  (it "numeric overlay content renders without crashing"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            overlay-state (wire-ui-hooks base-api)
            scoped        (create-scoped-api base-api "test-ext" #{:all})]
        (.showOverlay (.-ui scoped) 42)
        ;; Numbers are also non-Text content; Overlay should handle gracefully
        (let [{:keys [lastFrame]} (render
                                    #jsx [Overlay {:onClose (fn [])} @overlay-state])]
          (-> (expect (lastFrame)) (.toBeDefined))))))))

;;; ─── confirm ────────────────────────────────────────────

(defn ^:async test-confirm-sets-overlay []
  (let [agent         (create-agent {:model "mock" :system-prompt "test"})
        base-api      (create-extension-api agent)
        overlay-state (wire-ui-hooks base-api)
        scoped        (create-scoped-api base-api "test-ext" #{:all})
        result        (js-await (.confirm (.-ui scoped) "Delete this?"))]
    (-> (expect result) (.toBe true))
    (-> (expect @overlay-state) (.toBeTruthy))))

(defn ^:async test-confirm-renders-in-overlay []
  (let [agent         (create-agent {:model "mock" :system-prompt "test"})
        base-api      (create-extension-api agent)
        overlay-state (wire-ui-hooks base-api)
        scoped        (create-scoped-api base-api "test-ext" #{:all})]
    (js-await (.confirm (.-ui scoped) "Are you sure?"))
    (let [{:keys [lastFrame]} (render
                                #jsx [Overlay {:onClose (fn [])} @overlay-state])]
      (-> (expect (lastFrame)) (.toContain "Are you sure?")))))

(describe "extension confirm" (fn []
  (it "confirm sets overlay with message text" test-confirm-sets-overlay)
  (it "confirm overlay content renders without crashing" test-confirm-renders-in-overlay)))

;;; ─── Capability gating ──────────────────────────────────

(describe "extension UI capability gating" (fn []
  (it "UI is unavailable without :ui capability"
    (fn []
      (let [agent    (create-agent {:model "mock" :system-prompt "test"})
            base-api (create-extension-api agent)
            _        (wire-ui-hooks base-api)
            ;; Create scoped API with only :tools capability — no :ui
            scoped   (create-scoped-api base-api "restricted-ext" #{:tools})]
        (-> (expect (.-available (.-ui scoped))) (.toBe false)))))

  (it "UI is available with :ui capability"
    (fn []
      (let [agent    (create-agent {:model "mock" :system-prompt "test"})
            base-api (create-extension-api agent)
            _        (wire-ui-hooks base-api)
            scoped   (create-scoped-api base-api "full-ext" #{:all})]
        (-> (expect (.-available (.-ui scoped))) (.toBe true)))))

  (it "UI is available with explicit :ui capability"
    (fn []
      (let [agent    (create-agent {:model "mock" :system-prompt "test"})
            base-api (create-extension-api agent)
            _        (wire-ui-hooks base-api)
            scoped   (create-scoped-api base-api "ui-ext" #{:ui})]
        (-> (expect (.-available (.-ui scoped))) (.toBe true)))))))
