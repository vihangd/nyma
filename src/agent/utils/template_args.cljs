(ns agent.utils.template-args
  "Positional argument substitution for skill bodies and prompt templates.

   The vocabulary is the one agentskills.io and Claude Code slash commands
   share, so a SKILL.md or prompt written for either tool works unchanged:

     $ARGUMENTS / $@     every argument, joined by a single space
     $1 … $9             one argument, or empty when absent
     ${N:-default}       one argument, or `default` when absent

   Pure: no filesystem, no agent."
  (:require [clojure.string :as str]))

(def ^:private placeholder
  ;; One pass so a substituted argument is never re-scanned for placeholders.
  (js/RegExp. "\\$\\{([1-9]):-([^}]*)\\}|\\$ARGUMENTS|\\$@|\\$([1-9])" "g"))

(defn substitute
  "Replace argument placeholders in `text` with the strings in `args`."
  [text args]
  (let [args (vec (or args []))
        nth-arg (fn [n] (get args (dec (js/parseInt n 10))))]
    (.replace (str (or text "")) placeholder
              (fn [m dflt-n dflt n]
                (cond
                  dflt-n (or (nth-arg dflt-n) dflt)
                  n      (or (nth-arg n) "")
                  :else  (str/join " " args))))))
