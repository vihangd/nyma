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
                    (-> (expect (.includes out "\\")) (.toBe false)))))

            ;; Each case: the lines that must appear, ANSI stripped and trimmed,
            ;; in order — and never a stray backslash. Bun alone joins every one.
            (it "survive in lists, quotes and nested items; never leak a backslash"
                (fn []
                  (doseq [[md want]
                          [["- src/foo.cljs\n  renamed bar\n- b" ["* src/foo.cljs" "renamed bar" "* b"]]
                           ["- a\n  cont\n- b\n  end"            ["* a" "cont" "* b" "end"]]
                           ["1. step one\n   detail\n2. two"      ["1. step one" "detail" "2. two"]]
                           ["> q1\n> q2\n> q3"                   ["| q1" "| q2" "| q3"]]
                           ["- a\n  - b\n    more\n- c"          ["* a" "* b" "more" "* c"]]
                           ["- a\ncont lazy\n- b"                ["* a" "cont lazy" "* b"]]
                           ["- x\n\n  ```\n  a\n  b\n  ```"      ["* x" "a" "b"]]
                           ;; Bun breaks inside a code span itself; a backslash
                           ;; there would print.
                           ["use `a\nb` here\nnext"              ["use a" "b here" "next"]]]]
                    (let [prev (aget js/process.env "NO_COLOR")
                          _    (aset js/process.env "NO_COLOR" "1")
                          out  (try (:rendered (mb/incremental-render md nil {} 60))
                                    (finally (if (some? prev)
                                               (aset js/process.env "NO_COLOR" prev)
                                               (js-delete js/process.env "NO_COLOR"))))
                          got  (->> (.split out "\n") (map #(.trim %)) (filter seq) vec)]
                      (-> (expect #js [md (.includes out "\\")]) (.toEqual #js [md false]))
                      (-> (expect #js [md (clj->js (filterv (set want) got))])
                          (.toEqual #js [md (clj->js want)]))))))))

(defn- render-plain [md cols]
  (let [prev (aget js/process.env "NO_COLOR")]
    (aset js/process.env "NO_COLOR" "1")
    (try (:rendered (mb/incremental-render md nil {} cols))
         (finally (if (some? prev) (aset js/process.env "NO_COLOR" prev)
                      (js-delete js/process.env "NO_COLOR"))))))

(describe "code blocks: indent, not a frame"
          (fn []
            (it "Bun still frames code — the shape unframe-code expects"
                (fn []
                  (-> (expect (js/Bun.markdown.ansi "```js\nx\n```" #js {:colors false}))
                      (.toBe "+- js\n| x\n+-\n"))
                  (let [out (.replace (js/Bun.markdown.ansi "```js\nx\n```" #js {:colors true})
                                      (js/RegExp. (str ESC "\\[[0-9;]*m") "g") "")]
                    (-> (expect out) (.toBe "┌─ js\n│ x\n└─\n")))))

            (it "no frame glyph on any code line, colours on or off; label kept"
                (fn []
                  (doseq [plain? [true false]]
                    (let [md  "```js\nconst a = 1;\n\nlet b = 2;\n```"
                          out (if plain? (render-plain md 40)
                                  (:rendered (mb/incremental-render md nil {} 40)))
                          bare (.replace out (js/RegExp. (str ESC "\\[[0-9;]*m") "g") "")]
                      (-> (expect #js [plain? bare])
                          (.toEqual #js [plain? "  js\n  const a = 1;\n  \n  let b = 2;"]))))))

            (it "code that contains │ keeps it"
                (fn []
                  (-> (expect (render-plain "```\na │ b\n| c\n```" 40)) (.toBe "  a │ b\n  | c"))))

            (it "a fence inside a list item is unframed too"
                (fn []
                  (-> (expect (render-plain "- step\n\n  ```py\n  x = 1\n  ```" 40))
                      (.toBe "  * step\n\n      py\n      x = 1"))))

            (it "tables keep their borders"
                (fn []
                  (let [out (render-plain "| a | b |\n|---|---|\n| 1 | 2 |" 40)]
                    (-> (expect (.includes out "+---")) (.toBe true)))))))
