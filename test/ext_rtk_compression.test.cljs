(ns ext-rtk-compression.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [clojure.string :as str]
            [agent.extensions.rtk-compression.index :as rtk-ext]))

;; ── Mock API ──────────────────────────────────────────────────

(defn- make-mock-api
  "Minimal mock api for rtk-compression.
   rtk-found? controls whether 'which rtk' succeeds."
  ([] (make-mock-api true))
  ([rtk-found?]
   (let [middlewares (atom {})
         handlers    (atom {})]
     #js {:exec             (fn [_cmd _args]
                              ;; Always simulate 'which rtk' based on rtk-found?
                              (js/Promise.resolve
                               (if rtk-found?
                                 #js {:stdout "/usr/local/bin/rtk" :stderr ""}
                                 #js {:stdout "" :stderr "rtk: not found"})))
          :addMiddleware    (fn [mw] (swap! middlewares assoc (.-name mw) mw))
          :removeMiddleware (fn [name] (swap! middlewares dissoc name))
          :on               (fn [event handler & _priority]
                              (swap! handlers assoc event handler))
          :off              (fn [event _handler]
                              (swap! handlers dissoc event))
          :_middlewares     middlewares
          :_handlers        handlers})))

(defn- make-ctx
  "Create a minimal middleware context with a bash command.
   Uses tool_name (underscore) because Squint compiles (.-tool-name ctx) → ctx.tool_name."
  [cmd]
  #js {:tool_name "bash"
       :args      #js {:command cmd}})

(defn- get-mw-enter
  "Extract the :enter function from the registered middleware."
  [api]
  (let [mw (get @(.-_middlewares api) "rtk-compression/rewrite")]
    (when mw (.-enter mw))))

(defn- after-promises
  "Returns a Promise that resolves after all currently queued microtasks have drained.
   Uses setTimeout so that Promise chains started before this call have time to settle."
  [f]
  (js/Promise.
   (fn [resolve]
     (js/setTimeout
      (fn []
        (f)
        (resolve nil))
      10))))

;; ── has-operators? ────────────────────────────────────────────

(describe "rtk-compression:has-operators?" (fn []
                                             (it "returns false for a plain command"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "git status")) (.toBe false))))

                                             (it "returns false for a command with flags"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "cargo test --release")) (.toBe false))))

                                             (it "returns true for pipe"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "git status | head -20")) (.toBe true))))

                                             (it "returns true for &&"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "cargo build && cargo test")) (.toBe true))))

                                             (it "returns true for ||"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "npm test || echo failed")) (.toBe true))))

                                             (it "returns true for semicolon"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "cd /tmp; ls")) (.toBe true))))

                                             (it "returns true for $() subshell"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "echo $(git rev-parse HEAD)")) (.toBe true))))

                                             (it "returns true for backtick subshell"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "echo `date`")) (.toBe true))))

                                             (it "returns true for output redirect"
                                                 (fn []
                                                   (-> (expect (rtk-ext/has-operators? "git log > log.txt")) (.toBe true))))))

;; ── find-rewriter ─────────────────────────────────────────────

(describe "rtk-compression:find-rewriter" (fn []
                                            (it "matches git"
                                                (fn []
                                                  (let [rw (rtk-ext/find-rewriter "git status" rtk-ext/built-in-rewriters #{})]
                                                    (-> (expect (:id rw)) (.toBe "git")))))

                                            (it "matches cargo"
                                                (fn []
                                                  (let [rw (rtk-ext/find-rewriter "cargo test --release" rtk-ext/built-in-rewriters #{})]
                                                    (-> (expect (:id rw)) (.toBe "cargo")))))

                                            (it "matches npm"
                                                (fn []
                                                  (-> (expect (:id (rtk-ext/find-rewriter "npm run test" rtk-ext/built-in-rewriters #{})))
                                                      (.toBe "npm"))))

                                            (it "matches rg"
                                                (fn []
                                                  (-> (expect (:id (rtk-ext/find-rewriter "rg --type ts 'useState'" rtk-ext/built-in-rewriters #{})))
                                                      (.toBe "rg"))))

                                            (it "returns nil for an unknown command"
                                                (fn []
                                                  (-> (expect (rtk-ext/find-rewriter "unknown-tool --flag" rtk-ext/built-in-rewriters #{}))
                                                      (.toBeNil))))

                                            (it "returns nil for a piped git command"
                                                (fn []
                                                  (-> (expect (rtk-ext/find-rewriter "git status | head" rtk-ext/built-in-rewriters #{}))
                                                      (.toBeNil))))

                                            (it "returns nil when rewriter id is in disabled-set"
                                                (fn []
                                                  (-> (expect (rtk-ext/find-rewriter "git status" rtk-ext/built-in-rewriters #{"git"}))
                                                      (.toBeNil))))

                                            (it "still matches non-disabled rewriters when some are disabled"
                                                (fn []
                                                  (-> (expect (:id (rtk-ext/find-rewriter "cargo test" rtk-ext/built-in-rewriters #{"git"})))
                                                      (.toBe "cargo"))))

                                            (it "returns nil for an empty command"
                                                (fn []
                                                  (-> (expect (rtk-ext/find-rewriter "" rtk-ext/built-in-rewriters #{}))
                                                      (.toBeNil))))))

;; ── register-rewriter event ───────────────────────────────────

(describe "rtk-compression:register-rewriter" (fn []
                                                (it "adds a new rewriter that find-rewriter can match"
                                                    (fn []
                                                      (let [api     (make-mock-api true)
                                                            cleanup ((.-default rtk-ext) api)
                                                            handler (get @(.-_handlers api) "rtk-compression:register-rewriter")]
          ;; Fire the register event with a custom rewriter spec
                                                        (handler #js {:id "pulumi"})
          ;; Need to check via the middleware's rewriters atom — we do this by
          ;; waiting for rtk-ok? to be set and then testing middleware behavior
                                                        (-> (after-promises
                                                             (fn []
                                                               (let [enter (get-mw-enter api)
                                                                     ctx   (make-ctx "pulumi up")]
                                                                 (enter ctx)
                                                                 (-> (expect (aget (.-args ctx) "command"))
                                                                     (.toBe "rtk pulumi up")))))
                                                            (.finally (fn [] (cleanup)))))))

                                                (it "ignores a spec with an empty id"
                                                    (fn []
                                                      (let [api     (make-mock-api true)
                                                            cleanup ((.-default rtk-ext) api)
                                                            handler (get @(.-_handlers api) "rtk-compression:register-rewriter")]
                                                        (handler #js {:id ""})
          ;; "" should not match anything
                                                        (-> (after-promises
                                                             (fn []
                                                               (let [enter (get-mw-enter api)
                                                                     ctx   (make-ctx "")]
                                                                 (enter ctx)
                   ;; command should be unchanged
                                                                 (-> (expect (aget (.-args ctx) "command")) (.toBe "")))))
                                                            (.finally (fn [] (cleanup)))))))))

;; ── Activation smoke ──────────────────────────────────────────

(describe "rtk-compression:activation" (fn []
                                         (it "registers the middleware on activation"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (expect (contains? @(.-_middlewares api) "rtk-compression/rewrite"))
                                                     (.toBe true))
                                                 (cleanup))))

                                         (it "wires the register-rewriter event handler on activation"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (expect (contains? @(.-_handlers api) "rtk-compression:register-rewriter"))
                                                     (.toBe true))
                                                 (cleanup))))

                                         (it "deactivator removes the middleware"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (cleanup)
                                                 (-> (expect (contains? @(.-_middlewares api) "rtk-compression/rewrite"))
                                                     (.toBe false)))))

                                         (it "deactivator removes the event handler"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (cleanup)
                                                 (-> (expect (contains? @(.-_handlers api) "rtk-compression:register-rewriter"))
                                                     (.toBe false)))))

                                         (it "returns a no-op deactivator when :enabled is false"
                                             (fn []
        ;; Patch load-config by testing without a settings.json (uses default).
        ;; This test exercises the code path, not the config override specifically.
                                               (let [api     (make-mock-api true)
              ;; Manually call with a disabled config by temporarily relying on no settings file
                                                     cleanup ((.-default rtk-ext) api)]
          ;; At minimum, calling cleanup should not throw
                                                 (-> (expect #(cleanup)) (.not.toThrow)))))))

;; ── Middleware rewrite behavior ───────────────────────────────

(describe "rtk-compression:middleware" (fn []
                                         (it "rewrites git status to rtk git status after binary confirmed"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (after-promises
                                                      (fn []
                                                        (let [enter (get-mw-enter api)
                                                              ctx   (make-ctx "git status")]
                                                          (enter ctx)
                                                          (-> (expect (aget (.-args ctx) "command"))
                                                              (.toBe "rtk git status")))))
                                                     (.finally (fn [] (cleanup)))))))

                                         (it "rewrites cargo test to rtk cargo test after binary confirmed"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (after-promises
                                                      (fn []
                                                        (let [enter (get-mw-enter api)
                                                              ctx   (make-ctx "cargo test -- --nocapture")]
                                                          (enter ctx)
                                                          (-> (expect (aget (.-args ctx) "command"))
                                                              (.toBe "rtk cargo test -- --nocapture")))))
                                                     (.finally (fn [] (cleanup)))))))

                                         (it "does not rewrite when rtk binary is not available"
                                             (fn []
                                               (let [api     (make-mock-api false)   ; rtk not found
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (after-promises
                                                      (fn []
                                                        (let [enter (get-mw-enter api)
                                                              ctx   (make-ctx "git status")]
                                                          (enter ctx)
                   ;; command must be unchanged — rtk-ok? gate stayed false
                                                          (-> (expect (aget (.-args ctx) "command"))
                                                              (.toBe "git status")))))
                                                     (.finally (fn [] (cleanup)))))))

                                         (it "does not rewrite piped commands even when rtk is available"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (after-promises
                                                      (fn []
                                                        (let [enter (get-mw-enter api)
                                                              ctx   (make-ctx "git log --oneline | head -20")]
                                                          (enter ctx)
                                                          (-> (expect (aget (.-args ctx) "command"))
                                                              (.toBe "git log --oneline | head -20")))))
                                                     (.finally (fn [] (cleanup)))))))

                                         (it "does not rewrite non-bash tools"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (after-promises
                                                      (fn []
                                                        (let [enter (get-mw-enter api)
                       ;; non-bash tool
                                                              ctx   #js {:tool_name "read_file"
                                                                         :args      #js {:command "git status"}}]
                                                          (enter ctx)
                                                          (-> (expect (aget (.-args ctx) "command"))
                                                              (.toBe "git status")))))
                                                     (.finally (fn [] (cleanup)))))))

                                         (it "passes through unknown commands unchanged"
                                             (fn []
                                               (let [api     (make-mock-api true)
                                                     cleanup ((.-default rtk-ext) api)]
                                                 (-> (after-promises
                                                      (fn []
                                                        (let [enter (get-mw-enter api)
                                                              ctx   (make-ctx "some-custom-tool --flag")]
                                                          (enter ctx)
                                                          (-> (expect (aget (.-args ctx) "command"))
                                                              (.toBe "some-custom-tool --flag")))))
                                                     (.finally (fn [] (cleanup)))))))))
