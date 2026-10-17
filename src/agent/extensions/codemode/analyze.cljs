(ns agent.extensions.codemode.analyze
  "What a script names, before it runs. Pure.

   The script is squint source, so it is also data — and the cheap reading of
   it is enough here: scan the text for `ns/fn` references, interop, and the
   squint traps AGENTS.md lists. Scans run over the code with string, regex
   and comment contents blanked (`code-mask`), so `{:folder \"mail/x\"}` is not
   a call and a comment is not a trap. Only the named tools are bound at all,
   so a script reaches no tool it does not name.

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
      (.replaceAll "!" "_BANG_")
      (.replaceAll ">" "_GT_")
      (.replaceAll "<" "_LT_")
      (.replaceAll "=" "_EQ_")
      (.replaceAll "*" "_STAR_")
      (.replaceAll "+" "_PLUS_")))

(def helpers
  "Pure functions the worker binds beside the tools, munged, by namespace
   (`agent.utils.html`). Not tools: never approved, never a miss."
  {"nyma" #{"html__GT_text" "html__GT_md" "strip_quotes" "truncate"}})

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

(def ^:private ref-re
  ;; `ns/fn`; ns may be dotted (`clojure.string`, `globalThis.Bun`), and the
  ;; lookbehind keeps `b/c` in `a/b/c` out
  (js/RegExp. "(?<![\\w\\-/.:])([A-Za-z][\\w\\-.]*)/([A-Za-z_][\\w\\-?!.<>=*+]*)" "g"))

(defn- all-refs
  "[ns fn] pairs as written, from the masked code."
  [src]
  (->> (js/Array.from (.matchAll (code-mask (str src)) ref-re))
       (map (fn [m] [(aget m 1) (aget m 2)]))))

(defn references
  "Munged `[ns fn]` pairs the source mentions, `js/…` excluded."
  [src]
  (->> (all-refs src)
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
                    (filter (fn [[n f :as r]] (and (contains? nss n) (nil? (get by-key (str/join "/" r)))
                                                   (not (contains? (get helpers n) f)))))
                    (map (fn [[n f]] (str n "/" (.replaceAll f "_" "-")))))]
    {:tools (vec (distinct found)) :unknown (vec misses)}))

(def ^:private unrestricted-res
  "Ways out of the bound-functions-only world, matched against masked code.
   Any of them and the script is approved as what it can be: arbitrary code,
   like bash. `js/Promise.all` alone is let through: it is how a script awaits
   a batch, and it reaches nothing."
  [[#"(?<![\w\-.])js/(?!Promise\.all(?![\w.$\-]))" "js/ interop"]
   [#"\(\s*\.-?[A-Za-z]" "method/field interop"]
   [#"\b(aget|aset|js\*|js-eval|eval|require|import)\b" "dynamic access"]
   [#"\(\s*ns\b" "ns form"]
   ;; bare globals need no slash: `(get globalThis "Bun")`
   [#"(?<![\w\-/.:])(Bun|process|globalThis|global|self|window|Function|Reflect|Worker|fetch|WebSocket|Deno)(?![\w\-])"
    "global access"]])

(def ^:private plain-namespaces
  "Non-tool namespaces a script may name: clojure.string, bound in the worker."
  #{"str" "string" "clojure.string" "nyma" "saved"})

(defn unrestricted
  "Why the source is not confined to its bound functions, or nil. `tool-nss`
   are the munged tool namespaces. Any other `Ns/member` — `Bun/spawn`,
   `globalThis/fetch` — is a JS global, which squint emits as written.
   ponytail: a computed key (`(get x (str \"constr\" \"uctor\"))`) still gets
   past every check here; that is why the script asks like bash at all."
  ([src] (unrestricted src #{}))
  ([src tool-nss]
   (let [masked  (code-mask (str src))
         globals (->> (all-refs src)
                      (map first)
                      (remove #(or (= "js" %) (contains? plain-namespaces %)
                                   (contains? tool-nss (munge-part %))))
                      distinct)]
     (seq (concat (keep (fn [[re why]] (when (.test re masked) why)) unrestricted-res)
                  ;; a string key is how a prototype is reached, so raw source
                  (when (re-find #"\"(constructor|__proto__|prototype)\"" (str src)) ["prototype access"])
                  (when (seq globals) [(str "global access: " (str/join ", " (map #(str % "/") globals)))]))))))

(def ^:private traps
  "Clojure that squint compiles and then breaks at runtime (AGENTS.md)."
  [[#"js->clj" "js->clj does not exist in squint: the value is already a plain object"]
   [#"array-seq" "array-seq does not exist: pass the JS array directly"]
   [#"&\s*\{\s*:keys" "keyword-args destructuring does not work: take an explicit opts map"]
   ;; written as a char class so this file does not trip the hyphen lint
   [#"[.][-][a-z]+-[a-z]" "a dashed field access reads the underscored property: use (get x \"some-key\")"]])

(def ^:private sync-hofs
  "Fns that use what their fn argument returns as a value. An async fn hands
   them a promise — always truthy, never a number — so `filter` keeps every
   item and `reduce` threads a promise. `map`/`mapv`/`doseq` are fine: their
   promises are awaited at the end."
  "filter|filterv|remove|keep|some|every\\?|not-any\\?|reduce|sort-by|group-by|take-while|drop-while|partition-by|mapcat|min-key|max-key")

(defn- awaiting-hof-args
  "Names of `sync-hofs` handed an inline fn whose body awaits."
  [masked]
  (->> (js/Array.from (.matchAll masked (js/RegExp. (str "\\((" sync-hofs ")\\s+(\\(fn[\\s\\[]|#\\()") "g")))
       (keep (fn [m]
               (let [at   (+ (.-index m) (- (count (aget m 0)) (count (aget m 2))))
                     open (if (= "#" (nth masked at)) (inc at) at)
                     end  (matching-close masked open)]
                 (when (and end (.includes (subs masked at end) "js-await")) (aget m 1)))))
       distinct))

(defn trap-errors [src]
  (let [masked (code-mask (str src))]
    (vec (concat
          (keep (fn [[re fix]] (when (.test re masked) fix)) traps)
          (map (fn [f] (str "js-await inside a fn passed to " f " makes it return a promise, which " f
                            " uses as a value (always truthy): fetch first — "
                            "(let [rs (js-await (js/Promise.all (mapv #(ns/fn {…}) xs)))] (" f " … rs))"))
               (awaiting-hof-args masked))
          (when (re-find #"\(letfn\b[\s\S]*js-await" masked)
            ["letfn cannot be async: use (let [f (fn [x] …)] …)"])))))

(defn analyze
  "{:tools [registered names] :unknown [refs to missing tools]
    :unrestricted [reasons] :traps [fixes]}"
  [src tool-names]
  (merge (resolve-tools src tool-names)
         {:unrestricted (vec (or (unrestricted src (set (map #(first (binding-key %)) tool-names))) []))
          :traps        (trap-errors src)}))

(defn approval-line
  "One line for the permission prompt: what the script will call."
  [{:keys [tools unrestricted]} category-of]
  (str (if (seq tools)
         (str/join " · " (map (fn [t] (str (display-name t) " (" (category-of t) ")")) tools))
         "no tools")
       (when (seq unrestricted)
         (str " · UNRESTRICTED: " (str/join ", " unrestricted)))))

(defn strip-ns
  "`src` with a leading `(ns …)` form blanked (newlines kept, so error lines
   still match). Models write `(ns r (:require [clojure.string :as str]))` out
   of habit; everything it could name is already bound, and inside the async
   wrapper an ns form does not compile."
  [src]
  (let [s (str src) masked (code-mask s)
        m (re-find #"^\s*\(\s*ns\b" masked)]
    (if-not m
      s
      (let [open  (.indexOf masked "(")
            close (matching-close masked open)]
        (if-not close
          s
          (str (.replace (subs s 0 (inc close)) (js/RegExp. "[^\n]" "g") " ") (subs s (inc close))))))))

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

(defn asyncify
  "Mark every `(fn …)` / `#(…)` / `defn` whose body awaits as `^:async`.

   `js-await` inside a plain fn compiles to `await` in a non-async function — a
   SyntaxError, reported as \"Unexpected identifier\" (or, from the bundled
   binary, as a dump of its source). A benchmark model hit it with
   `(mapv (fn [id] (js-await (mail/get-attachment …))) ids)` and fell back to
   one script per attachment, eighteen of them. Async fns return promises,
   which the result's deep await resolves, so the map just works. As a
   predicate or reducer it would not — `trap-errors` refuses those first.
   `defn`/`defn-` take the tag before the name; `letfn` cannot take it."
  [src]
  (let [s      (str src)
        masked (code-mask s)
        re     (js/RegExp. "\\(fn[\\s\\[]|\\(fn\\s+[\\w\\-]+\\s*\\[|#\\(|\\(defn-?\\s+(?!\\^:async)" "g")
        hits   (->> (js/Array.from (.matchAll masked re))
                    (map (fn [m] (let [i     (.-index m)
                                       defn? (.startsWith (aget m 0) "(defn")
                                       open  (if (= "#" (nth masked i)) (inc i) i)]
                                   {:at    (if defn? (+ i (count (aget m 0))) i)
                                    :close (matching-close masked open)})))
                    (filter (fn [{:keys [at close]}]
                              (and close
                                   (.includes (subs masked at close) "js-await")
                                   (not (.endsWith (.trimEnd (subs masked (max 0 (- at 12)) at)) "^:async")))))
                    (sort-by :at >))]
    (reduce (fn [acc {:keys [at]}] (str (subs acc 0 at) "^:async " (subs acc at))) s hits)))
