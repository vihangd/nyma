(ns model-catalog.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/providers/catalog.mjs"
             :refer [list-all-models rank-by-recent search format-context format-price]]
            ["./agent/providers/builtins.mjs" :refer [builtin-providers]]
            ["./agent/commands/builtins.mjs"
             :refer [model->item model-catalogue-text current-model-id
                     current-models remember-model!]]))

(def ^:private providers
  {"alpha" {:models [{:id "m1" :name "Model One" :context-window 32768}
                     {:id "m2" :name "Model Two"
                      :cost {:input 1 :output 4}}]}
   "beta"  {:models [{:id "b1" :name "Beta One"}]}
   ;; A provider with no :models (built-ins looked like this before) must not
   ;; break enumeration.
   "empty" {}})

(defn- ctx-fn [_id] 100000)

(describe "catalog/list-all-models"
          (fn []
            (it "enumerates models across providers"
                (fn []
                  (let [ms (list-all-models providers ctx-fn)]
                    (-> (expect (count ms)) (.toBe 3)))))

            (it "builds provider/id specs"
                (fn []
                  (let [specs (mapv :spec (list-all-models providers ctx-fn))]
                    (-> (expect (clj->js specs)) (.toContain "alpha/m1"))
                    (-> (expect (clj->js specs)) (.toContain "beta/b1")))))

            (it "prefers a declared context window, else the registry fn"
                (fn []
                  (let [by-spec (into {} (map (juxt :spec identity)
                                              (list-all-models providers ctx-fn)))]
                    (-> (expect (:context-window (get by-spec "alpha/m1"))) (.toBe 32768))
                    (-> (expect (:context-window (get by-spec "beta/b1"))) (.toBe 100000)))))

            (it "carries declared cost through"
                (fn []
                  (let [by-spec (into {} (map (juxt :spec identity)
                                              (list-all-models providers ctx-fn)))]
                    (-> (expect (clj->js (:cost (get by-spec "alpha/m2"))))
                        (.toEqual (clj->js [1 4]))))))

            (it "tolerates a provider with no :models"
                (fn []
                  (-> (expect (count (list-all-models {"empty" {}} ctx-fn))) (.toBe 0))))

            (it "enumerates the real built-in providers"
                (fn []
                  ;; Built-ins declare :models so the catalogue needs no
                  ;; per-provider special-casing.
                  (let [ms (list-all-models builtin-providers ctx-fn)]
                    (-> (expect (count ms)) (.toBeGreaterThan 0))
                    (-> (expect (clj->js (mapv :provider ms))) (.toContain "anthropic")))))))

(describe "catalog/rank-by-recent (MRU)"
          (fn []
            (it "moves recently-used models to the front"
                (fn []
                  (let [ms (list-all-models providers ctx-fn)
                        ranked (rank-by-recent ms ["beta/b1"])]
                    (-> (expect (:spec (first ranked))) (.toBe "beta/b1")))))

            (it "preserves order when nothing is recent"
                (fn []
                  (let [ms (list-all-models providers ctx-fn)]
                    (-> (expect (clj->js (mapv :spec (rank-by-recent ms []))))
                        (.toEqual (clj->js (mapv :spec ms)))))))))

(describe "catalog/search"
          (fn []
            (it "matches on spec or name, case-insensitively"
                (fn []
                  (let [ms (list-all-models providers ctx-fn)]
                    (-> (expect (count (search ms "BETA"))) (.toBe 1))
                    (-> (expect (count (search ms "model"))) (.toBe 2)))))

            (it "blank query returns everything"
                (fn []
                  (let [ms (list-all-models providers ctx-fn)]
                    (-> (expect (count (search ms ""))) (.toBe 3)))))))

(describe "catalog formatting"
          (fn []
            (it "formats context windows compactly"
                (fn []
                  (-> (expect (format-context 200000)) (.toBe "200.0k"))
                  (-> (expect (format-context nil)) (.toBe ""))))

            (it "formats prices, empty when unpriced"
                (fn []
                  (-> (expect (format-price [3 15])) (.toBe "$3/$15"))
                  (-> (expect (format-price nil)) (.toBe ""))))))

;; ── /model command helpers ───────────────────────────────────────

(defn- fake-agent [model-id]
  {:config {:model model-id}
   :provider-registry {:list (fn [] providers)}
   :model-registry    {:context-window ctx-fn}})

(describe "builtins /model helpers"
          (fn []
            (it "current-model-id handles string, nil and object models"
                (fn []
                  (-> (expect (current-model-id (fake-agent "x/y"))) (.toBe "x/y"))
                  (-> (expect (current-model-id (fake-agent nil))) (.toBe "unknown"))
                  (-> (expect (current-model-id
                               {:config {:model #js {:modelId "obj-model"}}}))
                      (.toBe "obj-model"))))

            (it "model->item yields the ui.select shape with metadata"
                (fn []
                  (let [item (model->item {:spec "alpha/m1" :name "Model One"
                                           :context-window 32768 :cost [1 4]})]
                    (-> (expect (:value item)) (.toBe "alpha/m1"))
                    (-> (expect (:description item)) (.toContain "32.8k"))
                    (-> (expect (:description item)) (.toContain "$1/$4")))))

            (it "model->item falls back to the name when unpriced/unsized"
                (fn []
                  (let [item (model->item {:spec "beta/b1" :name "Beta One"})]
                    (-> (expect (:description item)) (.toBe "Beta One")))))

            (it "current-models reflects the MRU list"
                (fn []
                  (remember-model! "beta/b1")
                  (-> (expect (:spec (first (current-models (fake-agent "x")))))
                      (.toBe "beta/b1"))))

            (it "model-catalogue-text lists every model"
                (fn []
                  (let [txt (model-catalogue-text (fake-agent "alpha/m1"))]
                    (-> (expect txt) (.toContain "alpha/m1"))
                    (-> (expect txt) (.toContain "beta/b1"))
                    (-> (expect txt) (.toContain "current: alpha/m1")))))

            (it "model-catalogue-text handles an empty registry"
                (fn []
                  (-> (expect (model-catalogue-text
                               {:config {:model "x"}
                                :provider-registry {:list (fn [] {})}
                                :model-registry {:context-window ctx-fn}}))
                      (.toContain "No models registered"))))))
