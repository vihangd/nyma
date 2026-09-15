(ns agent.extensions.openwiki.events
  "Cosmetic UX: a progress status line while the agent writes files under the
   wiki dir.

   There is no session_start hint any more. cli.cljs emits session_start well
   before interactive/start subscribes, and the bus has no replay, so that
   handler had never once run (docs/roadmap.md:202 records the same trap for
   mcp_client and claude_hook_bridge). Dead, not merely early."
  (:require [agent.extensions.openwiki.shared :as shared]
            [agent.tool-metadata :as tool-metadata]))

;; multi_edit belongs here: it DOES carry a top-level :path
;; (token_suite/diff_edit.cljs reads `(.-path args)`) — an earlier comment
;; claimed otherwise and dropping it just blanked the status line for the tool
;; that does the bulk of wiki edits.
(def ^:private write-tools #{"write" "edit" "multi_edit"})

(defn- set-status! [ctx value]
  (when-let [ui (.-ui ctx)]
    (when (.-setStatus ui)
      (.setStatus ui "openwiki" value))))

(defn register!
  "Wire event handlers. Returns a cleanup fn."
  [api config]
  (let [dir      (:dir config)
        on-tool-start
        (fn [event ctx]
          (let [tool (or (.-toolName event) "")
                path (tool-metadata/tool-path (.-args event))]
            (when (and (contains? write-tools tool) (shared/under-dir? dir (str path)))
              (set-status! ctx (str "📝 documenting " path)))))
        ;; Clear only what we set — an unrelated write elsewhere in the repo
        ;; must not wipe the wiki status, and must not set it either.
        on-tool-end
        (fn [event ctx]
          (let [tool (or (.-toolName event) "")
                path (tool-metadata/tool-path (.-args event))]
            (when (and (contains? write-tools tool) (shared/under-dir? dir (str path)))
              (set-status! ctx ""))))
        handlers [["tool_execution_start" on-tool-start]
                  ["tool_execution_end" on-tool-end]]]
    (doseq [[e h] handlers] (.on api e h))
    (fn [] (doseq [[e h] handlers] (.off api e h)))))
