(ns dist-freshness.test
  "Tests run against dist/, and squint compiles incrementally without ever
   pruning. Two failure shapes followed from that:

   - `bun test dist` after editing a .cljs but before compiling tests the OLD
     code, silently. Only `bun run test` compiles first.
   - deleting a source leaves its compiled module and test in dist/, where the
     orphaned test still runs — `dist/protocols.test.mjs` failed for a whole
     afternoon after `src/agent/protocols.cljs` was removed, importing an export
     that no longer existed.

   This test is the guard: every compiled file has a source at least as old as
   it, and every source has a compiled file."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private root (js/process.cwd))

(defn- walk [dir ext]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e)               (into acc (walk p ext))
         (.endsWith (.-name e) ext)     (conj acc p)
         :else                          acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(defn- src->dist
  "src/agent/foo.cljs → dist/agent/foo.mjs (or .jsx when the source asks for it)."
  [src-path]
  (let [rel  (path/relative root src-path)
        base (-> rel
                 (str/replace #"^(src|test)/" "")
                 (str/replace #"\.cljs$" ""))
        ;; A reader-tag USE, not the two characters: this file names it in a string.
        jsx? (boolean (re-find #"#jsx\s*\[" (fs/readFileSync src-path "utf8")))]
    (path/join root "dist" (str base (if jsx? ".jsx" ".mjs")))))

(defn- dist->src [dist-path]
  (let [rel (-> (path/relative (path/join root "dist") dist-path)
                (str/replace #"\.(mjs|jsx)$" ".cljs"))]
    ;; src/ and test/ both compile into dist/ with their prefix stripped, so a
    ;; compiled file's source is under whichever of the two exists.
    (let [s (path/join root "src" rel)
          t (path/join root "test" rel)]
      (if (fs/existsSync s) s t))))

(defn- mtime [p] (.-mtimeMs (fs/statSync p)))

(describe "dist is fresh and has no orphans"
          (fn []
            (it "every source has a compiled file no older than itself"
                (fn []
                  (let [sources (concat (walk (path/join root "src") ".cljs")
                                        (walk (path/join root "test") ".cljs"))
                        stale   (->> sources
                                     (keep (fn [s]
                                             (let [d (src->dist s)]
                                               (cond
                                                 (not (fs/existsSync d)) (str (path/relative root s) " — not compiled")
                                                 (> (mtime s) (mtime d)) (str (path/relative root s) " — newer than dist")))))
                                     vec)]
                    ;; Guard the guard.
                    (-> (expect (> (count sources) 400)) (.toBe true))
                    (-> (expect (str/join "; " stale))
                        (.toBe "")))))

            (it "every compiled file still has a source"
                (fn []
                  (let [orphans (->> (concat (walk (path/join root "dist") ".mjs")
                                             (walk (path/join root "dist") ".jsx"))
                                     (remove (fn [d] (fs/existsSync (dist->src d))))
                                     (map #(path/relative root %))
                                     vec)]
                    (-> (expect (str/join "; " orphans))
                        (.toBe "")))))))
