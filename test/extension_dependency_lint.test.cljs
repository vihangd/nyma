(ns extension-dependency-lint.test
  "An extension that requires another extension's namespace must declare it
   in `dependsOn`, or the loader's topological sort cannot order them.

   `agent_shell/features/plan_capture.cljs` required
   `agent.extensions.model-roles.features.plan-mode` and
   `claude_hook_bridge/diagnostics.cljs` required
   `agent.extensions.token-suite.shared`, both from manifests saying
   `\"dependsOn\": []`. Squint hoists the import so the module resolves either
   way, but any activation-time state the dependency owns (its atoms, its
   registered flags) is only guaranteed to exist if the sort put it first.

   The lint reads the requires, not the prose."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private ext-root
  (path/resolve (js/process.cwd) "src" "agent" "extensions"))

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

(defn required-extensions
  "Namespace segments of every `agent.extensions.<x>` this source requires,
   as kebab-case strings, minus `self`."
  [source self]
  (->> (re-seq #"agent\.extensions\.([a-z0-9-]+)" source)
       (map second)
       (remove #(= % self))
       set))

(defn undeclared
  "Required extensions absent from `depends-on`. Pure, for the self-test."
  [required depends-on]
  (vec (sort (remove (set depends-on) required))))

(defn- extensions []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (keep (fn [e]
               (let [dir (path/join ext-root (.-name e))
                     mf  (path/join dir "extension.json")]
                 (when (fs/existsSync mf)
                   (let [json (js/JSON.parse (fs/readFileSync mf "utf8"))
                         self (.replace (.-name e) (js/RegExp. "_" "g") "-")]
                     {:name       (.-name e)
                      :self       self
                      :depends-on (set (map str (vec (or (aget json "dependsOn") #js []))))
                      :source     (->> (cljs-files dir)
                                       (map (fn [f] (fs/readFileSync f "utf8")))
                                       (str/join "\n"))})))))
       vec))

(describe "cross-extension requires are declared in dependsOn"
          (fn []
            (it "finds the built-in extensions"
                (fn []
                  (-> (expect (> (count (extensions)) 10)) (.toBe true))))

            (it "every required extension is in dependsOn"
                (fn []
                  (let [bad (->> (extensions)
                                 (keep (fn [e]
                                         (let [req (required-extensions (:source e) (:self e))
                                               u   (undeclared req (:depends-on e))]
                                           (when (seq u)
                                             (str (:name e) " requires " (str/join ", " u)
                                                  " but dependsOn is [" (str/join ", " (sort (:depends-on e))) "]")))))
                                 vec)]
                    (-> (expect (str/join "; " bad)) (.toBe "")))))

            (it "detects the regression it was written for"
                (fn []
                  (-> (expect (undeclared #{"model-roles"} #{}))
                      (.toEqual #js ["model-roles"]))
                  (-> (expect (undeclared #{"model-roles"} #{"model-roles"}))
                      (.toEqual #js []))
                  ;; Own namespace is never a dependency.
                  (-> (expect (required-extensions
                               "(:require [agent.extensions.agent-shell.shared :as s])"
                               "agent-shell"))
                      (.toEqual #{}))))))
