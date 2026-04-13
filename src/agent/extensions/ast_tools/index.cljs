(ns agent.extensions.ast-tools.index
  "AST-aware code search and transformation via ast-grep (sg binary).
   Registers ast_grep and ast_edit tools."
  (:require [clojure.string :as str]))

(def ^:private max-output-chars 5000)
(def ^:private max-matches 50)

(defn- sg-available? [api]
  (-> (.exec api "which" #js ["sg"])
      (.then (fn [result] (> (count (str (.-stdout result))) 0)))
      (.catch (fn [_] false))))

(defn- truncate-output [output]
  (let [lines (str/split-lines output)]
    (if (> (count lines) max-matches)
      (str (str/join "\n" (take max-matches lines))
           "\n\n(truncated, " (- (count lines) max-matches) " more lines)")
      (if (> (count output) max-output-chars)
        (str (subs output 0 max-output-chars)
             "\n\n(truncated, " (- (count output) max-output-chars) " more chars)")
        output))))

(defn- build-grep-args [pattern path language json?]
  (let [args ["run" "--pattern" pattern]]
    (cond-> args
      language (into ["--lang" language])
      json?    (conj "--json")
      path     (conj path)
      (not path) (conj "."))))

(defn- build-edit-args [pattern replacement path language dry-run?]
  (let [args ["run" "--pattern" pattern "--rewrite" replacement "--update-all"]]
    (cond-> args
      language  (into ["--lang" language])
      dry-run?  (conj "--dry-run")
      path      (conj path)
      (not path) (conj "."))))

(def ^:private not-installed-msg
  "ast-grep (sg) is not installed. Install: https://ast-grep.github.io/guide/quick-start.html")

(defn ^:async ast-grep-execute [api args]
  (let [available (js-await (sg-available? api))]
    (if-not available
      not-installed-msg
      (let [cmd-args (build-grep-args (.-pattern args) (.-path args) (.-language args) (.-json args))
            result   (js-await (.exec api "sg" (clj->js cmd-args)))
            output   (str (.-stdout result))]
        (if (empty? output)
          "No matches found."
          (truncate-output output))))))

(defn ^:async ast-edit-execute [api args]
  (let [available (js-await (sg-available? api))]
    (if-not available
      not-installed-msg
      (let [cmd-args (build-edit-args (.-pattern args) (.-replacement args)
                                      (.-path args) (.-language args) (.-dry_run args))
            result   (js-await (.exec api "sg" (clj->js cmd-args)))
            output   (str (.-stdout result) (.-stderr result))]
        (if (empty? output)
          "No matches found. Pattern did not match any code."
          (truncate-output output))))))

(defn ^:export default [api]
  ;; ast_grep tool
  (.registerTool api "ast_grep"
    #js {:description "Search code using AST patterns (syntax-aware, not regex). Uses ast-grep for structural matching."
         :parameters #js {:type "object"
                          :properties #js {:pattern    #js {:type "string" :description "ast-grep pattern (e.g., 'console.log($$$)')"}
                                           :path       #js {:type "string" :description "Directory or file to search (default: .)"}
                                           :language   #js {:type "string" :description "Language hint (js, ts, py, go, rust, etc.)"}
                                           :json       #js {:type "boolean" :description "Return structured JSON matches"}}
                          :required #js ["pattern"]}
         :display #js {:icon "🌳"
                       :formatArgs (fn [_name args] (str (.-pattern args) " in " (or (.-path args) ".")))
                       :formatResult (fn [result] (let [n (count (str/split-lines (str result)))]
                                                    (str n " lines")))}
         :execute (fn [args] (ast-grep-execute api args))})

  ;; ast_edit tool
  (.registerTool api "ast_edit"
    #js {:description "Apply structural code transformations using ast-grep rules. Rewrites matching AST patterns."
         :parameters #js {:type "object"
                          :properties #js {:pattern     #js {:type "string" :description "ast-grep pattern to match"}
                                           :replacement #js {:type "string" :description "Replacement pattern (use $VAR for captures)"}
                                           :path        #js {:type "string" :description "File or directory to transform"}
                                           :language    #js {:type "string" :description "Language hint"}
                                           :dry_run     #js {:type "boolean" :description "Preview changes without applying (default: false)"}}
                          :required #js ["pattern" "replacement" "path"]}
         :display #js {:icon "🌳"
                       :formatArgs (fn [_name args] (str (.-pattern args) " → " (.-replacement args)))
                       :formatResult (fn [result] (if (str/includes? (str result) "No matches")
                                                    "no matches" "applied"))}
         :execute (fn [args] (ast-edit-execute api args))})

  ;; Cleanup
  (fn []
    (.unregisterTool api "ast_grep")
    (.unregisterTool api "ast_edit")))
