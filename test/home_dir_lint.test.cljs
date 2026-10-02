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

(defn homedir-call?
  "Does `src` call os.homedir directly? Pure, so the detector is testable.

   Extracted and widened. The original pattern matched only the three literal
   spellings `os/homedir`, `.homedir os` and `homedir()` — so it assumed the
   require alias is exactly `os`. It missed an aliased require
   (`[\"node:os\" :as node-os]` giving `.homedir node-os`) and a referred one
   (`:refer [homedir]` giving a bare call). It also had no self-test, so
   neither gap was visible."
  [src]
  (boolean (re-find #"\(os/homedir\)|\(\.homedir\s+[\w$.-]+\)|\bhomedir\s*\(\)|\(homedir\)" (str src))))

(describe "the detector itself" (fn []
  (it "fires on every spelling of a direct call"
      (fn []
        (doseq [c ["(os/homedir)"
                   "(.homedir os)"
                   "(.homedir node-os)"
                   "(homedir)"
                   "os.homedir()"]]
          (-> (expect #js [c (homedir-call? c)]) (.toEqual #js [c true])))))

  (it "does not fire on the sanctioned helper or on prose"
      (fn []
        (doseq [c ["(home/dir)"
                   ";; never call os.homedir directly"
                   "(def home-dir (home/dir))"]]
          (-> (expect #js [c (homedir-call? c)]) (.toEqual #js [c false])))))))

(describe "home dir lint" (fn []
  (it "scans a believable number of files"
      (fn []
        ;; Guard the guard: an empty file list makes every `for` below yield
        ;; nothing and all three assertions pass against no input.
        (-> (expect (count (cljs-files "src"))) (.toBeGreaterThan 200))))

  (it "src never calls os.homedir directly"
      (fn []
        (let [hits (for [f (cljs-files "src")
                         :when (not (.endsWith f "utils/home.cljs"))
                         :let [src (fs/readFileSync f "utf8")]
                         :when (homedir-call? src)]
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
          (-> (expect (vec hits)) (.toEqual #js [])))))
  (it "src never bakes a *-path / *-dir helper's result into a load-time def"
      (fn []
        ;; The indirect form of the same bug: `(def x (audit-path))`, where the
        ;; helper reads HOME per call but the def captured its answer once.
        (let [hits (for [f (cljs-files "src")
                         :let [src (fs/readFileSync f "utf8")]
                         :when (re-find #"(?m)^\(def (?:\^\S+ )?\S+\s*\n?\s*\([\w.\-/]*(?:-path|-dir|/dir|/path)\)" src)]
                     f)]
          (-> (expect (vec hits)) (.toEqual #js [])))))))
