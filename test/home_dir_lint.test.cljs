(ns home-dir-lint.test
  "No `os.homedir()` outside agent.utils.home. Bun resolves it once at process
   start, so a path built from it ignores a HOME set later — which is exactly
   what the test preload does to keep the suite out of the developer's real
   ~/.nyma. `home/dir` reads process.env.HOME first."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- cljs-files [dir]
  (mapcat (fn [e]
            (let [p (path/join dir (.-name e))]
              (cond (.isDirectory e) (cljs-files p)
                    (.endsWith (.-name e) ".cljs") [p]
                    :else [])))
          (fs/readdirSync dir #js {:withFileTypes true})))

(describe "home dir lint" (fn []
  (it "src never calls os.homedir directly"
      (fn []
        (let [hits (for [f (cljs-files "src")
                         :when (not (.endsWith f "utils/home.cljs"))
                         :let [src (fs/readFileSync f "utf8")]
                         :when (re-find #"\(os/homedir\)|\(\.homedir os\)|homedir\(\)" src)]
                     f)]
          (-> (expect (vec hits)) (.toEqual #js [])))))
  (it "src never bakes the home directory into a load-time def"
      (fn []
        ;; A `(def x (… (home/dir) …))` captures HOME at import; a test that
        ;; sets HOME afterwards (and a future `nyma --home`) then reads one
        ;; directory and writes another.
        (let [hits (for [f (cljs-files "src")
                         :let [src (fs/readFileSync f "utf8")]
                         :when (re-find #"\(def [^\n]*\n?[^\n]*\(home/dir\)" src)]
                     f)]
          (-> (expect (vec hits)) (.toEqual #js [])))))))
