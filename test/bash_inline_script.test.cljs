(ns bash-inline-script-test
  "A script handed to an interpreter inline must be classified like the same
   script run directly.

   Found while reading shell-quote's changelog for a dependency bump, and
   verified against the live analyzer:

     echo 'rm -rf /' | bash        destructive
     bash -c 'rm -rf /'            SAFE
     bash <<< 'rm -rf /'           SAFE
     sh <<< \"rm -rf ~\"             SAFE
     bash <<EOF / rm -rf / / EOF   destructive — by accident

   Only the pipe form was caught: there is an `interpreter-commands` set, but
   only `check-pipe-to-interpreter` used it. The destructive patterns are
   raw-string regexes ending in `(\\s|$)`, so the closing quote after `/`
   defeated them.

   The heredoc row passed only because shell-quote 1.11 mis-tokenizes `<<` as
   two `<` operators and leaves the body as plain words. shell-quote 1.12 adds
   real heredoc support, which is exactly the upgrade that could have turned
   that row safe too — so the dependency bump is gated on this file.

   Severity: in default and accept-edits modes every bash call is still an
   `ask` prompt, so a human sees the command. This analyzer is the backstop for
   full-auto and for the block-destructive setting."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.bash-suite.security-analysis :as sa]
            [agent.extensions.bash-suite.shared :as shared]))

(def ^:private cfg (:security-analysis shared/default-config))

(defn- level [cmd] (:level (sa/classify-command cmd cfg)))

(describe "inline scripts are classified like direct ones"
          (fn []
            (it "the pipe form, which always worked — the control"
                (fn []
                  (-> (expect (level "echo 'rm -rf /' | bash")) (.toBe :destructive))))

            (it "interpreter -c '<script>'"
                (fn []
                  (doseq [c ["bash -c 'rm -rf /'"
                             "sh -c \"rm -rf ~\""
                             "zsh -c 'rm -rf /*'"
                             "bash -lc 'rm -rf /'"
                             ;; by path: the char before `sh` was `/`, not a separator
                             "/bin/sh -c 'rm -rf /'"
                             "/usr/bin/bash -c \"rm -rf ~\""
                             "/usr/bin/env bash -c 'rm -rf /'"]]
                    (-> (expect #js [c (level c)]) (.toEqual #js [c :destructive])))))

            (it "here-strings fed to an interpreter"
                (fn []
                  (doseq [c ["bash <<< 'rm -rf /'"
                             "sh <<< \"rm -rf ~\""
                             "bash <<<'rm -rf /'"]]
                    (-> (expect #js [c (level c)]) (.toEqual #js [c :destructive])))))

            (it "heredocs fed to an interpreter — the row shell-quote 1.12 could regress"
                (fn []
                  (doseq [c ["bash <<EOF\nrm -rf /\nEOF"
                             "sh <<'X'\nrm -rf ~\nX"
                             "bash <<-END\n\trm -rf /\nEND"]]
                    (-> (expect #js [c (level c)]) (.toEqual #js [c :destructive])))))

            (it "nested inside a subshell still counts"
                (fn []
                  (-> (expect (level "echo $(bash -c 'rm -rf /')")) (.toBe :destructive))))))

(describe "no false positives on benign inline use"
          (fn []
            (it "a harmless here-string or -c stays harmless"
                (fn []
        ;; The fix recurses into the script; it must not flag the interpreter
        ;; merely for being given one.
                  (-> (expect (level "cat <<< 'hello'")) (.not.toBe :destructive))
                  (-> (expect (level "bash -c 'echo hi'")) (.not.toBe :destructive))
                  (-> (expect (level "sh <<< 'ls -la'")) (.not.toBe :destructive))
                  (-> (expect (level "bash <<EOF\necho hi\nEOF")) (.not.toBe :destructive))))

            (it "a non-interpreter given -c is not treated as an interpreter"
                (fn []
        ;; `grep -c` counts; it does not execute its argument.
                  (-> (expect (level "grep -c 'rm -rf /' notes.txt")) (.not.toBe :destructive))))))

(describe "extract-inline-scripts"
          (fn []
            (it "pulls the script out of each form"
                (fn []
                  (-> (expect (clj->js (sa/extract-inline-scripts "bash -c 'rm -rf /'")))
                      (.toContain "rm -rf /"))
                  (-> (expect (clj->js (sa/extract-inline-scripts "bash <<< \"ls\"")))
                      (.toContain "ls"))
                  (-> (expect (clj->js (sa/extract-inline-scripts "sh <<EOF\nwhoami\nEOF")))
                      (.toContain "whoami"))))

            (it "returns nothing for a command with no inline script"
                (fn []
                  (-> (expect (count (sa/extract-inline-scripts "ls -la /tmp"))) (.toBe 0))
                  (-> (expect (count (sa/extract-inline-scripts "grep -c foo bar"))) (.toBe 0))))))
