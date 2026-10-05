(ns markdown-ansi-pin-test
  "Bun.markdown.ansi is undocumented. These pin what nyma relies on, so a Bun
   upgrade that renames an option or reshapes the output fails here — the
   failure that matters most being `colors` silently ignored, which would
   hand NO_COLOR users colour again.

   Also: the block cache is keyed on width. Rendering is width-aware now, so
   a cache built at one width served at another would show tables laid out
   for the old terminal."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.utils.ansi :as ansi]
            [agent.utils.markdown :as md]
            [agent.utils.markdown-blocks :as mb]))

(def ^:private ESC (js/String.fromCharCode 27))

(def ^:private table
  (str "| key | value |\n|---|---|\n"
       "| a | " (.repeat "a long cell that has to wrap " 4) "|\n"))

(defn- widest [s]
  (reduce max 0 (map ansi/string-width (.split s "\n"))))

(describe "Bun.markdown.ansi — the contract nyma uses"
          (fn []
            (it "colors:false emits no escape at all"
                (fn []
                  (let [out (js/Bun.markdown.ansi (str "# H\n\n**b** `c`\n\n```js\nlet x=1\n```\n\n" table)
                                                  #js {:colors false :columns 60})]
                    (-> (expect (.includes out ESC)) (.toBe false))
                    (-> (expect (.includes out "let x=1")) (.toBe true)))))

            (it "columns is honoured: a wide table fits, its cell wraps inside the box"
                (fn []
                  (doseq [cols [40 60 100]]
                    (let [out (js/Bun.markdown.ansi table #js {:colors true :columns cols})]
                      (-> (expect #js [cols (<= (widest out) cols)]) (.toEqual #js [cols true]))
                      ;; Box-drawn: every table line starts with a frame glyph.
                      (-> (expect (str/includes? out "┌")) (.toBe true))))))

            (it "colours by default, as 256-colour SGRs (what downgrade-256 rewrites)"
                (fn []
                  (let [out (js/Bun.markdown.ansi "`code`" #js {:colors true})]
                    (-> (expect (.includes out (str ESC "[38;5;"))) (.toBe true)))))

            (it "hyperlinks stay off unless asked — no OSC 8 by default"
                (fn []
                  (let [out (js/Bun.markdown.ansi "[l](http://x.y)" #js {:colors true})]
                    (-> (expect (.includes out (str ESC "]8;"))) (.toBe false)))))))

(describe "render-markdown"
          (fn []
            (it "under NO_COLOR, no escapes"
                (fn []
                  (let [env  js/process.env
                        prev (aget env "NO_COLOR")]
                    (aset env "NO_COLOR" "1")
                    (try
                      (-> (expect (.includes (md/render-markdown "**b** `c`" 40) ESC)) (.toBe false))
                      (finally
                        (if (some? prev) (aset env "NO_COLOR" prev) (js-delete env "NO_COLOR")))))))

            (it "trims the trailing newline"
                (fn []
                  (-> (expect (.endsWith (md/render-markdown "hi" 40) "\n")) (.toBe false))))))

(describe "downgrade-256"
          (fn []
            (it "rewrites 38;5 and 48;5 to 16-colour codes, background +10"
                (fn []
                  (let [out (ansi/downgrade-256 (str ESC "[48;5;236m" ESC "[38;5;215mx"))]
                    (-> (expect (.includes out ";5;")) (.toBe false))
                    ;; 236 is a dark grey, 215 an orange.
                    (-> (expect out) (.toBe (str ESC "[40m" ESC "[93mx"))))))

            (it "leaves other SGRs alone"
                (fn []
                  (let [s (str ESC "[1m" ESC "[38;2;1;2;3mx" ESC "[0m")]
                    (-> (expect (ansi/downgrade-256 s)) (.toBe s)))))))

(describe "block cache and width"
          (fn []
            (it "a cache built for another width is a full miss"
                (fn []
                  (let [calls (atom [])
                        r     {"paragraph" (fn [b] (swap! calls conj (:raw b)) (:raw b))}
                        c1    (mb/incremental-render "A\n\nB" nil r 80)
                        _     (reset! calls [])
                        _     (mb/incremental-render "A\n\nB" c1 r 80)
                        same  @calls
                        _     (reset! calls [])
                        _     (mb/incremental-render "A\n\nB" c1 r 40)]
                    ;; Same width: only the last block re-renders.
                    (-> (expect (clj->js same)) (.toEqual #js ["B"]))
                    ;; New width: every block does.
                    (-> (expect (clj->js @calls)) (.toEqual #js ["A" "B"])))))

            (it "the rendered table follows the width it was asked for"
                (fn []
                  (let [c1 (mb/incremental-render (str table "\n\nend") nil {} 100)
                        c2 (mb/incremental-render (str table "\n\nend") c1 {} 40)]
                    (-> (expect (<= (widest (:rendered c2)) 40)) (.toBe true)))))))

(describe "line breaks inside a paragraph"
          (fn []
            (it "survive — Bun alone would join them into one line"
                (fn []
                  (-> (expect (.includes (js/Bun.markdown.ansi "a\nb" #js {:colors false}) "a b"))
                      (.toBe true))
                  (let [out (:rendered (mb/incremental-render "one\ntwo\nthree" nil {} 40))
                        bare (.replace out (js/RegExp. (str ESC "\\[[0-9;]*m") "g") "")]
                    (-> (expect bare) (.toBe "one\ntwo\nthree")))))

            (it "leave code blocks alone — no backslashes added"
                (fn []
                  (let [out (:rendered (mb/incremental-render "```\na\nb\n```" nil {} 40))]
                    (-> (expect (.includes out "\\")) (.toBe false)))))))
