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

(defn bundle-scripts
  "The scripts that actually invoke `bun build` — `bundle:all` only chains the
   others, so including it would fail every flag check on a script that carries
   no flags."
  []
  (->> (js/Object.keys scripts)
       (filter (fn [k] (str/starts-with? k "bundle")))
       (map (fn [k] [k (aget scripts k)]))
       (filter (fn [[_ cmd]] (str/includes? cmd "bun build")))))

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

            ;; Minification is size-only here — bytecode already caches the
            ;; parse, so startup did not move (40ms both ways) while the binary
            ;; went 94.6MB -> 89.3MB. Pinned so a target cannot quietly ship
            ;; 5MB heavier than its siblings.
            (it "every compile target is minified"
                (fn []
                  (doseq [[name cmd] (bundle-scripts)]
                    (when (str/includes? cmd "--compile")
                      (-> (expect (str name ": " (str/includes? cmd "--minify")))
                          (.toBe (str name ": true")))))))

            (it "bundle:all builds every target"
                (fn []
                  (let [all (get scripts "bundle:all")]
                    (-> (expect (some? all)) (.toBe true))
                    (doseq [[name _] (bundle-scripts)]
                      (when-not (= name "bundle:all")
                        (-> (expect (str name " in bundle:all: "
                                         (str/includes? (str all) (str "run " name))))
                            (.toBe (str name " in bundle:all: true"))))))))

            (it "every compile target is a --compile build"
                (fn []
                  ;; Guard the guard: if the scripts stop compiling at all, the
                  ;; check above passes vacuously.
                  (doseq [[name cmd] (bundle-scripts)]
                    (-> (expect (str name ": " (str/includes? cmd "--compile")))
                        (.toBe (str name ": true"))))))))
