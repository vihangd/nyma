(ns agent.permissions
  (:require [agent.debug :as d]))

(def all-capabilities
  "All available extension capabilities (must match extension_scope gating)."
  #{:tools :tools-override :commands :shortcuts :events :messages :state :ui
    :middleware :exec :providers :model :session :flags :renderers :spawn
    :context})

(defn check
  "Check if a capability is granted. :all grants everything."
  [granted capability]
  (or (contains? granted :all) (contains? granted capability)))

(defn parse-capabilities
  "Parse a capabilities list into a set, warning on unknown names — a typo'd
   capability is otherwise accepted silently and every gated call it should
   have granted throws at runtime (usually swallowed into a silent no-op).
   In Squint, keywords compile to strings, so strings pass through.
   nil returns #{:all} (grant everything)."
  ([caps] (parse-capabilities caps nil))
  ([caps ext-name]
   (if (nil? caps)
     #{:all}
     (let [s (set caps)]
       (doseq [cap s]
         (when-not (contains? all-capabilities cap)
           (d/warn (str "Unknown capability \"" cap "\""
                        (when ext-name (str " in extension " ext-name))
                        " — known: " (.join (.sort (js/Array.from all-capabilities)) ", ")))))
       s))))
