(ns agent.modes.rpc
  (:require ["node:readline" :as readline]
            [agent.loop :refer [run]]
            [agent.events :refer [all-event-types]]))

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
  (let [rl (readline/createInterface
             #js {:input    js/process.stdin
                  :output   js/process.stdout
                  :terminal false})]

    ;; Subscribe to agent events → write as JSONL to stdout
    (doseq [event-type all-event-types]
      ((:on (:events agent)) event-type
        (fn [data]
          (println (js/JSON.stringify
            (clj->js {:type event-type :data data}))))))

    ;; Read commands from stdin as JSONL
    (.on rl "line" (partial handle-line agent))))
