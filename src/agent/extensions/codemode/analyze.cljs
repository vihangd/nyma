(ns agent.extensions.codemode.analyze
  "What a script names, before it runs. Pure.

   The script is squint source, so it is also data — and the cheap reading of
   it is enough here: scan the text for `ns/fn` references, interop, and the
   squint traps AGENTS.md lists. A string that happens to look like a tool
   reference over-approximates, which only widens what the approval shows and
   what gets bound; it never lets a script reach a tool it does not name,
   because only the named tools are bound at all.

   Tool names map to script names generically:
     mcp__github__list_issues  → github/list-issues   (any `<kind>__<ns>__<fn>`)
     git-tools__status         → git-tools/status
     read                      → nyma/read
   and both sides are compared munged the way squint munges a symbol
   (`-` → `_`), since `(github/list-issues …)` compiles to
   `github.list_issues(…)`."
  (:require [clojure.string :as str]))

(defn munge-part
  "A name the way squint emits it as a JS identifier/property."
  [s]
  (-> (str s)
      (.replaceAll "-" "_")
      (.replaceAll "?" "_QMARK_")
      (.replaceAll "!" "_BANG_")))

(defn script-name
  "`[ns fn]` for a registered tool name."
  [tool-name]
  (let [parts (str/split (str tool-name) #"__")]
    (case (count parts)
      1 ["nyma" (first parts)]
      2 parts
      [(nth parts (- (count parts) 2)) (last parts)])))

(defn display-name
  "`github/list-issues` — how the catalog and the approval show a tool."
  [tool-name]
  (let [[n f] (script-name tool-name)]
    (str n "/" (.replaceAll (str f) "_" "-"))))

(defn binding-key
  "The munged `[ns fn]` a compiled script will look up."
  [tool-name]
  (mapv munge-part (script-name tool-name)))

(def ^:private ref-re
  ;; `ns/fn` where ns is not `js` and the slash is not inside a path-ish token
  ;; like `a/b/c` — the lookbehind keeps `foo/bar` in "x/foo/bar" out.
  (js/RegExp. "(?<![\\w\\-/.:])([A-Za-z][\\w\\-]*)/([A-Za-z_][\\w\\-?!]*)" "g"))

(defn references
  "Munged `[ns fn]` pairs the source mentions, `js/…` excluded."
  [src]
  (->> (js/Array.from (.matchAll (str src) ref-re))
       (map (fn [m] [(aget m 1) (aget m 2)]))
       (remove (fn [[n _]] (= "js" n)))
       (map (fn [[n f]] [(munge-part n) (munge-part f)]))
       distinct
       vec))

(defn resolve-tools
  "Registered tool names the source refers to, plus the references that match
   no tool (for a did-you-mean)."
  [src tool-names]
  (let [by-key (into {} (map (fn [t] [(str/join "/" (binding-key t)) t]) tool-names))
        refs   (references src)
        found  (keep (fn [r] (get by-key (str/join "/" r))) refs)
        ;; only refs whose ns is a known namespace count as misses: `str/join`
        ;; is clojure.string, not a missing tool
        nss    (set (map (fn [t] (first (binding-key t))) tool-names))
        misses (->> refs
                    (filter (fn [[n _ :as r]] (and (contains? nss n) (nil? (get by-key (str/join "/" r))))))
                    (map (fn [[n f]] (str n "/" (.replaceAll f "_" "-")))))]
    {:tools (vec (distinct found)) :unknown (vec misses)}))

(def ^:private unrestricted-res
  "Ways out of the bound-functions-only world. Any of them and the script is
   approved as what it can be: arbitrary code, like bash."
  [[#"(?<![\w\-])js/" "js/ interop"]
   [#"\(\s*\.-?[A-Za-z]" "method/field interop"]
   [#"\b(aget|aset|js\*|js-eval|eval|require|import)\b" "dynamic access"]
   [#"\(\s*ns\b" "ns form"]
   [#"\"(constructor|__proto__|prototype)\"" "prototype access"]])

(defn unrestricted
  "Why the source is not confined to its bound functions, or nil."
  [src]
  (seq (keep (fn [[re why]] (when (.test re (str src)) why)) unrestricted-res)))

(def ^:private traps
  "Clojure that squint compiles and then breaks at runtime (AGENTS.md)."
  [[#"js->clj" "js->clj does not exist in squint: the value is already a plain object"]
   [#"array-seq" "array-seq does not exist: pass the JS array directly"]
   [#"&\s*\{\s*:keys" "keyword-args destructuring does not work: take an explicit opts map"]
   ;; written as a char class so this file does not trip the hyphen lint
   [#"[.][-][a-z]+-[a-z]" "a dashed field access reads the underscored property: use (get x \"some-key\")"]])

(defn trap-errors [src]
  (vec (keep (fn [[re fix]] (when (.test re (str src)) fix)) traps)))

(defn analyze
  "{:tools [registered names] :unknown [refs to missing tools]
    :unrestricted [reasons] :traps [fixes]}"
  [src tool-names]
  (merge (resolve-tools src tool-names)
         {:unrestricted (vec (or (unrestricted src) []))
          :traps        (trap-errors src)}))

(defn approval-line
  "One line for the permission prompt: what the script will call."
  [{:keys [tools unrestricted]} category-of]
  (str (if (seq tools)
         (str/join " · " (map (fn [t] (str (display-name t) " (" (category-of t) ")")) tools))
         "no tools")
       (when (seq unrestricted)
         (str " · UNRESTRICTED: " (str/join ", " unrestricted)))))

(defn paren-report
  "Where the brackets stop balancing, for a reader error that only says
   `EOF while reading … at [1,1]`. Skips strings, regexes, comments and char
   literals. nil when balanced. Two of fourteen scripts in one benchmark run
   were a missing `)`."
  [src]
  (let [s (str src) n (count s)
        pairs {")" "(" "]" "[" "}" "{"}]
    (loop [i 0 line 1 col 1 stack [] in-str? false]
      (if (>= i n)
        (when (seq stack)
          (let [{:keys [ch line col]} (peek stack)]
            (str (count stack) " unclosed — innermost `" ch "` opened at line " line ", col " col)))
        (let [c (nth s i)
              nl? (= c "\n")
              [l2 c2] (if nl? [(inc line) 1] [line (inc col)])]
          (cond
            in-str? (cond (= c "\\") (recur (+ i 2) line (+ col 2) stack true)
                          (= c "\"") (recur (inc i) l2 c2 stack false)
                          :else (recur (inc i) l2 c2 stack true))
            (= c "\"") (recur (inc i) l2 c2 stack true)
            (= c ";") (let [j (let [k (.indexOf s "\n" i)] (if (neg? k) n k))]
                        (recur j line col stack false))
            (= c "\\") (recur (+ i 2) line (+ col 2) stack false)
            (contains? #{"(" "[" "{"} c) (recur (inc i) l2 c2 (conj stack {:ch c :line line :col col}) false)
            (contains? pairs c)
            (if (= (get pairs c) (:ch (peek stack)))
              (recur (inc i) l2 c2 (pop stack) false)
              (str "unexpected `" c "` at line " line ", col " col
                   (when-let [o (peek stack)] (str " — innermost open is `" (:ch o) "` from line " (:line o) ", col " (:col o)))))
            :else (recur (inc i) l2 c2 stack false)))))))

(defn- code-mask
  "`s` with string, regex and comment contents blanked to spaces, same length,
   so bracket and token scans see only code."
  [s]
  (let [n (count s) out (js/Array.from s)]
    (loop [i 0 mode nil]
      (when (< i n)
        (let [c (nth s i)]
          (case mode
            :str     (cond (= c "\\") (do (aset out i " ") (when (< (inc i) n) (aset out (inc i) " ")) (recur (+ i 2) :str))
                           (= c "\"") (recur (inc i) nil)
                           :else (do (when (not= c "\n") (aset out i " ")) (recur (inc i) :str)))
            :comment (if (= c "\n") (recur (inc i) nil) (do (aset out i " ") (recur (inc i) :comment)))
            (cond (= c "\"") (recur (inc i) :str)
                  (= c ";")  (recur i :comment)
                  (= c "\\") (do (when (< (inc i) n) (aset out (inc i) " ")) (recur (+ i 2) nil))
                  :else (recur (inc i) nil))))))
    (.join out "")))

(defn- matching-close [masked open-idx]
  (loop [i open-idx depth 0]
    (when (< i (count masked))
      (let [c (nth masked i)]
        (cond (contains? #{"(" "[" "{"} c) (recur (inc i) (inc depth))
              (contains? #{")" "]" "}"} c) (if (= 1 depth) i (recur (inc i) (dec depth)))
              :else (recur (inc i) depth))))))

(defn asyncify
  "Mark every `(fn …)` / `#(…)` whose body awaits as `^:async`.

   `js-await` inside a plain fn compiles to `await` in a non-async function — a
   SyntaxError, reported as \"Unexpected identifier\" (or, from the bundled
   binary, as a dump of its source). A benchmark model hit it with
   `(mapv (fn [id] (js-await (mail/get-attachment …))) ids)` and fell back to
   one script per attachment, eighteen of them. Async fns return promises,
   which the result's deep await resolves, so the map just works. A fn that
   only CONTAINS an awaiting fn is marked too; harmless for the same reason,
   except as a predicate — rare enough to accept."
  [src]
  (let [s      (str src)
        masked (code-mask s)
        re     (js/RegExp. "\\(fn[\\s\\[]|\\(fn\\s+[\\w\\-]+\\s*\\[|#\\(" "g")
        hits   (->> (js/Array.from (.matchAll masked re))
                    (map (fn [m] (let [i (.-index m)
                                       open (if (= "#" (nth masked i)) (inc i) i)]
                                   {:at i :close (matching-close masked open)})))
                    (filter (fn [{:keys [at close]}]
                              (and close
                                   (.includes (subs masked at close) "js-await")
                                   (not (.endsWith (.trimEnd (subs masked (max 0 (- at 12)) at)) "^:async")))))
                    (sort-by :at >))]
    (reduce (fn [acc {:keys [at]}] (str (subs acc 0 at) "^:async " (subs acc at))) s hits)))
