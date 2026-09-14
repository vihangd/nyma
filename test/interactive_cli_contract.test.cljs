(ns interactive-cli-contract.test
  "The contract between agent.cli and interactive mode, plus the provider-error
   classifier. All pure, so none of it needs a terminal."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.interactive :refer [seed-prompt-of
                                             submit-seed-prompt!
                                             mark-streaming!
                                             notify-role
                                             classify-error-message]]))

;;; ─── a prompt given on the command line runs as the first turn ────────────

(describe "interactive/seed prompt runs as the first turn"
          (fn []
            (it "puts the seed prompt in the editor and submits it"
                (fn []
                  (let [typed    (atom nil)
                        submitted (atom nil)]
                    (submit-seed-prompt! #js {:seed-prompt "fix the build"}
                                         {:set-text (fn [t] (reset! typed t))
                                          :submit   (fn [t] (reset! submitted t))})
                    (-> (expect @typed)     (.toBe "fix the build"))
                    (-> (expect @submitted) (.toBe "fix the build")))))

            (it "accepts the camelCase spelling too"
                (fn []
                  (-> (expect (seed-prompt-of #js {:seedPrompt "hello"})) (.toBe "hello"))))

            (it "submits nothing when there is no seed prompt"
                (fn []
                  (let [submitted (atom :untouched)]
                    (submit-seed-prompt! #js {} {:submit (fn [t] (reset! submitted t))})
                    (submit-seed-prompt! nil  {:submit (fn [t] (reset! submitted t))})
                    (-> (expect @submitted) (.toBe :untouched)))))

            (it "ignores a blank seed prompt"
                (fn []
                  (-> (expect (seed-prompt-of #js {:seed-prompt "   "})) (.toBeUndefined))
                  (-> (expect (seed-prompt-of #js {:seed-prompt ""}))    (.toBeUndefined))))))

;;; ─── a supervisor can see a turn is in flight ─────────────────────────────
;;
;; The streaming flag lived only in the mode's local atom, so cli's SIGINT
;; handler — which reads `:streaming?` off the agent's state — could never tell
;; an in-flight turn from an idle prompt, and Ctrl-C did the wrong thing.

(describe "interactive/streaming state is visible to the CLI"
          (fn []
            (it "mirrors the streaming flag into the agent's state atom"
                (fn []
                  (let [agent {:state (atom {:messages []})}]
                    (mark-streaming! agent true)
                    (-> (expect (:streaming? @(:state agent))) (.toBe true))
                    (mark-streaming! agent false)
                    (-> (expect (:streaming? @(:state agent))) (.toBe false)))))

            (it "leaves the rest of the state alone"
                (fn []
                  (let [agent {:state (atom {:messages [1 2]})}]
                    (mark-streaming! agent true)
                    (-> (expect (count (:messages @(:state agent)))) (.toBe 2)))))

            (it "is a no-op for an agent with no state atom"
                (fn []
                  (-> (expect (mark-streaming! {} true)) (.toBe true))))))

;;; ─── notify keeps its level ───────────────────────────────────────────────

(describe "interactive/notify keeps the level it was given"
          (fn []
            (it "maps each level to its transcript role"
                (fn []
                  (-> (expect (notify-role "info"))    (.toBe "info"))
                  (-> (expect (notify-role "warn"))    (.toBe "warn"))
                  (-> (expect (notify-role "warning")) (.toBe "warn"))
                  (-> (expect (notify-role "error"))   (.toBe "error"))
                  (-> (expect (notify-role "success")) (.toBe "success"))))

            (it "is case-insensitive"
                (fn []
                  (-> (expect (notify-role "ERROR")) (.toBe "error"))
                  (-> (expect (notify-role "Warning")) (.toBe "warn"))))

            (it "falls back to info for nil and for anything unrecognised"
                (fn []
                  (-> (expect (notify-role nil))       (.toBe "info"))
                  (-> (expect (notify-role "gibberish")) (.toBe "info"))))))

;;; ─── a provider error says what to do about it ────────────────────────────

(describe "interactive/provider errors say what to do about them"
          (fn []
            (it "keeps the raw message first"
                (fn []
                  (-> (expect (.startsWith (classify-error-message "401 Unauthorized")
                                           "401 Unauthorized"))
                      (.toBe true))))

            (it "points an auth failure at the key or /login"
                (fn []
                  (doseq [m ["401 Unauthorized"
                             "invalid x-api-key"
                             "Incorrect API key provided"]]
                    (-> (expect (.includes (classify-error-message m)
                                           "check ANTHROPIC_API_KEY or /login <provider>"))
                        (.toBe true)))))

            (it "tells a rate-limited user to try again once retries are spent"
                (fn []
                  (-> (expect (.includes (classify-error-message "429 Too Many Requests")
                                         "try again"))
                      (.toBe true))
                  (-> (expect (.includes (classify-error-message "rate_limit_error")
                                         "try again"))
                      (.toBe true))))

            (it "says retrying while the loop is still retrying"
                (fn []
                  (-> (expect (.includes (classify-error-message "429 slow down" true)
                                         "rate limited; retrying"))
                      (.toBe true))))

            (it "points a context overflow at /compact"
                (fn []
                  (doseq [m ["prompt is too long: 210000 tokens"
                             "context_length_exceeded"
                             "maximum context length is 200000 tokens"]]
                    (-> (expect (.includes (classify-error-message m) "run /compact"))
                        (.toBe true)))))

            (it "adds nothing it cannot classify"
                (fn []
                  (-> (expect (classify-error-message "socket hang up"))
                      (.toBe "socket hang up"))))

            (it "does not throw on nil"
                (fn []
                  (-> (expect (string? (classify-error-message nil))) (.toBe true))))))
