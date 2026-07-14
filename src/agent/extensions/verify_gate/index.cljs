(ns agent.extensions.verify-gate.index
  "Verify-before-done gate: after a turn that edited files, run the
   configured quality command; on failure, inject the output as a follow-up
   so the agent fixes it before stopping. Off unless settings#verify.cmd is
   set. Attempts are capped so a stubbornly red suite can't loop forever."
  (:require [agent.extensions.verify-gate.shared :as shared]))

(defn ^:async run-cmd [cmd timeout-ms]
  (let [proc   (js/Bun.spawn #js ["sh" "-c" cmd]
                             #js {:timeout timeout-ms :stdout "pipe" :stderr "pipe"})
        stdout (js-await (.text (js/Response. (.-stdout proc))))
        stderr (js-await (.text (js/Response. (.-stderr proc))))
        code   (js-await (.-exited proc))]
    {:exit-code code :output (str stdout (when (seq stderr) (str "\n" stderr)))}))

(defn ^:export activate [api]
  (let [cfg      (shared/config (try (.getSettings api) (catch :default _ nil)))
        edited?  (atom false)
        attempts (atom 0)

        on-complete
        (fn [event _ctx]
          (when (and (shared/edit-tool? (.-toolName event))
                     (not (.-isError event)))
            (reset! edited? true)))

        on-finalize
        (^:async fn [event _ctx]
          (when (and (:cmd cfg)
                     @edited?
                     (not (.-error event)))
            (reset! edited? false)
            (if (>= @attempts (:max-attempts cfg))
              (reset! attempts 0)  ;; give up quietly; the user sees the red output
              (let [{:keys [exit-code output]}
                    (js-await (run-cmd (:cmd cfg) (:timeout-ms cfg)))]
                (if (zero? exit-code)
                  (reset! attempts 0)
                  (do (swap! attempts inc)
                      ((.-sendUserMessage api)
                       (shared/failure-message (:cmd cfg) exit-code output
                                               @attempts (:max-attempts cfg))
                       #js {:deliverAs "followUp"})))))))]

    (when (:cmd cfg)
      (.on api "tool_complete" on-complete)
      (.on api "turn_finalize" on-finalize))

    (fn []
      (when (:cmd cfg)
        (.off api "tool_complete" on-complete)
        (.off api "turn_finalize" on-finalize)))))

(def ^:export default activate)
