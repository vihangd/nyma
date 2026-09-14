(ns dead-namespace-lint.test
  "Every namespace under src/ is required by some other src file, or is an
   entry point. Five abstractions sat in this tree with zero callers for
   months (agent.schema, agent.protocols, parse-command-line, two js-interop
   copies) — roadmap §3a's \"registry written but nobody reads it\", at the
   namespace level. A test file requiring a namespace does not count: that is
   how dead code stays green."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private src-root (path/resolve (js/process.cwd) "src"))

(defn- cljs-files [dir]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e)               (into acc (cljs-files p))
         (.endsWith (.-name e) ".cljs") (conj acc p)
         :else                          acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(defn ns-of
  "The declared namespace of a source, or nil."
  [source]
  (second (re-find #"\(ns\s+([a-zA-Z0-9.*+!\-_?]+)" source)))

(defn- ns->path-forms
  "How another file may name this namespace: dotted in a :require (with `-`
   or `_` — squint resolves both to the same file, and lsp_suite requires
   its siblings as `lsp-suite.lsp_manager`), or as the compiled path in a
   string require (`\"./modes/interactive.mjs\"`)."
  [ns-str]
  (let [p    (-> ns-str (str/replace "." "/") (str/replace "-" "_"))
        base (last (str/split p #"/"))]
    [ns-str (str/replace ns-str "-" "_") (str base ".mjs") (str base ".jsx")]))

(def entry-points
  "Loaded by something other than a require: the two binaries, the generated
   registry (read by resources/loader), the event-map generator script, and
   every extension index (the registry requires them, but by the compiled
   path in a generated file — counted here so a regenerated registry never
   flips the verdict)."
  #{"agent.cli" "gateway.entry" "agent.builtin-extensions" "agent.dev.event-map"})

(defn unreferenced
  "Namespaces in `sources` ({path source}) that no OTHER source mentions.
   Pure, for the self-test."
  [sources]
  (let [decls (keep (fn [[p s]] (when-let [n (ns-of s)] [p n])) sources)]
    (->> decls
         (remove (fn [[_ n]] (contains? entry-points n)))
         (remove (fn [[_ n]] (re-find #"\.index$" n)))
         (remove (fn [[p n]]
                   (let [forms (ns->path-forms n)]
                     (some (fn [[p2 s2]]
                             (and (not= p p2)
                                  (let [s2' (str/replace s2 "_" "-")]
                                    (some #(str/includes? s2' (str/replace % "_" "-")) forms))))
                           sources))))
         (map second)
         sort
         vec)))

(describe "every src namespace has a requirer" (fn []
                                                 (it "finds the tree"
                                                     (fn []
                                                       (-> (expect (> (count (cljs-files src-root)) 200)) (.toBe true))))

                                                 (it "no namespace is required by nothing"
                                                     (fn []
                                                       (let [sources (into {} (map (fn [f] [f (fs/readFileSync f "utf8")]) (cljs-files src-root)))]
                                                         (-> (expect (str/join ", " (unreferenced sources))) (.toBe "")))))

                                                 (it "detects the shape it was written for"
                                                     (fn []
                                                       (-> (expect (unreferenced {"a.cljs" "(ns agent.a (:require [agent.b]))"
                                                                                  "b.cljs" "(ns agent.b)"
                                                                                  "c.cljs" "(ns agent.c)"}))
                                                           (.toEqual #js ["agent.a" "agent.c"]))
        ;; string-path requires count
                                                       (-> (expect (unreferenced {"a.cljs" "(ns agent.a (:require [\"./ui/app.jsx\" :as app]))"
                                                                                  "b.cljs" "(ns agent.ui.app)"}))
                                                           (.toEqual #js ["agent.a"]))))))
