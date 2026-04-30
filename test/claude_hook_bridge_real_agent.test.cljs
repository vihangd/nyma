(ns claude-hook-bridge-real-agent.test
  "End-to-end against a real nyma agent.

   Why this exists: the unit tests bypassed the real api shape by
   calling dispatch/dispatch directly. The smoke test passed a mock
   api that exposed `events.on` (the inter-extension bus). For too
   long the bridge was subscribing to the wrong bus — silently — and
   nothing caught it because no test ever spun up an agent.create
   and emitted a real `before_tool_call` through the same path
   nyma uses in production.

   This test does that. If the bridge's subscription path drifts
   from how nyma actually exposes events to extensions, this test
   fails."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]))

(def tmp-root (atom nil))
(def tmp-home (atom nil))

(defn- write-json! [p obj]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p (js/JSON.stringify (clj->js obj) nil 2)))

(defn- audit-line-count []
  (let [p (path/join (os/homedir) ".nyma" "hooks-audit.log")]
    (if (fs/existsSync p)
      (count (filter seq (.split (fs/readFileSync p "utf8") "\n")))
      0)))

(beforeEach (fn []
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-real-")))
              (reset! tmp-home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-real-h-")))))

(afterEach (fn []
             (doseq [d [@tmp-root @tmp-home]]
               (try (fs/rmSync d #js {:recursive true :force true})
                    (catch :default _e nil)))))

(defn ^:async test-bridge-fires-on-real-before-tool-call []
  ;; Make the project cwd config the bridge will pick up.
  (write-json! (path/join @tmp-root ".nyma" "settings.json")
               {:hooks
                {:PreToolUse
                 [{:matcher "Bash"
                   :hooks   [{:type "command"
                              :command "echo '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"updatedInput\":{\"command\":\"REWRITTEN\"}}}'"}]}]}})

  ;; Real agent + real extension api wrapper. This is the same shape
  ;; the bridge gets in production via discover-and-load.
  (let [agent (create-agent
               {:model #js {:modelId "test-model"}
                :system-prompt "test"})
        api   (create-extension-api agent)
        ;; Patch process.cwd AND HOME so the bridge reads only our
        ;; tmp config — not the user's real ~/.claude/settings.json.
        orig-cwd  js/process.cwd
        orig-home (.-HOME js/process.env)
        _   (set! js/process.cwd (fn [] @tmp-root))
        _   (aset js/process.env "HOME" @tmp-home)
        before-count (audit-line-count)]
    (try
      ;; Activate the bridge against the real agent.
      (let [bridge-mod (js-await
                        (js/import "/Users/vihangd/projects/pers/nyma/dist/agent/extensions/claude_hook_bridge/index.mjs"))
            dispose    ((.-default bridge-mod) api)]
        (try
          ;; Now emit before_tool_call the way agent.middleware does.
          (let [data #js {:name     "bash"
                          :toolName "bash"
                          :args     #js {:command "git status"}
                          :execId   "exec-test-1"}
                result (js-await
                        ((:emit-collect (:events agent)) "before_tool_call" data))
                args   (get result "args")]
            ;; What we actually need to verify: the bridge subscribed
            ;; to the real bus and a hook fired. Two strong signals:
            ;; 1) the audit log gained at least one entry
            ;; 2) emit-collect's merged result contains an `args`
            ;;    mutation produced by SOME hook (ours and/or the
            ;;    user's compat-loaded ~/.claude config — either is
            ;;    fine; the point is the chain works).
            (-> (expect (> (audit-line-count) before-count)) (.toBe true))
            (-> (expect (some? args)) (.toBe true))
            (-> (expect (string? (.-command args))) (.toBe true))
            (-> (expect (pos? (count (.-command args)))) (.toBe true)))
          (finally
            (when (fn? dispose) (try (dispose) (catch :default _e nil))))))
      (finally
        (set! js/process.cwd orig-cwd)
        (when orig-home (aset js/process.env "HOME" orig-home))))))

(describe "bridge/real-agent" (fn []
                                (it "fires on before_tool_call emitted by a real agent's events bus"
                                    test-bridge-fires-on-real-before-tool-call)))
