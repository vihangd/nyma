(ns agent.extensions
  (:require [agent.loop :refer [steer follow-up]]))

(defn create-extension-api
  "Build the API object that extensions receive."
  [agent]
  #js {:on              (fn [event handler]
                          ((:on (:events agent)) event handler))
       :registerTool    (fn [name tool-def]
                          ((:register (:tool-registry agent)) name tool-def))
       :registerCommand (fn [name opts]
                          (swap! (:commands agent) assoc name opts))
       :registerShortcut (fn [key handler]
                           (swap! (:shortcuts agent) assoc key handler))
       :getCommands     (fn [] @(:commands agent))
       :sendMessage     (fn [msg]
                          (swap! (:state agent) update :messages conj msg))
       :sendUserMessage (fn [text opts]
                          (let [deliver-as (or (and opts (.-deliverAs opts)) "steer")]
                            (case deliver-as
                              "steer"    (steer agent {:role "user" :content text})
                              "followUp" (follow-up agent {:role "user" :content text}))))
       ;; UI hooks — populated when running in interactive mode
       :ui              #js {:showOverlay    nil
                             :setWidget      nil
                             :confirm        nil
                             :setEditor      nil
                             :custom         nil}})
