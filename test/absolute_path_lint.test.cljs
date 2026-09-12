(ns absolute-path-lint.test
  "No source or test file may hardcode a path into somebody's home directory.

   Four test files pointed at `/Users/vihangd/projects/pers/nyma/dist/...`. They
   passed on that machine and failed on every other one, and nothing noticed
   until a CI workflow existed to run them somewhere else — at which point they
   accounted for 7 of 15 failures in the first run.

   The fix is `import.meta.dir`, not cwd: tests that chdir into a temp project
   would break a cwd-relative path just as silently.

   Scope note: this is about *committed source*. Fixtures under bench/results
   are recorded data and are not scanned."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private roots ["src" "test" "scripts"])

;; A path rooted in a user's home on any of the three platforms. `/home/runner`
;; is included on purpose: a CI path baked into source is the same bug wearing
;; different clothes.
(def ^:private home-path #"(/Users/[a-zA-Z0-9._-]+/|/home/[a-zA-Z0-9._-]+/|C:\\\\Users\\\\)")

(defn- source-files
  "Every .cljs/.mjs/.js under `dir`, recursively."
  [dir]
  (if-not (fs/existsSync dir)
    []
    (->> (vec (fs/readdirSync dir #js {:withFileTypes true}))
         (mapcat (fn [e]
                   (let [p (path/join dir (.-name e))]
                     (cond
                       (.isDirectory e)                     (source-files p)
                       (re-find #"\.(cljs|mjs|js)$" (.-name e)) [p]
                       :else                                []))))
         vec)))

;; A home path is only a bug when it points back into THIS checkout. Tests use
;; invented absolute paths as data all the time — `/home/u/.nyma/sessions/x`,
;; `/Users/me/projects/deep/repo/...` — and those are values being formatted,
;; not files anything opens. What broke was specifically a path to this repo's
;; own `dist/`, so that is what is banned.
(def ^:private into-this-repo #"(/dist/|/nyma/)")

(defn offenders
  "[[file line-number line] …] for every hardcoded path into this checkout."
  []
  (vec
   (for [f    (mapcat source-files roots)
         ;; This file quotes the offending shape on purpose, in its docstring
         ;; and in the test below.
         :when (not (str/includes? f "absolute_path_lint"))
         :let [lines (str/split-lines (fs/readFileSync f "utf8"))]
         [i l] (map-indexed vector lines)
         :when (and (re-find home-path l) (re-find into-this-repo l))]
     [f (inc i) (str/trim l)])))

(describe "no hardcoded home paths"
          (fn []
            (it "finds files to scan"
                (fn []
                  ;; Guard the guard: an empty scan agrees with everything.
                  (-> (expect (count (mapcat source-files roots))) (.toBeGreaterThan 100))))

            (it "detects one when it is there"
                (fn []
                  ;; And guard it again: the regex has to actually match the
                  ;; shape that caused this, or the check above is decorative.
                  (-> (expect (boolean (re-find home-path "/Users/someone/projects/x/dist/a.mjs")))
                      (.toBe true))
                  (-> (expect (boolean (re-find home-path "/home/runner/work/nyma/dist/a.mjs")))
                      (.toBe true))
                  (-> (expect (boolean (re-find home-path (path/join "dist" "agent" "x.mjs"))))
                      (.toBe false))
                  ;; …and an invented path used as test data is not a finding.
                  (-> (expect (boolean (re-find into-this-repo "/home/u/.nyma/sessions/123.jsonl")))
                      (.toBe false))))

            (it "no source file hardcodes one"
                (fn []
                  ;; Named, not counted, so a failure says which line to fix.
                  (-> (expect (vec (map (fn [[f n l]] (str f ":" n " " l)) (offenders))))
                      (.toEqual #js []))))))
