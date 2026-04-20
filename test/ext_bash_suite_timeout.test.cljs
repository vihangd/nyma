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

            (it "enter mutates args.timeout to 300000 for matching command"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool_name "bash" :args #js {:command "npm install"}}]
                    ((.-enter @registered) ctx)
                    (-> (expect (.-timeout (.-args ctx))) (.toBe 300000)))))

            (it "enter leaves args.timeout unset for safe command"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool_name "bash" :args #js {:command "ls -la"}}]
                    ((.-enter @registered) ctx)
                    (-> (expect (.-timeout (.-args ctx))) (.toBeUndefined)))))

            (it "enter respects an explicit user-provided timeout"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool_name "bash" :args #js {:command "npm install" :timeout 5000}}]
                    ((.-enter @registered) ctx)
                    (-> (expect (.-timeout (.-args ctx))) (.toBe 5000)))))

            (it "enter skips non-bash tools"
                (fn []
                  (let [{:keys [api registered]} (make-stub-api)
                        _ (timeout-classifier/activate api)
                        ctx #js {:tool_name "read" :args #js {:path "/tmp/foo"}}]
                    ((.-enter @registered) ctx)
                    (-> (expect (.-timeout (.-args ctx))) (.toBeUndefined)))))))
