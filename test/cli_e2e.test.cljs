(ns cli-e2e.test
  "The compiled CLI, end to end, against a scripted fake model server.

   Everything below spawns `bun dist/agent/cli.mjs` the way a user or a script
   would and asserts on what leaves the process: stdout, stderr, exit code,
   the session files it writes, and the requests the model server received.
   Unit tests drive `run` and `resolve-session` directly; none of them can
   see a tool result reach the second request, a stream dying under
   `--output-format stream-json`, or a flag typo's exit code.

   One server for the whole file (test-util.fake-model-server); each test
   enqueues the replies it needs and gets its own HOME. Skipped when the CLI
   has not been compiled — `bun run test` always compiles first."
  (:require ["bun:test" :refer [describe it expect beforeAll afterAll]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [test-util.fake-model-server :as fms]))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(def ^:private server (atom nil))

(defn- ^:async read-until
  "Read `stream` until its text contains `needle` or `timeout-ms` passes."
  [stream needle timeout-ms]
  (let [reader   (.getReader stream)
        dec      (js/TextDecoder.)
        deadline (+ (js/Date.now) timeout-ms)]
    (loop [acc ""]
      (if (or (.includes acc needle) (> (js/Date.now) deadline))
        acc
        (let [r (js-await (js/Promise.race
                           #js [(.read reader)
                                (js/Promise. (fn [res]
                                               (js/setTimeout #(res #js {:done true})
                                                              (max 0 (- deadline (js/Date.now))))))]))]
          (if (.-done r)
            acc
            (recur (str acc (.decode dec (.-value r))))))))))

(defn- ^:async run-cli
  "Spawn the CLI with `args` in a fresh HOME wired to the fake server.
   Returns {:exit :out :err :home}.

   opts: :home     reuse a HOME (session tests span two runs)
         :until    a stderr substring — kill the process once it appears
                   (for modes that would otherwise sit in the TUI forever)"
  [args & [{:keys [home until]}]]
  (let [home (or home (fms/temp-home! (:base-url @server)))
        proc (js/Bun.spawn
              (clj->js (concat ["bun" cli] args))
              ;; cwd is the temp HOME: project settings merge shallowly, so
              ;; the repo's own .nyma/settings.json would hide the fake server.
              #js {:cwd home
                   :stdin "pipe" :stdout "pipe" :stderr "pipe"
                   :env (js/Object.assign
                         #js {} js/process.env
                         #js {"HOME" home
                              "NYMA_NO_MODEL_DISCOVERY" "1"})})]
    (.end (.-stdin proc))
    (if until
      (let [err (js-await (read-until (.-stderr proc) until 15000))]
        (.kill proc)
        (js-await (.-exited proc))
        {:exit nil :out "" :err err :home home})
      (let [out-p (js/Bun.readableStreamToText (.-stdout proc))
            err-p (js/Bun.readableStreamToText (.-stderr proc))
            timer (js/Promise. (fn [res] (js/setTimeout #(res :timeout) 20000)))
            raced (js-await (js/Promise.race #js [(.-exited proc) timer]))]
        (when (= raced :timeout) (.kill proc))
        {:exit  (if (= raced :timeout) nil raced)
         :out   (js-await out-p)
         :err   (js-await err-p)
         :home  home}))))

(def ^:private model-args ["--model" "faketest/m1" "--tools" "bash"])

(defn- json-lines [out]
  (->> (str/split-lines out)
       (remove str/blank?)
       (mapv #(js/JSON.parse %))))

(defn- write-session!
  "A session JSONL from [{:role :content}…] with a valid id/parent-id chain."
  [file entries]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync
   file
   (->> (map-indexed (fn [i e]
                       (js/JSON.stringify
                        (clj->js (assoc e :id (str "e" i)
                                        :parent-id (when (pos? i) (str "e" (dec i)))
                                        :timestamp i))))
                     entries)
        (map #(str % "\n"))
        (apply str))))

;;; ─── tool round trip ────────────────────────────────────────

(defn ^:async t-tool-round-trip []
  ((:script! @server)
   {:tool-call {:name "bash" :args {:command "echo hi"}}}
   {:text "final-answer"})
  (let [{:keys [exit out]} (js-await (run-cli (concat ["-p" "run it" "--no-session"
                                                       "--permission-mode" "full-auto"]
                                                      model-args)))]
    (-> (expect exit) (.toBe 0))
    (-> (expect out) (.toContain "final-answer"))
    ;; One request per step: the call, then the call plus its result.
    (-> (expect (count @(:requests @server))) (.toBe 2))
    (let [msgs (fms/request-messages @server 1)
          call (some #(when (seq (:tool_calls %)) %) msgs)
          res  (some #(when (= "tool" (:role %)) %) msgs)]
      (-> (expect (get-in call [:tool_calls 0 :function :name])) (.toBe "bash"))
      (-> (expect (str (:content res))) (.toContain "hi")))))

;;; ─── stream-json ────────────────────────────────────────────

(defn ^:async t-stream-json-mid-stream-failure []
  ;; One chunk (the role delta) and then the error: no text was produced, so
  ;; the run fails. An error AFTER text keeps the partial answer instead —
  ;; loop.cljs's deliberate "failed turn, not an empty one" rule.
  ((:script! @server) {:fail-after 1})
  (let [{:keys [exit out]} (js-await (run-cli (concat ["-p" "go" "--no-session"
                                                       "--output-format" "stream-json"]
                                                      model-args)))
        lines (json-lines out)
        last* (last lines)]
    (-> (expect exit) (.toBe 1))
    (-> (expect (.-type last*)) (.toBe "result"))
    (-> (expect (.-is_error last*)) (.toBe true))))

(defn ^:async t-stream-json-happy-path []
  ((:script! @server)
   {:tool-call {:name "bash" :args {:command "echo hi"}}}
   {:text "done"})
  (let [{:keys [exit out]} (js-await (run-cli (concat ["-p" "go" "--no-session"
                                                       "--permission-mode" "full-auto"
                                                       "--output-format" "stream-json"]
                                                      model-args)))
        lines (json-lines out)
        types (mapv #(.-type %) lines)]
    (-> (expect exit) (.toBe 0))
    ;; Two steps (the call, the answer) → two usage lines, no more.
    (-> (expect (count (filter #{"usage"} types))) (.toBe 2))
    (-> (expect (some #{"agent_end"} types)) (.toBeUndefined))
    (-> (expect (last types)) (.toBe "result"))
    (-> (expect (.-is_error (last lines))) (.toBe false))))

;;; ─── exit codes ─────────────────────────────────────────────

(defn ^:async t-bad-output-format []
  (let [{:keys [exit err]} (js-await (run-cli ["-p" "x" "--output-format" "josn"]))]
    (-> (expect exit) (.toBe 2))
    (-> (expect (str/trim err)) (.toBe "nyma: --output-format must be text, json or stream-json"))))

(defn ^:async t-bad-mode []
  (let [{:keys [exit err]} (js-await (run-cli ["--mode" "josn" "x"]))]
    (-> (expect exit) (.toBe 2))
    (-> (expect (str/trim err)) (.toBe "nyma: --mode must be interactive, print, json, rpc or pi-rpc"))))

(defn ^:async t-unknown-flag []
  (let [{:keys [exit err]} (js-await (run-cli ["--bogus"]))]
    (-> (expect exit) (.toBe 2))
    (-> (expect err) (.toContain "unknown option '--bogus'"))))

(defn ^:async t-no-prompt []
  (let [{:keys [exit err]} (js-await (run-cli (concat ["-p" "--no-session"] model-args)))]
    (-> (expect exit) (.toBe 2))
    (-> (expect err) (.toContain "no prompt"))))

(defn ^:async t-help-exits-zero []
  (-> (expect (:exit (js-await (run-cli ["--help"])))) (.toBe 0)))

(defn ^:async t-version-exits-zero []
  (-> (expect (:exit (js-await (run-cli ["--version"])))) (.toBe 0)))

;;; ─── sessions ───────────────────────────────────────────────

(defn ^:async t-session-carries-prior-turn []
  ((:script! @server) {:text "noted"} {:text "pineapple"})
  (let [home (fms/temp-home! (:base-url @server))
        file (path/join home "s.jsonl")
        r1   (js-await (run-cli (concat ["-p" "remember the word pineapple" "--session" file]
                                        model-args)
                                {:home home}))
        r2   (js-await (run-cli (concat ["-p" "what word?" "--session" file] model-args)
                                {:home home}))]
    (-> (expect (:exit r1)) (.toBe 0))
    (-> (expect (:exit r2)) (.toBe 0))
    (let [msgs (fms/request-messages @server 1)]
      (-> (expect (some #(.includes (str (:content %)) "pineapple") msgs)) (.toBe true))
      ;; And the prior answer came along too, not only the prompt.
      (-> (expect (some #(= "noted" (:content %)) msgs)) (.toBe true)))))

(defn ^:async t-fork-leaves-source-untouched []
  (let [home (fms/temp-home! (:base-url @server))
        src  (path/join home "src.jsonl")
        _    (write-session! src [{:role "user" :content "first"}
                                  {:role "assistant" :content "second"}])
        before (fs/readFileSync src "utf8")
        ;; rpc mode exits at stdin EOF, so the fork happens and the process
        ;; ends without a TUI.
        {:keys [exit]} (js-await (run-cli (concat ["--mode" "rpc" "--fork" src] model-args)
                                          {:home home}))
        sessions (fs/readdirSync (path/join home ".nyma" "sessions"))]
    (-> (expect exit) (.toBe 0))
    (-> (expect (fs/readFileSync src "utf8")) (.toBe before))
    (-> (expect (count sessions)) (.toBe 1))
    (-> (expect (fs/readFileSync (path/join home ".nyma" "sessions" (first sessions)) "utf8"))
        (.toContain "\"second\""))))

(defn ^:async t-resume-with-no-sessions []
  (let [{:keys [err]} (js-await (run-cli (concat ["-r"] model-args)
                                         {:until "no sessions"}))]
    (-> (expect err) (.toContain "nyma: no sessions for this project — starting fresh"))))

(defn ^:async t-resume-drops-dangling-tool-call []
  ((:script! @server) {:text "fine"})
  (let [home (fms/temp-home! (:base-url @server))
        file (path/join home "dangling.jsonl")
        _    (write-session! file [{:role "user" :content "run ls"}
                                   {:role "tool_call"
                                    :content (js/JSON.stringify
                                              #js {:name "bash" :args #js {:command "ls"}})}])
        {:keys [exit out]} (js-await (run-cli (concat ["-p" "hi" "--session" file] model-args)
                                              {:home home}))
        msgs (fms/request-messages @server 0)]
    (-> (expect exit) (.toBe 0))
    (-> (expect out) (.toContain "fine"))
    ;; No tool half reaches the provider — neither the call nor a tool role.
    (-> (expect (some #(or (= "tool" (:role %)) (seq (:tool_calls %))) msgs)) (.toBeUndefined))
    (-> (expect (:content (last msgs))) (.toBe "hi"))))

(def ^:private describe* (if (fs/existsSync cli) describe (.-skip describe)))

(describe* "cli end to end"
           (fn []
             (beforeAll (fn [] (reset! server (fms/start!))))
             (afterAll (fn [] ((:stop @server))))
    ;; Each test starts from an empty script queue and request log.
             (let [fresh (fn [f] (fn [] ((:reset! @server)) (f)))
                   t 30000]
               (it "a bash tool call round-trips: result reaches the second request, final text is printed"
                   (fresh t-tool-round-trip) t)
               (it "stream-json: a stream that dies still ends with an is_error result and exit 1"
                   (fresh t-stream-json-mid-stream-failure) t)
               (it "stream-json: one usage line per step, no agent_end, result last"
                   (fresh t-stream-json-happy-path) t)
               (it "--output-format josn exits 2 with the one-line message" (fresh t-bad-output-format) t)
               (it "--mode josn exits 2 with the one-line message" (fresh t-bad-mode) t)
               (it "an unknown flag exits 2 naming it" (fresh t-unknown-flag) t)
               (it "-p with no prompt exits 2" (fresh t-no-prompt) t)
               (it "--help exits 0" (fresh t-help-exits-zero) t)
               (it "--version exits 0" (fresh t-version-exits-zero) t)
               (it "--session: the second run sends the first run's turn to the model"
                   (fresh t-session-carries-prior-turn) t)
               (it "--fork writes a new session file and leaves the source untouched"
                   (fresh t-fork-leaves-source-untouched) t)
               (it "-r with no sessions says so" (fresh t-resume-with-no-sessions) t)
               (it "resuming a session that ends in a tool call without its result sends no dangling tool call"
                   (fresh t-resume-drops-dangling-tool-call) t))))
