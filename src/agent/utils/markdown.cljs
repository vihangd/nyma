(ns agent.utils.markdown
  "Markdown → ANSI, via Bun.markdown.ansi; `marked` only for its lexer.

   Bun.markdown.ansi is undocumented (Bun 1.4.2) but renders to a WIDTH:
   tables are box-drawn with cells wrapped inside the cell, where
   marked-terminal rendered width-blind and a later hard wrap chopped wide
   tables mid-row. Its options (`colors`, `columns`) and output shape are
   pinned by test/markdown_ansi_pin.test.cljs, so a Bun upgrade that renames
   them fails there instead of quietly ignoring NO_COLOR."
  (:require ["marked" :refer [Marked]]
            [agent.utils.ansi :as ansi]))

(def ^:private parser (Marked.))

(def lexer
  "Access to the marked lexer for block-level tokenization."
  (.-lexer parser))

(defn render-markdown
  "Convert a markdown string to ANSI-styled terminal text, laid out for
   `columns` when given. Returns the original string if rendering fails.

   Colour follows `ansi/color-depth`: none under NO_COLOR / TERM=dumb (which
   Bun does not check itself), 256-colour codes rewritten for a 16-colour
   terminal. OSC 8 hyperlinks stay off — pi-tui's width clamp could cut one
   mid-sequence and leave the terminal in link state."
  ([s] (render-markdown s nil))
  ([s columns]
   (if (or (nil? s) (= s ""))
     (or s "")
     (try
       (let [depth    (ansi/color-depth)
             opts     (if columns
                        #js {:colors (pos? depth) :columns columns}
                        #js {:colors (pos? depth)})
             rendered (js/Bun.markdown.ansi s opts)
             rendered (if (< 0 depth 256) (ansi/downgrade-256 rendered) rendered)]
         (.replace rendered #"\n+$" ""))
       (catch :default _e
         s)))))
