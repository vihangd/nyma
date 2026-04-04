(ns interceptors.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.interceptors :refer [execute interceptor into-chain]]))

;; All async test fns must be top-level defn ^:async (Squint pitfall)

(defn ^:async test-empty-chain []
  (let [result (js-await (execute [] {:value 1}))]
    (-> (expect (:value result)) (.toBe 1))))

(defn ^:async test-single-enter []
  (let [ic (interceptor :inc {:enter (fn [ctx] (update ctx :value inc))})
        result (js-await (execute [ic] {:value 0}))]
    (-> (expect (:value result)) (.toBe 1))))

(defn ^:async test-enter-ordering []
  (let [log (atom [])
        a (interceptor :a {:enter (fn [ctx] (swap! log conj "a") (update ctx :value str "a"))})
        b (interceptor :b {:enter (fn [ctx] (swap! log conj "b") (update ctx :value str "b"))})
        c (interceptor :c {:enter (fn [ctx] (swap! log conj "c") (update ctx :value str "c"))})
        result (js-await (execute [a b c] {:value ""}))]
    (-> (expect (:value result)) (.toBe "abc"))
    (-> (expect (clj->js @log)) (.toEqual #js ["a" "b" "c"]))))

(defn ^:async test-skips-missing-enter []
  (let [a (interceptor :a {:enter (fn [ctx] (update ctx :value inc))})
        b (interceptor :b {:leave (fn [ctx] ctx)})
        c (interceptor :c {:enter (fn [ctx] (update ctx :value inc))})
        result (js-await (execute [a b c] {:value 0}))]
    (-> (expect (:value result)) (.toBe 2))))

(defn ^:async test-leave-reverse-order []
  (let [log (atom [])
        a (interceptor :a {:leave (fn [ctx] (swap! log conj "a") ctx)})
        b (interceptor :b {:leave (fn [ctx] (swap! log conj "b") ctx)})
        c (interceptor :c {:leave (fn [ctx] (swap! log conj "c") ctx)})
        _result (js-await (execute [a b c] {:value 1}))]
    (-> (expect (clj->js @log)) (.toEqual #js ["c" "b" "a"]))))

(defn ^:async test-enter-then-leave []
  (let [log (atom [])
        ic (interceptor :test
             {:enter (fn [ctx] (swap! log conj "enter") (assoc ctx :entered true))
              :leave (fn [ctx] (swap! log conj "leave") (assoc ctx :left true))})
        result (js-await (execute [ic] {}))]
    (-> (expect (:entered result)) (.toBe true))
    (-> (expect (:left result)) (.toBe true))
    (-> (expect (clj->js @log)) (.toEqual #js ["enter" "leave"]))))

(defn ^:async test-leave-modifies-ctx []
  (let [a (interceptor :a {:leave (fn [ctx] (update ctx :value str "-a"))})
        b (interceptor :b {:leave (fn [ctx] (update ctx :value str "-b"))})
        result (js-await (execute [a b] {:value "start"}))]
    ;; Leave runs right-to-left: b then a
    (-> (expect (:value result)) (.toBe "start-b-a"))))

(defn ^:async test-error-skips-remaining-enters []
  (let [log (atom [])
        a (interceptor :a {:enter (fn [ctx] (swap! log conj "a") ctx)})
        b (interceptor :b {:enter (fn [_ctx] (throw (js/Error. "boom")))})
        c (interceptor :c {:enter (fn [ctx] (swap! log conj "c") ctx)})
        result (js-await (execute [a b c] {}))]
    (-> (expect (clj->js @log)) (.toEqual #js ["a"]))
    (-> (expect (:error result)) (.toBeDefined))))

(defn ^:async test-error-runs-error-stages []
  (let [caught (atom nil)
        a (interceptor :a
            {:error (fn [ctx] (reset! caught (:error ctx)) ctx)})
        b (interceptor :b
            {:enter (fn [_ctx] (throw (js/Error. "boom")))})
        _result (js-await (execute [a b] {}))]
    (-> (expect (.-message @caught)) (.toBe "boom"))))

(defn ^:async test-error-recovery-clears-error []
  (let [a (interceptor :a
            {:error (fn [ctx] (dissoc ctx :error :error-interceptor))})
        b (interceptor :b
            {:enter (fn [_ctx] (throw (js/Error. "boom")))})
        result (js-await (execute [a b] {}))]
    (-> (expect (:error result)) (.toBeUndefined))))

(defn ^:async test-error-interceptor-tracked []
  (let [result (js-await (execute
                           [(interceptor :bad {:enter (fn [_] (throw (js/Error. "fail")))})]
                           {}))]
    (-> (expect (:error-interceptor result)) (.toBe :bad))))

(defn ^:async test-async-enter []
  (let [ic (interceptor :async-inc
             {:enter (fn [ctx] (js/Promise.resolve (update ctx :value inc)))})
        result (js-await (execute [ic] {:value 0}))]
    (-> (expect (:value result)) (.toBe 1))))

(defn ^:async test-async-leave []
  (let [ic (interceptor :async-leave
             {:leave (fn [ctx] (js/Promise.resolve (assoc ctx :async-done true)))})
        result (js-await (execute [ic] {}))]
    (-> (expect (:async-done result)) (.toBe true))))

(defn ^:async test-mixed-sync-async []
  (let [sync-ic  (interceptor :sync {:enter (fn [ctx] (update ctx :value inc))})
        async-ic (interceptor :async {:enter (fn [ctx] (js/Promise.resolve (update ctx :value inc)))})
        result   (js-await (execute [sync-ic async-ic] {:value 0}))]
    (-> (expect (:value result)) (.toBe 2))))

(defn ^:async test-composed-chain-executes []
  (let [a (interceptor :a {:enter (fn [ctx] (update ctx :value inc))})
        b (interceptor :b {:enter (fn [ctx] (update ctx :value inc))})
        chain (into-chain [a] [b])
        result (js-await (execute chain {:value 0}))]
    (-> (expect (:value result)) (.toBe 2))))

(defn ^:async test-error-during-leave []
  (let [log (atom [])
        a (interceptor :a
            {:leave (fn [ctx] (swap! log conj "a-leave") ctx)
             :error (fn [ctx] (swap! log conj "a-error") ctx)})
        b (interceptor :b
            {:leave (fn [_ctx] (throw (js/Error. "leave-boom")))})
        result (js-await (execute [a b] {}))]
    (-> (expect (:error result)) (.toBeDefined))
    (-> (expect (clj->js @log)) (.toContain "a-error"))))

(defn ^:async test-error-during-leave-recovery-resumes-leave []
  (let [log (atom [])
        a (interceptor :a
            {:leave (fn [ctx] (swap! log conj "a-leave") ctx)
             :error (fn [ctx]
                      (swap! log conj "a-error-clears")
                      (dissoc ctx :error :error-interceptor))})
        b (interceptor :b
            {:leave (fn [_ctx] (throw (js/Error. "leave-boom")))})
        c (interceptor :c
            {:enter (fn [ctx] ctx)
             :leave (fn [ctx] (swap! log conj "c-leave") ctx)})
        result (js-await (execute [a b c] {}))]
    ;; c-leave runs, b-leave throws, a-error clears error and resumes leave for remaining (a)
    ;; So we should see: c-leave, a-error-clears, a-leave (a is left of error point)
    (-> (expect (clj->js @log)) (.toContain "c-leave"))
    (-> (expect (clj->js @log)) (.toContain "a-error-clears"))
    ;; Error should be cleared
    (-> (expect (:error result)) (.toBeUndefined))))

(defn ^:async test-context-preserved-through-chain []
  (let [a (interceptor :a {:enter (fn [ctx] (assoc ctx :a-data "hello"))})
        b (interceptor :b {:enter (fn [ctx] (assoc ctx :b-data (str (:a-data ctx) " world")))})
        result (js-await (execute [a b] {}))]
    (-> (expect (:a-data result)) (.toBe "hello"))
    (-> (expect (:b-data result)) (.toBe "hello world"))))

(describe "interceptor constructor" (fn []
  (it "creates interceptor with all stages"
    (fn []
      (let [ic (interceptor :test
                 {:enter (fn [ctx] ctx)
                  :leave (fn [ctx] ctx)
                  :error (fn [ctx] ctx)})]
        (-> (expect (:name ic)) (.toBe :test))
        (-> (expect (:enter ic)) (.toBeDefined))
        (-> (expect (:leave ic)) (.toBeDefined))
        (-> (expect (:error ic)) (.toBeDefined)))))

  (it "creates interceptor with partial stages"
    (fn []
      (let [ic (interceptor :partial {:enter (fn [ctx] ctx)})]
        (-> (expect (:name ic)) (.toBe :partial))
        (-> (expect (:enter ic)) (.toBeDefined))
        (-> (expect (:leave ic)) (.toBeUndefined))
        (-> (expect (:error ic)) (.toBeUndefined)))))))

(describe "execute - enter phase" (fn []
  (it "passes through empty chain" test-empty-chain)
  (it "runs single enter" test-single-enter)
  (it "runs enters left-to-right" test-enter-ordering)
  (it "skips interceptors without enter" test-skips-missing-enter)
  (it "preserves context through chain" test-context-preserved-through-chain)))

(describe "execute - leave phase" (fn []
  (it "runs leaves right-to-left" test-leave-reverse-order)
  (it "runs enter then leave" test-enter-then-leave)
  (it "leave stages modify context" test-leave-modifies-ctx)))

(describe "execute - error phase" (fn []
  (it "error in enter skips remaining enters" test-error-skips-remaining-enters)
  (it "error triggers :error stages" test-error-runs-error-stages)
  (it "error recovery clears error" test-error-recovery-clears-error)
  (it "tracks which interceptor caused error" test-error-interceptor-tracked)
  (it "handles error during leave" test-error-during-leave)
  (it "leave-phase error recovery resumes remaining leave handlers" test-error-during-leave-recovery-resumes-leave)))

(describe "execute - async stages" (fn []
  (it "awaits async enter stages" test-async-enter)
  (it "awaits async leave stages" test-async-leave)
  (it "handles mixed sync and async" test-mixed-sync-async)))

(describe "into-chain" (fn []
  (it "composes individual interceptors and chains"
    (fn []
      (let [a (interceptor :a {:enter (fn [ctx] ctx)})
            b (interceptor :b {:enter (fn [ctx] ctx)})
            chain (into-chain [a] b [a b])]
        (-> (expect (count chain)) (.toBe 4))
        (-> (expect (:name (first chain))) (.toBe :a))
        (-> (expect (:name (second chain))) (.toBe :b)))))

  (it "composed chain executes correctly" test-composed-chain-executes)))
