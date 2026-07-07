(ns agent.extensions.openwiki
  "OpenWiki — AI-maintained, git-aware living documentation for the repo.

   Ported from barvhaim/pi-openwiki (itself a port of langchain-ai/openwiki),
   enriched with the SOTA features the naive port lacked: mermaid diagrams,
   file:line source citations, grounded chat, and a SHA-256 content-snapshot
   with a no-op guard.

   Commands (type /openwiki <verb>):
     init             — generate the wiki under <dir>/ (default openwiki/)
     update           — refresh only what git changed since the last run
     chat <question>  — read-only, grounded Q&A over the codebase/docs

   Generation is agentic: the command primes a follow-up prompt and the agent
   writes the docs with its own read/write/bash tools on the next turn.

   Off by default; enable with --ext-openwiki or settings."
  (:require [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.commands :as commands]
            [agent.extensions.openwiki.tools :as tools]
            [agent.extensions.openwiki.events :as events]))

(defn ^:export default [api]
  (let [settings  (try (when (.-getSettings api) (.getSettings api)) (catch :default _ nil))
        base      (shared/config settings)
        flag-val  (.getFlag api "openwiki")
        config    (cond
                    (true? flag-val)  (assoc base :enabled true)
                    (false? flag-val) (assoc base :enabled false)
                    :else             base)
        cleanups  (atom [])]

    ;; Register the opt-in flag (must exist before resolve-ext-flags runs).
    (.registerFlag api "openwiki"
                   #js {:description "Enable OpenWiki living-docs commands for this session"
                        :type "boolean" :default false})

    (when (:enabled config)
      (let [tool-list (tools/make-tools config)]
        (.registerCommand api "openwiki"
                          #js {:description "AI-maintained living docs. Usage: /openwiki [init | update | chat <question>]"
                               :handler (commands/make-handler config)})
        (doseq [[name td] tool-list]
          (.registerTool api name td))
        (swap! cleanups conj
               (fn []
                 (.unregisterCommand api "openwiki")
                 (doseq [[name _] tool-list] (.unregisterTool api name))))
        (swap! cleanups conj (events/register! api config))))

    ;; Cleanup (no-op when disabled)
    (fn [] (doseq [c @cleanups] (when (fn? c) (c))))))
