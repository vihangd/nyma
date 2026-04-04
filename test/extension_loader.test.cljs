(ns extension-loader.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.extension-loader :refer [deactivate-all discover-and-load
                                              reload-extension reload-all]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]))

;; Helper: create a minimal base API for testing discover-and-load
(defn make-test-api []
  (let [agent (create-agent {:model "mock" :system-prompt "test"})]
    (create-extension-api agent)))

;; --- deactivate-all (pure logic, no I/O) ---

(describe "agent.extension-loader - deactivate-all"
  (fn []
    (it "calls deactivate function on each extension that has one"
      (fn []
        (let [called (atom [])]
          (deactivate-all
            [{:path "ext-a.ts" :deactivate (fn [] (swap! called conj "a"))}
             {:path "ext-b.ts" :deactivate (fn [] (swap! called conj "b"))}])
          (-> (expect (count @called)) (.toBe 2))
          (-> (expect (first @called)) (.toBe "a"))
          (-> (expect (second @called)) (.toBe "b")))))

    (it "skips extensions without deactivate"
      (fn []
        (let [called (atom [])]
          (deactivate-all
            [{:path "ext-a.ts" :deactivate nil}
             {:path "ext-b.ts" :deactivate (fn [] (swap! called conj "b"))}])
          (-> (expect (count @called)) (.toBe 1))
          (-> (expect (first @called)) (.toBe "b")))))

    (it "continues if one deactivate throws"
      (fn []
        (let [called (atom [])
              orig   js/console.error]
          (set! js/console.error (fn [& _]))
          (deactivate-all
            [{:path "ext-a.ts" :deactivate (fn [] (throw (js/Error. "fail")))}
             {:path "ext-b.ts" :deactivate (fn [] (swap! called conj "b"))}])
          (set! js/console.error orig)
          (-> (expect (count @called)) (.toBe 1))
          (-> (expect (first @called)) (.toBe "b")))))

    (it "error in deactivate is logged"
      (fn []
        (let [logged (atom nil)
              orig   js/console.error]
          (set! js/console.error (fn [& args] (reset! logged args)))
          (deactivate-all
            [{:path "bad-ext.ts" :deactivate (fn [] (throw (js/Error. "boom")))}])
          (set! js/console.error orig)
          (-> (expect @logged) (.toBeTruthy)))))

    (it "handles empty extension list"
      (fn []
        (deactivate-all [])))))

;; --- discover-and-load with real filesystem ---

(defn ^:async test-skips-missing-dirs []
  (let [result (js-await (discover-and-load ["/nonexistent/dir/xyz"] (make-test-api)))]
    (-> (expect (count result)) (.toBe 0))))

(defn ^:async test-discovers-mjs-files []
  (let [tmp-dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-ext-test-"))
        ext-path (path/join tmp-dir "test-ext.mjs")
        _        (.writeFileSync fs ext-path
                   "export default function(api) { api._test_loaded = true; }")
        api      (make-test-api)
        result   (js-await (discover-and-load [tmp-dir] api))]
    (-> (expect (count result)) (.toBe 1))
    ;; Extension receives scoped API (not raw api), so check namespace
    (-> (expect (:namespace (first result))) (.toBe "test-ext"))
    (.rmSync fs tmp-dir #js {:recursive true})))

(defn ^:async test-stores-deactivate []
  (let [tmp-dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-ext-test-"))
        ext-path (path/join tmp-dir "lifecycle-ext.mjs")
        cleaned  (atom false)
        _        (.writeFileSync fs ext-path
                   "export default function(api) { return () => { globalThis.__nyma_test_cleaned = true; }; }")
        api      (make-test-api)
        result   (js-await (discover-and-load [tmp-dir] api))]
    (-> (expect (:deactivate (first result))) (.toBeTruthy))
    ((:deactivate (first result)))
    (-> (expect js/globalThis.__nyma_test_cleaned) (.toBe true))
    (js-delete js/globalThis "__nyma_test_cleaned")
    (.rmSync fs tmp-dir #js {:recursive true})))

(defn ^:async test-deactivate-nil-when-no-return []
  (let [tmp-dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-ext-test-"))
        ext-path (path/join tmp-dir "simple-ext.mjs")
        _        (.writeFileSync fs ext-path
                   "export default function(api) { /* no return */ }")
        api      (make-test-api)
        result   (js-await (discover-and-load [tmp-dir] api))]
    (-> (expect (:deactivate (first result))) (.toBeNull))
    (.rmSync fs tmp-dir #js {:recursive true})))

(defn ^:async test-skips-non-extension-files []
  (let [tmp-dir (.mkdtempSync fs (str (.tmpdir os) "/nyma-ext-test-"))
        _       (.writeFileSync fs (path/join tmp-dir "readme.md") "# hello")
        _       (.writeFileSync fs (path/join tmp-dir "data.json") "{}")
        result  (js-await (discover-and-load [tmp-dir] (make-test-api)))]
    (-> (expect (count result)) (.toBe 0))
    (.rmSync fs tmp-dir #js {:recursive true})))

(defn ^:async test-handles-throwing-extension []
  (let [tmp-dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-ext-test-"))
        ext-path (path/join tmp-dir "bad-ext.mjs")
        _        (.writeFileSync fs ext-path
                   "export default function(api) { throw new Error('init fail'); }")
        orig     js/console.error
        _        (set! js/console.error (fn [& _]))
        result   (js-await (discover-and-load [tmp-dir] (make-test-api)))]
    (set! js/console.error orig)
    (-> (expect (count result)) (.toBe 0))
    (.rmSync fs tmp-dir #js {:recursive true})))

(defn ^:async test-loads-manifest []
  (let [tmp-dir  (.mkdtempSync fs (str (.tmpdir os) "/nyma-ext-test-"))
        ext-path (path/join tmp-dir "my-plugin.mjs")
        _        (.writeFileSync fs ext-path
                   "export default function(api) { }")
        _        (.writeFileSync fs (path/join tmp-dir "extension.json")
                   (js/JSON.stringify #js {:namespace "custom-ns"
                                           :capabilities #js ["tools" "events"]}))
        result   (js-await (discover-and-load [tmp-dir] (make-test-api)))]
    (-> (expect (:namespace (first result))) (.toBe "custom-ns"))
    (.rmSync fs tmp-dir #js {:recursive true})))

(describe "agent.extension-loader - discover-and-load"
  (fn []
    (it "skips missing directories gracefully" test-skips-missing-dirs)
    (it "discovers and loads .mjs extension files" test-discovers-mjs-files)
    (it "stores deactivate function when extension returns one" test-stores-deactivate)
    (it "deactivate is nil when extension returns nothing" test-deactivate-nil-when-no-return)
    (it "skips non-extension files" test-skips-non-extension-files)
    (it "handles extension that throws during load" test-handles-throwing-extension)
    (it "loads extension.json manifest for namespace" test-loads-manifest)))

;; ── Extension reload ─────────────────────────────────────────

(defn ^:async test-reload-extension-calls-deactivate []
  (let [deactivated (atom false)
        ext-info {:path "/tmp/fake.mjs"
                  :namespace "fake"
                  :type :ts
                  :deactivate (fn [] (reset! deactivated true))}]
    ;; reload-extension will call deactivate, then try to re-load the file
    ;; Since /tmp/fake.mjs doesn't exist, it will catch the error
    (js-await (reload-extension ext-info (make-test-api)))
    ;; Deactivate should have been called
    (-> (expect @deactivated) (.toBe true))))

(defn ^:async test-reload-extension-handles-no-deactivate []
  (let [ext-info {:path "/tmp/fake.mjs"
                  :namespace "fake"
                  :type :ts
                  :deactivate nil}]
    ;; Should not throw even without deactivate function
    (let [result (js-await (reload-extension ext-info (make-test-api)))]
      ;; Returns ext-info on error (file doesn't exist)
      (-> (expect (:namespace result)) (.toBe "fake")))))

(defn ^:async test-reload-all-processes-all []
  (let [count-atom (atom 0)
        ext1 {:path "/tmp/f1.mjs" :namespace "a" :type :ts
              :deactivate (fn [] (swap! count-atom inc))}
        ext2 {:path "/tmp/f2.mjs" :namespace "b" :type :ts
              :deactivate (fn [] (swap! count-atom inc))}]
    (js-await (reload-all [ext1 ext2] (make-test-api)))
    ;; Both should have had deactivate called
    (-> (expect @count-atom) (.toBe 2))))

(describe "agent.extension-loader - reload" (fn []
  (it "reload-extension calls deactivate" test-reload-extension-calls-deactivate)
  (it "reload-extension handles missing deactivate" test-reload-extension-handles-no-deactivate)
  (it "reload-all processes all extensions" test-reload-all-processes-all)))
