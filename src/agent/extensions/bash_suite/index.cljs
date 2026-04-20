(ns agent.extensions.bash-suite.index
  (:require [agent.extensions.bash-suite.shared :as shared]
            [agent.extensions.bash-suite.security-analysis :as security-analysis]
            [agent.extensions.bash-suite.permissions :as permissions]
            [agent.extensions.bash-suite.output-handling :as output-handling]
            [agent.extensions.bash-suite.env-filter :as env-filter]
            [agent.extensions.bash-suite.cwd-manager :as cwd-manager]
            [agent.extensions.bash-suite.timeout-classifier :as timeout-classifier]
            [agent.extensions.bash-suite.background-jobs :as background-jobs]
            [clojure.string :as str]))

(def ^:private bash-security-notice
  "## Bash Security
Commands are analyzed for safety. Destructive operations (rm -rf /, dd, fork bombs) and piped-to-interpreter patterns (curl|bash) are blocked. Safe read-only commands auto-execute. Use /bash-stats to see security statistics.")

(defn- format-stats []
  (let [s   @shared/suite-stats
        sa  (:security-analysis s)
        cl  (:classified sa)
        pm  (:permissions s)
        oh  (:output-handling s)
        ef  (:env-filter s)
        cw  (:cwd-manager s)
        bg  (:background-jobs s)]
    (str "Bash Suite — Session Stats\n"
         "─────────────────────────────────────────\n"
         "Security Analysis:  " (:commands-analyzed sa) " commands analyzed, "
         (:blocked sa) " blocked\n"
         "                    safe:" (:safe cl) " read-only:" (:read-only cl)
         " write:" (:write cl) " network:" (:network cl)
         " destructive:" (:destructive cl) "\n"
         "Permissions:        " (:auto-approved pm) " auto-approved, "
         (:denied pm) " denied, " (:remembered pm) " remembered\n"
         "Output Handling:    " (:truncations oh) " truncations, "
         (:bytes-saved oh) " bytes saved, "
         (:temp-files-created oh) " temp files\n"
         "Env Filter:         " (:commands-filtered ef) " commands filtered, "
         (:vars-stripped ef) " vars stripped\n"
         "CWD Manager:        " (:cd-tracked cw) " cd tracked, "
         (:cwd-prepended cw) " cwd prepended, "
         (:invalid-cwd-caught cw) " invalid caught\n"
         "Background Jobs:    " (:jobs-started bg) " started, "
         (:jobs-completed bg) " completed, "
         (:jobs-killed bg) " killed")))

(defn ^:export default [api]
  (let [deactivators (atom [])]

    ;; Activate sub-extensions in dependency order
    ;; security_analysis must be first (emits classification events)
    (swap! deactivators conj (security-analysis/activate api))
    ;; permissions consumes classification
    (swap! deactivators conj (permissions/activate api))
    ;; env_filter is independent
    (swap! deactivators conj (env-filter/activate api))
    ;; middleware: cwd_manager, timeout_classifier, background_jobs, output_handling
    (swap! deactivators conj (cwd-manager/activate api))
    (swap! deactivators conj (timeout-classifier/activate api))
    (swap! deactivators conj (background-jobs/activate api))
    (swap! deactivators conj (output-handling/activate api))

    ;; Register /bash-stats command
    (.registerCommand api "bash-stats"
                      #js {:description "Show bash security and execution stats"
                           :handler (fn [_args ctx]
                                      (let [text (format-stats)]
                                        (when (and ctx (.-ui ctx) (.-available (.-ui ctx)))
                                          (.notify (.-ui ctx) text "info"))
                                        text))})

    ;; Inject security notice into system prompt
    (.on api "before_agent_start"
         (fn [_event _ctx]
           #js {:prompt-sections
                #js [#js {:content bash-security-notice :priority 30}]})
         40)

    ;; Return combined deactivate
    (fn []
      (doseq [deactivate @deactivators]
        (when (fn? deactivate)
          (deactivate))))))
