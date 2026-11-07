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
      ((:script! srv) {:text "first answer"} {:text "second answer"})
      (let [{:keys [exit lines]} (js-await (run-json home ["--session-dir" dir "say one"]))
            types (mapv #(.-type %) lines)]
        (-> (expect exit) (.toBe 0))
        (-> (expect (.-type (first lines))) (.toBe "session"))
        (-> (expect (.-version (first lines))) (.toBe 3))
        (-> (expect (.-cwd (first lines))) (.toBe (fs/realpathSync home)))
        (doseq [t ["agent_start" "turn_start" "message_update" "turn_end" "agent_end" "agent_settled"]]
          (-> (expect (clj->js types)) (.toContain t)))
        (-> (expect (clj->js types)) (.not.toContain "response")))
      (let [{:keys [exit]} (js-await (run-json home ["--session-dir" dir "--continue" "say two"]))]
        (-> (expect exit) (.toBe 0))
        (-> (expect (js/JSON.stringify (clj->js (fms/request-messages srv 1)))) (.toContain "first answer")))
      (finally
        ((:stop srv))
        (fs/rmSync home #js {:recursive true :force true})))))

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
