(ns agent.extensions.openwiki.events
  "Cosmetic UX: a session_start hint and a progress status line while the
   agent writes files under the wiki dir."
  (:require [agent.extensions.openwiki.git :as git]
            [agent.extensions.openwiki.shared :as shared]))

(def ^:private write-tools #{"write" "edit" "multi_edit"})

(defn- set-status! [ctx value]
  (when-let [ui (.-ui ctx)]
    (when (.-setStatus ui)
      (.setStatus ui "openwiki" value))))

(defn register!
  "Wire event handlers. Returns a cleanup fn."
  [api config]
  (let [dir      (:dir config)
        handlers (atom [])
        on-start
        (fn [_data ctx]
          (when (git/in-git-repo?)
            (when-let [ui (.-ui ctx)]
              (when (.-notify ui)
                (.notify ui "OpenWiki loaded. Use /openwiki init to generate docs." "info")))))
        on-tool-start
        (fn [event ctx]
          (let [tool (or (.-toolName event) "")
                path (some-> (.-args event) (aget "path"))]
            (when (and (contains? write-tools tool) (shared/under-dir? dir (str path)))
              (set-status! ctx (str "📝 documenting " path)))))
        on-tool-end
        (fn [event ctx]
          (when (contains? write-tools (or (.-toolName event) ""))
            (set-status! ctx "")))]
    (.on api "session_start" on-start)
    (.on api "tool_execution_start" on-tool-start)
    (.on api "tool_execution_end" on-tool-end)
    (reset! handlers [["session_start" on-start]
                      ["tool_execution_start" on-tool-start]
                      ["tool_execution_end" on-tool-end]])
    (fn [] (doseq [[e h] @handlers] (.off api e h)))))
