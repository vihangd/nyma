(ns test-util.text
  "String helpers shared by renderer tests.")

(defn strip-ansi
  "Drop SGR colour sequences so a test can assert on visible text."
  [s]
  (.replace (str s) (js/RegExp. (str (js/String.fromCharCode 27) "\\[[0-9;]*m") "g") ""))
