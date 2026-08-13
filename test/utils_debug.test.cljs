(ns utils-debug.test
  "Unit tests for agent.debug — env-gate, level rules, configurable
   sink. We inject a collecting sink in every test so nothing leaks to
   stderr/file and assertions can inspect what was emitted.

   Post-consolidation (was agent.utils.debug) — the module moved to
   agent.debug; all existing tests still apply."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            [agent.debug :as d]))

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

;;; ─── Multi-env-var gate resolution ───────────────────
;;;
;;; REGRESSION GUARD for the consolidation. Before the merge, two
;;; separate modules each honoured exactly one env var
;;; (`NYMA_DEBUG_USERMSG` → file sink, `NYMA_DEBUG` → stderr sink).
;;; After the merge, a single gate accepts all of: `NYMA_DEBUG_USERMSG`,
;;; `NYMA_DEBUG`, and `DEBUG=*|nyma|<tag>`. These tests pin each path
;;; so a future refactor can't silently drop an alias.

(defn- with-env
  "Temporarily set `k` to `v` (nil clears) inside `f`, restoring the
   prior value on exit. Bun test runs node/bun in-process so mutating
   `process.env` is safe and cheap."
  [k v f]
  (let [prev (aget js/process.env k)]
    (try
      (if v
        (aset js/process.env k v)
        (js-delete js/process.env k))
      (f)
      (finally
        (if prev
          (aset js/process.env k prev)
          (js-delete js/process.env k))))))

(describe "env-var gate resolution" (fn []
                                      (beforeEach (fn []
                                                    (capture-setup!)
                                                    ;; Start from a clean slate: no override, no env.
                                                    (d/set-enabled! nil)))
                                      (afterEach  (fn [] (capture-teardown!)))

                                      (it "NYMA_DEBUG_USERMSG alone enables the gate"
                                          ;; Legacy file-logger flag — must still work.
                                          (fn []
                                            (with-env "NYMA_DEBUG" nil
                                              (fn []
                                                (with-env "DEBUG" nil
                                                  (fn []
                                                    (with-env "NYMA_DEBUG_USERMSG" "1"
                                                      (fn []
                                                        (-> (expect (d/enabled?)) (.toBe true))))))))))

                                      (it "NYMA_DEBUG alone enables the gate"
                                          ;; Legacy stderr-logger flag — must still work.
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "DEBUG" nil
                                                  (fn []
                                                    (with-env "NYMA_DEBUG" "1"
                                                      (fn []
                                                        (-> (expect (d/enabled?)) (.toBe true))))))))))

                                      (it "DEBUG=* enables regardless of tag"
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "NYMA_DEBUG" nil
                                                  (fn []
                                                    (with-env "DEBUG" "*"
                                                      (fn []
                                                        (-> (expect (d/enabled? "whatever")) (.toBe true))))))))))

                                      (it "DEBUG=nyma enables for any nyma tag"
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "NYMA_DEBUG" nil
                                                  (fn []
                                                    (with-env "DEBUG" "nyma"
                                                      (fn []
                                                        (-> (expect (d/enabled? "commit-sweep")) (.toBe true))))))))))

                                      (it "DEBUG=commit-sweep enables only for that tag"
                                          ;; Per-tag scoping — user can trace one subsystem.
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "NYMA_DEBUG" nil
                                                  (fn []
                                                    (with-env "DEBUG" "commit-sweep"
                                                      (fn []
                                                        (-> (expect (d/enabled? "commit-sweep")) (.toBe true))
                                                                                ;; Different tag → not enabled (and "nyma"
                                                                                ;; is NOT a substring of "commit-sweep").
                                                        (-> (expect (d/enabled? "loop")) (.toBe false))))))))))

                                      (it "no env vars → gate closed"
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "NYMA_DEBUG" nil
                                                  (fn []
                                                    (with-env "DEBUG" nil
                                                      (fn []
                                                        (-> (expect (d/enabled?)) (.toBe false))))))))))

                                      (it "set-enabled! overrides env off-state"
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "NYMA_DEBUG" nil
                                                  (fn []
                                                    (with-env "DEBUG" nil
                                                      (fn []
                                                        (d/set-enabled! true)
                                                        (-> (expect (d/enabled?)) (.toBe true))))))))))

                                      (it "set-enabled! false overrides env on-state"
                                          ;; Critical: a --quiet CLI flag must be able to silence
                                          ;; even when NYMA_DEBUG=1 is already in the shell env.
                                          (fn []
                                            (with-env "NYMA_DEBUG" "1"
                                              (fn []
                                                (d/set-enabled! false)
                                                (-> (expect (d/enabled?)) (.toBe false))))))

                                      (it "empty-string env var does NOT enable"
                                          ;; Edge case: `NYMA_DEBUG=` in a shell script sets it to
                                          ;; empty string. `aget` returns "" (truthy in JS) but
                                          ;; our gate explicitly requires a non-empty string.
                                          (fn []
                                            (with-env "NYMA_DEBUG_USERMSG" nil
                                              (fn []
                                                (with-env "NYMA_DEBUG" ""
                                                  (fn []
                                                    (with-env "DEBUG" nil
                                                      (fn []
                                                        (-> (expect (d/enabled?)) (.toBe false))))))))))))

;;; ─── AI SDK warning bridge ───────────────────────────────────────────────
;;; The SDK emits via process.emitWarning, which writes to stderr with no idea
;;; the TUI owns the screen — an "unsupported reasoning metadata" warning
;;; appeared spliced through the overlay border, and pi-tui's differential
;;; renderer cannot account for bytes it did not write. The same warnings
;;; reached no log at all, because nyma never reads result.warnings anywhere.

(describe "install-sdk-warning-bridge!"
          (fn []
            (it "logs every warning in the payload"
                (fn []
                  (let [captured (atom [])]
                    (d/configure-logger! (fn [l] (swap! captured conj l)))
                    (d/install-sdk-warning-bridge!)
                    ((aget js/globalThis "AI_SDK_LOG_WARNINGS")
                     #js {:provider "anthropic.messages" :model "claude-opus-5"
                          :warnings #js [#js {:type "other" :message "unsupported reasoning metadata"}
                                         #js {:type "other" :message "second one"}]})
                    (-> (expect (count @captured)) (.toBe 2))
                    (-> (expect (first @captured)) (.toContain "ai-sdk"))
                    (-> (expect (first @captured)) (.toContain "claude-opus-5"))
                    (-> (expect (first @captured)) (.toContain "unsupported reasoning metadata"))
                    (d/reset-logger!))))

            (it "never writes to stderr, where plain warn does"
                (fn []
                  ;; DIFFERENTIAL, and it has to be. `log`'s stderr mirror only
                  ;; fires when the sink is the DEFAULT file sink, so a test
                  ;; using configure-logger! cannot tell warn from warn-quiet —
                  ;; an earlier version of this test passed with the fix
                  ;; reverted. This one restores the default sink (costing two
                  ;; lines in ~/.nyma/debug.log) and asserts the difference.
                  (let [orig-write (.-write (.-stderr js/process))
                        writes     (atom 0)]
                    (d/reset-logger!)
                    (set! (.-write (.-stderr js/process))
                          (fn [& _] (swap! writes inc) true))
                    (try
                      (d/warn "selftest" "SYNTHETIC selftest line, ignore")
                      (let [after-warn @writes]
                        (d/warn-quiet "selftest" "SYNTHETIC selftest line, ignore")
                        (let [after-quiet @writes]
                          ;; The mirror is live…
                          (-> (expect (> after-warn 0)) (.toBe true))
                          ;; …and warn-quiet skips it.
                          (-> (expect after-quiet) (.toBe after-warn))))
                      (finally
                        (set! (.-write (.-stderr js/process)) orig-write))))))

            (it "routes SDK warnings through the quiet path"
                (fn []
                  (let [orig-write (.-write (.-stderr js/process))
                        writes     (atom 0)]
                    (d/reset-logger!)
                    (set! (.-write (.-stderr js/process))
                          (fn [& _] (swap! writes inc) true))
                    (try
                      (d/install-sdk-warning-bridge!)
                      ((aget js/globalThis "AI_SDK_LOG_WARNINGS")
                       #js {:provider "anthropic.messages" :model "claude-opus-5"
                            ;; NOT the real message. This test runs against the
                            ;; DEFAULT sink, so whatever it writes lands in the
                            ;; user's ~/.nyma/debug.log — and an earlier version
                            ;; wrote a line byte-identical to the real warning we
                            ;; were hunting, so grepping the log turned up three
                            ;; decoys from this very test.
                            :warnings #js [#js {:type "other"
                                                :message "SYNTHETIC selftest warning, not a real SDK warning"}]})
                      (-> (expect @writes) (.toBe 0))
                      (finally
                        (set! (.-write (.-stderr js/process)) orig-write))))))

            (it "installs a function, which also silences the SDK's own notice"
                (fn []
                  ;; `false` would silence warnings entirely; a FUNCTION both
                  ;; captures them and suppresses the one-time "to turn off
                  ;; warning logging" line (ai/dist/index.js:624-630).
                  (d/install-sdk-warning-bridge!)
                  (-> (expect (fn? (aget js/globalThis "AI_SDK_LOG_WARNINGS"))) (.toBe true))))

            (it "survives a malformed payload rather than killing the turn"
                (fn []
                  (let [captured (atom [])]
                    (d/configure-logger! (fn [l] (swap! captured conj l)))
                    (d/install-sdk-warning-bridge!)
                    (let [f (aget js/globalThis "AI_SDK_LOG_WARNINGS")]
                      (f nil)
                      (f #js {})
                      (f #js {:warnings #js [#js {}]}))
                    ;; No throw; the empty warning still gets a line.
                    (-> (expect (>= (count @captured) 1)) (.toBe true))
                    (d/reset-logger!))))))

(describe "warn-quiet"
          (fn []
            (it "reaches the sink without the stderr mirror"
                (fn []
                  (let [captured (atom [])
                        emitted  (atom 0)
                        orig     (.-emitWarning js/process)]
                    (d/configure-logger! (fn [l] (swap! captured conj l)))
                    (set! (.-emitWarning js/process) (fn [& _] (swap! emitted inc) nil))
                    (d/warn-quiet "tag" "message")
                    (set! (.-emitWarning js/process) orig)
                    (-> (expect (count @captured)) (.toBe 1))
                    (-> (expect @emitted) (.toBe 0))
                    (d/reset-logger!))))

            (it "is not gated on the debug env switch"
                (fn []
                  ;; A warning nobody can see and nobody logged is the worst of
                  ;; both; warn-quiet is always emitted like warn/error.
                  (let [captured (atom [])]
                    (d/configure-logger! (fn [l] (swap! captured conj l)))
                    (d/warn-quiet "untagged-namespace" "still logged")
                    (-> (expect (count @captured)) (.toBe 1))
                    (d/reset-logger!))))))

;;; ─── wiring ──────────────────────────────────────────────────────────────
;;; A bridge that is correct but installed after the first model call is
;;; useless. Asserted on compiled source: driving cli/main needs a live
;;; terminal, argv and provider credentials.

(describe "the bridge is installed before anything can reach a model"
          (fn []
            (it "runs at the top of main, before mode dispatch"
                (fn []
                  (let [src      (str (fs/readFileSync "dist/agent/cli.mjs" "utf8"))
                        install  (.indexOf src "install_sdk_warning_bridge")
                        dispatch (.indexOf src "interactive.start")]
                    ;; Both markers must EXIST — a `when` guard here would let
                    ;; the ordering assertion silently skip itself, which is the
                    ;; shape of test theatre.
                    (-> (expect (> install -1)) (.toBe true))
                    (-> (expect (> dispatch -1)) (.toBe true))
                    (-> (expect (< install dispatch)) (.toBe true)))))))
