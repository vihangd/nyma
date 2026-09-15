(ns extension-activation.test
  "Each thin extension activated ALONE through the real loader, then one
   handler driven end to end — the command or tool a user would actually hit.
   The helpers behind these extensions have unit tests; nothing exercised the
   wiring (scoped api, manifest, registry entry) until here. That gap is how
   /history and /stats shipped reading a store the scoped api never forwarded."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.builtin-extensions :refer [registry]]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.sessions.storage :refer [create-sqlite-store]]
            [agent.ui.status-line-segments :as status-segments]
            [agent.providers.registry :refer [resolve-api-key]]
            [agent.providers.oauth :as oauth]
            [tool-ctx-fixture :refer [mk-tool-ctx]]))

;;; ─── harness ─────────────────────────────────────────────────────────────

(defn- entry [ns] (first (filter #(= (:namespace %) ns) registry)))

(defn- ^:async load-alone
  "Agent + base api with one builtin activated. `prep` runs on the base api
   before activation (cli.cljs hangs the SQLite store there)."
  [ns & [prep]]
  (let [agent (create-agent {:model "test" :system-prompt "activation"})
        api   (create-extension-api agent)]
    (set! (.-extension-api agent) api)
    (when prep (prep api))
    {:agent agent :api api :loaded (js-await (discover-and-load [] api [(entry ns)]))}))

(defn- command [agent name] (:handler (resolve-command @(:commands agent) name)))
(defn- tool
  "An extension's tool lives in the registry as `<namespace>__<name>`."
  [agent name]
  (let [all ((:all (:tool-registry agent)))]
    (some (fn [[k t]] (when (or (= k name) (.endsWith (str k) (str "__" name))) t)) all)))
(defn- ui-ctx [notes] #js {:ui #js {:notify (fn [m & [_l]] (swap! notes conj m))}})
(defn- ^:async prompt-additions [agent]
  (let [r (js-await ((:emit-collect (:events agent)) "before_agent_start" #js {}))]
    (vec (or (get r "system-prompt-additions") []))))

(defn- tmpdir [tag] (fs/mkdtempSync (path/join (os/tmpdir) (str "nyma-act-" tag "-"))))

(defn- ^:async in-dir
  "Run `f` with the process cwd moved to a fresh temp dir — memory, handoff
   and refine resolve `.nyma/…` against cwd."
  [tag f]
  (let [dir  (tmpdir tag)
        prev (js/process.cwd)]
    (js/process.chdir dir)
    (try
      (js-await (f dir))
      (finally
        (js/process.chdir prev)
        (fs/rmSync dir #js {:recursive true :force true})))))

(defn- memory-store []
  (let [s (create-sqlite-store ":memory:")]
    ((:init-schema s))
    s))

;;; ─── the extensions ──────────────────────────────────────────────────────

(defn ^:async test-memory []
  (in-dir "memory"
          ^:async (fn [dir]
            (let [{:keys [agent loaded]} (js-await (load-alone "memory"))]
              (try
                (-> (expect (some? (tool agent "memory_forget"))) (.toBe true))
                (let [out (js-await ((.-execute (tool agent "memory_write"))
                                     #js {:key "build" :value "use bun"} (mk-tool-ctx)))
                      f   (path/join dir ".nyma" "memory" "MEMORY.md")]
                  (-> (expect out) (.toBe "Remembered \"build\"."))
                  (-> (expect (fs/readFileSync f "utf8")) (.toContain "## build\nuse bun"))
                  (-> (expect (first (js-await (prompt-additions agent)))) (.toContain "use bun")))
                (finally (js-await (deactivate-all loaded))))))))

(defn ^:async test-todos []
  (let [{:keys [agent loaded]} (js-await (load-alone "todos"))]
    (try
      (-> (expect (contains? (status-segments/segment-registry) "todos.progress")) (.toBe true))
      (js-await ((.-execute (tool agent "todo_write"))
                 #js {:todos #js [#js {:content "write test" :status "in_progress"}
                                  #js {:content "run it"}]}
                 (mk-tool-ctx)))
      (let [read (js-await ((.-execute (tool agent "todo_read")) #js {} (mk-tool-ctx)))]
        (-> (expect read) (.toContain "write test"))
        (-> (expect read) (.toContain "run it"))
        (-> (expect (first (js-await (prompt-additions agent)))) (.toContain "write test")))
      (finally (js-await (deactivate-all loaded))))))

(defn ^:async test-handoff []
  (in-dir "handoff"
          ^:async (fn [dir]
            (let [{:keys [agent loaded]} (js-await (load-alone "handoff"))
                  f     (path/join dir ".nyma" "handoff.md")
                  notes (atom [])]
              (try
                (fs/mkdirSync (path/dirname f) #js {:recursive true})
                (fs/writeFileSync f "Left off at the parser.")
                ;; A brief from the previous session seeds this one…
                (-> (expect (first (js-await (prompt-additions agent)))) (.toContain "Left off at the parser."))
                ;; …until /handoff clear removes it.
                ((command agent "handoff") ["clear"] (ui-ctx notes))
                (-> (expect (fs/existsSync f)) (.toBe false))
                (-> (expect (last @notes)) (.toBe "Handoff brief cleared."))
                (-> (expect (js-await (prompt-additions agent))) (.toEqual []))
                (finally (js-await (deactivate-all loaded))))))))

(defn ^:async test-checkpoints []
  (let [{:keys [agent loaded]} (js-await (load-alone "checkpoints"))
        emit  (:emit (:events agent))
        dir   (tmpdir "checkpoints")
        f     (path/join dir "f.txt")
        notes (atom [])]
    (try
      (fs/writeFileSync f "v1")
      (emit "before_tool_call" #js {:toolName "edit" :args #js {:path f}})
      (fs/writeFileSync f "v2")
      (emit "tool_complete" #js {:toolName "edit" :args #js {:path f} :cancelled false :isError false})
      (emit "turn_finalize" #js {})
      ((command agent "rewind") [] (ui-ctx notes))
      (-> (expect (fs/readFileSync f "utf8")) (.toBe "v1"))
      (-> (expect (last @notes)) (.toContain "Rewound turn 0"))
      ((command agent "rewind") [] (ui-ctx notes))
      (-> (expect (last @notes)) (.toBe "Nothing to rewind."))
      (finally
        (js-await (deactivate-all loaded))
        (fs/rmSync dir #js {:recursive true :force true})))))

(defn ^:async test-add-dir []
  (let [{:keys [agent loaded]} (js-await (load-alone "add-dir"))
        extra (tmpdir "extra-root")
        notes (atom [])]
    (try
      (fs/writeFileSync (path/join extra "AGENTS.md") "# rules")
      ((command agent "add-dir") [extra] (ui-ctx notes))
      (-> (expect (last @notes)) (.toContain (str "Added root: " extra)))
      (let [block (first (js-await (prompt-additions agent)))]
        (-> (expect block) (.toContain "# Additional project roots"))
        (-> (expect block) (.toContain (str "- " extra "\n  (has AGENTS.md)"))))
      ((command agent "add-dir") ["remove" extra] (ui-ctx notes))
      (-> (expect (js-await (prompt-additions agent))) (.toEqual []))
      (finally
        (js-await (deactivate-all loaded))
        (fs/rmSync extra #js {:recursive true :force true})))))

(defn ^:async test-refine []
  (in-dir "refine"
          ^:async (fn [dir]
            (let [{:keys [agent loaded]} (js-await (load-alone "refine"))
                  notes   (atom [])
                  session (path/join dir "s.jsonl")]
              (try
                ;; No session manager, no file argument: nothing to read.
                (js-await ((command agent "refine") [] (ui-ctx notes)))
                (-> (expect (last @notes)) (.toBe "refine: no session entries to read."))
                ;; A healthy session: say so and write nothing.
                (fs/writeFileSync session (str (js/JSON.stringify #js {:role "user" :content "hi"}) "\n"
                                               (js/JSON.stringify #js {:role "assistant" :content "hello"}) "\n"))
                (js-await ((command agent "refine") [session] (ui-ctx notes)))
                (-> (expect (last @notes)) (.toBe "refine: nothing worth refining in this session."))
                (-> (expect (fs/existsSync (path/join dir ".nyma" "refine"))) (.toBe false))
                (finally (js-await (deactivate-all loaded))))))))

(defn ^:async test-openwiki []
  (let [{:keys [agent loaded]} (js-await (load-alone "openwiki"))
        notes (atom [])]
    (try
      ;; Off by default: the command exists and says how to turn it on.
      (let [text ((command agent "openwiki") [] (ui-ctx notes))]
        (-> (expect text) (.toContain "openwiki"))
        (-> (expect (last @notes)) (.toBe text)))
      (-> (expect (tool agent "openwiki_read")) (.toBeUndefined))
      (finally (js-await (deactivate-all loaded))))))

(defn ^:async test-prompt-history []
  (let [store (memory-store)
        {:keys [agent loaded]} (js-await (load-alone "prompt-history"
                                                     (fn [api] (aset api "__sqlite-store" store))))
        notes (atom [])]
    (try
      ;; Submitting a prompt records it; /history reads it back.
      ((:emit (:events agent)) "input_submit" #js {:text "fix the build"})
      ((:emit (:events agent)) "input_submit" #js {:text "fix the build"}) ; consecutive dup dropped
      ((:emit (:events agent)) "input_submit" #js {:text "run the tests"})
      (-> (expect (count ((:recent-prompts store) 10))) (.toBe 2))
      ((command agent "history") [] (ui-ctx notes))
      (-> (expect (last @notes)) (.toContain "Recent prompts:"))
      (-> (expect (last @notes)) (.toContain "run the tests"))
      ((command agent "history") ["build"] (ui-ctx notes))
      (-> (expect (last @notes)) (.toContain "fix the build"))
      (-> (expect (last @notes)) (.not.toContain "run the tests"))
      (finally
        (js-await (deactivate-all loaded))
        ((:close store))))))

(defn ^:async test-stats-dashboard []
  (let [store (memory-store)
        {:keys [agent loaded]} (js-await (load-alone "stats-dashboard"
                                                     (fn [api] (aset api "__sqlite-store" store))))
        notes (atom [])]
    (try
      ((:record-usage store) {:session-file "s" :model "m1" :input-tokens 100 :output-tokens 50 :cost 0.01})
      ;; A headless ctx has no overlay, so the dashboard arrives as a notification.
      ((command agent "stats") [] (ui-ctx notes))
      (-> (expect (last @notes)) (.toContain "Usage Stats"))
      (-> (expect (last @notes)) (.toContain "m1"))
      (-> (expect (some? (command agent "stats-session"))) (.toBe true))
      (finally
        (js-await (deactivate-all loaded))
        ((:close store))))))

(defn ^:async test-qwen-cli []
  (let [{:keys [agent loaded]} (js-await (load-alone "custom-provider-qwen-cli"))
        cfg        ((:get (:provider-registry agent)) "qwen-cli")
        real-fetch js/globalThis.fetch
        requests   (atom [])]
    (try
      (-> (expect (some? cfg)) (.toBe true))
      ;; The device-code login left a token on disk (HOME is the test scratch
      ;; dir); that token is what every request is built with.
      (oauth/save-credentials "qwen-cli" {:access "devtok" :refresh "r1"
                                          :expires-at (+ (js/Date.now) 3600000)})
      (-> (expect (clj->js (resolve-api-key "qwen-cli" cfg)))
          (.toEqual #js {:key "devtok" :oauth? true}))
      ;; Refresh posts the refresh token to the token endpoint and stores what
      ;; comes back.
      (set! js/globalThis.fetch
            (fn [url init]
              (swap! requests conj {:url (str url) :body (.-body init)})
              (js/Promise.resolve
               #js {:json (fn [] (js/Promise.resolve
                                  #js {:access_token "newtok" :refresh_token "r2" :expires_in 3600}))})))
      (let [creds (js-await ((:refresh-token (:oauth cfg)) #js {"refresh" "r1" "access" "devtok"}))]
        (-> (expect (:access creds)) (.toBe "newtok"))
        (-> (expect (:url (first @requests))) (.toContain "/oauth2/token"))
        (-> (expect (:body (first @requests))) (.toContain "grant_type=refresh_token"))
        (-> (expect (:body (first @requests))) (.toContain "refresh_token=r1"))
        (-> (expect (:access (oauth/load-credentials "qwen-cli"))) (.toBe "newtok")))
      (finally
        (set! js/globalThis.fetch real-fetch)
        (js-await (deactivate-all loaded))))))

(describe "thin extensions activate through the loader and do their one thing"
          (fn []
            (it "memory: memory_write writes .nyma/memory/MEMORY.md and it is injected next turn" test-memory)
            (it "todos: todo_write then todo_read round-trips; the progress segment is registered" test-todos)
            (it "handoff: an existing .nyma/handoff.md is injected until /handoff clear removes it" test-handoff)
            (it "checkpoints: an edit is snapshotted and /rewind restores the file" test-checkpoints)
            (it "add-dir: /add-dir injects the root into the prompt and remove takes it out" test-add-dir)
            (it "refine: reports nothing to read / nothing to refine without writing a report" test-refine)
            (it "openwiki: off by default, /openwiki explains how to turn it on" test-openwiki)
            (it "prompt-history: input_submit is stored and /history reads it from SQLite" test-prompt-history)
            (it "stats-dashboard: /stats renders the SQLite usage totals" test-stats-dashboard)
            (it "custom-provider-qwen-cli: registers the provider and resolves/refreshes the device-code token" test-qwen-cli)))
