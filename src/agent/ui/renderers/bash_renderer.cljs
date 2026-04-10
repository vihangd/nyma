(ns agent.ui.renderers.bash-renderer
  "Renderer for the built-in `bash` tool — delegates to BashExecution for
   a rich bordered streaming view. Registered with :merge-call-and-result?
   so a single component owns both phases."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["../bash_execution.jsx" :refer [BashExecution]]
            [agent.ui.tool-renderer-registry :refer [register-renderer]]))

(defn- lines-from
  "Turn the mix of possible result shapes into a vector of output lines."
  [result]
  (cond
    (nil? result) []
    (array? result) (vec result)
    (string? result) (vec (.split result "\n"))
    :else [(str result)]))

(defn- BashRenderer [{:keys [args result is-partial duration theme]}]
  (let [command (or (get args :command) (get args "command") "")
        lines   (lines-from result)
        status  (if is-partial :running :complete)
        ;; Exit-code may not be surfaced by the current tool pipeline — assume
        ;; 0 when the result is present and empty otherwise.
        exit-code (if (or is-partial (nil? result)) 0 0)]
    #jsx [BashExecution {:command    command
                         :is-python  false
                         :lines      lines
                         :status     status
                         :exit-code  exit-code
                         :expanded   false
                         :is-active  false
                         :theme      theme}]))

(register-renderer "bash"
  {:render-call            BashRenderer
   :merge-call-and-result? true})
