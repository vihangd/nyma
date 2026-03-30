(ns agent.modes.print
  (:require [agent.loop :refer [run]]))

(defn ^:async start [agent prompt]
  (when prompt
    (js-await (run agent prompt))
    (let [messages (:messages @(:state agent))
          last-msg  (last messages)]
      (println (:content last-msg)))))

(defn ^:async start-json [agent prompt]
  (when prompt
    (js-await (run agent prompt))
    (let [messages (:messages @(:state agent))]
      (println (js/JSON.stringify (clj->js messages) nil 2)))))
