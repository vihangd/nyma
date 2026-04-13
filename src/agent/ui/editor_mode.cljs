(ns agent.ui.editor-mode
  "Detect the user's editor 'mode' from its content.

   Mode conventions, adapted from oh-my-pi / Claude Code:
     ! prefix → :bash  (shell passthrough via bash_suite)
     $ prefix → :bb    (Babashka expression eval, with form-start guard)
     anything else → :normal (prompt goes to the LLM)

   Leading whitespace is ignored because users often type a space
   before the sigil. This module is pure — callers supply the theme
   and consume the returned border color.

   Detection here only recolors the border + shows a label. The
   actual execution paths live in `agent.ui.editor-bash` and
   `agent.ui.editor-eval` and are dispatched from `do-submit` in
   app.cljs. Bash mode runs through the bash_suite middleware chain
   (security, env filter, cwd); eval mode runs `bb -e` directly.

   Note: the :bb branch reuses `editor-eval/parse-eval-input` so the
   form-start guard stays consistent — the border does NOT turn blue
   for `$PATH` / `$HOME` / `$foo`, which fall through as normal LLM
   prompts."
  (:require [agent.ui.editor-eval :as ee]))

(defn detect-mode
  "Return :bash, :bb, or :normal based on the editor value.
   nil or empty text is always :normal."
  [text]
  (let [s (when text (.trimStart (str text)))]
    (cond
      (or (nil? s) (= s ""))
      :normal

      (.startsWith s "!")
      :bash

      ;; :bb only when `parse-eval-input` confirms the form-start
      ;; guard. `$PATH`, `$HOME`, etc. return :not-eval and flow
      ;; through as :normal.
      (.startsWith s "$")
      (let [parsed (ee/parse-eval-input s)]
        (if (contains? #{:eval :eval-hidden} (:kind parsed))
          :bb
          :normal))

      :else
      :normal)))

(defn border-color-for-mode
  "Map a mode to the theme border hex. :normal falls back to the
   plain :border color; :bash uses :warning; :bb uses :primary."
  [mode theme]
  (case mode
    :bash (get-in theme [:colors :warning] "#e0af68")
    :bb   (get-in theme [:colors :primary] "#7aa2f7")
    (get-in theme [:colors :border] "#3b4261")))

(defn mode-label
  "Short string shown in the editor hint gutter when mode is not
   :normal. Returns nil for :normal so callers can conditionally
   render."
  [mode]
  (case mode
    :bash "bash"
    :bb   "bb"
    nil))
