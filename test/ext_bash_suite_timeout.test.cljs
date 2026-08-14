(ns ext-bash-suite-timeout.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.extensions.bash-suite.timeout-classifier :as timeout-classifier]))

(def default-cfg shared/default-config)
(def disabled-cfg (assoc-in shared/default-config [:timeout-classifier :enabled] false))

;; ── classify-timeout pure function ──────────────────────────────

(describe "timeout-classifier:classify-timeout"
          (fn []
            (it "bumps npm install to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "npm install" default-cfg))
                      (.toBe 300000))))

            (it "bumps pnpm run build to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "pnpm run build" default-cfg))
                      (.toBe 300000))))

            (it "bumps bun test to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "bun test" default-cfg))
                      (.toBe 300000))))

            (it "bumps pytest to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "pytest tests/" default-cfg))
                      (.toBe 300000))))

            (it "bumps jest to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "jest --coverage" default-cfg))
                      (.toBe 300000))))

            (it "bumps cargo build to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "cargo build --release" default-cfg))
                      (.toBe 300000))))

            (it "bumps go test to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "go test ./..." default-cfg))
                      (.toBe 300000))))

            (it "bumps make to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "make all" default-cfg))
                      (.toBe 300000))))

            (it "bumps docker build to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "docker build -t myapp ." default-cfg))
                      (.toBe 300000))))

            (it "bumps tsc to long timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "tsc --noEmit" default-cfg))
                      (.toBe 300000))))

            (it "keeps ls at default timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "ls -la" default-cfg))
                      (.toBe 30000))))

            (it "keeps echo at default timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "echo hello" default-cfg))
                      (.toBe 30000))))

            (it "keeps git status at default timeout"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "git status" default-cfg))
                      (.toBe 30000))))

            (it "returns default when disabled"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "npm install" disabled-cfg))
                      (.toBe 30000))))

            (it "returns default for empty command"
                (fn []
                  (-> (expect (timeout-classifier/classify-timeout "" default-cfg))
                      (.toBe 30000))))))

;; ── activate / middleware integration ───────────────────────────

(defn- make-stub-api []
  (let [registered (atom nil)
        removed?   (atom false)]
    {:api         #js {:addMiddleware    (fn [interceptor] (reset! registered interceptor))
                       :removeMiddleware (fn [_name] (reset! removed? true))}
     :registered  registered
     :removed?    removed?}))

(describe "timeout-classifier:middleware"
          (fn []
            (it "activate registers a named middleware"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)]
                    (-> (expect (.-name @registered)) (.toBe "bash-suite/timeout-classifier")))))

            (it "deactivator calls removeMiddleware"
                (fn []
                  (let [{:keys [api removed?]} (make-stub-api)
                        deactivate (timeout-classifier/activate api)]
                    (deactivate)
                    (-> (expect @removed?) (.toBe true)))))

            (it "enter RETURNS a ctx carrying the classified timeout"
                (fn []
                  ;; Was "enter mutates args.timeout". It must not: the args
                  ;; object is aliased onto the model's own tool-call part and
                  ;; replayed to it, which is how cwd-manager's in-place `aset`
                  ;; stacked `cd` prefixes 25 deep. The classifier now returns a
                  ;; ctx with replaced args.
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        args #js {:command "npm install"}
                        ctx #js {:tool-name "bash" :args args}
                        out ((.-enter @registered) ctx)]
                    (-> (expect (.-timeout (.-args out))) (.toBe 300000))
                    ;; The caller's object is untouched.
                    (-> (expect (.-timeout args)) (.toBeUndefined)))))

            (it "enter preserves the other args when it sets a timeout"
                (fn []
                  ;; Rebuilding args as a fresh single-key object is how
                  ;; env-filter silently dropped `timeout` on every bash call.
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool-name "bash"
                                 :args #js {:command "npm install" :description "install deps"}}
                        out ((.-enter @registered) ctx)]
                    (-> (expect (.-command (.-args out))) (.toBe "npm install"))
                    (-> (expect (.-description (.-args out))) (.toBe "install deps")))))

            (it "enter leaves args.timeout unset for safe command"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool-name "bash" :args #js {:command "ls -la"}}
                        out ((.-enter @registered) ctx)]
                    (-> (expect (.-timeout (.-args out))) (.toBeUndefined)))))

            (it "enter respects an explicit user-provided timeout"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool-name "bash" :args #js {:command "npm install" :timeout 5000}}]
                    ((.-enter @registered) ctx)
                    (-> (expect (.-timeout (.-args ctx))) (.toBe 5000)))))

            (it "enter skips non-bash tools"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool-name "read" :args #js {:path "/tmp/foo"}}]
                    ((.-enter @registered) ctx)
                    (-> (expect (.-timeout (.-args ctx))) (.toBeUndefined)))))))
