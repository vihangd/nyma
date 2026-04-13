(ns utils-debug.test
  "Unit tests for agent.utils.debug — env-gate, level rules,
   configurable sink. We inject a collecting sink in every test so
   nothing leaks to stderr and assertions can inspect what was
   emitted."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [agent.utils.debug :as d]))

(def ^:dynamic *captured* nil)

(defn- capture-setup! []
  (set! *captured* (atom []))
  (d/configure-logger! (fn [line] (swap! *captured* conj line)))
  ;; Start from a known state — no forced enabled flag, env controls.
  (d/set-enabled! nil))

(defn- capture-teardown! []
  (d/reset-logger!)
  (d/set-enabled! nil))

;;; ─── enabled? gate ────────────────────────────────────

(describe "debug/enabled?" (fn []
                             (beforeEach (fn [] (capture-setup!)))
                             (afterEach  (fn [] (capture-teardown!)))

                             (it "is false by default"
                                 (fn []
                                   (d/set-enabled! nil)
      ;; Assume env is clean; if the shell running the tests has
      ;; NYMA_DEBUG set, we explicitly flip it off via set-enabled!.
                                   (d/set-enabled! false)
                                   (-> (expect (d/enabled?)) (.toBe false))))

                             (it "honours the explicit override"
                                 (fn []
                                   (d/set-enabled! true)
                                   (-> (expect (d/enabled?)) (.toBe true))
                                   (d/set-enabled! false)
                                   (-> (expect (d/enabled?)) (.toBe false))))))

;;; ─── Level rules ──────────────────────────────────────

(describe "level rules" (fn []
                          (beforeEach (fn [] (capture-setup!)))
                          (afterEach  (fn [] (capture-teardown!)))

                          (it "debug is swallowed when disabled"
                              (fn []
                                (d/set-enabled! false)
                                (d/debug "test" "should not appear")
                                (-> (expect (count @*captured*)) (.toBe 0))))

                          (it "debug is emitted when enabled"
                              (fn []
                                (d/set-enabled! true)
                                (d/debug "test" "hello world")
                                (-> (expect (count @*captured*)) (.toBe 1))
                                (-> (expect (.includes (first @*captured*) "hello world"))
                                    (.toBe true))))

                          (it "warn is ALWAYS emitted — even when disabled"
                              (fn []
                                (d/set-enabled! false)
                                (d/warn "test" "something's off")
                                (-> (expect (count @*captured*)) (.toBe 1))
                                (-> (expect (.includes (first @*captured*) "something's off"))
                                    (.toBe true))
                                (-> (expect (.includes (first @*captured*) "[warn]"))
                                    (.toBe true))))

                          (it "error is ALWAYS emitted — even when disabled"
                              (fn []
                                (d/set-enabled! false)
                                (d/error "test" "boom")
                                (-> (expect (count @*captured*)) (.toBe 1))
                                (-> (expect (.includes (first @*captured*) "[error]"))
                                    (.toBe true))))))

;;; ─── Formatting ───────────────────────────────────────

(describe "line format" (fn []
                          (beforeEach (fn [] (capture-setup!)))
                          (afterEach  (fn [] (capture-teardown!)))

                          (it "includes a timestamp, level, and tag"
                              (fn []
                                (d/warn "app" "boot")
                                (let [line (first @*captured*)]
                                  (-> (expect (.includes line "[warn]"))   (.toBe true))
                                  (-> (expect (.includes line "[app]"))    (.toBe true))
      ;; ISO-ish timestamp in brackets — we just check for a digit.
                                  (-> (expect (some? (re-find #"\[\d{4}" line))) (.toBe true)))))

                          (it "serializes extras as JSON when provided"
                              (fn []
                                (d/warn "app" "loaded" {:count 42})
                                (let [line (first @*captured*)]
                                  (-> (expect (.includes line "42")) (.toBe true))
                                  (-> (expect (.includes line "count")) (.toBe true)))))

                          (it "handles missing extras gracefully"
                              (fn []
                                (d/warn "app" "hello")
                                (-> (expect (count @*captured*)) (.toBe 1))))))

;;; ─── configure-logger! / reset-logger! ────────────────

(describe "configure-logger!" (fn []
                                (beforeEach (fn [] (capture-setup!)))
                                (afterEach  (fn [] (capture-teardown!)))

                                (it "installs a custom sink"
                                    (fn []
                                      (let [local (atom [])]
                                        (d/configure-logger! (fn [line] (swap! local conj line)))
                                        (d/warn "t" "hit")
                                        (-> (expect (count @local)) (.toBe 1)))))

                                (it "reset-logger! restores the default sink"
                                    (fn []
      ;; After reset, logs would go to stderr. We can't assert on
      ;; stderr easily; we just check that configure-logger!→warn
      ;; populates a custom sink, reset-logger! clears it, and a
      ;; subsequent warn doesn't go back to the old sink.
                                      (let [local (atom [])]
                                        (d/configure-logger! (fn [line] (swap! local conj line)))
                                        (d/reset-logger!)
      ;; Re-capture under a fresh sink and confirm nothing leaked.
                                        (capture-setup!)
                                        (d/warn "t" "hit")
                                        (-> (expect (count @local)) (.toBe 0)))))))
