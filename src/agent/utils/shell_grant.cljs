(ns agent.utils.shell-grant
  "What one shell command is, and what an \"Allow always\" for it covers.

   Shared by the bash permission gate (bash_suite) and the prompt's
   \"Allow always\" (middleware), so the two cannot disagree about what counts
   as one simple command."
  (:require [clojure.string :as str]))

(def ^:private compound-re
  "Anything that makes a command more than one simple command: a separator,
   pipe, redirect, background `&`, newline, or command substitution."
  #"[;&|<>`\n]|\$\(")

(defn simple-command?
  "True when `cmd` is ONE simple command — the only shape a remembered grant
   can vouch for. A glob like `echo *` matched against a whole string also
   swallowed `echo x >> ~/.zshrc` and `echo hi; curl … | sh`."
  [cmd]
  (not (re-find compound-re (str cmd))))

(def subcommand-tools
  "Tools whose second word is the real verb: `git push` and `git status` are
   different permissions, `pytest -x` and `pytest tests/` are not."
  #{"git" "npm" "pnpm" "yarn" "bun" "bunx" "npx" "deno" "cargo" "go" "docker"
    "kubectl" "gh" "pip" "pip3" "uv" "poetry" "brew" "apt" "apt-get" "systemctl"
    "make" "dotnet" "mvn" "gradle" "terraform" "helm" "aws" "gcloud" "az"})

(defn grant-base
  "What \"Allow always\" for `cmd` remembers: `git push` for `git push origin
   main`, `pytest` for `pytest -x tests/`. nil for a compound command or an
   empty one — those never get an always-allow."
  [cmd]
  (let [c (str/trim (str cmd))]
    (when (and (seq c) (simple-command? c))
      (let [[w sub] (str/split c #"\s+")]
        (if (and (contains? subcommand-tools w) sub (not (str/starts-with? sub "-")))
          (str w " " sub)
          w)))))

(defn grant-matches?
  "Does the remembered `base` cover `cmd`? Only a simple command, and only on a
   word boundary: `git push` covers `git push --force` but not `git pushx`, and
   never `git push; rm -rf ~`."
  [base cmd]
  (let [c (str/trim (str cmd))
        b (str base)]
    (boolean (and (seq b) (simple-command? c)
                  (or (= c b) (str/starts-with? c (str b " ")))))))
