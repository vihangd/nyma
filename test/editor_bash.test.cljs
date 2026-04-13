(ns editor-bash.test
  "Tests for agent.ui.editor-bash — the module that powers `!cmd`
   and `!!cmd` typed directly into the prompt editor. Three surfaces
   are covered:

     - parse-bash-input (pure: kind detection + command extraction)
     - run-bash! (integration: emits before_tool_call, respects
       block/rewrite, falls back when no event bus is present)
     - format-bash-output (pure: text block layout)"
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]
            [agent.ui.editor-bash :refer [parse-bash-input
                                          format-bash-output
                                          run-bash!]]))

;;; ─── parse-bash-input ──────────────────────────────────

(describe "parse-bash-input"
          (fn []
            (it "returns :not-bash for nil and empty input"
                (fn []
                  (-> (expect (:kind (parse-bash-input nil)))  (.toBe "not-bash"))
                  (-> (expect (:kind (parse-bash-input "")))   (.toBe "not-bash"))))

            (it "returns :not-bash for text with no sigil"
                (fn []
                  (-> (expect (:kind (parse-bash-input "hello world"))) (.toBe "not-bash"))
                  (-> (expect (:kind (parse-bash-input "/help")))       (.toBe "not-bash"))))

            (it ":run for !cmd, stripping the sigil"
                (fn []
                  (let [p (parse-bash-input "!git status")]
                    (-> (expect (:kind p))    (.toBe "run"))
                    (-> (expect (:command p)) (.toBe "git status")))))

            (it ":run-hidden for !!cmd (takes precedence over single !)"
                (fn []
                  (let [p (parse-bash-input "!!git status")]
                    (-> (expect (:kind p))    (.toBe "run-hidden"))
                    (-> (expect (:command p)) (.toBe "git status")))))

            (it "trims leading whitespace before the sigil"
                (fn []
                  (let [p1 (parse-bash-input "  !ls")
                        p2 (parse-bash-input "\t!!ls")]
                    (-> (expect (:kind p1)) (.toBe "run"))
                    (-> (expect (:kind p2)) (.toBe "run-hidden")))))

            (it "trims whitespace between sigil and command"
                (fn []
                  (let [p (parse-bash-input "!  ls -la")]
                    (-> (expect (:command p)) (.toBe "ls -la")))))

            (it "bare sigil with no command is :not-bash"
                (fn []
                  ;; A stray `!` or `!!` alone shouldn't punish the user
                  ;; — they probably hit the key by accident.
                  (-> (expect (:kind (parse-bash-input "!")))    (.toBe "not-bash"))
                  (-> (expect (:kind (parse-bash-input "!!")))   (.toBe "not-bash"))
                  (-> (expect (:kind (parse-bash-input "!  "))) (.toBe "not-bash"))))

            (it "preserves special characters, including /, in the command"
                (fn []
                  ;; Critical: `!git log --pretty=/foo` must route to
                  ;; bash even though the command body contains a `/`.
                  (let [p (parse-bash-input "!git log --pretty=/foo")]
                    (-> (expect (:kind p))    (.toBe "run"))
                    (-> (expect (:command p)) (.toBe "git log --pretty=/foo")))))))

;;; ─── format-bash-output ────────────────────────────────

(describe "format-bash-output"
          (fn []
            (it "renders command header plus stdout"
                (fn []
                  (let [s (format-bash-output
                           {:command   "echo hi"
                            :stdout    "hi\n"
                            :stderr    ""
                            :exit-code 0})]
                    (-> (expect (.includes s "$ echo hi")) (.toBe true))
                    (-> (expect (.includes s "hi"))        (.toBe true)))))

            (it "omits the exit footer on exit 0"
                (fn []
                  (let [s (format-bash-output
                           {:command "true" :stdout "" :stderr "" :exit-code 0})]
                    (-> (expect (.includes s "exit")) (.toBe false)))))

            (it "emits the exit footer on non-zero exit"
                (fn []
                  (let [s (format-bash-output
                           {:command "false" :stdout "" :stderr "" :exit-code 1})]
                    (-> (expect (.includes s "exit 1")) (.toBe true)))))

            (it "labels stderr under a dedicated 'stderr:' line"
                (fn []
                  (let [s (format-bash-output
                           {:command "badcmd" :stdout "" :stderr "not found" :exit-code 127})]
                    (-> (expect (.includes s "stderr:")) (.toBe true))
                    (-> (expect (.includes s "not found")) (.toBe true))
                    (-> (expect (.includes s "exit 127")) (.toBe true)))))

            (it "renders a BLOCKED message for vetoed commands"
                (fn []
                  (let [s (format-bash-output
                           {:blocked? true :reason "BLOCKED: dangerous"
                            :command "rm -rf /" :stdout "" :stderr "" :exit-code -1})]
                    (-> (expect (.includes s "rm -rf /")) (.toBe true))
                    (-> (expect (.includes s "BLOCKED")) (.toBe true)))))

            (it "truncates very large output with a byte-count note"
                (fn []
                  (let [huge (.repeat "x" 20000)
                        s    (format-bash-output
                              {:command "big" :stdout huge :stderr "" :exit-code 0})]
                    (-> (expect (.includes s "truncated")) (.toBe true)))))))

;;; ─── run-bash! integration ─────────────────────────────

(defn- fake-agent
  "Minimal agent shape with a real event bus so handlers can be
   registered via :on and observed via :emit-collect."
  []
  {:events (create-event-bus)
   :state  (atom {:messages []})})

(defn ^:async test-run-bash-happy-path []
  (let [agent  (fake-agent)
        result (js-await (run-bash! agent "echo editor-bash-test-marker"))]
    (-> (expect (:blocked? result)) (.toBe false))
    (-> (expect (:exit-code result)) (.toBe 0))
    (-> (expect (.includes (:stdout result) "editor-bash-test-marker")) (.toBe true))))

(defn ^:async test-run-bash-blocked-by-before-tool-call []
  (let [agent  (fake-agent)
        events (:events agent)]
    ;; A fake security handler that blocks any bash tool call.
    ((:on events) "before_tool_call"
                  (fn [_data]
                    #js {:block true :reason "BLOCKED: test sentinel"})
                  100)
    (let [result (js-await (run-bash! agent "echo should-not-run"))]
      (-> (expect (:blocked? result)) (.toBe true))
      (-> (expect (.includes (:reason result) "test sentinel")) (.toBe true))
      ;; stdout must NOT contain the echo payload — the executor
      ;; should have been short-circuited before spawning sh.
      (-> (expect (.includes (or (:stdout result) "") "should-not-run")) (.toBe false)))))

(defn ^:async test-run-bash-respects-command-rewrite []
  (let [agent  (fake-agent)
        events (:events agent)]
    ;; Simulate env_filter / cwd_manager: rewrite the command to
    ;; something that produces a distinct marker so we can prove
    ;; the rewritten command was executed, not the original.
    ((:on events) "before_tool_call"
                  (fn [data]
                    (let [orig (.-command (.-args data))]
                      #js {:args #js {:command (str "echo REWRITTEN:" orig)}}))
                  80)
    (let [result (js-await (run-bash! agent "hello"))]
      (-> (expect (:blocked? result)) (.toBe false))
      (-> (expect (.includes (:stdout result) "REWRITTEN:hello")) (.toBe true)))))

(defn ^:async test-run-bash-without-events-falls-back []
  ;; Minimal agent with NO :events bus at all — run-bash! should
  ;; still execute the command directly so unit tests don't need a
  ;; full event bus wired up.
  (let [agent  {}
        result (js-await (run-bash! agent "echo fallback-works"))]
    (-> (expect (:blocked? result)) (.toBe false))
    (-> (expect (.includes (:stdout result) "fallback-works")) (.toBe true))))

(defn ^:async test-run-bash-captures-nonzero-exit []
  (let [agent  (fake-agent)
        result (js-await (run-bash! agent "sh -c 'exit 42'"))]
    (-> (expect (:blocked? result)) (.toBe false))
    (-> (expect (:exit-code result)) (.toBe 42))))

(describe "run-bash!"
          (fn []
            (it "executes a real shell command and captures stdout"
                test-run-bash-happy-path)
            (it "short-circuits when a handler returns {:block true}"
                test-run-bash-blocked-by-before-tool-call)
            (it "uses the rewritten :args.command from handlers"
                test-run-bash-respects-command-rewrite)
            (it "falls back to direct execution when :events is absent"
                test-run-bash-without-events-falls-back)
            (it "captures non-zero exit codes without throwing"
                test-run-bash-captures-nonzero-exit)))
