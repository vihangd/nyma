(ns extension-disable-reload.test
  "`/extensions disable X` then `/reload`, through the real commands: an
   extension that depends on X is skipped with the reason /extensions shows,
   and everything it had registered — command, tool — is gone, not left
   dangling under a namespace nothing will ever deactivate.

   extension_loader.test covers the loader given a pre-written setting; this
   drives toggle-extension! (the write) and handle-reload (the sweep + reload)
   the way the two slash commands do."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all last-load-failures]]
            [agent.builtin-extensions :refer [registry]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.commands.builtins :refer [toggle-extension! handle-reload]]))

;; subagent is the shipped extension that declares `dependsOn: ["model-roles"]`.
(defn- ^:async disable-then-reload []
  (let [tmp    (fs/mkdtempSync (path/join (os/tmpdir) "nyma-ext-disable-"))
        mgr    (create-settings-manager {:global-path  (path/join tmp "global.json")
                                         :project-path (path/join tmp "project.json")})
        agent  (create-agent {:model "test" :system-prompt "reload" :settings mgr})
        api    (create-extension-api agent)
        _      (set! (.-extension-api agent) api)
        notes  (atom [])
        ctx    #js {:ui #js {:notify (fn [m & [l]] (swap! notes conj {:msg m :level l}))}}
        loaded (atom (js-await (discover-and-load [] api registry)))
        tools  (fn [] (set (keys ((:all (:tool-registry agent))))))]
    (try
      ;; Before: the dependent is live.
      (-> (expect (some? (resolve-command @(:commands agent) "agents"))) (.toBe true))
      (-> (expect (contains? (tools) "subagent__subagent")) (.toBe true))

      (toggle-extension! {:settings mgr} loaded "disable" "model-roles" true ctx)
      (-> (expect (:msg (last @notes))) (.toContain "Disabled model-roles"))
      (-> (expect (aget (js/JSON.parse (fs/readFileSync (path/join tmp "project.json") "utf8"))
                        "extensions" "model-roles"))
          (.toBe false))

      (js-await (handle-reload agent {:settings mgr} loaded nil ctx))

      (-> (expect (:msg (last @notes))) (.toBe "Extensions reloaded"))
      (-> (expect (get @last-load-failures "subagent")) (.toBe "depends on disabled model-roles"))
      (-> (expect (some #(= (:namespace %) "model-roles") @loaded)) (.toBeFalsy))
      (-> (expect (some #(= (:namespace %) "subagent") @loaded)) (.toBeFalsy))
      ;; No residue: the dependent's registrations were swept with it.
      (-> (expect (resolve-command @(:commands agent) "agents")) (.toBeNil))
      (-> (expect (resolve-command @(:commands agent) "role")) (.toBeNil))
      (-> (expect (contains? (tools) "subagent__subagent")) (.toBe false))
      ;; …while an unrelated extension came back.
      (-> (expect (some? (resolve-command @(:commands agent) "rewind"))) (.toBe true))
      (finally
        (js-await (deactivate-all @loaded))
        (fs/rmSync tmp #js {:recursive true :force true})))))

(describe "/extensions disable + /reload"
          (fn []
            (it "a dependent of the disabled extension is skipped with the reason and leaves no registrations"
                disable-then-reload)))
