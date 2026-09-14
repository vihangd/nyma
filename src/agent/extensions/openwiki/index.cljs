(ns agent.extensions.openwiki
  "OpenWiki — AI-maintained, git-aware living documentation for the repo.

   A small subset of upstream langchain-ai/openwiki (reached here via
   barvhaim/pi-openwiki), not an enrichment of it. What it shares: the OKF v0.2
   output format, the SHA-256 content snapshot, the git-diff update window.
   What upstream has and this does not: Grounded Claims (facts pinned to
   versioned source evidence), a resumable page-job queue, diagram validation,
   connectors, and a graph visualizer. An `init` here is one unbounded agent
   turn, not a checkpointed job.

   Commands (type /openwiki <verb>):
     init    — generate the OKF bundle under <dir>/ (default openwiki/)
     update  — refresh only what git changed since the last run

   Generation is agentic: the command primes a follow-up prompt and the agent
   writes the docs with its own read/write/bash tools on the next turn.

   Off by default; enable with --ext-openwiki or settings."
  (:require [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.commands :as commands]
            [agent.extensions.openwiki.tools :as tools]
            [agent.extensions.openwiki.events :as events]))

(defn ^:export default [api]
  (let [base      (shared/config (.settings api))
        cleanups  (atom [])]

    ;; Register BEFORE reading. Extensions load before cli's resolve-ext-flags
    ;; runs, so registerFlag is what applies the parsed --ext-* argv value — a
    ;; getFlag call placed above this line can only ever return nil.
    (.registerFlag api "openwiki"
                   ;; No :default — an absent flag must read as nil so settings
                   ;; decide. A `false` default would win over
                   ;; settings#openwiki.enabled=true and silently disable it.
                   #js {:description "Enable OpenWiki living-docs commands for this session"
                        :type "boolean"})

    (let [flag-val (.getFlag api "openwiki")
          config   (cond
                     (true? flag-val)  (assoc base :enabled true)
                     (false? flag-val) (assoc base :enabled false)
                     :else             base)]

      (when (:enabled config)
        (let [tool-list (tools/make-tools config)]
          (.registerCommand api "openwiki"
                            #js {:description "AI-maintained living docs. Usage: /openwiki [init | update]"
                                 :handler (commands/make-handler config)})
          (doseq [[name td] tool-list]
            (.registerTool api name td))
          (swap! cleanups conj
                 (fn []
                   (.unregisterCommand api "openwiki")
                   (doseq [[name _] tool-list] (.unregisterTool api name))))
          (swap! cleanups conj (events/register! api config)))))

    ;; Cleanup (no-op when disabled)
    (fn [] (doseq [c @cleanups] (when (fn? c) (c))))))
