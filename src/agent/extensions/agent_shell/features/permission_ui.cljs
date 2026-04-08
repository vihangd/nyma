(ns agent.extensions.agent-shell.features.permission-ui
  "Permission request handling is done in acp/handlers.cljs.
   This module registers the auto-approve flag."
  (:require [agent.extensions.agent-shell.shared :as shared]))

(defn activate
  "Register the auto-approve flag."
  [api]
  (.registerFlag api "auto-approve"
    #js {:description "Auto-approve all agent tool calls"
         :default     false})

  (fn [] nil))
