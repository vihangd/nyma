(ns agent.ui.status-line-separators
  "Separator glyph tables for status line segment groups.")

(def separators
  "Each style returns {:left :right :middle} where :middle is rendered
   between adjacent segments in the same group.

   * :powerline     — full powerline with solid end caps
   * :powerline-thin — thin middle, no end caps (default)
   * :slash         — ASCII '/'
   * :ascii         — ASCII '|'
   * :none          — spaces only"
  {:powerline      {:left "\ue0b0" :right "\ue0b2" :middle " \ue0b1 "}
   :powerline-thin {:left ""        :right ""        :middle "  "}
   :slash          {:left ""        :right ""        :middle " / "}
   :ascii          {:left ""        :right ""        :middle " | "}
   :none           {:left ""        :right ""        :middle "  "}})

(defn get-separator
  "Look up a separator style, falling back to :powerline-thin."
  [style]
  (or (get separators (or style :powerline-thin))
      (get separators :powerline-thin)))
