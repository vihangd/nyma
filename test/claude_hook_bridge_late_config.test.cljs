(ns claude-hook-bridge-late-config.test
  "Regression: handlers must be subscribed even when the bridge
   activates with an empty hooks-atom. Otherwise enabling
   hooks-compat (or adding a `hooks` block) AFTER nyma started
   silently has no effect — the watcher updates the atom but
   nothing is listening on the event bus."
  (:require ["bun:test" :refer [describe it expect]]))

;; Build a tiny mock api the bridge can register against, then
;; check whether it added a listener for `before_tool_call`.
(defn- mock-api []
  (let [listeners (atom {})]
    #js {:events  #js {:on  (fn [evt h _p]
                              (swap! listeners update evt (fnil conj []) h)
                              nil)
                       :off (fn [evt h]
                              (swap! listeners update evt
                                     (fn [hs] (filterv #(not= % h) (or hs []))))
                              nil)}
         :ui       #js {:available false}
         :__listeners listeners}))

(defn ^:async test-handlers-subscribed-even-with-empty-hooks []
  (let [mod (js-await (js/import "/Users/vihangd/projects/pers/nyma/dist/agent/extensions/claude_hook_bridge/index.mjs"))
        api (mock-api)]
    ;; Important: this test runs in a tmpdir-like environment where
    ;; the user's real settings.json IS present. We can't fully
    ;; isolate without monkey-patching cwd/HOME, so the assertion is
    ;; just \"some handlers were registered\" — even if the user's
    ;; real config happens to have hooks.
    ((.-default mod) api)
    (let [listeners @(.-__listeners api)
          subscribed-events (set (keys listeners))]
      ;; The bridge should have subscribed handlers for the canonical
      ;; events regardless of whether hooks were present at load time.
      (-> (expect (contains? subscribed-events "before_tool_call"))
          (.toBe true))
      (-> (expect (contains? subscribed-events "tool_complete"))
          (.toBe true))
      (-> (expect (contains? subscribed-events "session_start"))
          (.toBe true))
      (-> (expect (contains? subscribed-events "agent_end"))
          (.toBe true)))))

(describe "bridge/late-config-regression"
          (fn []
            (it "always subscribes handlers, even with no hooks loaded"
                test-handlers-subscribed-even-with-empty-hooks)))
