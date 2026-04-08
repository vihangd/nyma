(ns agent.utils.markdown
  (:require ["marked" :refer [Marked]]
            ["marked-terminal" :refer [markedTerminal]]))

(def ^:private renderer
  (Marked. (markedTerminal #js {:reflowText false})))

(def lexer
  "Access to the marked lexer for block-level tokenization."
  (.-lexer renderer))

(defn render-markdown
  "Convert markdown string to ANSI-styled terminal text.
   Returns the original string if rendering fails."
  [s]
  (if (or (nil? s) (= s ""))
    (or s "")
    (try
      (let [rendered (.parse renderer s)]
        ;; marked-terminal adds trailing newlines; trim them
        (.replace rendered #"\n+$" ""))
      (catch :default _e
        s))))
