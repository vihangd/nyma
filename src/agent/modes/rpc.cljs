(ns agent.modes.rpc
  (:require [agent.utils.jsonl-stdin :refer [read-lines!]]
            [agent.loop :refer [run]]
            [agent.events :refer [wire-event-types]]))

(defn ^:async handle-line [agent line]
  (try
    (let [cmd (js/JSON.parse line)]
      (case (.-type cmd)
        "prompt"       (js-await (run agent (.-message cmd)))
        "abort"        nil
        "get_commands" (println (js/JSON.stringify (clj->js @(:commands agent))))
        "get_settings" (println (js/JSON.stringify (clj->js (:config agent))))
        (js/console.error (str "Unknown command: " (.-type cmd)))))
    (catch :default e
      (js/console.error (str "RPC error: " (.-message e))))))

(defn ^:async start [agent]
  (let [stop-reading (atom nil)]

    ;; Subscribe to agent events → write as JSONL to stdout.
    ;; Retain refs so start returns a cleanup thunk (allows clean teardown if
    ;; RPC mode is ever stopped without a process exit).
    (let [handler-refs
          (mapv (fn [event-type]
                  (let [h (fn [data]
                            (println (js/JSON.stringify
                                      (clj->js {:type event-type :data data}))))]
                    ((:on (:events agent)) event-type h)
                    [event-type h]))
                wire-event-types)]

      ;; Read commands from stdin as JSONL. Not node:readline — it also splits
      ;; on U+2028/U+2029, which are legal inside a JSON string.
      ;; EOF on stdin means the host is gone; without this the process sat
      ;; forever on an idle event loop (`nyma --mode rpc </dev/null` hung).
      (reset! stop-reading
              (read-lines! js/process.stdin (partial handle-line agent)
                           (fn [] (js/process.exit 0))))

      ;; Return a cleanup thunk — call it to deregister all handlers
      (fn []
        (when-let [stop @stop-reading] (stop))
        (doseq [[ev h] handler-refs]
          ((:off (:events agent)) ev h))))))
