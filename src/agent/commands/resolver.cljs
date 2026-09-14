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
          (second (first matches))))
      ;; Aliases: `/q`, `/quit`, `/?` were printed by /help and resolved by
      ;; nothing — the `:aliases` on a registration were never consulted.
      (let [by-alias (filterv (fn [[_ v]]
                                (some #(= (str %) (str cmd)) (or (:aliases v) [])))
                              (seq commands))]
        (when (= (count by-alias) 1)
          (second (first by-alias))))))

(defn split-skill-invocation
  "`/skill:<name> [args]` is the agentskills.io / Claude Code spelling of
   `/skill <name> [args]`. Only the literal `skill` prefix gets the colon
   form — `:` is not a delimiter anywhere else, so an extension command
   whose name contains one still resolves as typed. Returns [cmd rest-text]."
  [cmd rest-text]
  (if (and (string? cmd) (.startsWith cmd "skill:") (> (count cmd) 6))
    ["skill" (str (subs cmd 6) " " (or rest-text ""))]
    [cmd rest-text]))
