(ns agent.utils.template-args
  "Positional argument substitution for skill bodies and prompt templates.

   The vocabulary is the one agentskills.io and Claude Code slash commands
   share, so a SKILL.md or prompt written for either tool works unchanged:

     $ARGUMENTS / $@     every argument, joined by a single space
     $1 … $9             one argument, or empty when absent
     ${N:-default}       one argument, or `default` when absent

   `{:keep-when-empty? true}` leaves the text untouched when there are no
   arguments at all. Skill activation asks for it: a SKILL.md quoting a shell
   snippet (`echo $1`, `for f in \"$@\"`) is read far more often than it is
   invoked with arguments, and substituting empty strings silently broke the
   snippet the skill was written to show. A prompt template must NOT get it —
   `/review` with no arguments sent a literal `$ARGUMENTS` to the model.

   Pure: no filesystem, no agent."
  (:require [clojure.string :as str]))

(def ^:private placeholder
  ;; One pass so a substituted argument is never re-scanned for placeholders.
  (js/RegExp. "\\$\\{([1-9]):-([^}]*)\\}|\\$ARGUMENTS|\\$@|\\$([1-9])" "g"))

(defn substitute
  "Replace argument placeholders in `text` with the strings in `args`."
  [text args & [{:keys [keep-when-empty?]}]]
  (let [args (vec (or args []))
        text (str (or text ""))
        nth-arg (fn [n] (get args (dec (js/parseInt n 10))))]
    (if (and keep-when-empty? (empty? args))
      text
      (.replace text placeholder
                (fn [m dflt-n dflt n]
                  (cond
                    dflt-n (or (nth-arg dflt-n) dflt)
                    n      (or (nth-arg n) "")
                    :else  (str/join " " args)))))))
