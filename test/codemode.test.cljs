(ns codemode.test
  "Code mode: a squint script calls tools as functions, in one round trip.
   Pure parts (analysis, catalog, OpenAPI) by value; the run end-to-end through
   the real Worker with a fake api whose executeTool records what it was asked."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.codemode.analyze :as an]
            [agent.extensions.codemode.catalog :as catalog]
            [agent.extensions.codemode.openapi :as openapi]
            [agent.extensions.codemode.index :as cm]))

(def ^:private tools
  ["mcp__github__list_issues" "mcp__github__get_issue" "mcp__lean-ctx__ctx_search"
   "read" "git-tools__status" "codemode__petstore__getPetById"])

;;; ─── analyze ───────────────────────────────────────────────────

(describe "codemode: names" (fn []
                              (it "maps registered names to script names generically"
                                  (fn []
                                    (-> (expect (an/display-name "mcp__github__list_issues")) (.toBe "github/list-issues"))
                                    (-> (expect (an/display-name "git-tools__status")) (.toBe "git-tools/status"))
                                    (-> (expect (an/display-name "read")) (.toBe "nyma/read"))
                                    (-> (expect (an/display-name "codemode__petstore__getPetById")) (.toBe "petstore/getPetById"))
        ;; the binding key is what squint emits for `lean-ctx/ctx-search`
                                    (-> (expect (clj->js (an/binding-key "mcp__lean-ctx__ctx_search"))) (.toEqual #js ["lean_ctx" "ctx_search"]))))

                              (it "resolves the tools a script names, in either spelling, and nothing else"
                                  (fn []
                                    (let [r (an/analyze "(let [xs (js-await (github/list-issues {:repo \"a/b\"}))]
                               (map #(github/get_issue {:number %}) xs)
                               (str/join \",\" [(lean-ctx/ctx-search {}) (nyma/read {:path \"x\"})]))"
                                                        tools)]
                                      (-> (expect (clj->js (sort (:tools r))))
                                          (.toEqual (clj->js (sort ["mcp__github__list_issues" "mcp__github__get_issue"
                                                                    "mcp__lean-ctx__ctx_search" "read"]))))
          ;; "a/b" is a string, `str/join` is clojure.string: neither is a miss
                                      (-> (expect (clj->js (:unknown r))) (.toEqual #js [])))))

                              (it "a reference into a known namespace with no such tool is a miss"
                                  (fn []
                                    (-> (expect (clj->js (:unknown (an/analyze "(github/delete-repo {})" tools))))
                                        (.toEqual #js ["github/delete-repo"]))))

                              (it "flags ways out of the bound functions"
                                  (fn []
                                    (doseq [src ["(js/Bun.spawn [\"ls\"])" "(.constructor x)" "(get f \"constructor\")"
                                                 "(aget x \"y\")" "(ns foo)"]]
                                      (-> (expect (pos? (count (:unrestricted (an/analyze src tools))))) (.toBe true)))
                                    (-> (expect (clj->js (:unrestricted (an/analyze "(github/list-issues {})" tools)))) (.toEqual #js []))))

                              (it "rejects the squint traps with a fix"
                                  (fn []
                                    (-> (expect (first (:traps (an/analyze "(js->clj x)" tools)))) (.toContain "already a plain object"))
                                    (-> (expect (count (:traps (an/analyze "(defn f [& {:keys [a]}] a)" tools)))) (.toBe 1))))

                              (it "the approval line names each tool with its category"
                                  (fn []
                                    (-> (expect (an/approval-line {:tools ["mcp__github__list_issues" "read"] :unrestricted []}
                                                                  (fn [t] (if (= t "read") "read" "write"))))
                                        (.toBe "github/list-issues (write) · nyma/read (read)"))))))

;;; ─── catalog ───────────────────────────────────────────────────

(defn- entry [n]
  {:name n :description (str "does " n)
   :schema #js {:type "object" :properties #js {:per_page #js {:type "integer"}}}})

(describe "codemode: asyncify" (fn []
  (it "marks awaiting fns, leaves the rest and strings alone"
      (fn []
        (-> (expect (an/asyncify "(mapv (fn [x] (js-await (f x))) xs)"))
            (.toBe "(mapv ^:async (fn [x] (js-await (f x))) xs)"))
        (-> (expect (an/asyncify "(map #(js-await (f %)) xs)")) (.toBe "(map ^:async #(js-await (f %)) xs)"))
        (-> (expect (an/asyncify "(map (fn [x] (inc x)) xs)")) (.toBe "(map (fn [x] (inc x)) xs)"))
        (-> (expect (an/asyncify "(str \"(fn [x] js-await\")")) (.toBe "(str \"(fn [x] js-await\")"))
        (-> (expect (an/asyncify "(map ^:async (fn [x] (js-await x)) xs)")) (.toBe "(map ^:async (fn [x] (js-await x)) xs)"))))))

(describe "codemode: catalog" (fn []
                                (it "keeps real argument names and lists every namespace"
                                    (fn []
                                      (let [out (catalog/render (map entry ["mcp__github__list_issues" "mcp__slack__post"]) 8000)]
                                        (-> (expect out) (.toContain "(github/list-issues {per_page?: int}) — does mcp__github__list_issues"))
                                        (-> (expect out) (.toContain "slack (1 tools):")))))

                                (it "fills the budget round-robin, so a big namespace cannot crowd out a small one"
                                    (fn []
                                      (let [big (map #(entry (str "mcp__big__tool_" %)) (range 40))
                                            out (catalog/render (concat big [(entry "mcp__small__one")]) 600)]
                                        (-> (expect out) (.toContain "small (1 tools):"))
                                        (-> (expect out) (.toContain "(small/one"))
                                        (-> (expect out) (.toMatch #"big \(40 tools, \d+ shown\)"))
                                        (-> (expect (<= (count out) 900)) (.toBe true)))))))

;;; ─── openapi ───────────────────────────────────────────────────

(def ^:private spec
  (clj->js {:paths {"/pets/{petId}" {:parameters [{:name "petId" :in "path" :required true
                                                   :schema {:type "integer"}}]
                                     :get {:operationId "getPetById" :summary "Find pet"
                                           :parameters [{:name "fields" :in "query" :schema {:type "string"}}]}}
                    "/pets" {:post {:operationId "addPet"
                                    :requestBody {:required true
                                                  :content {"application/json" {:schema {"$ref" "#/components/schemas/Pet"}}}}}}}
            :components {:schemas {:Pet {:type "object" :properties {:name {:type "string"}}}}}}))

(describe "codemode: openapi" (fn []
                                (it "one operation per method, path+query params and a resolved JSON body"
                                    (fn []
                                      (let [ops (openapi/operations spec)
                                            get-op (first (filter #(= "getPetById" (:name %)) ops))
                                            add-op (first (filter #(= "addPet" (:name %)) ops))]
                                        (-> (expect (count ops)) (.toBe 2))
                                        (-> (expect (js/JSON.stringify (aget (:schema get-op) "required"))) (.toBe "[\"petId\"]"))
                                        (-> (expect (aget (:schema add-op) "properties" "body" "properties" "name" "type")) (.toBe "string")))))

                                (it "builds the request, expanding ${ENV} only into headers"
                                    (fn []
                                      (let [op (first (filter #(= "getPetById" (:name %)) (openapi/operations spec)))
                                            {:keys [url init]} (openapi/request {:base-url "https://api.x/v1/"
                                                                                 :headers #js {:Authorization "Bearer ${TOK}"}}
                                                                                op #js {:petId 7 :fields "a b"} #js {:TOK "s3cret"})]
                                        (-> (expect url) (.toBe "https://api.x/v1/pets/7?fields=a+b"))
                                        (-> (expect (aget (:headers init) "Authorization")) (.toBe "Bearer s3cret")))))

                                (it "the tool's schema and description never carry the auth header"
                                    (fn []
                                      (let [op (first (openapi/operations spec))
                                            t  (openapi/tool-def {:base-url "https://x" :headers #js {:Authorization "Bearer ${TOK}"}} op)]
                                        (-> (expect (str (.-description t) (js/JSON.stringify (.-inputSchema t)))) (.not.toContain "TOK")))))))

;;; ─── running, through the real Worker ──────────────────────────

(defn- fake-api
  "getAllTools/getTool/executeTool over `impls` {tool-name (fn [args] result)}.
   `seen` collects [tool args opts]."
  [impls seen]
  #js {:getAllTools (fn [] (clj->js (keys impls)))
       :getTool     (fn [n] #js {:description n})
       :executeTool (fn [n args opts]
                      (swap! seen conj [n args opts])
                      (js/Promise.resolve
                       (try #js {:ok true :result ((get impls n) args)}
                            (catch :default e #js {:ok false :error (.-message e)
                                                   :denied (= "denied" (.-message e))}))))})

(def ^:private cfg (assoc cm/default-config :enabled true :timeout-ms 3000 :max-calls 5))

(defn ^:async run [impls code & [c]]
  (let [seen (atom [])
        out  (js-await (cm/execute-script (fake-api impls seen) (or c cfg) #js {:code code} nil))]
    {:out out :seen @seen}))

(def ^:private gh
  {"mcp__github__list_issues" (fn [_] (js/JSON.stringify #js [#js {:number 1 :title "a"} #js {:number 2 :title "b"}]))
   "mcp__github__get_issue"   (fn [a] (str "body of " (.-number a)))
   "mcp__github__boom"        (fn [_] (throw (js/Error. "denied")))
   "mcp__github__flaky"       (fn [_] "[ERROR] An error occurred invoking 'flaky'.")})

(defn ^:async test-chain []
  (let [{:keys [out seen]} (js-await (run gh "(let [xs (js-await (github/list-issues {}))]
                                               (println \"got\" (count xs))
                                               (map #(github/get-issue {:number (:number %)}) xs))"))]
    ;; JSON text arrived parsed; the un-awaited map was realized deeply
    (-> (expect out) (.toContain "\"body of 1\""))
    (-> (expect out) (.toContain "\"body of 2\""))
    (-> (expect out) (.toContain "logs:\ngot 2"))
    (-> (expect out) (.toContain "calls: github/list-issues ok, github/get-issue ok, github/get-issue ok"))
    ;; the tools the source names are preapproved, by their real names
    (-> (expect (js/JSON.stringify (.-preapproved (nth (first seen) 2))))
        (.toBe (js/JSON.stringify #js ["mcp__github__list_issues" "mcp__github__get_issue"])))))

(defn ^:async test-denied []
  (let [{:keys [out]} (js-await (run gh "(try (js-await (github/boom {})) (catch :default e (str \"caught \" (.-message e))))"))]
    (-> (expect out) (.toContain "caught denied"))
    (-> (expect out) (.toContain "github/boom denied"))))

(defn ^:async test-uncaught []
  (let [{:keys [out]} (js-await (run gh "(js-await (github/list-issues {})) (js-await (github/boom {}))"))]
    (-> (expect out) (.toContain "[Denied] denied (in github/boom)"))
    ;; the ledger says the first call already ran
    (-> (expect out) (.toContain "calls: github/list-issues ok, github/boom denied"))))

(defn ^:async test-call-limit []
  (let [{:keys [out seen]} (js-await (run gh "(doseq [i (range 10)] (js-await (github/get-issue {:number i})))"))]
    (-> (expect out) (.toContain "[CallLimit]"))
    (-> (expect (count seen)) (.toBe 5))))

(defn ^:async test-timeout []
  (let [t0 (js/Date.now)
        {:keys [out]} (js-await (run gh "(loop [] (recur))" (assoc cfg :timeout-ms 300)))]
    (-> (expect out) (.toContain "[Timeout]"))
    (-> (expect (< (- (js/Date.now) t0) 2500)) (.toBe true))))

(defn ^:async test-unknown []
  (let [{:keys [out seen]} (js-await (run gh "(github/list-isues {})"))]
    (-> (expect out) (.toContain "[UnknownTool] github/list-isues — did you mean"))
    (-> (expect (count seen)) (.toBe 0))))

(defn ^:async test-parse-error []
  (let [{:keys [out]} (js-await (run gh "(let [x 1] x"))]
    (-> (expect out) (.toContain "[ParseError]"))))

(defn ^:async test-big-output []
  (let [{:keys [out]} (js-await (run gh "(apply str (repeat 500 \"abcdefghij\"))"
                                     (assoc cfg :max-output-chars 100)))]
    (-> (expect out) (.toContain "…[truncated: 5000 chars; full result in "))))

(defn ^:async test-benchmark-traps []
  ;; each of these lost a benchmark model a script (glm-5.3-flash, mail task)
  (let [d (js-await (run gh "(def xs (js-await (github/list-issues {}))) (count xs)"))
        j (js-await (run gh "(println (js/JSON.stringify (map :number (js-await (github/list-issues {}))))) 1"))
        e (js-await (run gh "(js-await (github/flaky {}))"))
        n (js-await (run gh "(ns placeholder) nil"))]
    (-> (expect (:out d)) (.toContain "2"))
    (-> (expect (:out d)) (.not.toContain "Error"))
    (-> (expect (:out j)) (.toContain "logs:\n[1,2]"))
    (-> (expect (:out e)) (.toContain "[ToolFailure] An error occurred invoking 'flaky'."))
    (-> (expect (:out n)) (.toMatch #"^nil"))))

(defn ^:async test-scale-traps []
  ;; the second benchmark round (≈400 KB of mail): an error printed as
  ;; #<Error>, clojure.string unbound, and a missing paren reported at [1,1]
  (let [e (js-await (run gh "(try (js-await (github/boom {})) (catch :default e (println \"ERR\" e)))"))
        s (js-await (run gh "[(str/join \",\" [1 2]) (clojure.string/upper-case \"a\") (string/trim \" x \")]"))
        p (js-await (run gh "(let [x 1]\n  (println (inc x))"))]
    (-> (expect (:out e)) (.toContain "ERR #error \"Denied: denied (in github/boom)\""))
    (-> (expect (:out s)) (.toContain "\"1,2\""))
    (-> (expect (:out s)) (.toContain "\"A\""))
    (-> (expect (:out p)) (.toContain "1 unclosed — innermost `(` opened at line 1, col 1"))))

(defn ^:async test-await-in-fn []
  ;; the third benchmark round: js-await inside a mapv fn was a SyntaxError
  (let [a (js-await (run gh "(mapv (fn [n] (js-await (github/get-issue {:number n}))) [1 2])"))
        b (js-await (run gh "(map #(str \"x\" (js-await (github/get-issue {:number %}))) [3])"))]
    (-> (expect (:out a)) (.toContain "\"body of 1\""))
    (-> (expect (:out a)) (.toContain "\"body of 2\""))
    (-> (expect (:out b)) (.toContain "\"xbody of 3\""))
    (-> (expect (:out (js-await (run gh "(let [xs (github/list-issues {})] (vec xs))"))))
        (.toContain "(js-await (ns/fn …)) before using its value"))))

(defn ^:async test-settle []
  ;; a script that throws while slow parallel calls run reports them done
  (let [slow {"mcp__github__slow" (fn [_] "late")}
        api  #js {:getAllTools (fn [] #js ["mcp__github__slow"])
                  :getTool (fn [_] #js {})
                  :executeTool (fn [_ _ _] (js/Promise. (fn [r] (js/setTimeout #(r #js {:ok true :result "late"}) 200))))}
        out  (js-await (cm/execute-script api cfg #js {:code "(github/slow {}) (github/slow {}) (throw (js/Error. \"early\"))"} nil))]
    (-> (expect out) (.toContain "early"))
    (-> (expect out) (.toContain "calls: github/slow ok, github/slow ok"))
    slow))

(describe "codemode: running" (fn []
  (it "a failing script waits for its in-flight calls, so the ledger is true" test-settle)
  (it "js-await inside a fn works: the fn is made async" test-await-in-fn)
  (it "errors print their message, clojure.string is bound, parens are located" test-scale-traps)
  (it "def, JSON of a lazy seq, MCP [ERROR] text and a nil result behave" test-benchmark-traps)
                                (it "chains calls, parses JSON, realizes lazy parallel maps, keeps logs" test-chain)
                                (it "a denied call throws into the script, which can catch it" test-denied)
                                (it "an uncaught failure names the call and keeps the ledger" test-uncaught)
                                (it "stops at max-calls" test-call-limit)
                                (it "a script that never yields is killed at the timeout" test-timeout)
                                (it "an unknown tool fails before anything runs, with suggestions" test-unknown)
                                (it "a syntax error is a ParseError" test-parse-error)
                                (it "oversized output is cut and spilled to a private file" test-big-output)))
