(ns gateway-pipelines.test
  "Unit tests for gateway.pipelines — auth/approval pipeline composition.
   Verifies registration-order execution, first-deny short-circuit,
   nil-returns-allow, and error-defaults-allow behaviour."
  (:require ["bun:test" :refer [describe it expect]]
            [gateway.pipelines :as pipelines]))

;;; ─── create-pipeline: structure ──────────────────────────────

(describe "gateway.pipelines/create-pipeline" (fn []
                                                (it "exposes the expected map keys"
                                                    (fn []
                                                      (let [pl (pipelines/create-pipeline)]
                                                        (-> (expect (fn? (:add! pl))) (.toBe true))
                                                        (-> (expect (fn? (:remove! pl))) (.toBe true))
                                                        (-> (expect (fn? (:clear! pl))) (.toBe true))
                                                        (-> (expect (fn? (:run! pl))) (.toBe true)))))

                                                (it "starts with zero checks"
                                                    (fn []
                                                      (let [pl (pipelines/create-pipeline)]
                                                        (-> (expect (count @(:checks pl))) (.toBe 0)))))

                                                (it "add! appends to checks atom"
                                                    (fn []
                                                      (let [pl (pipelines/create-pipeline)]
                                                        ((:add! pl) (fn [_] #js {:allow? true}))
                                                        ((:add! pl) (fn [_] #js {:allow? true}))
                                                        (-> (expect (count @(:checks pl))) (.toBe 2)))))

                                                (it "remove! removes an exact check-fn"
                                                    (fn []
                                                      (let [pl    (pipelines/create-pipeline)
                                                            check (fn [_] #js {:allow? true})]
                                                        ((:add! pl) check)
                                                        ((:remove! pl) check)
                                                        (-> (expect (count @(:checks pl))) (.toBe 0)))))

                                                (it "clear! empties the checks vector"
                                                    (fn []
                                                      (let [pl (pipelines/create-pipeline)]
                                                        ((:add! pl) (fn [_] nil))
                                                        ((:add! pl) (fn [_] nil))
                                                        ((:clear! pl))
                                                        (-> (expect (count @(:checks pl))) (.toBe 0)))))))

;;; ─── run!: allow / deny / short-circuit / async ──────────────

(defn ^:async test-empty-pipeline-allows []
  (let [pl (pipelines/create-pipeline)
        r  (js-await ((:run! pl) {}))]
    (-> (expect (:allow? r)) (.toBe true))))

(defn ^:async test-single-allow-check []
  (let [pl (pipelines/create-pipeline)]
    ((:add! pl) (fn [_] #js {:allow? true}))
    (let [r (js-await ((:run! pl) {}))]
      (-> (expect (:allow? r)) (.toBe true)))))

(defn ^:async test-single-deny-check []
  (let [pl (pipelines/create-pipeline)]
    ((:add! pl) (fn [_] #js {:allow? false :reason "nope"}))
    (let [r (js-await ((:run! pl) {}))]
      (-> (expect (:allow? r)) (.toBe false))
      (-> (expect (:reason r)) (.toBe "nope")))))

(defn ^:async test-first-deny-wins-and-short-circuits []
  (let [pl    (pipelines/create-pipeline)
        calls (atom [])]
    ((:add! pl) (fn [_]
                  (swap! calls conj :first)
                  #js {:allow? true}))
    ((:add! pl) (fn [_]
                  (swap! calls conj :second)
                  #js {:allow? false :reason "denied by second"}))
    ((:add! pl) (fn [_]
                  (swap! calls conj :third)
                  #js {:allow? true}))
    (let [r (js-await ((:run! pl) {:user "x"}))]
      (-> (expect (:allow? r)) (.toBe false))
      (-> (expect (:reason r)) (.toBe "denied by second"))
      ;; Third check must never run after deny. `some` returns undefined on
      ;; no match in squint, so compare counts explicitly instead.
      (-> (expect (count (filter #(= % :first)  @calls))) (.toBe 1))
      (-> (expect (count (filter #(= % :second) @calls))) (.toBe 1))
      (-> (expect (count (filter #(= % :third)  @calls))) (.toBe 0)))))

(defn ^:async test-nil-return-is-allow []
  (let [pl (pipelines/create-pipeline)]
    ((:add! pl) (fn [_] nil))
    ((:add! pl) (fn [_] #js {:allow? true}))
    (let [r (js-await ((:run! pl) {}))]
      (-> (expect (:allow? r)) (.toBe true)))))

(defn ^:async test-check-that-throws-defaults-to-allow []
  (let [pl (pipelines/create-pipeline)]
    ((:add! pl) (fn [_] (throw (js/Error. "boom"))))
    ((:add! pl) (fn [_] #js {:allow? true}))
    ;; Error in one check should not prevent subsequent checks
    (let [r (js-await ((:run! pl) {}))]
      (-> (expect (:allow? r)) (.toBe true)))))

(defn ^:async test-async-check-promise []
  (let [pl (pipelines/create-pipeline)]
    ((:add! pl) (fn [_]
                  (js/Promise.resolve #js {:allow? false :reason "async deny"})))
    (let [r (js-await ((:run! pl) {}))]
      (-> (expect (:allow? r)) (.toBe false))
      (-> (expect (:reason r)) (.toBe "async deny")))))

(defn ^:async test-request-is-passed-as-js []
  (let [pl   (pipelines/create-pipeline)
        seen (atom nil)]
    ;; clj->js keeps kebab-case keys (does not rewrite to camelCase),
    ;; so access via aget on the string key used in the source map.
    ((:add! pl) (fn [req]
                  (reset! seen (aget req "user-id"))
                  #js {:allow? true}))
    (js-await ((:run! pl) {:user-id "U123" :channel-name "general"}))
    (-> (expect @seen) (.toBe "U123"))))

(defn ^:async test-run-auth-convenience []
  (let [pl (pipelines/create-auth-pipeline)]
    ((:add! pl) (fn [_] #js {:allow? true}))
    (let [r (js-await (pipelines/run-auth pl {:user-id "u"}))]
      (-> (expect (:allow? r)) (.toBe true)))))

(describe "gateway.pipelines/run! — evaluation semantics" (fn []
                                                            (it "empty pipeline resolves to allow"
                                                                test-empty-pipeline-allows)
                                                            (it "single allow check passes"
                                                                test-single-allow-check)
                                                            (it "single deny check fails with reason"
                                                                test-single-deny-check)
                                                            (it "first deny wins and short-circuits remaining checks"
                                                                test-first-deny-wins-and-short-circuits)
                                                            (it "nil return is treated as allow"
                                                                test-nil-return-is-allow)
                                                            (it "check that throws defaults to allow (does not deny the request)"
                                                                test-check-that-throws-defaults-to-allow)
                                                            (it "awaits async check returning a Promise"
                                                                test-async-check-promise)
                                                            (it "passes request as a JS object to the check fn"
                                                                test-request-is-passed-as-js)
                                                            (it "run-auth convenience wrapper returns the same shape"
                                                                test-run-auth-convenience)))
