(ns command-args-quoting.test
  "A quoted argument reaches a prompt template or skill body as ONE argument.
   `/greet \"Ada Lovelace\" x` is split by parse-command-args (the one splitter
   interactive mode uses) and filled in by template-args/substitute — this
   drives both, in that order, rather than either alone."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.parser :refer [parse-command-args]]
            [agent.utils.template-args :refer [substitute]]))

(defn- fill [template typed]
  (substitute template (vec (:args (parse-command-args typed)))))

(describe "$ARGUMENTS and $N through the real command-args path"
          (fn []
            (it "a double-quoted span is one positional argument"
                (fn []
                  (-> (expect (fill "Hi $1, then $2" "\"Ada Lovelace\" x"))
                      (.toBe "Hi Ada Lovelace, then x"))))

            (it "$ARGUMENTS joins the split arguments with single spaces"
                (fn []
                  (-> (expect (fill "<$ARGUMENTS>" "\"Ada Lovelace\"   x"))
                      (.toBe "<Ada Lovelace x>"))))

            (it "an absent $N is empty and ${N:-default} takes its default"
                (fn []
                  (-> (expect (fill "[$2] ${2:-nobody}" "\"Ada Lovelace\""))
                      (.toBe "[] nobody"))))

            (it "a --flag token is still part of $ARGUMENTS (commands hand-parse flags)"
                (fn []
                  (-> (expect (fill "$ARGUMENTS" "--dry-run \"a b\""))
                      (.toBe "--dry-run a b"))))

            (it "no arguments at all leaves the template untouched"
                (fn []
                  (-> (expect (fill "echo $1 \"$@\"" ""))
                      (.toBe "echo $1 \"$@\""))))))
