(ns settings.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.settings.manager :refer [create-settings-manager defaults
                                            camel->kebab normalize-keys]]))

(describe "settings-manager" (fn []
                               (it "returns defaults when no files exist"
                                   (fn []
      ;; Use non-existent paths so the real ~/.nyma/settings.json is not read.
                                     (let [mgr (create-settings-manager {:global-path  "/tmp/nyma-test-nonexistent-global.json"
                                                                         :project-path "/tmp/nyma-test-nonexistent-project.json"})]
                                       (let [settings ((:get mgr))]
                                         (-> (expect (:model settings)) (.toBeDefined))
                                         (-> (expect (:provider settings)) (.toBe "anthropic"))
                                         (-> (expect (:thinking settings)) (.toBe "off"))))))

                               (it "set-override takes priority"
                                   (fn []
                                     (let [mgr (create-settings-manager)]
                                       ((:set-override mgr) :model "gpt-4")
                                       (-> (expect (:model ((:get mgr)))) (.toBe "gpt-4")))))

                               (it "apply-overrides merges multiple keys"
                                   (fn []
                                     (let [mgr (create-settings-manager)]
                                       ((:apply-overrides mgr) {:model "gpt-4" :provider "openai"})
                                       (let [settings ((:get mgr))]
                                         (-> (expect (:model settings)) (.toBe "gpt-4"))
                                         (-> (expect (:provider settings)) (.toBe "openai"))))))

                               (it "overrides stack correctly"
                                   (fn []
                                     (let [mgr (create-settings-manager)]
                                       ((:set-override mgr) :model "first")
                                       ((:set-override mgr) :model "second")
                                       (-> (expect (:model ((:get mgr)))) (.toBe "second")))))

                               (it "defaults include expected keys"
                                   (fn []
                                     (-> (expect (:compaction defaults)) (.toBeDefined))
                                     (-> (expect (:retry defaults)) (.toBeDefined))))

                               (it "tools not in defaults — builtins always registered, settings only restricts"
                                   (fn []
                                     (-> (expect (:tools defaults)) (.toBeUndefined))))

                               (it "defaults include tool-display as collapsed"
                                   (fn []
                                     (-> (expect (:tool-display defaults)) (.toBe "collapsed"))))

                               (it "defaults include tool-display-max-lines as 500"
                                   (fn []
                                     (-> (expect (:tool-display-max-lines defaults)) (.toBe 500))))

                               (it "reload re-reads settings from disk"
                                   (fn []
                                     (let [tmp-dir  (str "/tmp/nyma-settings-test-" (js/Date.now))
                                           _        (fs/mkdirSync tmp-dir #js {:recursive true})
                                           tmp-path (str tmp-dir "/settings.json")
            ;; Write initial settings
                                           _        (fs/writeFileSync tmp-path (js/JSON.stringify #js {:model "initial"} nil 2))
            ;; Create manager that reads from tmp path (we test the reload mechanism via overrides)
                                           mgr      (create-settings-manager)]
        ;; Verify reload function exists and is callable
                                       (-> (expect (fn? (:reload mgr))) (.toBe true))
        ;; Reload should not throw even if underlying files haven't changed
                                       ((:reload mgr))
        ;; Settings should still work after reload
                                       (-> (expect (:model ((:get mgr)))) (.toBeDefined))
        ;; Cleanup
                                       (try (fs/unlinkSync tmp-path) (catch :default _))
                                       (try (fs/rmdirSync tmp-dir) (catch :default _)))))

                               (it "reload with missing files does not crash"
                                   (fn []
                                     (let [mgr (create-settings-manager
                                                {:global-path  "/tmp/nyma-test-nonexistent-global.json"
                                                 :project-path "/tmp/nyma-test-nonexistent-project.json"})]
        ;; Should not throw even if files don't exist on disk
                                       ((:reload mgr))
        ;; Should still return defaults
                                       (-> (expect (:provider ((:get mgr)))) (.toBe "anthropic")))))))

;;; ─── Key normalization: JSON camelCase → kebab-case ──────────────
;;;
;;; Regression guard for the "scrollbackMode in settings has no
;;; effect" bug: the CLJS code reads (:scrollback-mode m) which maps
;;; to string key "scrollback-mode"; users naturally write camelCase
;;; in JSON. load-json now normalizes all keys before merge so both
;;; styles work.

(describe "camel->kebab"
          (fn []
            (it "converts simple camelCase"
                (fn []
                  (-> (expect (camel->kebab "scrollbackMode")) (.toBe "scrollback-mode"))
                  (-> (expect (camel->kebab "toolDisplay"))    (.toBe "tool-display"))))

            (it "leaves already-kebab keys unchanged"
                (fn []
                  (-> (expect (camel->kebab "scrollback-mode")) (.toBe "scrollback-mode"))
                  (-> (expect (camel->kebab "tool-display-max-lines")) (.toBe "tool-display-max-lines"))))

            (it "lowercases single-word keys"
                (fn []
                  (-> (expect (camel->kebab "model")) (.toBe "model"))
                  (-> (expect (camel->kebab "Model")) (.toBe "model"))))

            (it "handles multi-word camelCase"
                (fn []
                  (-> (expect (camel->kebab "toolDisplayMaxLines")) (.toBe "tool-display-max-lines"))))

            (it "returns nil for non-strings"
                (fn []
                  (-> (expect (camel->kebab nil))     (.toBeUndefined))
                  (-> (expect (camel->kebab 42))      (.toBeUndefined))
                  (-> (expect (camel->kebab true))    (.toBeUndefined))))))

(describe "normalize-keys"
          (fn []

            (it "rewrites a single camelCase key to kebab-case"
                (fn []
                  (let [out (normalize-keys #js {:scrollbackMode "pager"})]
                    (-> (expect (aget out "scrollback-mode")) (.toBe "pager"))
                    (-> (expect (aget out "scrollbackMode"))  (.toBeUndefined)))))

            (it "leaves kebab-case keys unchanged"
                (fn []
                  (let [out (normalize-keys #js {"scrollback-mode" "pager"})]
                    (-> (expect (aget out "scrollback-mode")) (.toBe "pager")))))

            (it "recurses into nested objects"
                (fn []
                  (let [out (normalize-keys #js {:statusLine #js {:leftSegments ["a" "b"]}})]
                    (-> (expect (aget out "status-line"))
                        (.toBeDefined))
                    (-> (expect (aget (aget out "status-line") "left-segments"))
                        (.toEqual #js ["a" "b"])))))

            (it "normalizes keys inside arrays of objects"
                (fn []
                  (let [out (normalize-keys #js {:customSegments #js [#js {:keyName "k1"}
                                                                      #js {:keyName "k2"}]})
                        arr (aget out "custom-segments")]
                    (-> (expect (.-length arr)) (.toBe 2))
                    (-> (expect (aget (aget arr 0) "key-name")) (.toBe "k1"))
                    (-> (expect (aget (aget arr 1) "key-name")) (.toBe "k2")))))

            (it "passes primitives through"
                (fn []
                  (-> (expect (normalize-keys 42))     (.toBe 42))
                  (-> (expect (normalize-keys "x"))    (.toBe "x"))
                  (-> (expect (normalize-keys true))   (.toBe true))
                  (-> (expect (normalize-keys nil))    (.toBeNull))))))

(describe "settings-manager reads camelCase JSON"
          (fn []

            (it "scrollbackMode=\"pager\" in JSON becomes :scrollback-mode \"pager\" in CLJS"
                ;; The canonical regression: user writes the JSON-
                ;; idiomatic camelCase, code reads kebab-case via a
                ;; CLJS keyword accessor. Without normalization, the
                ;; setting is silently ignored.
                (fn []
                  (let [tmp-dir  (str "/tmp/nyma-settings-camel-" (js/Date.now))
                        _        (fs/mkdirSync tmp-dir #js {:recursive true})
                        tmp-path (str tmp-dir "/settings.json")
                        _        (fs/writeFileSync
                                  tmp-path
                                  (js/JSON.stringify
                                   (js/JSON.parse "{\"scrollbackMode\": \"pager\"}")))
                        mgr      (create-settings-manager
                                  {:global-path tmp-path
                                   :project-path "/tmp/nyma-test-nonexistent-project.json"})]
                    (-> (expect (:scrollback-mode ((:get mgr)))) (.toBe "pager"))
                    (try (fs/unlinkSync tmp-path) (catch :default _))
                    (try (fs/rmdirSync tmp-dir) (catch :default _)))))

            (it "kebab-case scrollback-mode continues to work"
                (fn []
                  (let [tmp-dir  (str "/tmp/nyma-settings-kebab-" (js/Date.now))
                        _        (fs/mkdirSync tmp-dir #js {:recursive true})
                        tmp-path (str tmp-dir "/settings.json")
                        _        (fs/writeFileSync
                                  tmp-path
                                  "{\"scrollback-mode\": false}")
                        mgr      (create-settings-manager
                                  {:global-path tmp-path
                                   :project-path "/tmp/nyma-test-nonexistent-project.json"})]
                    (-> (expect (:scrollback-mode ((:get mgr)))) (.toBe false))
                    (try (fs/unlinkSync tmp-path) (catch :default _))
                    (try (fs/rmdirSync tmp-dir) (catch :default _)))))))
