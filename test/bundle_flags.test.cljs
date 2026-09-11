(ns bundle-flags.test
  "`--bytecode` without `--format=esm` produces a binary that cannot load
   extensions.

   Bytecode implies CJS, and the extension loader imports .mjs files from disk
   that carry bare npm specifiers (`ai`, `@ai-sdk/openai`, `headroom-ai`). Out of
   a CJS-compiled binary those resolve against the binary's own module graph and
   fail — measured: every such extension logged `Cannot find package 'ai'`, while
   the same build with `--format=esm` logged none.

   The flag pairing is the whole fix and it is invisible in the output — a
   bytecode binary starts fine, prints help fine, and only falls over on the
   extensions. So it is pinned here rather than left to a comment."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private scripts
  (-> (fs/readFileSync (path/join (js/process.cwd) "package.json") "utf8")
      (js/JSON.parse)
      (aget "scripts")))

(defn bundle-scripts []
  (->> (js/Object.keys scripts)
       (filter (fn [k] (str/starts-with? k "bundle")))
       (map (fn [k] [k (aget scripts k)]))))

(describe "bundle scripts"
          (fn []
            (it "there are bundle scripts to check"
                (fn []
                  (-> (expect (count (bundle-scripts))) (.toBeGreaterThan 0))))

            (it "every --bytecode build also passes --format=esm"
                (fn []
                  (doseq [[name cmd] (bundle-scripts)]
                    (when (str/includes? cmd "--bytecode")
                      (-> (expect (str name ": " (str/includes? cmd "--format=esm")))
                          (.toBe (str name ": true")))))))

            (it "every compile target is a --compile build"
                (fn []
                  ;; Guard the guard: if the scripts stop compiling at all, the
                  ;; check above passes vacuously.
                  (doseq [[name cmd] (bundle-scripts)]
                    (-> (expect (str name ": " (str/includes? cmd "--compile")))
                        (.toBe (str name ": true"))))))))
