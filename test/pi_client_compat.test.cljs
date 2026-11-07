(ns pi-client-compat.test
  "A client built for pi spawns nyma with pi's argv and speaks pi's protocol.
   The strict parser exits 2 on one flag it does not know, before any JSON, so
   each client's real argv is held here."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.cli :refer [normalize-argv apply-tool-flags! find-session selected-provider]]
            [agent.utils.credentials :as credentials]
            [agent.resources.loader :as loader]
            [agent.sessions.pi-mirror :as mirror]
            [agent.utils.pi-shape :as shape]
            [test-util.fake-model-server :as fms]))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn ^:async ask-state
  "Spawn nyma with `argv`, ask get_state, close stdin. `{:exit :reply}`."
  [argv home]
  (let [proc (js/Bun.spawn (clj->js (concat ["bun" cli] argv))
                           #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "pipe"
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {:HOME home :ANTHROPIC_API_KEY "test"
                                                            :NYMA_NO_MODEL_DISCOVERY "1"})})]
    (.write (.-stdin proc) "{\"id\":\"s\",\"type\":\"get_state\"}\n")
    (.flush (.-stdin proc))
    (.end (.-stdin proc))
    (let [out  (js-await (.text (js/Response. (.-stdout proc))))
          exit (js-await (.-exited proc))]
      {:exit  exit
       :reply (some (fn [l] (let [o (try (js/JSON.parse l) (catch :default _ nil))]
                              (when (and o (= "s" (.-id o))) o)))
                    (.split out "\n"))})))

(defn- temp-home [] (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pi-compat-")))

(defn ^:async test-session-flags []
  (let [home (temp-home)
        dir  (path/join home "store")
        {:keys [exit reply]}
        (js-await (ask-state ["--mode" "pi-rpc" "--session-dir" dir "--session-id" "abc-1" "-n" "named"
                              "--no-themes" "--offline" "-ne" "-ns" "-np" "-nc"] home))]
    (-> (expect exit) (.toBe 0))
    (-> (expect (.-sessionFile (.-data reply))) (.toBe (path/join dir "abc-1.jsonl")))
    (-> (expect (.-sessionId (.-data reply))) (.toBe "abc-1"))
    (-> (expect (.-sessionName (.-data reply))) (.toBe "named"))
    (fs/rmSync home #js {:recursive true :force true})))

(defn ^:async test-bad-session-id []
  (let [home (temp-home)
        {:keys [exit]} (js-await (ask-state ["--mode" "pi-rpc" "--session-id" "../evil"] home))]
    (-> (expect exit) (.toBe 2))
    (fs/rmSync home #js {:recursive true :force true})))

(def ^:private clients
  "Each client's argv after the binary, from its source. Paths and models
   stand in for what it would really pass."
  {"pilish"       ["--mode" "rpc"]
   "pi-acp"       ["--mode" "rpc" "--no-themes"]
   "pi-vscode"    ["--mode" "rpc" "--no-session"]
   "pi.nvim"      ["--mode" "rpc" "--model" "anthropic/claude-sonnet-5-5"]
   "spynel"       ["--mode" "rpc" "--no-extensions" "--no-skills" "--no-prompt-templates"
                   "--no-themes" "--no-session"]
   "autocontext"  ["--mode" "rpc" "--model" "anthropic/claude-sonnet-5-5" "--no-session"]
   "openrig"      ["--mode" "rpc" "--session-dir" "store"]
   "cmux"         ["--mode" "rpc" "--session" "store/cmux.jsonl"]
   "Coffee-CLI"   ["--mode" "rpc" "--session-id" "coffee-1"]})

(defn ^:async test-client-argv [argv]
  (let [home (temp-home)
        {:keys [exit reply]} (js-await (ask-state argv home))]
    (-> (expect exit) (.toBe 0))
    (-> (expect (.-success reply)) (.toBe true))
    (-> (expect (.-command reply)) (.toBe "get_state"))
    (fs/rmSync home #js {:recursive true :force true})))

(describe "a pi client's spawn"
  (fn []
    (doseq [[client argv] clients]
      (it client #(test-client-argv argv) 30000))))

(defn ^:async test-parse-error []
  (let [home (temp-home)
        proc (js/Bun.spawn #js ["bun" cli "--mode" "rpc"]
                           #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "ignore"
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {:HOME home :ANTHROPIC_API_KEY "test"
                                                            :NYMA_NO_MODEL_DISCOVERY "1"})})]
    (.write (.-stdin proc) "{not json\n")
    (.flush (.-stdin proc))
    (.end (.-stdin proc))
    (let [out (js-await (.text (js/Response. (.-stdout proc))))
          r   (js/JSON.parse (first (filter seq (.split out "\n"))))]
      (-> (expect (.-command r)) (.toBe "parse"))
      (-> (expect (.-success r)) (.toBe false)))
    (fs/rmSync home #js {:recursive true :force true})))

(defn ^:async run-json [home argv]
  (let [proc (js/Bun.spawn (clj->js (concat ["bun" cli "--print" "--mode" "json"] argv
                                            ["--provider" "faketest" "--model" "m1"]))
                           #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "ignore"
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {:HOME home :NYMA_NO_MODEL_DISCOVERY "1"})})]
    (.end (.-stdin proc))
    (let [out (js-await (.text (js/Response. (.-stdout proc))))]
      {:exit  (js-await (.-exited proc))
       :lines (mapv #(js/JSON.parse %) (filter seq (.split out "\n")))})))

(defn ^:async test-json-stream []
  ;; Harbor's two calls: a run, then --continue in the same --session-dir.
  (let [srv  (fms/start!)
        home (fms/temp-home! (:base-url srv))
        dir  (path/join home "store")]
    (try
      ((:script! srv) {:tool-call {:name "read" :args {:path "x"}}} {:text "first answer"}
                      {:text "second answer"})
      (let [{:keys [exit lines]} (js-await (run-json home ["--session-dir" dir "say one"]))
            types (mapv #(.-type %) lines)]
        (-> (expect exit) (.toBe 0))
        (-> (expect (.-type (first lines))) (.toBe "session"))
        (-> (expect (.-version (first lines))) (.toBe 3))
        (-> (expect (.-cwd (first lines))) (.toBe (fs/realpathSync home)))
        (doseq [t ["agent_start" "turn_start" "message_update" "turn_end" "agent_end" "agent_settled"]]
          (-> (expect (clj->js types)) (.toContain t)))
        (let [sub-types (keep #(some-> % .-assistantMessageEvent .-type) lines)
              ends      (filter #(and (= "message_end" (.-type %)) (= "assistant" (.. % -message -role))) lines)
              tool-end  (first (filter #(= "tool_execution_end" (.-type %)) lines))]
          (-> (expect (clj->js sub-types)) (.toContain "toolcall_delta"))
          (-> (expect (.. (first lines) -type)) (.toBe "session"))
          (-> (expect (.. (nth lines 3) -message -role)) (.toBe "system"))
          (-> (expect (.. (last ends) -message -thinkingLevel)) (.toBe "off"))
          (-> (expect (.. (last ends) -message -durationMs)) (.toBeGreaterThanOrEqual 0))
          (-> (expect (.-durationMs tool-end)) (.toBeGreaterThanOrEqual 0)))
        (-> (expect (clj->js types)) (.not.toContain "response"))
        (-> (expect (clj->js (take 4 types)))
            (.toEqual #js ["session" "agent_start" "turn_start" "message_start"])))
      (let [{:keys [exit]} (js-await (run-json home ["--session-dir" dir "--continue" "say two"]))]
        (-> (expect exit) (.toBe 0))
        (-> (expect (js/JSON.stringify (clj->js (fms/request-messages srv 2)))) (.toContain "first answer")))
      (finally
        ((:stop srv))
        (fs/rmSync home #js {:recursive true :force true})))))

(describe "pi's directories"
  (fn []
    (it "skills load from .pi/skills and PI_CODING_AGENT_DIR"
        (fn []
          (let [d    (temp-home)
                env  (.-env js/process)
                prev (aget env "PI_CODING_AGENT_DIR")
                skill! (fn [dir n]
                         (fs/mkdirSync (path/join dir n) #js {:recursive true})
                         (fs/writeFileSync (path/join dir n "SKILL.md")
                                           (str "---\nname: " n "\ndescription: d\n---\nbody")))]
            (aset env "PI_CODING_AGENT_DIR" (path/join d "agent"))
            (try
              (skill! (path/join d "proj" ".pi" "skills") "proj-skill")
              (skill! (path/join d "agent" "skills") "agent-skill")
              (let [found (loader/discover-all-skills (path/join d "nohome") (path/join d "proj"))]
                (-> (expect (:scope (get found "proj-skill"))) (.toBe "project"))
                (-> (expect (:scope (get found "agent-skill"))) (.toBe "global")))
              (finally
                (if prev (aset env "PI_CODING_AGENT_DIR" prev) (js-delete env "PI_CODING_AGENT_DIR"))
                (fs/rmSync d #js {:recursive true :force true}))))))))

(defn- mirror-lines [dir]
  (let [sub  (path/join dir (first (fs/readdirSync dir)))
        file (path/join sub (first (fs/readdirSync sub)))]
    {:file file
     :lines (mapv #(js/JSON.parse %) (filter seq (.split (fs/readFileSync file "utf8") "\n")))}))

(defn ^:async test-mirror []
  (let [srv   (fms/start!)
        home  (fms/temp-home! (:base-url srv))
        store (path/join home "pi-sessions")
        proc-env #js {:PI_CODING_AGENT_SESSION_DIR store}
        cfg   (path/join home ".nyma" "settings.json")
        prev  (js/JSON.parse (fs/readFileSync cfg "utf8"))]
    (aset prev "pi" #js {:sessions true})
    (fs/writeFileSync cfg (js/JSON.stringify prev))
    (try
      ((:script! srv) {:tool-call {:name "read" :args {:path "nope.txt"}}} {:text "mirrored answer"})
      (let [proc (js/Bun.spawn (clj->js ["bun" cli "--print" "--mode" "json" "--provider" "faketest"
                                         "--model" "m1" "--session-id" "mir-1" "-n" "titled" "go"])
                               #js {:cwd home :stdin "pipe" :stdout "ignore" :stderr "ignore"
                                    :env (js/Object.assign #js {} js/process.env proc-env
                                                           #js {:HOME home :NYMA_NO_MODEL_DISCOVERY "1"})})]
        (.end (.-stdin proc))
        (js-await (.-exited proc)))
      ;; The nyma file sits beside pi's per-cwd folder.
      (let [nyma-file (path/join store "mir-1.jsonl")
            _ (fs/renameSync nyma-file (path/join home "kept.jsonl"))
            {:keys [file lines]} (mirror-lines store)
            head  (first lines)
            kinds (mapv #(or (some-> % .-message .-role) (.-type %)) (rest lines))
            final (last (filter #(= "assistant" (some-> % .-message .-role)) lines))]
        (-> (expect (.-type head)) (.toBe "session"))
        (-> (expect (.-version head)) (.toBe 3))
        (-> (expect (.-id head)) (.toBe "mir-1"))
        (doseq [l (filter seq (.split (fs/readFileSync file "utf8") "\n"))]
          (-> (expect (.startsWith l "{\"type\":")) (.toBe true)))
        (-> (expect (path/basename (path/dirname file)))
            (.toBe (mirror/cwd-dir (fs/realpathSync home))))
        (-> (expect (clj->js kinds)) (.toEqual #js ["session_info" "user" "assistant" "toolResult" "assistant"]))
        (-> (expect (js/JSON.stringify (.-content (.-message final)))) (.toContain "mirrored answer"))
        (-> (expect (.-isError (.-message (nth lines 4)))) (.toBe true))
        (-> (expect (.-provider (.-message final))) (.toBe "faketest"))
        (-> (expect (.-model (.-message final))) (.toBe "m1"))
        (-> (expect (.-totalTokens (.-usage (.-message final)))) (.toBeGreaterThan 0))
        ;; parents chain: every entry names the one before it
        (doseq [[a b] (partition 2 1 (rest lines))]
          (-> (expect (.-parentId b)) (.toBe (.-id a))))
        (fs/renameSync (path/join home "kept.jsonl") nyma-file)
        (-> (expect (mirror/nyma-session file)) (.toBe nyma-file)))
      (finally
        ((:stop srv))
        (fs/rmSync home #js {:recursive true :force true})))))

(describe "pi session mirror"
  (fn []
    (it "writes pi's format, usage included" test-mirror 60000)
    (it "a tool call becomes pi's assistant and toolResult pair"
        (fn []
          (let [[a r] (mirror/entry->lines {:role "tool_call" :id "e1" :timestamp 1000 :content "out"
                                            :metadata {:tool-call-id "c1" :tool-name "read" :args {:path "x"}}}
                                           "p0" {:provider "z"})]
            (-> (expect (:parentId a)) (.toBe "p0"))
            (-> (expect (:stopReason (:message a))) (.toBe "toolUse"))
            (-> (expect (:provider (:message a))) (.toBe "z"))
            (-> (expect (:name (first (:content (:message a))))) (.toBe "read"))
            (-> (expect (:parentId r)) (.toBe "e1"))
            (-> (expect (:role (:message r))) (.toBe "toolResult"))
            (-> (expect (:toolCallId (:message r))) (.toBe "c1")))))
    (it "prices usage, and sums it across a run's steps"
        (fn []
          (let [step #js {:usage #js {:inputTokens 1000000 :outputTokens 0}}
                u    (shape/usage step "anthropic/claude-sonnet-5-5")
                both (mirror/add-usage (mirror/add-usage nil u) u)]
            (-> (expect (:total (:cost u))) (.toBeGreaterThan 0))
            (-> (expect (:input both)) (.toBe 2000000))
            (-> (expect (:total (:cost both))) (.toBe (* 2 (:total (:cost u))))))))
    (it "names folders as pi does"
        (fn [] (-> (expect (mirror/cwd-dir "/a/b:c")) (.toBe "--a-b-c--"))))
    (it "is not read as pi's file unless it is one"
        (fn [] (-> (expect (mirror/nyma-session "/no/such.jsonl")) (.toBe "/no/such.jsonl"))))))

(defn ^:async test-pi-guard []
  ;; The guard overstory-style tools write: it must stop the tool, not advise.
  (let [srv  (fms/start!)
        home (fms/temp-home! (:base-url srv))]
    (fs/mkdirSync (path/join home ".pi" "extensions") #js {:recursive true})
    (fs/writeFileSync (path/join home ".pi" "extensions" "guard.ts")
                      (str "export default function (pi) {"
                           " pi.on('tool_call', (e) => {"
                           "  if (e.toolName === 'write' && e.input.path === 'no.txt')"
                           "   return { block: true, reason: 'guarded' };"
                           "  if (e.toolName === 'write') e.input.path = 'moved.txt';"
                           " }); }"))
    (try
      ((:script! srv) {:tool-call {:name "write" :args {:path "no.txt" :content "x"}}}
                      {:tool-call {:name "write" :args {:path "yes.txt" :content "x"}}}
                      {:text "done"})
      (let [{:keys [lines]} (js-await (run-json home ["--no-session" "--trust" "go"]))
            ends (filter #(= "tool_execution_end" (.-type %)) lines)]
        (-> (expect (fs/existsSync (path/join home "no.txt"))) (.toBe false))
        (-> (expect (js/JSON.stringify (.-result (first ends)))) (.toContain "guarded"))
        (-> (expect (fs/existsSync (path/join home "yes.txt"))) (.toBe false))
        (-> (expect (fs/existsSync (path/join home "moved.txt"))) (.toBe true)))
      (finally
        ((:stop srv))
        (fs/rmSync home #js {:recursive true :force true})))))

(describe "a pi extension in .pi/extensions"
  (fn [] (it "blocks a tool and edits its input" test-pi-guard 60000)))

(describe "--mode json"
  (fn [] (it "streams pi's events and resumes with --continue" test-json-stream 60000)))

(describe "--mode rpc"
  (fn [] (it "answers bad JSON on the wire" test-parse-error 30000)))

(describe "pi's flags"
  (fn []
    (it "spells out multi-letter shorts, and leaves what follows -- alone"
        (fn []
          (-> (expect (clj->js (vec (normalize-argv ["-ne" "--ext-x" "-nt" "--" "-ne"]))))
              (.toEqual #js ["--no-extensions" "--no-tools" "--" "-ne"]))))

    (it "exclude takes globs; no-builtin keeps extension tools"
        (fn []
          (let [active (atom #{"read" "bash" "mcp__a" "mine"})
                reg    {:get-active (fn [] (into {} (map (fn [k] [k 1]) @active)))
                        :set-active (fn [s] (reset! active s))}]
            (apply-tool-flags! reg {:exclude-tools "mcp__*, bash"} #{"read" "bash"})
            (-> (expect (vec (sort @active))) (.toEqual #js ["mine" "read"]))
            (apply-tool-flags! reg {:no-builtin-tools true} #{"read" "bash"})
            (-> (expect (vec @active)) (.toEqual #js ["mine"]))
            (apply-tool-flags! reg {} #{})
            (-> (expect (vec @active)) (.toEqual #js ["mine"]))
            (apply-tool-flags! reg {:no-tools true} #{})
            (-> (expect (count @active)) (.toBe 0)))))

    (it "--session finds a partial id; a path stays a path"
        (fn []
          (let [d (temp-home)]
            (fs/writeFileSync (path/join d "123-abcdef.jsonl") "")
            (-> (expect (find-session d "abcd")) (.toBe (path/join d "123-abcdef.jsonl")))
            (-> (expect (find-session d "/x/y.jsonl")) (.toBe "/x/y.jsonl"))
            (-> (expect (find-session d "nomatch")) (.toBe "nomatch"))
            (fs/rmSync d #js {:recursive true :force true}))))

    (it "--api-key reaches only the selected provider"
        (fn []
          (-> (expect (selected-provider {:model "zai/glm"} {:provider "x"})) (.toBe "zai"))
          (-> (expect (selected-provider {:provider "a" :model "zai/glm"} {})) (.toBe "a"))
          (credentials/set-run-key! "only-this" "k")
          (-> (expect (credentials/read-credential "only-this")) (.toBe "k"))
          (-> (expect (credentials/run-key "another")) (.toBeUndefined))))

    (it "session flags reach get_state" test-session-flags 30000)
    (it "a session id with a path in it is refused" test-bad-session-id 30000)))
