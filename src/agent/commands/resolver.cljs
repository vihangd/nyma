(ns agent.commands.resolver
  "Command resolution with namespace-prefix fallback.
   When /agent doesn't match exactly, finds agent-shell__agent automatically.")

(defn resolve-command
  "Look up a command by name. If not found by exact name, try suffix match
   on 'namespace__cmd' entries. Returns the command entry or nil.
   Ambiguous matches (multiple extensions register the same short name) return nil."
  [commands cmd]
  (or (get commands cmd)
      (let [suffix   (str "__" cmd)
            matches  (filterv (fn [[k _]] (.endsWith k suffix)) (seq commands))]
        (when (= (count matches) 1)
          (second (first matches))))))
