(ns agent.permissions
  (:require [agent.debug :as d]))

(def all-capabilities
  "All available extension capabilities (must match extension_scope gating)."
  #{:tools :tools-override :commands :shortcuts :events :messages :state :ui
    :middleware :exec :providers :model :session :flags :spawn
    :context})

(def default-capabilities
  "What a manifest-less extension gets. Deliberately excludes the four that
   run code or rewire the pipeline; declare them in extension.json."
  (disj all-capabilities :exec :spawn :tools-override :middleware))

(defn check
  "Check if a capability is granted. :all grants everything."
  [granted capability]
  (or (contains? granted :all) (contains? granted capability)))

(defn parse-capabilities
  "Parse a capabilities list into a set, warning on unknown names — a typo'd
   capability is otherwise accepted silently and every gated call it should
   have granted throws at runtime (usually swallowed into a silent no-op).
   In Squint, keywords compile to strings, so strings pass through.
   nil (no manifest) grants `default-capabilities` — everything except the
   four that reach outside the sandbox — and warns once, so a manifest-less
   file in ~/.nyma/extensions is not silently handed exec/spawn."
  ([caps] (parse-capabilities caps nil))
  ([caps ext-name]
   (if (nil? caps)
     (do (d/warn (str "[nyma] Extension " (or ext-name "?")
                      " has no manifest; granting default capabilities (no exec/spawn/tools-override/middleware)"))
         default-capabilities)
     (let [s (set caps)]
       (doseq [cap s]
         (when-not (contains? all-capabilities cap)
           (d/warn (str "Unknown capability \"" cap "\""
                        (when ext-name (str " in extension " ext-name))
                        " — known: " (.join (.sort (js/Array.from all-capabilities)) ", ")))))
       s))))
