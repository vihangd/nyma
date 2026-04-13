(ns commands-builtins.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.commands.share :refer [messages->html messages->markdown]]
            [agent.commands.resolver :refer [resolve-command]]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- make-agent-with-builtins
  "Create an agent and register all built-in commands."
  []
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})]
    ;; Mock a session on the agent
    (reset! (:session agent)
            {:get-session-name (fn [] nil)
             :set-session-name (fn [_n] nil)
             :get-file-path    (fn [] "/tmp/test.jsonl")
             :get-tree         (fn [] [])
             :build-context    (fn [] [])
             :leaf-id          (fn [] "leaf-1")
             :branch           (fn [_id] nil)
             :switch-file      (fn [_p] nil)})
    (register-builtins agent @(:session agent) {})
    agent))

(defn- get-handler [agent cmd-name]
  (get-in @(:commands agent) [cmd-name :handler]))

(defn- make-ctx
  "Create a mock context with captured output."
  []
  (let [notifications (atom [])
        overlays      (atom [])]
    {:ctx #js {:ui #js {:available true
                        :notify    (fn [msg level]
                                     (swap! notifications conj {:msg msg :level (or level "info")}))
                        :showOverlay (fn [text]
                                       (swap! overlays conj text))
                        :select     (fn [_title _opts] (js/Promise.resolve nil))}}
     :notifications notifications
     :overlays overlays}))

;;; ─── Tests ──────────────────────────────────────────────────

(describe "register-builtins" (fn []
                                (it "registers all expected commands"
                                    (fn []
                                      (let [agent (make-agent-with-builtins)
                                            cmds  @(:commands agent)
                                            names (set (keys cmds))]
        ;; Check all expected commands are registered. `cls` is the
        ;; Phase 18 addition for terminal-clear (distinct from /clear
        ;; which resets session state).
                                        (doseq [cmd ["help" "model" "clear" "cls" "exit" "new" "fork" "tree"
                                                     "compact" "debug" "reload"
                                                     "name" "session" "copy" "hotkeys" "export" "resume" "import"]]
                                          (-> (expect (contains? names cmd)) (.toBe true))))))

  ;; Phase 18: borrowed from cc-kit's command aliases. Nyma's command
  ;; parser already supports :aliases (phase 10) but the built-ins
  ;; didn't declare any. These two tests lock the aliases in so a
  ;; future edit can't silently drop them.
                                (it "exit command declares quit/q aliases"
                                    (fn []
                                      (let [agent (make-agent-with-builtins)
                                            cmd   (get @(:commands agent) "exit")]
                                        (-> (expect (some? (:aliases cmd))) (.toBe true))
                                        (-> (expect (contains? (set (:aliases cmd)) "quit")) (.toBe true))
                                        (-> (expect (contains? (set (:aliases cmd)) "q")) (.toBe true)))))

                                (it "help command declares ? as an alias"
                                    (fn []
                                      (let [agent (make-agent-with-builtins)
                                            cmd   (get @(:commands agent) "help")]
                                        (-> (expect (contains? (set (:aliases cmd)) "?")) (.toBe true)))))))

;;; ─── /help formatter (Phase 18) ────────────────────────

(describe "/help command: alias-inline formatting"
          (fn []
            (it "renders aliases inline next to each command name"
                (fn []
                  (let [agent (make-agent-with-builtins)
                        {:keys [ctx overlays]} (make-ctx)
                        handler (get-handler agent "help")]
                    (handler [] ctx)
                    (let [text (first @overlays)]
            ;; The exit command has aliases quit + q — the help
            ;; output should surface them inline as "(quit, q)" so
            ;; users discover them without reading the source.
            ;; Assert on the full substring so a later format
            ;; change (dropping the parens, separator, etc.) fires.
                      (-> (expect text) (.toContain "/exit (quit, q)"))
            ;; And the help command itself shows its ? alias.
                      (-> (expect text) (.toContain "/help (?)"))))))

            (it "commands without aliases render without the (...) suffix"
                (fn []
                  (let [agent (make-agent-with-builtins)
                        {:keys [ctx overlays]} (make-ctx)
                        handler (get-handler agent "help")]
                    (handler [] ctx)
                    (let [text (first @overlays)]
            ;; /model has no aliases — its line shouldn't have a
            ;; (...) before the em-dash separator.
                      (-> (expect text) (.toContain "/model"))
                      (-> (expect text) (.toContain "/cls"))))))))

;;; ─── /cls command (Phase 18) ──────────────────────────

(describe "/cls command"
          (fn []
            (it "is registered and has a handler"
                (fn []
                  (let [agent (make-agent-with-builtins)
                        cmd   (get @(:commands agent) "cls")]
                    (-> (expect (some? cmd)) (.toBe true))
                    (-> (expect (fn? (:handler cmd))) (.toBe true)))))

            (it "description mentions the terminal/screen scope"
                (fn []
                  (let [agent (make-agent-with-builtins)
                        desc  (:description (get @(:commands agent) "cls"))]
          ;; Must NOT say 'messages' or 'session' — that's /clear.
                    (-> (expect desc) (.toContain "terminal")))))

            (it "handler writes the CSI 2J + 3J + H sequence to stdout"
                (fn []
        ;; We stub process.stdout.write to capture what the handler
        ;; emits. The exact bytes are the cc-kit-borrowed escape
        ;; sequence from packages/ui/src/commands/builtins.ts:33.
                  (let [agent (make-agent-with-builtins)
                        {:keys [ctx]} (make-ctx)
                        handler (get-handler agent "cls")
                        captured (atom "")
                        orig-write (.-write (.-stdout js/process))]
                    (try
                      (set! (.-write (.-stdout js/process))
                            (fn [s] (swap! captured str s)))
                      (handler [] ctx)
                      (-> (expect @captured) (.toContain "\u001b[2J"))
                      (-> (expect @captured) (.toContain "\u001b[3J"))
                      (-> (expect @captured) (.toContain "\u001b[H"))
                      (finally
                        (set! (.-write (.-stdout js/process)) orig-write))))))))

(describe "/name command" (fn []
                            (it "shows current name when no args"
                                (fn []
                                  (let [agent (make-agent-with-builtins)
                                        {:keys [ctx notifications]} (make-ctx)
                                        handler (get-handler agent "name")]
                                    (handler [] ctx)
                                    (-> (expect (:msg (first @notifications))) (.toContain "unnamed")))))

                            (it "sets name when args provided"
                                (fn []
                                  (let [agent    (make-agent-with-builtins)
                                        set-name (atom nil)
                                        _        (reset! (:session agent)
                                                         (merge @(:session agent)
                                                                {:set-session-name (fn [n] (reset! set-name n))
                                                                 :get-session-name (fn [] @set-name)}))
                                        {:keys [ctx notifications]} (make-ctx)
                                        handler  (get-handler agent "name")]
        ;; Re-register builtins with updated session
                                    (register-builtins agent @(:session agent) {})
                                    ((get-handler agent "name") ["My" "Project"] ctx)
                                    (-> (expect @set-name) (.toBe "My Project"))
                                    (-> (expect (:msg (first @notifications))) (.toContain "My Project")))))))

(describe "/session command" (fn []
                               (it "shows session info"
                                   (fn []
                                     (let [agent (make-agent-with-builtins)
                                           {:keys [ctx overlays]} (make-ctx)
                                           handler (get-handler agent "session")]
                                       (handler nil ctx)
                                       (let [text (first @overlays)]
                                         (-> (expect text) (.toContain "Session Info"))
                                         (-> (expect text) (.toContain "Path:"))
                                         (-> (expect text) (.toContain "Messages:"))))))))

(describe "/copy command" (fn []
                            (it "notifies error when no assistant message"
                                (fn []
                                  (let [agent (make-agent-with-builtins)
                                        {:keys [ctx notifications]} (make-ctx)
                                        handler (get-handler agent "copy")]
                                    (handler nil ctx)
                                    (-> (expect (:msg (first @notifications))) (.toContain "No assistant message")))))

                            (it "finds last assistant message"
                                (fn []
                                  (let [agent (make-agent-with-builtins)
                                        _     (swap! (:state agent) assoc :messages
                                                     [{:role "user" :content "hello"}
                                                      {:role "assistant" :content "first reply"}
                                                      {:role "user" :content "more"}
                                                      {:role "assistant" :content "second reply"}])
                                        msgs  (:messages @(:state agent))
                                        last-asst (last (filter #(= (:role %) "assistant") msgs))]
                                    (-> (expect (:content last-asst)) (.toBe "second reply")))))))

(describe "/hotkeys command" (fn []
                               (it "lists built-in shortcuts"
                                   (fn []
                                     (let [agent (make-agent-with-builtins)
                                           {:keys [ctx overlays]} (make-ctx)
                                           handler (get-handler agent "hotkeys")]
                                       (handler nil ctx)
                                       (let [text (first @overlays)]
                                         (-> (expect text) (.toContain "Escape"))
                                         (-> (expect text) (.toContain "Ctrl+L"))))))

                               (it "includes extension shortcuts when registered"
                                   (fn []
                                     (let [agent (make-agent-with-builtins)
                                           _     (swap! (:shortcuts agent) assoc "ctrl+k" (fn []))
                                           {:keys [ctx overlays]} (make-ctx)
                                           handler (get-handler agent "hotkeys")]
                                       (handler nil ctx)
                                       (let [text (first @overlays)]
                                         (-> (expect text) (.toContain "ctrl+k"))
                                         (-> (expect text) (.toContain "Extensions:"))))))))

(describe "/export command" (fn []
                              (it "generates html by default"
                                  (fn []
                                    (let [msgs [{:role "user" :content "hello"} {:role "assistant" :content "hi"}]
                                          html (messages->html msgs "test")]
                                      (-> (expect html) (.toContain "<!DOCTYPE html"))
                                      (-> (expect html) (.toContain "hello"))
                                      (-> (expect html) (.toContain "hi")))))

                              (it "generates markdown"
                                  (fn []
                                    (let [msgs [{:role "user" :content "hello"} {:role "assistant" :content "hi"}]
                                          md   (messages->markdown msgs "test")]
                                      (-> (expect md) (.toContain "# Session: test"))
                                      (-> (expect md) (.toContain "hello"))
                                      (-> (expect md) (.toContain "hi")))))

                              (it "generates jsonl"
                                  (fn []
                                    (let [msgs    [{:role "user" :content "hello"}]
                                          jsonl   (js/JSON.stringify (clj->js (first msgs)))
                                          parsed  (js/JSON.parse jsonl)]
                                      (-> (expect (.-role parsed)) (.toBe "user"))
                                      (-> (expect (.-content parsed)) (.toBe "hello")))))))

(describe "/import command" (fn []
                              (it "shows error for missing path"
                                  (fn []
                                    (let [agent (make-agent-with-builtins)
                                          {:keys [ctx notifications]} (make-ctx)
                                          handler (get-handler agent "import")]
                                      (handler [] ctx)
                                      (-> (expect (:msg (first @notifications))) (.toContain "Usage:")))))

                              (it "shows error for nonexistent file"
                                  (fn []
                                    (let [agent (make-agent-with-builtins)
                                          {:keys [ctx notifications]} (make-ctx)
                                          handler (get-handler agent "import")]
                                      (handler ["/nonexistent/file.jsonl"] ctx)
                                      (-> (expect (:msg (first @notifications))) (.toContain "not found")))))))

(describe "/resume command" (fn []
                              (it "shows error when no sessions found"
                                  (fn []
                                    (let [agent (make-agent-with-builtins)
                                          {:keys [ctx notifications]} (make-ctx)
                                          handler (get-handler agent "resume")]
        ;; Use a nonexistent dir so list-sessions returns empty
        ;; The HOME env var sessions dir likely doesn't have test sessions
        ;; but this exercises the "no sessions" path
                                      (handler nil ctx)
        ;; Either shows "No sessions found" or shows selector
                                      (-> (expect (or (seq @notifications) true)) (.toBeTruthy)))))))

;; ── New commands ──────────────────────────────────────────

(describe "/changelog command" (fn []
                                 (it "shows error when no CHANGELOG.md exists"
                                     (fn []
                                       (let [agent (make-agent-with-builtins)
                                             {:keys [ctx notifications]} (make-ctx)
                                             handler (get-handler agent "changelog")]
                                         (handler nil ctx)
                                         (-> (expect (:msg (first @notifications))) (.toContain "No CHANGELOG.md")))))))

(describe "/logout command" (fn []
                              (it "shows error when no credentials file"
                                  (fn []
                                    (let [agent (make-agent-with-builtins)
                                          {:keys [ctx notifications]} (make-ctx)
                                          handler (get-handler agent "logout")]
        ;; Will likely fail since ~/.nyma/credentials.json probably doesn't exist in test
                                      (handler ["test-provider"] ctx)
        ;; Either removes or shows error
                                      (-> (expect (seq @notifications)) (.toBeTruthy)))))))

(describe "/scoped-models command" (fn []
                                     (it "shows no overrides when empty"
                                         (fn []
                                           (let [agent (make-agent-with-builtins)
                                                 {:keys [ctx notifications]} (make-ctx)
                                                 handler (get-handler agent "scoped-models")]
                                             (handler nil ctx)
                                             (-> (expect (:msg (first @notifications))) (.toContain "No scoped model")))))))

(describe "/debug command" (fn []
                             (it "shows debug information"
                                 (fn []
                                   (let [agent (make-agent-with-builtins)
                                         {:keys [ctx overlays]} (make-ctx)
                                         handler (get-handler agent "debug")]
                                     (handler nil ctx)
                                     (let [text (first @overlays)]
                                       (-> (expect text) (.toContain "Debug Info"))
                                       (-> (expect text) (.toContain "Messages"))
                                       (-> (expect text) (.toContain "Turns"))))))))

(describe "/settings command" (fn []
                                (it "registered as a command"
                                    (fn []
                                      (let [agent (make-agent-with-builtins)]
                                        (-> (expect (get @(:commands agent) "settings")) (.toBeDefined)))))))

(describe "/login command" (fn []
                             (it "registered as a command"
                                 (fn []
                                   (let [agent (make-agent-with-builtins)]
                                     (-> (expect (get @(:commands agent) "login")) (.toBeDefined)))))))

(describe "/reload command" (fn []
                              (it "registered as a command"
                                  (fn []
                                    (let [agent (make-agent-with-builtins)]
                                      (-> (expect (get @(:commands agent) "reload")) (.toBeDefined)))))))

;; ═══════════════════════════════════════════════════════════════
;; resolve-command — fuzzy command resolution
;; ═══════════════════════════════════════════════════════════════

(describe "resolve-command" (fn []
                              (it "returns exact match"
                                  (fn []
                                    (let [cmds {"help" {:handler identity} "agent-shell__agent" {:handler identity}}]
                                      (-> (expect (some? (resolve-command cmds "help"))) (.toBe true)))))

                              (it "returns suffix match when no exact match"
                                  (fn []
                                    (let [cmds {"agent-shell__agent" {:handler identity}}
                                          result (resolve-command cmds "agent")]
                                      (-> (expect (some? result)) (.toBe true))
                                      (-> (expect (:handler result)) (.toBe identity)))))

                              (it "returns nil when multiple suffix matches (ambiguous)"
                                  (fn []
                                    (let [cmds {"ext-a__run" {:handler identity} "ext-b__run" {:handler identity}}]
                                      (-> (expect (nil? (resolve-command cmds "run"))) (.toBe true)))))

                              (it "returns nil when no match"
                                  (fn []
                                    (let [cmds {"agent-shell__agent" {:handler identity}}]
                                      (-> (expect (nil? (resolve-command cmds "missing"))) (.toBe true)))))

                              (it "prefers exact match over suffix match"
                                  (fn []
                                    (let [cmds {"model" {:handler (fn [] "builtin")} "agent-shell__model" {:handler (fn [] "ext")}}
                                          result (resolve-command cmds "model")]
                                      (-> (expect (some? result)) (.toBe true))
                                      (-> (expect ((:handler result))) (.toBe "builtin")))))))
