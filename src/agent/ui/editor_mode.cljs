(ns agent.ui.editor-mode
  "Detect the user's editor 'mode' from its content.

   Mode conventions borrowed from oh-my-pi / Claude Code:
     ! prefix → :bash   (shell passthrough)
     $ prefix → :python (python expression)
     anything else → :normal (prompt goes to the LLM)

   Leading whitespace is ignored because users often type a space
   before the sigil. This module is pure — callers supply the theme
   and consume the returned border color.

   NOTE: detection only recolors the border — actually executing the
   command is a separate concern handled by do-submit in app.cljs
   (future work).")

(defn detect-mode
  "Return :bash, :python, or :normal based on the editor value.
   nil or empty text is always :normal."
  [text]
  (let [s (when text (.trimStart (str text)))]
    (cond
      (or (nil? s) (= s "")) :normal
      (.startsWith s "!")    :bash
      (.startsWith s "$")    :python
      :else                  :normal)))

(defn border-color-for-mode
  "Map a mode to the theme border hex. :normal falls back to the
   plain :border color; :bash uses :warning; :python uses :primary."
  [mode theme]
  (case mode
    :bash   (get-in theme [:colors :warning] "#e0af68")
    :python (get-in theme [:colors :primary] "#7aa2f7")
    (get-in theme [:colors :border] "#3b4261")))

(defn mode-label
  "Short string shown in the editor hint gutter when mode is not :normal.
   Returns nil for :normal so callers can conditionally render."
  [mode]
  (case mode
    :bash   "bash"
    :python "python"
    nil))
