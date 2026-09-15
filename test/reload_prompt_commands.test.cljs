(ns reload-prompt-commands.test
  "/reload re-registers prompt templates AFTER session_ready, so an extension
   that registers its command on that event is never overwritten by a prompt
   of the same name — and a prompt whose name is free still comes back."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.commands.builtins :refer [register-builtins handle-reload]]))

;; discover reads ~/.nyma/prompts; HOME is the test scratch dir (test-preload).
(def ^:private prompts-dir (path/join (os/homedir) ".nyma" "prompts"))

(beforeEach (fn [] (fs/mkdirSync prompts-dir #js {:recursive true})))
(afterEach (fn [] (fs/rmSync prompts-dir #js {:recursive true :force true})))

(defn- ^:async reload-with-extension-command []
  (fs/writeFileSync (path/join prompts-dir "deploy.md") "Prompt deploy $1")
  (fs/writeFileSync (path/join prompts-dir "greet.md") "Hello $1")
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})
        notes (atom [])
        ctx   #js {:ui #js {:available true
                            :notify (fn [m l] (swap! notes conj {:msg (str m) :level (or l "info")}))}}]
    (register-builtins agent nil {})
    ;; What an extension does: register its command when the session is ready.
    ((:on (:events agent)) "session_ready"
                           (fn [_]
                             (swap! (:commands agent) assoc "deploy"
                                    {:description "Extension deploy" :handler (fn [_ _] nil)})))
    (js-await (handle-reload agent {} nil nil ctx))
    (let [cmds @(:commands agent)]
      ;; The extension's command survives; the prompt stepped aside.
      (-> (expect (:description (get cmds "deploy"))) (.toBe "Extension deploy"))
      (-> (expect (:group (get cmds "deploy"))) (.not.toBe :prompts))
      ;; A prompt with a free name is registered as usual.
      (-> (expect (:group (get cmds "greet"))) (.toBe :prompts))
      (-> (expect (:msg (last @notes))) (.toBe "Extensions reloaded")))))

(describe "/reload and prompt templates"
          (fn []
            (it "an extension command registered on session_ready is not shadowed by a prompt of the same name, and a free-named prompt still registers"
                reload-with-extension-command)))
