(ns ast-tools.test
  "Load test for the ast-tools extension.

   Imports the compiled extension module and exercises its default export
   against a mocked API. This catches compile-level breakage (e.g. the
   `^:async` metadata on inline `fn` that Squint silently drops) that an
   inlined-helper unit test would never see."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.ast-tools.index :as ast-tools-ext]))

;;; ─── Mock API ───────────────────────────────────────────────

(defn- make-mock-api [exec-impl]
  (let [tools (atom {})]
    #js {:registerTool   (fn [name spec] (swap! tools assoc name spec))
         :unregisterTool (fn [name] (swap! tools dissoc name))
         :exec           exec-impl
         :_tools         tools}))

(defn- exec-sg-available []
  (fn [_bin _args]
    (js/Promise.resolve #js {:stdout "/usr/local/bin/sg\n" :stderr "" :code 0})))

(defn- exec-sg-missing []
  (fn [_bin _args]
    (js/Promise.resolve #js {:stdout "" :stderr "" :code 1})))

(defn- exec-returning [stdout]
  (let [calls (atom [])]
    [calls
     (fn [bin args]
       (swap! calls conj [bin args])
       (if (= bin "which")
         (js/Promise.resolve #js {:stdout "/usr/local/bin/sg\n"})
         (js/Promise.resolve #js {:stdout stdout :stderr ""})))]))

(defn- activate [api]
  ((.-default ast-tools-ext) api))

;;; ─── Registration ──────────────────────────────────────────

(describe "ast-tools:registration" (fn []
  (it "loads the compiled extension module without errors"
    (fn []
      (-> (expect (fn? (.-default ast-tools-ext))) (.toBe true))))

  (it "registers ast_grep and ast_edit tools on activate"
    (fn []
      (let [api (make-mock-api (exec-sg-available))
            _   (activate api)]
        (-> (expect (contains? @(.-_tools api) "ast_grep")) (.toBe true))
        (-> (expect (contains? @(.-_tools api) "ast_edit")) (.toBe true)))))

  (it "returns a deactivator that unregisters both tools"
    (fn []
      (let [api   (make-mock-api (exec-sg-available))
            deact (activate api)]
        (deact)
        (-> (expect (contains? @(.-_tools api) "ast_grep")) (.toBe false))
        (-> (expect (contains? @(.-_tools api) "ast_edit")) (.toBe false)))))

  (it "exposes an execute function on each tool"
    (fn []
      (let [api (make-mock-api (exec-sg-available))
            _   (activate api)]
        (-> (expect (fn? (.-execute (get @(.-_tools api) "ast_grep")))) (.toBe true))
        (-> (expect (fn? (.-execute (get @(.-_tools api) "ast_edit")))) (.toBe true)))))))

;;; ─── ast_grep.execute ──────────────────────────────────────
;;; These tests invoke the execute function returned by the real compiled
;;; module. They are the tests that would have caught the ^:async bug.

(describe "ast-tools:ast_grep.execute" (fn []
  (it "returns not-installed message when sg is missing"
    (fn []
      (let [api (make-mock-api (exec-sg-missing))
            _   (activate api)
            tool (get @(.-_tools api) "ast_grep")]
        (-> (.execute tool #js {:pattern "foo"})
            (.then (fn [result]
                     (-> (expect (str/includes? result "not installed")) (.toBe true))))))))

  (it "passes pattern through to sg and returns stdout"
    (fn []
      (let [[calls exec-fn] (exec-returning "src/foo.js:12:  console.log('hi')\n")
            api  (make-mock-api exec-fn)
            _    (activate api)
            tool (get @(.-_tools api) "ast_grep")]
        (-> (.execute tool #js {:pattern "console.log($$$)"})
            (.then (fn [result]
                     (-> (expect (str/includes? result "console.log")) (.toBe true))
                     ;; second call is the actual sg invocation
                     (let [[bin args] (second @calls)]
                       (-> (expect bin) (.toBe "sg"))
                       (-> (expect (.includes args "console.log($$$)")) (.toBeTruthy)))))))))

  (it "returns No matches found when sg stdout is empty"
    (fn []
      (let [[_ exec-fn] (exec-returning "")
            api  (make-mock-api exec-fn)
            _    (activate api)
            tool (get @(.-_tools api) "ast_grep")]
        (-> (.execute tool #js {:pattern "nothing"})
            (.then (fn [result]
                     (-> (expect (str/includes? result "No matches")) (.toBe true))))))))

  (it "forwards --lang when language arg provided"
    (fn []
      (let [[calls exec-fn] (exec-returning "match\n")
            api  (make-mock-api exec-fn)
            _    (activate api)
            tool (get @(.-_tools api) "ast_grep")]
        (-> (.execute tool #js {:pattern "x" :language "typescript"})
            (.then (fn [_]
                     (let [[_ args] (second @calls)]
                       (-> (expect (.includes args "--lang")) (.toBeTruthy))
                       (-> (expect (.includes args "typescript")) (.toBeTruthy)))))))))))

;;; ─── ast_edit.execute ──────────────────────────────────────

(describe "ast-tools:ast_edit.execute" (fn []
  (it "returns not-installed message when sg is missing"
    (fn []
      (let [api (make-mock-api (exec-sg-missing))
            _   (activate api)
            tool (get @(.-_tools api) "ast_edit")]
        (-> (.execute tool #js {:pattern "a" :replacement "b" :path "src/"})
            (.then (fn [result]
                     (-> (expect (str/includes? result "not installed")) (.toBe true))))))))

  (it "passes pattern, replacement and --update-all to sg"
    (fn []
      (let [[calls exec-fn] (exec-returning "changed 3 files\n")
            api  (make-mock-api exec-fn)
            _    (activate api)
            tool (get @(.-_tools api) "ast_edit")]
        (-> (.execute tool #js {:pattern "old($A)" :replacement "new($A)" :path "src/"})
            (.then (fn [_result]
                     (let [[bin args] (second @calls)]
                       (-> (expect bin) (.toBe "sg"))
                       (-> (expect (.includes args "--rewrite")) (.toBeTruthy))
                       (-> (expect (.includes args "new($A)")) (.toBeTruthy))
                       (-> (expect (.includes args "--update-all")) (.toBeTruthy)))))))))

  (it "adds --dry-run when dry_run true"
    (fn []
      (let [[calls exec-fn] (exec-returning "would change 1 file\n")
            api  (make-mock-api exec-fn)
            _    (activate api)
            tool (get @(.-_tools api) "ast_edit")]
        (-> (.execute tool #js {:pattern "a" :replacement "b" :path "." :dry_run true})
            (.then (fn [_]
                     (let [[_ args] (second @calls)]
                       (-> (expect (.includes args "--dry-run")) (.toBeTruthy)))))))))))
