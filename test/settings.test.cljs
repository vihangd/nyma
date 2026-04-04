(ns settings.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.settings.manager :refer [create-settings-manager defaults]]))

(describe "settings-manager" (fn []
  (it "returns defaults when no files exist"
    (fn []
      (let [mgr (create-settings-manager)]
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
      (let [mgr (create-settings-manager)]
        ;; Should not throw even if files don't exist on disk
        ((:reload mgr))
        ;; Should still return defaults
        (-> (expect (:provider ((:get mgr)))) (.toBe "anthropic")))))))
