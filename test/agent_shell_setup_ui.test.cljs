(ns agent-shell-setup-ui.test
  "Regression tests for agent_shell/shared/setup-ui!.

   Bug history: cli.cljs emits :session_ready BEFORE interactive/start
   mounts the ink app, so api.ui is still undefined at the moment the
   agent_shell session_ready handler runs. The setup-ui! guard
   `(when (and (.-ui api) (.-available (.-ui api))))` silently bailed,
   the rich header factory was never installed, and the user saw the
   default 'nyma + model' header even when connected to an ACP agent.
   Status line ACP segments worked because they go through
   registerStatusSegment on the base API (available from agent
   creation time), not through api.ui.setHeader.

   The fix: setup-ui! is now also invoked from the agent_switcher and
   handoff connect paths — both run AFTER the ink app is mounted, so
   api.ui is guaranteed live. setup-ui! remains idempotent: the
   footer-set? gate short-circuits once setHeader has been successfully
   installed."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.agent-shell.shared :as shared]))

(defn- reset-gates! []
  (reset! shared/api-ref nil)
  (reset! shared/footer-set? false))

(describe "setup-ui!: silent no-op when api.ui is not yet available"
          (fn []
            (beforeEach (fn [] (reset-gates!)))

            (it "does not throw when api-ref is nil (pre-activation)"
                (fn []
        ;; This is the state at extension-module load time, before
        ;; activation has stored the api reference.
                  (reset! shared/api-ref nil)
                  (-> (expect (fn [] (shared/setup-ui!))) (.not.toThrow))
                  (-> (expect @shared/footer-set?) (.toBe false))))

            (it "does not throw when api is present but api.ui is undefined"
                (fn []
        ;; This is the exact state when cli.cljs fires :session_ready
        ;; — api is stored, but the ink app hasn't mounted yet so
        ;; (.-ui api) is still undefined. setup-ui! must bail silently
        ;; so the retry from agent_switcher has a chance to succeed.
                  (reset! shared/api-ref #js {})
                  (-> (expect (fn [] (shared/setup-ui!))) (.not.toThrow))
                  (-> (expect @shared/footer-set?) (.toBe false))))

            (it "does not throw when api.ui exists but :available is false"
                (fn []
                  (reset! shared/api-ref
                          #js {:ui #js {:available false}})
                  (-> (expect (fn [] (shared/setup-ui!))) (.not.toThrow))
                  (-> (expect @shared/footer-set?) (.toBe false))))))

(describe "setup-ui!: installs header when api.ui is live"
          (fn []
            (beforeEach (fn [] (reset-gates!)))

            (it "calls setHeader with a factory once api.ui is ready"
                (fn []
                  (let [received (atom nil)
                        set-header-calls (atom 0)
                        fake-ui #js {:available true}]
                    (set! (.-setHeader fake-ui)
                          (fn [factory]
                            (swap! set-header-calls inc)
                            (reset! received factory)))
                    (reset! shared/api-ref #js {:ui fake-ui})
                    (shared/setup-ui!)
          ;; setHeader was called exactly once with the header factory fn.
                    (-> (expect @set-header-calls) (.toBe 1))
                    (-> (expect (fn? @received)) (.toBe true))
          ;; Gate flips so subsequent calls are no-ops.
                    (-> (expect @shared/footer-set?) (.toBe true)))))

            (it "is idempotent: second call does not re-install setHeader"
                (fn []
                  (let [set-header-calls (atom 0)
                        fake-ui #js {:available true}]
                    (set! (.-setHeader fake-ui)
                          (fn [_factory] (swap! set-header-calls inc)))
                    (reset! shared/api-ref #js {:ui fake-ui})
                    (shared/setup-ui!)
                    (shared/setup-ui!)
                    (shared/setup-ui!)
          ;; Still only one install despite three calls — the
          ;; footer-set? gate guarantees this.
                    (-> (expect @set-header-calls) (.toBe 1)))))

            (it "retries after a prior failed call (lazy-UI timing)"
                (fn []
        ;; Simulates the exact timing bug: first call happens at
        ;; session_ready with no ui, second call happens later (e.g.
        ;; from agent_switcher after the ink app has mounted). The
        ;; second call MUST succeed — the footer-set? gate must not
        ;; have been flipped by the first failed attempt.
                  (let [set-header-calls (atom 0)]
          ;; 1. session_ready-time call: api has no ui yet
                    (reset! shared/api-ref #js {})
                    (shared/setup-ui!)
                    (-> (expect @set-header-calls) (.toBe 0))
                    (-> (expect @shared/footer-set?) (.toBe false))
          ;; 2. Ink mounts; app.cljs sets api.ui
                    (let [fake-ui #js {:available true}]
                      (set! (.-setHeader fake-ui)
                            (fn [_factory] (swap! set-header-calls inc)))
                      (set! (.-ui @shared/api-ref) fake-ui))
          ;; 3. agent_switcher-time call: api.ui is now live
                    (shared/setup-ui!)
                    (-> (expect @set-header-calls) (.toBe 1))
                    (-> (expect @shared/footer-set?) (.toBe true)))))))
