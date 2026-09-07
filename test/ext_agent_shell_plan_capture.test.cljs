(ns ext-agent-shell-plan-capture.test
  "`/plan-capture` — the bridge from an ACP planning session to nyma's own loop.

   Most of this is pure and testable without an agent: arg parsing, name
   derivation, artifact assembly, and the refusal ladder. The end-to-end path
   is covered by driving `capture!` against a temp cwd."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.features.plan-capture :as pc]))

(def ^:private plan-text
  (str "Plan:\n"
       "1. Add TokenStore in src/auth/store.ts:1\n"
       "2. Wire the callback in src/auth/routes.ts:40\n"
       "3. Add tests in test/auth.test.ts\n"))

(defn- transcript [& turns] (vec turns))
(defn- u [t] {:role "user" :text t})
(defn- a [t] {:role "assistant" :text t})

;;; ─── Arg parsing ────────────────────────────────────────────

(describe "plan-capture/parse-args" (fn []

                                      (it "takes the first non-flag token as the name"
                                          (fn []
                                            (let [r (pc/parse-args ["oauth-login" "--dry-run"])]
                                              (-> (expect (:name r)) (.toBe "oauth-login"))
                                              (-> (expect (:dry-run? r)) (.toBe true)))))

                                      (it "leaves the name nil when only flags are given, so it can be derived"
                                          (fn []
                                            (-> (expect (:name (pc/parse-args ["--all" "--execute"]))) (.toBeUndefined))))

                                      (it "reads --role and defaults it to a role that APPLIES"
                                          (fn []
        ;; Not `default`: model_roles/on-resolve guards
        ;; `(not= role "default")`, so setting :active-role to "default" is a
        ;; silent no-op and --execute would hand off on settings.model instead
        ;; of anything chosen here. Verified against the compiled module —
        ;; active-role "default" produces zero setModel calls.
        ;;
        ;; Not `build` either: that is not a BUILTIN role, and shipping a
        ;; default naming a role the user may not have declared is a mistake
        ;; already made once this cycle. `fast` is builtin and is the role for
        ;; cheap execution of a plan someone else wrote.
                                            (-> (expect (:role (pc/parse-args []))) (.toBe "fast"))
                                            (-> (expect (:role (pc/parse-args ["--role=deep"]))) (.toBe "deep"))
                                            (-> (expect (boolean (some #(= % (:role (pc/parse-args [])))
                                                                       ["default" ""])))
                                                (.toBe false))))))

;;; ─── Name derivation ────────────────────────────────────────

(describe "plan-capture/slug" (fn []

                                (it "drops stopwords so the word budget goes on meaning"
                                    (fn []
                                      (-> (expect (pc/slug "add OAuth login to the API")) (.toBe "oauth-login-api"))))

                                (it "drops meta-instruction and collapses repeated stems"
                                    (fn []
        ;; Observed live: "let's plan to write a script that writes today's
        ;; date" produced `plan-write-script-that` — the whole budget spent on
        ;; how the user wanted to work, plus a duplicate of `write`.
                                      (-> (expect (pc/slug "let's plan to write a script that writes today's date"))
                                          (.toBe "write-script-today-date"))
                                      (-> (expect (pc/slug "help me refactor the token store for per-request scope"))
                                          (.toBe "refactor-token-store-per"))))

                                (it "produces something valid from punctuation-heavy text"
                                    (fn []
                                      (-> (expect (pc/slug "Fix bug #42 in src/auth.ts!")) (.toBe "fix-bug-42-src"))))

                                (it "falls back to the raw words rather than returning nothing"
                                    (fn []
        ;; All-stopword input would otherwise slug to "", and a nameless spec
        ;; is worse than an ugly one.
                                      (-> (expect (pc/slug "the a to of")) (.toBe "the-a-to-of"))))

                                (it "is empty for empty input"
                                    (fn []
                                      (-> (expect (pc/slug "")) (.toBe ""))))))

;;; ─── Transcript → plan ──────────────────────────────────────

(describe "plan-capture/plan-text" (fn []

                                     (it "takes the LAST assistant turn by default"
                                         (fn []
                                           (let [t (transcript (u "do a thing") (a "first draft")
                                                               (u "no, like this") (a "second draft"))]
                                             (-> (expect (pc/plan-text t false)) (.toBe "second draft")))))

                                     (it "--all keeps the whole conversation, labelled"
                                         (fn []
                                           (let [out (pc/plan-text (transcript (u "ask") (a "answer")) true)]
                                             (-> (expect (.includes out "## Request")) (.toBe true))
                                             (-> (expect (.includes out "## Response")) (.toBe true))
                                             (-> (expect (.includes out "ask")) (.toBe true)))))

                                     (it "first-request is the originating ask, not the latest"
                                         (fn []
                                           (-> (expect (pc/first-request (transcript (u "one") (a "x") (u "two"))))
                                               (.toBe "one"))))))

;;; ─── Artifact ───────────────────────────────────────────────

(describe "plan-capture/build-artifact" (fn []

                                          (it "records provenance, because .nyma/plans has two writers"
                                              (fn []
                                                (let [out (pc/build-artifact {:agent "claude" :mode "plan"
                                                                              :request "add OAuth login"
                                                                              :plan plan-text
                                                                              :captured-at "2026-09-04T11:20:03Z"})]
                                                  (-> (expect (.includes out "source: agent-shell/claude")) (.toBe true))
                                                  (-> (expect (.includes out "mode: plan")) (.toBe true))
                                                  (-> (expect (.includes out "request: add OAuth login")) (.toBe true))
                                                  (-> (expect (.includes out "TokenStore")) (.toBe true)))))

                                          (it "flattens a multi-line request so the frontmatter stays parseable"
                                              (fn []
                                                (let [out (pc/build-artifact {:agent "claude" :mode "plan"
                                                                              :request "line one\nline two"
                                                                              :plan "x" :captured-at "t"})]
                                                  (-> (expect (.includes out "request: line one line two")) (.toBe true)))))

                                          (it "tells the reader it has no access to the planning conversation"
                                              (fn []
        ;; The executing model has none of Claude Code's exploration; a plan
        ;; written for its author is not executable by a stranger.
                                                (-> (expect (.includes (pc/build-artifact {:plan "x"}) "no access"))
                                                    (.toBe true))))

                                          (it "names the file so both writers sort together"
                                              (fn []
                                                (let [f (pc/artifact-filename (js/Date. "2026-09-04T11:20:03.000Z"))]
                                                  (-> (expect (.startsWith f "plan-2026-09-04T11-20-03")) (.toBe true))
                                                  (-> (expect (.endsWith f ".md")) (.toBe true)))))))

;;; ─── Refusals ───────────────────────────────────────────────

(describe "plan-capture/capture-refusal" (fn []

                                           (it "refuses with no agent"
                                               (fn []
                                                 (-> (expect (.includes (pc/capture-refusal {:agent-key nil}) "no ACP agent"))
                                                     (.toBe true))))

                                           (it "refuses on an empty transcript"
                                               (fn []
                                                 (-> (expect (.includes (pc/capture-refusal {:agent-key "claude" :transcript []})
                                                                        "nothing captured"))
                                                     (.toBe true))))

                                           (it "warns on ACTUAL edits, not on mode"
                                               (fn []
        ;; Mode was the first attempt and is only a proxy. Observed live: an
        ;; agent in "default" mode tried to write, nyma prompted, the write was
        ;; denied — nothing changed, so there was nothing to warn about. The
        ;; real question is whether edits COMPLETED.
                                                 (-> (expect (pc/edits-warning 0 "default")) (.toBeNil))
                                                 (-> (expect (.includes (pc/edits-warning 2 "plan") "2 file changes")) (.toBe true))
                                                 (-> (expect (.includes (pc/edits-warning 1 "default") "1 file change")) (.toBe true))
                                                 ;; and it is a warning, never a refusal
                                                 (-> (expect (pc/capture-refusal {:agent-key "claude"
                                                                                  :transcript (transcript (u "x") (a plan-text))
                                                                                  :steps 3}))
                                                     (.toBeNil))))

                                           (it "refuses when the last turn has no numbered steps"
                                               (fn []
                                                 (let [r (pc/capture-refusal {:agent-key "claude"
                                                                              :transcript (transcript (u "x") (a "Which provider?"))
                                                                              :mode "plan" :steps 0})]
                                                   (-> (expect (.includes r "question, not a plan")) (.toBe true)))))

                                           (it "allows a real plan in plan mode"
                                               (fn []
                                                 (-> (expect (pc/capture-refusal {:agent-key "claude"
                                                                                  :transcript (transcript (u "x") (a plan-text))
                                                                                  :mode "plan" :steps 3}))
                                                     (.toBeNil))))))

;;; ─── End to end ─────────────────────────────────────────────

(defn- ^:async with-tmp
  "Async: capture! now awaits an optional refine round-trip."
  [body]
  (let [tmp  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pc-"))
        prev (js/process.cwd)]
    (try (.chdir js/process tmp) (js-await (body tmp))
         (finally (.chdir js/process prev)
                  (try (fs/rmSync tmp #js {:recursive true :force true})
                       (catch :default _ nil))))))

(defn- harness []
  (let [notes (atom []) sent (atom []) st (atom {})]
    {:api #js {:ui #js {:notify (fn [m & _] (swap! notes conj m)) :available true}
               :__state_atom st
               :sendUserMessage (fn [m _o] (swap! sent conj m))
               :registerCommand (fn [_n _c] nil)
               :unregisterCommand (fn [_n] nil)}
     :notes notes :sent sent :state st}))

(defn- seed! []
  (reset! shared/active-agent "claude")
  (shared/update-agent-state! "claude" :mode "plan")
  (let [pk (shared/pool-key "claude" (js/process.cwd))]
    (shared/clear-transcript! pk)
    (shared/append-turn! pk "user" "add OAuth login to the API")
    (shared/append-turn! pk "assistant" plan-text)
    pk))

(def ^:private executable-plan-text
  "Names files AND a command with an exit code — the bar the refine pass is
   trying to reach. The `plan-text` fixture above deliberately does not: it
   cites paths but verifies nothing, which is the commonest real shape."
  (str "1. Add TokenStore in `src/auth/store.ts:1`.\n"
       "2. Wire the callback in `src/auth/routes.ts:40`.\n"
       "3. Verify: `bun test test/auth.test.ts` exits 0.\n"))

(defn- thin-plan-text
  "Reads well, cannot be executed cold: no file path, no verifying command."
  []
  (str "Plan:\n1. Add a token store.\n2. Wire it into the callback.\n"
       "3. Check it works by trying a login.\n"))

(describe "plan-capture/capture! end to end" (fn []

  (it "writes an artifact and DETACHES, leaving the session reattachable" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [tmp]
             (let [_ (seed!) {:keys [api notes]} (harness)]
               (js-await (pc/capture! api []))
               (let [files (vec (fs/readdirSync (path/join tmp ".nyma" "plans")))]
                 (-> (expect (count files)) (.toBe 1))
                 (-> (expect (.startsWith (first files) "plan-")) (.toBe true)))
               ;; detach, not disconnect — the pooled process must survive so a
               ;; follow-up refinement still has the planning context
               (-> (expect @shared/active-agent) (.toBeNil))
               (-> (expect (.includes (str/join " " @notes) "detached")) (.toBe true))))))))

  (it "--dry-run writes nothing and stays attached" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [tmp]
             (let [_ (seed!) {:keys [api notes]} (harness)]
               (js-await (pc/capture! api ["--dry-run"]))
               (-> (expect (fs/existsSync (path/join tmp ".nyma" "plans"))) (.toBe false))
               (-> (expect @shared/active-agent) (.toBe "claude"))
               (-> (expect (.includes (str/join " " @notes) "dry run")) (.toBe true))))))))

  (it "derives the name from the first request when none is given" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [_tmp]
             (let [_ (seed!) {:keys [api notes]} (harness)]
               (js-await (pc/capture! api []))
               (-> (expect (.includes (str/join " " @notes) "oauth-login-api")) (.toBe true))))))))

  (it "--execute sets the role and sends exactly one follow-up" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [_tmp]
             (let [_ (seed!) {:keys [api sent state]} (harness)]
               (js-await (pc/capture! api ["--execute" "--role=fast"]))
               (-> (expect (:active-role @state)) (.toBe "fast"))
               (-> (expect (count @sent)) (.toBe 1))
               (-> (expect (.includes (first @sent) ".nyma/plans/")) (.toBe true))))))))

  (it "writes nothing when it refuses" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [tmp]
             (reset! shared/active-agent nil)
             (let [{:keys [api]} (harness)]
               (js-await (pc/capture! api []))
               (-> (expect (fs/existsSync (path/join tmp ".nyma"))) (.toBe false))))))))))

;;; ─── Self-containment: the refine round-trip ────────────────
;;
;; The plan the agent writes is addressed to YOU, mid-conversation. A real
;; capture named an absolute path and inlined the file body — executable — but
;; verified with "run it, eyeball `2026-09-04`", which no gate can act on, and
;; carried an offer ("say which and me adjust") aimed at the planner rather
;; than the reader. `thin-plan?` decides whether to ask for a rewrite.

(describe "plan-capture/thin-plan?" (fn []

  (it "passes a plan that names files and a verifying command"
      (fn []
        (-> (expect (pc/thin-plan? executable-plan-text 3)) (.toBeNil))))

  (it "flags the commonest real shape: paths cited, nothing verified"
      (fn []
        ;; `plan-text` is exactly that — three steps naming files, no command.
        (-> (expect (pc/thin-plan? plan-text 3))
            (.toBe "no step names a command that verifies it"))))

  (it "flags a plan that names no file"
      (fn []
        (-> (expect (pc/thin-plan? (thin-plan-text) 3)) (.toBe "no step names a file"))))

  (it "flags a plan whose check is an instruction to look at something"
      (fn []
        ;; The observed case: paths present, verification by eyeball.
        (let [t (str "1. Create `/tmp/today.sh` with `date +%F`.\n"
                     "2. chmod +x it.\n"
                     "3. Check: run it, eyeball the date.\n")]
          (-> (expect (pc/thin-plan? t 3))
              (.toBe "no step names a command that verifies it")))))

  (it "says nothing when there are no steps — capture-refusal owns that"
      (fn []
        (-> (expect (pc/thin-plan? "Which provider?" 0)) (.toBeNil))))))

(describe "plan-capture/capture! refine pass" (fn []

  (it "asks the agent to rewrite a thin plan, then captures the rewrite" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [tmp]
             (reset! shared/active-agent "claude")
             (let [pk (shared/pool-key "claude" (js/process.cwd))
                   {:keys [api notes]} (harness)
                   asked (atom [])]
               (shared/clear-transcript! pk)
               (shared/append-turn! pk "user" "write a date script")
               (shared/append-turn! pk "assistant" (thin-plan-text))
               ;; Stands in for client/send-prompt: records the ask and appends
               ;; the reply, exactly as the real one does.
               (js-await (pc/capture!
                          api []
                          (fn [text]
                            (swap! asked conj text)
                            (shared/append-turn! pk "user" text)
                            (shared/append-turn!
                             pk "assistant"
                             (str "1. Create `src/date.sh` with `date +%F`.\n"
                                  "2. Verify: `bash src/date.sh` exits 0.\n"))
                            (js/Promise.resolve #js {}))))
               (-> (expect (count @asked)) (.toBe 1))
               (-> (expect (.includes (first @asked) "no access to this conversation"))
                   (.toBe true))
               (-> (expect (.includes (str/join " " @notes) "asking claude to rewrite"))
                   (.toBe true))
               ;; The ARTIFACT must hold the rewrite, not the original.
               (let [dir  (path/join tmp ".nyma" "plans")
                     body (fs/readFileSync
                           (path/join dir (first (fs/readdirSync dir))) "utf8")]
                 (-> (expect (.includes body "bash src/date.sh")) (.toBe true))
                 (-> (expect (.includes body "Check it works by trying a login")) (.toBe false)))))))))

  (it "leaves a plan alone when it is already executable" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [_tmp]
             (reset! shared/active-agent "claude")
             (let [pk (shared/pool-key "claude" (js/process.cwd))
                   {:keys [api]} (harness)
                   asked (atom 0)]
               (shared/clear-transcript! pk)
               (shared/append-turn! pk "user" "add OAuth login")
               (shared/append-turn! pk "assistant" executable-plan-text)
               (js-await (pc/capture! api [] (fn [_] (swap! asked inc)
                                               (js/Promise.resolve #js {}))))
               (-> (expect @asked) (.toBe 0))))))))

  (it "--no-refine skips the round-trip" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [_tmp]
             (reset! shared/active-agent "claude")
             (let [pk (shared/pool-key "claude" (js/process.cwd))
                   {:keys [api]} (harness)
                   asked (atom 0)]
               (shared/clear-transcript! pk)
               (shared/append-turn! pk "user" "x")
               (shared/append-turn! pk "assistant" (thin-plan-text))
               (js-await (pc/capture! api ["--no-refine"]
                                      (fn [_] (swap! asked inc)
                                        (js/Promise.resolve #js {}))))
               (-> (expect @asked) (.toBe 0))))))))

  (it "still captures when the rewrite fails" ^:async
      (fn []
        (js-await
         (with-tmp
           ^:async
           (fn [tmp]
             (reset! shared/active-agent "claude")
             (let [pk (shared/pool-key "claude" (js/process.cwd))
                   {:keys [api notes]} (harness)]
               (shared/clear-transcript! pk)
               (shared/append-turn! pk "user" "x")
               (shared/append-turn! pk "assistant" (thin-plan-text))
               ;; A failed rewrite must not lose the plan already on the
               ;; transcript — the agent may simply have timed out.
               (js-await (pc/capture! api []
                                      (fn [_] (js/Promise.reject (js/Error. "timeout")))))
               (-> (expect (.includes (str/join " " @notes) "rewrite failed")) (.toBe true))
               (-> (expect (count (fs/readdirSync (path/join tmp ".nyma" "plans"))))
                   (.toBe 1)))))))))
)
