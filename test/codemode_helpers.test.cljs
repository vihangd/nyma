(ns codemode-helpers-test
  "Code mode, round 2: pure helpers a script calls inline, file helpers that
   go through the gate, typed returns (declared, structured, observed), and
   saved scripts the user keeps."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.trust :as trust]
            [agent.file-access :as fa]
            [agent.schema :as schema]
            [agent.utils.html :as html]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.tool-bridge :as bridge]
            [agent.extensions.agent-shell.features.mcp-discovery :as disc]
            [agent.extensions.codemode.analyze :as an]
            [agent.extensions.codemode.catalog :as catalog]
            [agent.extensions.codemode.files :as files]
            [agent.extensions.codemode.saved :as saved]
            [agent.extensions.codemode.index :as cm]
            [agent.extensions.codemode.openapi :as openapi]
            [test-util.pdf-fixture :refer [make-pdf]]))

(afterEach (fn [] (trust/decide! :trusted)))

(defn- tmp [] (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cm2-")))

(defn- in-dir [d f]
  (let [prev (js/process.cwd)]
    (js/process.chdir d)
    (-> (js/Promise.resolve (f)) (.finally #(js/process.chdir prev)))))

;;; ─── text helpers ──────────────────────────────────────────────

(def ^:private gmail
  "<div dir=\"ltr\">Please send the AML report by Friday.<br>Thanks, Renga</div><br>
   <div class=\"gmail_quote\"><div class=\"gmail_attr\">On Mon, Oct 6, 2026 at 9:12 AM Bob &lt;b@x.io&gt; wrote:<br></div>
   <blockquote class=\"gmail_quote\">Earlier text about FRM</blockquote></div>")

(def ^:private outlook
  "<html><body><p>Attached is the AFU file &amp; notes.</p>
   <div id=\"appendonsend\"></div><hr><div id=\"divRplyFwdMsg\"><b>From:</b> Bob<br><b>Sent:</b> Monday</div>
   <div>old thread</div></body></html>")

(describe "codemode: text helpers" (fn []
                                     (it "strip-quotes keeps the new part of Gmail and Outlook replies"
                                         (fn []
                                           (let [g (html/strip-quotes gmail) o (html/strip-quotes outlook)]
                                             (-> (expect g) (.toContain "Please send the AML report by Friday."))
                                             (-> (expect g) (.toContain "Thanks, Renga"))
                                             (-> (expect g) (.not.toContain "FRM"))
                                             (-> (expect g) (.not.toContain "wrote:"))
                                             (-> (expect o) (.toBe "Attached is the AFU file & notes.")))))
                                     (it "strip-quotes on plain text: On … wrote:, > lines, Original Message, signature"
                                         (fn []
                                           (-> (expect (html/strip-quotes "Yes, approved.\n\nOn Tue, Bob wrote:\n> can you approve?")) (.toBe "Yes, approved."))
                                           (-> (expect (html/strip-quotes "Fine\n> quoted\nstill mine")) (.toBe "Fine\nstill mine"))
                                           (-> (expect (html/strip-quotes "Hi\n-----Original Message-----\nFrom: x")) (.toBe "Hi"))
                                           (-> (expect (html/strip-quotes "Body\n-- \nRenga | Bank")) (.toBe "Body"))))
                                     (it "html->text: tables, entities, numeric entities"
                                         (fn []
                                           (-> (expect (html/html->text "<table><tr><td>a</td><td>b&amp;c</td></tr></table><p>&#8364;5 &lt;ok&gt;</p>"))
                                               (.toBe "a b&c\n€5 <ok>"))))
                                     (it "truncate marks what it cut"
                                         (fn [] (-> (expect (html/truncate "abcdef" 3)) (.toBe "abc…[+3 chars]"))))
                                     (it "nyma/ helpers are neither tools, misses nor unrestricted"
                                         (fn []
                                           (let [r (an/analyze "(nyma/truncate (nyma/strip-quotes (nyma/html->text x)) 10)" ["read"])]
                                             (-> (expect (clj->js (:tools r))) (.toEqual #js []))
                                             (-> (expect (clj->js (:unknown r))) (.toEqual #js []))
                                             (-> (expect (clj->js (:unrestricted r))) (.toEqual #js [])))))))

(defn- fake-api [impls]
  #js {:getAllTools (fn [] (clj->js (keys impls)))
       :getTool     (fn [n] #js {:description n})
       :executeTool (fn [n args _] (js/Promise.resolve #js {:ok true :result ((get impls n) args)}))})

(def ^:private cfg (assoc cm/default-config :enabled true :timeout-ms 5000))

(defn ^:async test-helpers-in-worker []
  (let [api (fake-api {"mcp__mail__get" (fn [_] gmail)})
        out (js-await (cm/execute-script api cfg
                                         #js {:code "(let [b (js-await (mail/get {}))]
                                                       {:t (nyma/strip-quotes b) :md (nyma/html->md \"<h1>Hi</h1>\")})"}
                                         nil))]
    (-> (expect out) (.toContain "Please send the AML report by Friday."))
    (-> (expect out) (.not.toContain "FRM"))
    (-> (expect out) (.toContain "# Hi"))))

(describe "codemode: helpers in a script" (fn []
                                            (it "strip-quotes and html->md run inline in the worker" test-helpers-in-worker)))

;;; ─── file helpers ──────────────────────────────────────────────

(defn ^:async test-save-file []
  (let [d (tmp)]
    (js-await
     (in-dir d
             ^:async (fn []
                       (fs/writeFileSync "att.bin" (js/Buffer.from #js [0 1 2 255]))
                       (let [a (js/JSON.parse (js-await (files/save-file #js {:path "out/a.bin" :from "att.bin"})))
                             b (js/JSON.parse (js-await (files/save-file #js {:path "out/b.bin" :base64 (.toString (js/Buffer.from #js [0 1 2 255]) "base64")})))]
                         (-> (expect (.-bytes a)) (.toBe 4))
                         (-> (expect (.equals (fs/readFileSync (.-path a)) (fs/readFileSync (.-path b)))) (.toBe true))
                         (-> (expect (.equals (fs/readFileSync (.-path a)) (fs/readFileSync "att.bin"))) (.toBe true))))))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-pdf-text []
  (let [d (tmp) f (path/join d "r.pdf")]
    (fs/writeFileSync f (make-pdf ["Hello PDF" "Second page"]))
    (let [r (js/JSON.parse (js-await (files/pdf-text #js {:path f})))]
      (-> (expect (.-text r)) (.toContain "Hello PDF"))
      (-> (expect (.-pages r)) (.toBe 2)))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-save-gated []
  ;; registered for real: write category, file-editing, and a .nymaignore deny holds
  (let [d (tmp)]
    (js-await
     (in-dir d
             ^:async (fn []
                       (fs/writeFileSync ".nymaignore" "secret.txt\n")
                       (let [a   (create-agent {:model #js {:modelId "test"} :system-prompt "s"})
                             api (create-extension-api a)]
                         (fa/register-access-check (:events a) d)
                         (doseq [[n t] files/tools] (.registerTool api (str "codemode__" n) t))
                         (-> (expect (cm/approval {:ex {:used []} :result {:tools ["codemode__nyma__save_file"] :unrestricted []}}))
                             (.toContain "nyma/save-file (write)"))
                         (let [r (js-await (.executeTool api "codemode__nyma__save_file" #js {:path "secret.txt" :text "hi"}
                                                         #js {:preapproved #js ["codemode__nyma__save_file"]}))]
                           (-> (expect (.-ok r)) (.toBe false))
                           (-> (expect (fs/existsSync "secret.txt")) (.toBe false)))
                         ;; nor can it copy an ignored file out
                         (fs/writeFileSync "secret.txt" "s")
                         (let [r (js-await (.executeTool api "codemode__nyma__save_file" #js {:path "out.txt" :from "secret.txt"}
                                                         #js {:preapproved #js ["codemode__nyma__save_file"]}))]
                           (-> (expect (.-ok r)) (.toBe false))
                           (-> (expect (fs/existsSync "out.txt")) (.toBe false)))))))
    (fs/rmSync d #js {:recursive true})))

(describe "codemode: file helpers" (fn []
                                     (it "save-file copies or decodes to identical bytes" test-save-file)
                                     (it "pdf-text reads a PDF" test-pdf-text)
                                     (it "save-file is a write tool, and .nymaignore denies it" test-save-gated)))

;;; ─── typed returns ─────────────────────────────────────────────

(def ^:private fixture (path/resolve (js/process.cwd) "test" "fixtures" "echo_mcp_server.mjs"))

(defn ^:async test-structured-mcp []
  (let [home (tmp)
        old  (.. js/process -env -HOME)
        c    (client/create (assoc (first (disc/object->array (clj->js {"echo" {:command "bun" :args [fixture]}})))
                                   :startup-timeout-ms 15000))
        a    (create-agent {:model #js {:modelId "test"} :system-prompt "s"})
        api  (create-extension-api a)]
    (aset (.-env js/process) "HOME" home)
    (try
      (js-await (client/start! c))
      (doseq [t (client/list-tools c)]
        ((:register (:tool-registry a)) (str "mcp__echo__" (:name t)) (bridge/build-tool-def "echo" c t)))
      ;; declared outputSchema reaches the catalog
      (let [t (.getTool api "mcp__echo__get_structured")]
        (-> (expect (catalog/line {:name "mcp__echo__get_structured" :schema #js {} :output (.-outputSchema t)}))
            (.toContain "→ {count: num, names: [str]}")))
      ;; a script gets structuredContent as data; the model-facing text is unchanged
      (let [r (js-await (.executeTool api "mcp__echo__get_structured" #js {}))]
        (-> (expect (js/JSON.stringify (.-result r))) (.toBe "{\"count\":2,\"names\":[\"a\",\"b\"]}")))
      ;; a saved blob arrives as {:text :files [{:path …}]}
      (let [r (js-await (.executeTool api "mcp__echo__get_file" #js {}))
            f (aget (.. r -result -files) 0)]
        (-> (expect (fs/readFileSync (.-path f) "utf8")) (.toBe "%PDF-1.4 hi"))
        (-> (expect (.-mime f)) (.toBe "application/pdf")))
      ;; a text-only tool is still a plain string
      (let [r (js-await (.executeTool api "mcp__echo__plain" #js {}))]
        (-> (expect (.-result r)) (.toBe "plain")))
      (finally
        (aset (.-env js/process) "HOME" old)
        (js-await (client/stop! c))
        (fs/rmSync home #js {:recursive true})))))

(defn- state-api
  "A fake api with an in-memory state store, one tool, and a mutable schema."
  [schema-ref impl]
  (let [m (atom {})]
    #js {:getAllTools (fn [] #js ["mcp__mail__search"])
         :getTool     (fn [_] #js {:description "search" :inputSchema @schema-ref})
         :state       #js {:get (fn [k] (get @m k)) :set (fn [k v] (swap! m assoc k v))}
         :executeTool (fn [_ _ _] (js/Promise.resolve #js {:ok true :result (impl)}))}))

(defn ^:async test-observed []
  (let [sch (atom #js {:type "object" :properties #js {:q #js {:type "string"}}})
        api (state-api sch (fn [] (js/JSON.stringify #js [#js {:id 1 :subject "s"}])))]
    (-> (expect (cm/observed-shape api "mcp__mail__search")) (.toBeFalsy))
    (js-await (cm/execute-script api cfg #js {:code "(mail/search {:q \"x\"})"} nil))
    (-> (expect (schema/signature (cm/observed-shape api "mcp__mail__search"))) (.toBe "[{id: int, subject: str}]"))
    (-> (expect (cm/prompt-block api cfg)) (.toContain "→ ~[{id: int, subject: str}]"))
    ;; the server changed its input schema: the old shape is not trusted
    (reset! sch #js {:type "object" :properties #js {:query #js {:type "string"}}})
    (-> (expect (cm/observed-shape api "mcp__mail__search")) (.toBeFalsy))))

(describe "codemode: typed returns" (fn []
                                      (it "MCP outputSchema, structuredContent and saved files reach a script as data" test-structured-mcp 30000)
                                      (it "an observed result shape is listed, and dropped when the tool's schema changes" test-observed)
                                      (it "OpenAPI responses become the operation's return type"
                                          (fn []
                                            (let [[op] (openapi/operations
                                                        #js {:paths #js {"/pet/{id}" #js {:get #js {:operationId "getPet"
                                                                                                    :parameters #js [#js {:name "id" :in "path"}]
                                                                                                    :responses #js {"200" #js {:content #js {"application/json" #js {:schema #js {:$ref "#/components/schemas/Pet"}}}}}}}}
                                                             :components #js {:schemas #js {:Pet #js {:type "object" :properties #js {:name #js {:type "string"}} :required #js ["name"]}}}})]
                                              (-> (expect (schema/signature (:output op))) (.toBe "{name: str}")))))))

;;; ─── saved scripts ─────────────────────────────────────────────

(defn ^:async test-saved-roundtrip []
  (let [d (tmp) home (tmp) old (.. js/process -env -HOME)]
    (aset (.-env js/process) "HOME" home)
    (try
      (js-await
       (in-dir d
               ^:async (fn []
                         (let [api (fake-api {"mcp__mail__search" (fn [a] (js/JSON.stringify #js [(str "hit:" (.-q a))]))
                                              "read" (fn [_] "x")})]
                           ;; nothing has run yet
                           (reset! cm/last-ok nil)
                           (-> (expect (cm/command api ["save" "find-mail"])) (.toContain "No script has run ok"))
                           (js-await (cm/execute-script api cfg #js {:code "(js-await (mail/search {:q (or (:q args) \"aml\")}))"} nil))
                           (-> (expect (cm/command api ["save" "find-mail" "mails" "by" "topic"])) (.toContain "Saved saved/find-mail"))
                           (-> (expect (fs/readFileSync ".nyma/scripts/find-mail.cljs" "utf8")) (.toContain ";; tools: mcp__mail__search"))
                           (-> (expect (cm/command api ["scripts"])) (.toContain "saved/find-mail  [project]  mails by topic"))
                           ;; called from a new script, with args; its tools are in the approval
                           (let [p (cm/prepare api "(saved/find-mail {:q \"frm\"})")]
                             (-> (expect (cm/approval p)) (.toContain "saved/find-mail → mail/search (")))
                           (-> (expect (js-await (cm/execute-script api cfg #js {:code "(saved/find-mail {:q \"frm\"})"} nil)))
                               (.toContain "hit:frm"))
                           (-> (expect (cm/prompt-block api cfg)) (.toContain "saved/find-mail → [str] — mails by topic"))
                           ;; a missing saved script is a miss with the list
                           (-> (expect (js-await (cm/execute-script api cfg #js {:code "(saved/nope {})"} nil)))
                               (.toContain "no saved script saved/nope — saved: find-mail"))
                           ;; a tool gone from the registry marks it broken
                           (-> (expect (cm/command (fake-api {"read" (fn [_] "x")}) ["scripts"])) (.toContain "[broken: missing mcp__mail__search]"))
                           ;; untrusted: project scripts do not load
                           (trust/decide! :untrusted)
                           (-> (expect (count (saved/load-all))) (.toBe 0))
                           (trust/decide! :trusted)
                           (-> (expect (cm/command api ["rm" "find-mail"])) (.toContain "Removed saved/find-mail"))
                           (-> (expect (count (saved/load-all))) (.toBe 0))))))
      (finally
        (aset (.-env js/process) "HOME" old)
        (fs/rmSync d #js {:recursive true})
        (fs/rmSync home #js {:recursive true})))))

(describe "codemode: saved scripts" (fn []
                                      (it "parse and render round-trip"
                                          (fn []
                                            (let [e (saved/parse "x" (saved/render {:description "d" :tools ["a" "b"] :returns "[str]" :body "(+ 1 2)"}))]
                                              (-> (expect (clj->js (select-keys e [:description :tools :returns :body])))
                                                  (.toEqual (clj->js {:description "d" :tools ["a" "b"] :returns "[str]" :body "(+ 1 2)"}))))))
                                      (it "a saved script calling another is refused"
                                          (fn []
                                            (let [ex (saved/expand "(saved/a {})" {"a" {:name "a" :body "(saved/b {})"} "b" {:name "b" :body "1"}})]
                                              (-> (expect (clj->js (:nested ex))) (.toEqual #js ["a"])))))
                                      (it "save, list, call with args, approve its tools, break, untrust, remove" test-saved-roundtrip)))
