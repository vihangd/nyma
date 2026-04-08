(ns agent.permissions)

(def all-capabilities
  "All available extension capabilities."
  #{:tools :commands :shortcuts :events :messages :state :ui :middleware :exec
    :providers :model :session :flags :renderers :spawn :context})

(defn check
  "Check if a capability is granted. :all grants everything."
  [granted capability]
  (or (contains? granted :all) (contains? granted capability)))

(defn parse-capabilities
  "Parse a capabilities list into a set.
   In Squint, keywords compile to strings, so strings pass through.
   nil returns #{:all} (grant everything)."
  [caps]
  (if (nil? caps)
    #{:all}
    (set caps)))
