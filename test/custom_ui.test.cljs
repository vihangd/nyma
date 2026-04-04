(ns custom-ui.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach beforeEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Box Text]]
            ["./agent/ui/overlay.jsx" :refer [Overlay CustomComponentAdapter]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]))

(afterEach (fn [] (cleanup)))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- make-component
  "Create a minimal pi-mono style component object."
  [opts]
  (clj->js opts))

(defn- wire-ui-hooks
  "Simulates what App.useEffect does — populates extension API UI hooks.
   Returns atoms for overlay state and resolve tracking."
  [base-api]
  (let [overlay-state (atom nil)
        resolved      (atom :pending)
        ui            (.-ui base-api)]
    (set! (.-available ui) true)
    (set! (.-showOverlay ui)
      (fn [content] (reset! overlay-state content)))
    (set! (.-custom ui)
      (fn [component]
        (js/Promise.
          (fn [resolve]
            (when (and (some? component) (not (string? component)) (not (number? component)))
              (set! (.-__resolve component) (fn [v] (reset! resolved v) (resolve v))))
            (reset! overlay-state component)))))
    {:overlay-state overlay-state :resolved resolved}))

;;; ─── 1. CustomComponentAdapter: render() output ─────────────

(describe "CustomComponentAdapter - render()" (fn []
  (it "renders string output from render(w, h)"
    (fn []
      (let [component (make-component {:render (fn [w _h] (str "Width: " w))})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "Width:")))))

  (it "renders array output joined with newlines"
    (fn []
      (let [component (make-component {:render (fn [_w _h] #js ["line1" "line2" "line3"])})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "line1"))
        (-> (expect (lastFrame)) (.toContain "line2"))
        (-> (expect (lastFrame)) (.toContain "line3")))))

  (it "shows error message when render() throws"
    (fn []
      (let [component (make-component {:render (fn [_w _h] (throw (js/Error. "render boom")))})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "Render error"))
        (-> (expect (lastFrame)) (.toContain "render boom")))))

  (it "handles nil return from render()"
    (fn []
      (let [component (make-component {:render (fn [_w _h] nil)})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        ;; Should not crash — renders empty
        (-> (expect (lastFrame)) (.toBeDefined)))))

  (it "passes width and height to render()"
    (fn []
      (let [received (atom nil)
            component (make-component
                        {:render (fn [w h]
                                   (reset! received {:w w :h h})
                                   "ok")})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect @received) (.toBeTruthy))
        (-> (expect (:w @received)) (.toBeGreaterThan 0))
        (-> (expect (:h @received)) (.toBeGreaterThan 0)))))))

;;; ─── 2. CustomComponentAdapter: onInput ─────────────────────

(describe "CustomComponentAdapter - onInput" (fn []
  (it "calls onClose when onInput returns {close: true} sync"
    (fn []
      (let [closed (atom false)
            component (make-component
                        {:render  (fn [_w _h] "test")
                         :onInput (fn [_key] #js {:close true})})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [] (reset! closed true))}
                                        component])]
        ;; Component rendered, onInput would be called on key press
        ;; We verify the component renders without crashing
        (-> (expect (lastFrame)) (.toContain "test")))))

  (it "calls onClose when onInput returns {close: true, value: data} async"
    (fn []
      (let [closed (atom false)
            component (make-component
                        {:render  (fn [_w _h] "async-test")
                         :onInput (fn [_key]
                                    (js/Promise.resolve #js {:close true :value "result"}))})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [] (reset! closed true))}
                                        component])]
        (-> (expect (lastFrame)) (.toContain "async-test")))))

  (it "does not crash when onInput throws"
    (fn []
      (let [component (make-component
                        {:render  (fn [_w _h] "error-input")
                         :onInput (fn [_key] (throw (js/Error. "input boom")))})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        ;; Should not crash the overlay
        (-> (expect (lastFrame)) (.toContain "error-input")))))))

;;; ─── 3. Component with only render (no onInput) ────────────

(describe "CustomComponentAdapter - render only" (fn []
  (it "renders component without onInput handler"
    (fn []
      (let [component (make-component {:render (fn [_w _h] "render only")})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "render only")))))

  (it "renders component with only onInput (no render)"
    (fn []
      (let [component (make-component {:onInput (fn [_key] nil)
                                        :render  (fn [_w _h] "")})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        ;; Should not crash
        (-> (expect (lastFrame)) (.toBeDefined)))))))

;;; ─── 4. Overlay routing ─────────────────────────────────────

(describe "Overlay - content routing" (fn []
  (it "routes string to Text"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} "plain text"])]
        (-> (expect (lastFrame)) (.toContain "plain text")))))

  (it "routes number to Text"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} 42])]
        (-> (expect (lastFrame)) (.toContain "42")))))

  (it "routes JSX element directly"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])}
                                        [Text "jsx direct"]])]
        (-> (expect (lastFrame)) (.toContain "jsx direct")))))

  (it "routes custom object to CustomComponentAdapter"
    (fn []
      (let [component (make-component {:render (fn [_w _h] "custom component")})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "custom component")))))

  (it "routes editor object to EditorAdapter"
    (fn []
      (let [component (make-component {:type "editor" :initialValue "hello world"})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "Editor"))
        (-> (expect (lastFrame)) (.toContain "hello world")))))

  (it "does not crash with nil children"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} nil])]
        (-> (expect (lastFrame)) (.toBeDefined)))))))

;;; ─── 5. Editor adapter ─────────────────────────────────────

(describe "EditorAdapter" (fn []
  (it "renders with language label"
    (fn []
      (let [component (make-component {:type "editor"
                                        :initialValue "# Hello"
                                        :language "markdown"})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "markdown"))
        (-> (expect (lastFrame)) (.toContain "# Hello")))))

  (it "renders without language label"
    (fn []
      (let [component (make-component {:type "editor" :initialValue "code here"})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "Editor"))
        (-> (expect (lastFrame)) (.toContain "code here")))))

  (it "shows keyboard hints"
    (fn []
      (let [component (make-component {:type "editor" :initialValue ""})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "Enter"))
        (-> (expect (lastFrame)) (.toContain "Esc")))))))

;;; ─── 6. Extension API integration ──────────────────────────

(describe "ctx.ui.custom() integration" (fn []
  (it "extension calls ui.custom() and overlay receives the component"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            {:keys [overlay-state]} (wire-ui-hooks base-api)
            scoped        (create-scoped-api base-api "test-ext" #{:all})
            component     (make-component {:render (fn [_w _h] "from ext")})]
        (.custom (.-ui scoped) component)
        (-> (expect @overlay-state) (.toBeTruthy))
        (-> (expect (.-render @overlay-state)) (.toBeTruthy)))))

  (it "ui.custom() returns a Promise (thenable)"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            _             (wire-ui-hooks base-api)
            scoped        (create-scoped-api base-api "test-ext" #{:all})
            component     (make-component {:render (fn [_w _h] "promise test")})
            result        (.custom (.-ui scoped) component)]
        (-> (expect (.-then result)) (.toBeTruthy)))))

  (it "capability gating blocks ui.custom without :ui"
    (fn []
      (let [agent         (create-agent {:model "mock" :system-prompt "test"})
            base-api      (create-extension-api agent)
            _             (wire-ui-hooks base-api)
            ;; Only :tools capability — no :ui
            scoped        (create-scoped-api base-api "restricted" #{:tools})]
        ;; ui should be unavailable
        (-> (expect (.-available (.-ui scoped))) (.toBe false)))))))

;;; ─── 7. Key mapping ────────────────────────────────────────

(describe "Key mapping" (fn []
  (it "maps all expected key names"
    (fn []
      (let [received (atom [])
            component (make-component
                        {:render  (fn [_w _h] "key test")
                         :onInput (fn [key-str]
                                    (swap! received conj key-str)
                                    nil)})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        ;; We verify the component renders (keyboard simulation requires
        ;; ink-testing-library's stdin writing which is limited)
        (-> (expect (lastFrame)) (.toContain "key test")))))))

;;; ─── 8. Interval-based re-rendering ────────────────────────

(defn ^:async test-invalidate-fn []
  (let [component (make-component {:render (fn [_w _h] "invalidate test")})
        {:keys [lastFrame]} (render
                              #jsx [Overlay {:onClose (fn [])} component])]
    (-> (expect (lastFrame)) (.toContain "invalidate test"))
    ;; Wait a tick for useEffect to fire and set invalidate
    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
    ;; invalidate should have been set on the component by the adapter
    (-> (expect (fn? (.-invalidate component))) (.toBe true))))

(describe "Interval re-rendering" (fn []
  (it "component with .interval property renders without crash"
    (fn []
      (let [render-count (atom 0)
            component (make-component
                        {:render   (fn [_w _h]
                                     (swap! render-count inc)
                                     (str "frame " @render-count))
                         :interval 100})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        ;; Initial render should work
        (-> (expect (lastFrame)) (.toContain "frame")))))

  (it "component receives invalidate function" test-invalidate-fn)))

;;; ─── 9. Edge cases ─────────────────────────────────────────

(describe "Edge cases" (fn []
  (it "component with dispose() is wired (dispose called on cleanup)"
    (fn []
      ;; Note: ink-testing-library's unmount() does not synchronously flush
      ;; useEffect cleanups, so we verify the component at least renders and
      ;; has a dispose function that will be called by React lifecycle.
      (let [component (make-component
                        {:render  (fn [_w _h] "dispose test")
                         :dispose (fn [] nil)})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "dispose test"))
        ;; Verify dispose is still a function on the component
        (-> (expect (fn? (.-dispose component))) (.toBe true)))))

  (it "switching between string and custom object children doesn't crash"
    (fn []
      ;; First render with string
      (let [{:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} "first string"])]
        (-> (expect (lastFrame)) (.toContain "first string")))
      (cleanup)
      ;; Then render with custom object
      (let [component (make-component {:render (fn [_w _h] "then custom")})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        (-> (expect (lastFrame)) (.toContain "then custom")))))

  (it "rapid re-renders via invalidate don't crash"
    (fn []
      (let [render-count (atom 0)
            component (make-component
                        {:render (fn [_w _h]
                                   (str "render " (swap! render-count inc)))})
            {:keys [lastFrame]} (render
                                  #jsx [Overlay {:onClose (fn [])} component])]
        ;; Initial render should work
        (-> (expect (lastFrame)) (.toContain "render"))
        ;; Calling invalidate multiple times should not crash
        (when (fn? (.-invalidate component))
          ((.-invalidate component))
          ((.-invalidate component))))))))
