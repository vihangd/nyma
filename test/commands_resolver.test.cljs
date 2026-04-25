(ns commands-resolver.test
  "Tests for resolve-command — exact lookup, suffix-match fallback, ambiguity."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.resolver :refer [resolve-command]]))

(def ^:private commands
  {"help"              {:handler :help-fn   :description "Help"}
   "settings"          {:handler :settings-fn}
   "ext__agent"        {:handler :agent-fn}
   "ext__shell"        {:handler :shell-fn}
   "other-ext__agent"  {:handler :other-agent-fn}
   "ext__unique"       {:handler :unique-fn}})

;;; ─── exact match ──────────────────────────────────────────────────────────

(describe "resolve-command/exact" (fn []
                                    (it "resolves an exact top-level command"
                                        (fn []
                                          (-> (expect (:handler (resolve-command commands "help"))) (.toBe :help-fn))))

                                    (it "resolves exact namespaced key"
                                        (fn []
                                          (-> (expect (:handler (resolve-command commands "ext__agent"))) (.toBe :agent-fn))))

                                    (it "returns nil for unknown command"
                                        (fn []
                                          (-> (expect (resolve-command commands "unknown")) (.toBeNil))))

                                    (it "returns nil for empty string"
                                        (fn []
                                          (-> (expect (resolve-command commands "")) (.toBeNil))))

                                    (it "returns nil for nil commands map"
                                        (fn []
                                          (-> (expect (resolve-command nil "help")) (.toBeNil))))))

;;; ─── suffix-match fallback ────────────────────────────────────────────────

(describe "resolve-command/suffix-fallback" (fn []
                                              (it "resolves unambiguous namespaced command by short name"
                                                  (fn []
                                                    (-> (expect (:handler (resolve-command commands "unique"))) (.toBe :unique-fn))))

                                              (it "resolves __shell by short name"
                                                  (fn []
                                                    (-> (expect (:handler (resolve-command commands "shell"))) (.toBe :shell-fn))))

                                              (it "returns nil when short name matches multiple namespaces (ambiguous)"
                                                  (fn []
        ;; both ext__agent and other-ext__agent end in __agent
                                                    (-> (expect (resolve-command commands "agent")) (.toBeNil))))

                                              (it "exact match wins over suffix match"
                                                  (fn []
        ;; "settings" is an exact key; if there were also "x__settings" exact wins
                                                    (let [cmds (assoc commands "x__settings" {:handler :x-settings-fn})]
                                                      (-> (expect (:handler (resolve-command cmds "settings"))) (.toBe :settings-fn)))))

                                              (it "returns nil when no exact and no suffix matches"
                                                  (fn []
                                                    (-> (expect (resolve-command commands "nonexistent")) (.toBeNil))))))
