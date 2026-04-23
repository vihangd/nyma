(ns ext-lsp-suite.test
  "Unit tests for the lsp_suite extension.
   Uses a fake ILspConnection so no real language server is needed."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.lsp-suite.lsp-formatters :as fmt]
            [agent.extensions.lsp-suite.lsp-diagnostics :as diags]
            [agent.extensions.lsp-suite.lsp-servers-catalog :as catalog]
            [agent.extensions.lsp-suite.lsp-config :as config]
            [agent.extensions.lsp-suite.lsp-manager :as mgr]))

;; ── Formatters ────────────────────────────────────────────────────

(describe "lsp-formatters — URI conversion" (fn []
                                              (it "path->uri prepends file://"
                                                  (fn []
                                                    (-> (expect (fmt/path->uri "/tmp/foo.ts")) (.toBe "file:///tmp/foo.ts"))))

                                              (it "uri->path strips file://"
                                                  (fn []
                                                    (-> (expect (fmt/uri->path "file:///tmp/foo.ts")) (.toBe "/tmp/foo.ts"))))

                                              (it "format-location converts 0-based to 1-based"
                                                  (fn []
                                                    (-> (expect (fmt/format-location "file:///a/b.ts" 0 0))
                                                        (.toBe "/a/b.ts:1:1"))
                                                    (-> (expect (fmt/format-location "file:///a/b.ts" 9 19))
                                                        (.toBe "/a/b.ts:10:20"))))))

(describe "lsp-formatters — hover" (fn []
                                     (it "nil hover returns falsy"
                                         (fn []
                                           (-> (expect (fmt/format-hover nil)) (.toBeFalsy))))

                                     (it "string contents returned as-is"
                                         (fn []
                                           (let [h #js {:contents "hello world"}]
                                             (-> (expect (fmt/format-hover h)) (.toBe "hello world")))))

                                     (it "MarkupContent {kind value} returns value"
                                         (fn []
                                           (let [h #js {:contents #js {:kind "markdown" :value "**bold**"}}]
                                             (-> (expect (fmt/format-hover h)) (.toBe "**bold**")))))

                                     (it "empty string contents returns falsy"
                                         (fn []
                                           (let [h #js {:contents ""}]
                                             (-> (expect (fmt/format-hover h)) (.toBeFalsy)))))

                                     (it "array of MarkedStrings joined with separator"
                                         (fn []
                                           (let [h #js {:contents #js ["first" "second"]}]
                                             (-> (expect (fmt/format-hover h)) (.toContain "first"))
                                             (-> (expect (fmt/format-hover h)) (.toContain "second")))))))

(describe "lsp-formatters — symbol kinds" (fn []
                                            (it "maps known numeric kind to name"
                                                (fn []
                                                  (-> (expect (fmt/symbol-kind-name 5))  (.toBe "Class"))
                                                  (-> (expect (fmt/symbol-kind-name 12)) (.toBe "Function"))
                                                  (-> (expect (fmt/symbol-kind-name 6))  (.toBe "Method"))))

                                            (it "unknown kind falls back to Kind<N>"
                                                (fn []
                                                  (-> (expect (fmt/symbol-kind-name 99)) (.toBe "Kind99"))))))

(describe "lsp-formatters — diagnostics" (fn []
                                           (it "formats error diagnostic with path:line:col"
                                               (fn []
                                                 (let [d #js {:severity 1 :message "Type mismatch"
                                                              :range #js {:start #js {:line 4 :character 2}}}
                                                       s (fmt/format-diagnostic d "/a/b.ts")]
                                                   (-> (expect s) (.toContain "Error"))
                                                   (-> (expect s) (.toContain "/a/b.ts:5:3"))
                                                   (-> (expect s) (.toContain "Type mismatch")))))

                                           (it "labels severity 2 as Warning"
                                               (fn []
                                                 (let [d #js {:severity 2 :message "unused var"
                                                              :range #js {:start #js {:line 0 :character 0}}}
                                                       s (fmt/format-diagnostic d "/x.ts")]
                                                   (-> (expect s) (.toContain "Warning")))))))

;; ── Diagnostics registry ──────────────────────────────────────────

(describe "lsp-diagnostics — dedup and caps" (fn []
                                               (beforeEach (fn [] (diags/clear!)))

                                               (it "stores a pending diagnostic"
                                                   (fn []
                                                     (let [d #js {:severity 1 :message "err" :range #js {:start #js {:line 0 :character 0}}}]
                                                       (diags/register-pending! "file:///a.ts" #js [d] "/a.ts")
                                                       (-> (expect (diags/pending-count)) (.toBe 1)))))

                                               (it "deduplicates identical diagnostics"
                                                   (fn []
                                                     (let [d #js {:severity 1 :message "dup" :range #js {:start #js {:line 3 :character 0}}}]
                                                       (diags/register-pending! "file:///a.ts" #js [d] "/a.ts")
                                                       (diags/register-pending! "file:///a.ts" #js [d] "/a.ts")
                                                       (-> (expect (diags/pending-count)) (.toBe 1)))))

                                               (it "allows diagnostics with same message but different lines"
                                                   (fn []
                                                     (let [d1 #js {:severity 1 :message "err" :range #js {:start #js {:line 1 :character 0}}}
                                                           d2 #js {:severity 1 :message "err" :range #js {:start #js {:line 2 :character 0}}}]
                                                       (diags/register-pending! "file:///a.ts" #js [d1 d2] "/a.ts")
                                                       (-> (expect (diags/pending-count)) (.toBe 2)))))

                                               (it "drain-pending! empties the buffer"
                                                   (fn []
                                                     (let [d #js {:severity 2 :message "w" :range #js {:start #js {:line 0 :character 0}}}]
                                                       (diags/register-pending! "file:///a.ts" #js [d] "/a.ts")
                                                       (let [result (diags/drain-pending!)]
                                                         (-> (expect (count result)) (.toBe 1))
                                                         (-> (expect (diags/pending-count)) (.toBe 0))))))

                                               (it "caps per-file at 10"
                                                   (fn []
                                                     (let [diag-arr (clj->js (map (fn [i]
                                                                                    #js {:severity 1 :message (str "e" i)
                                                                                         :range #js {:start #js {:line i :character 0}}})
                                                                                  (range 20)))]
                                                       (diags/register-pending! "file:///a.ts" diag-arr "/a.ts")
                                                       (-> (expect (diags/pending-count)) (.toBeLessThanOrEqual 10)))))))

;; ── Catalog ───────────────────────────────────────────────────────

(describe "lsp-servers-catalog" (fn []
                                  (it "server-for-extension finds TypeScript server for .ts"
                                      (fn []
                                        (let [[id _] (catalog/server-for-extension ".ts")]
                                          (-> (expect id) (.toBe "typescript")))))

                                  (it "server-for-extension finds clojure-lsp for .clj"
                                      (fn []
                                        (let [[id _] (catalog/server-for-extension ".clj")]
                                          (-> (expect id) (.toBe "clojure-lsp")))))

                                  (it "server-for-extension returns falsy for unknown extension"
                                      (fn []
                                        (-> (expect (catalog/server-for-extension ".xyz")) (.toBeFalsy))))

                                  (it "catalog has at least 5 entries"
                                      (fn []
                                        (-> (expect (count catalog/catalog)) (.toBeGreaterThanOrEqual 5))))))

;; ── Config loading ────────────────────────────────────────────────

(describe "lsp-config — load-config" (fn []
                                       (it "returns a map with typescript entry"
                                           (fn []
                                             (let [cfg (config/load-config)]
                                               (-> (expect (some? (get cfg "typescript"))) (.toBe true)))))

                                       (it "typescript config has :command vector"
                                           (fn []
                                             (let [ts (get (config/load-config) "typescript")]
                                               (-> (expect (vector? (:command ts))) (.toBe true))
                                               (-> (expect (pos? (count (:command ts)))) (.toBe true)))))

                                       (it "typescript config has :extensions vector"
                                           (fn []
                                             (let [ts (get (config/load-config) "typescript")]
                                               (-> (expect (contains? (set (:extensions ts)) ".ts")) (.toBe true)))))

                                       (it "default startupTimeout is 15000"
                                           (fn []
                                             (let [ts (get (config/load-config) "typescript")]
                                               (-> (expect (:startupTimeout ts)) (.toBe 15000)))))

                                       (it "default maxRestarts is 3"
                                           (fn []
                                             (let [ts (get (config/load-config) "typescript")]
                                               (-> (expect (:maxRestarts ts)) (.toBe 3)))))

                                       (it "disabled? is false by default"
                                           (fn []
                                             (let [ts (get (config/load-config) "typescript")]
                                               (-> (expect (:disabled? ts)) (.toBe false)))))))

;; ── lsp-manager — no-spawn variants ──────────────────────────────────

(defn ^:async test-ensure-open-if-running-returns-nil-for-rs []
  (let [manager (mgr/create-manager)
        result  (js-await (mgr/ensure-open-if-running! manager "/tmp/foo.rs"))]
    (-> (expect (nil? result)) (.toBe true))))

(defn ^:async test-ensure-open-if-running-returns-nil-for-unknown-ext []
  (let [manager (mgr/create-manager)
        result  (js-await (mgr/ensure-open-if-running! manager "/tmp/foo.xyz"))]
    (-> (expect (nil? result)) (.toBe true))))

(defn ^:async test-ensure-open-if-running-does-not-add-clients []
  (let [manager (mgr/create-manager)
        _       (js-await (mgr/ensure-open-if-running! manager "/tmp/foo.rs"))]
    (-> (expect (= {} @(:clients manager))) (.toBe true))))

(defn ^:async test-did-change-if-running-returns-nil-when-no-client []
  (let [manager (mgr/create-manager)
        result  (js-await (mgr/did-change-if-running! manager "/tmp/foo.rs" "fn main() {}"))]
    (-> (expect (nil? result)) (.toBe true))))

(defn ^:async test-did-change-if-running-does-not-add-clients []
  (let [manager (mgr/create-manager)
        _       (js-await (mgr/did-change-if-running! manager "/tmp/foo.rs" "fn main() {}"))]
    (-> (expect (= {} @(:clients manager))) (.toBe true))))

(describe "lsp-manager — ensure-open-if-running! (no-spawn)" (fn []
                                                               (it "returns nil when no client running for a .rs file"
                                                                   test-ensure-open-if-running-returns-nil-for-rs)
                                                               (it "returns nil when no client running for an unknown extension"
                                                                   test-ensure-open-if-running-returns-nil-for-unknown-ext)
                                                               (it "does not add any clients to the manager"
                                                                   test-ensure-open-if-running-does-not-add-clients)
                                                               (it "did-change-if-running! returns nil when no client running"
                                                                   test-did-change-if-running-returns-nil-when-no-client)
                                                               (it "did-change-if-running! does not add any clients"
                                                                   test-did-change-if-running-does-not-add-clients)))
