(ns prompt-history.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [agent.sessions.storage :refer [create-sqlite-store]]
            ["./agent/extensions/prompt_history/index.mjs" :refer [create_history_picker]]))

;;; ─── SQLite prompt_history table ────────────────────────

(def ^:private test-db-path ":memory:")

(describe "prompt-history:sqlite" (fn []
                                    (let [store (atom nil)]
                                      (beforeEach (fn []
                                                    (let [s (create-sqlite-store test-db-path)]
                                                      ((:init-schema s))
                                                      (reset! store s))))
                                      (afterEach (fn []
                                                   (when @store ((:close @store)))))

                                      (it "inserts and retrieves a prompt"
                                          (fn []
                                            ((:insert-prompt @store) "hello world" "session-1")
                                            (let [results ((:recent-prompts @store) 10)]
                                              (-> (expect (count results)) (.toBe 1))
                                              (-> (expect (:text (first results))) (.toBe "hello world")))))

                                      (it "returns prompts ordered by recency (newest first)"
                                          (fn []
                                            ((:insert-prompt @store) "first" "s1")
                                            ((:insert-prompt @store) "second" "s1")
                                            ((:insert-prompt @store) "third" "s1")
                                            (let [results ((:recent-prompts @store) 10)]
                                              (-> (expect (:text (first results))) (.toBe "third"))
                                              (-> (expect (:text (last results))) (.toBe "first")))))

                                      (it "search-prompts finds matching text"
                                          (fn []
                                            ((:insert-prompt @store) "deploy the auth service" "s1")
                                            ((:insert-prompt @store) "fix the login bug" "s1")
                                            ((:insert-prompt @store) "deploy to staging" "s1")
                                            (let [results ((:search-prompts @store) "deploy" 10)]
                                              (-> (expect (count results)) (.toBe 2)))))

                                      (it "search-prompts escapes SQL wildcards"
                                          (fn []
                                            ((:insert-prompt @store) "100% done" "s1")
                                            ((:insert-prompt @store) "not matched" "s1")
                                            (let [results ((:search-prompts @store) "100%" 10)]
                                              (-> (expect (count results)) (.toBe 1))
                                              (-> (expect (:text (first results))) (.toBe "100% done")))))

                                      (it "recent-prompts respects limit"
                                          (fn []
                                            (dotimes [i 20]
                                              ((:insert-prompt @store) (str "prompt-" i) "s1"))
                                            (let [results ((:recent-prompts @store) 5)]
                                              (-> (expect (count results)) (.toBe 5)))))

                                      (it "returns empty vector when no prompts"
                                          (fn []
                                            (let [results ((:recent-prompts @store) 10)]
                                              (-> (expect (count results)) (.toBe 0))))))))

;;; ─── History picker component ───────────────────────────

(describe "prompt-history:picker" (fn []
                                    (it "renders search UI with prompts"
                                        (fn []
                                          (let [prompts [{:text "hello" :timestamp (- (js/Date.now) 60000) :session-file "s1"}
                                                         {:text "world" :timestamp (- (js/Date.now) 120000) :session-file "s1"}]
                                                picker  (create_history_picker prompts (fn [_]))]
                                            (let [output (.render picker 80 24)]
                                              (-> (expect (.includes output "Search history")) (.toBe true))
                                              (-> (expect (.includes output "hello")) (.toBe true))
                                              (-> (expect (.includes output "world")) (.toBe true))))))

                                    (it "shows 'No matching prompts' when filter has no match"
                                        (fn []
                                          (let [prompts [{:text "hello" :timestamp (js/Date.now) :session-file "s1"}]
                                                picker  (create_history_picker prompts (fn [_]))]
        ;; Simulate typing "zzz"
                                            (.onInput picker "z" #js {})
                                            (.onInput picker "z" #js {})
                                            (.onInput picker "z" #js {})
                                            (let [output (.render picker 80 24)]
                                              (-> (expect (.includes output "No matching prompts")) (.toBe true))))))

                                    (it "calls on-resolve with selected prompt text on Enter"
                                        (fn []
                                          (let [resolved (atom nil)
                                                prompts  [{:text "selected-prompt" :timestamp (js/Date.now) :session-file "s1"}]
                                                picker   (create_history_picker prompts (fn [v] (reset! resolved v)))]
                                            (.onInput picker nil #js {:return true})
                                            (-> (expect @resolved) (.toBe "selected-prompt")))))

                                    (it "calls on-resolve with nil on Escape"
                                        (fn []
                                          (let [resolved (atom :unset)
                                                prompts  [{:text "test" :timestamp (js/Date.now) :session-file "s1"}]
                                                picker   (create_history_picker prompts (fn [v] (reset! resolved v)))]
                                            (.onInput picker nil #js {:escape true})
                                            (-> (expect @resolved) (.toBeNull)))))

                                    (it "filters prompts by typed text"
                                        (fn []
                                          (let [prompts [{:text "deploy service" :timestamp (js/Date.now) :session-file "s1"}
                                                         {:text "fix bug" :timestamp (js/Date.now) :session-file "s1"}]
                                                picker  (create_history_picker prompts (fn [_]))]
                                            (.onInput picker "d" #js {})
                                            (.onInput picker "e" #js {})
                                            (.onInput picker "p" #js {})
                                            (let [output (.render picker 80 24)]
                                              (-> (expect (.includes output "deploy")) (.toBe true))
                                              (-> (expect (.includes output "fix bug")) (.toBe false))))))

                                    (it "key.backspace removes last filter character"
                                        (fn []
                                          (let [prompts [{:text "deploy" :timestamp (js/Date.now) :session-file "s1"}
                                                         {:text "fix"    :timestamp (js/Date.now) :session-file "s1"}]
                                                picker  (create_history_picker prompts (fn [_]))]
                                            (.onInput picker "d" #js {})
        ;; filter = "d" → only deploy
                                            (.onInput picker nil #js {:backspace true})
        ;; filter = "" → both visible
                                            (let [output (.render picker 80 24)]
                                              (-> (expect (.includes output "deploy")) (.toBe true))
                                              (-> (expect (.includes output "fix")) (.toBe true))))))

  ;; Regression: macOS sends \x7f which ink v6 maps to key.delete, not
  ;; key.backspace. Pickers must handle both or backspace silently does nothing.
                                    (it "key.delete (macOS \\x7f backspace) removes last filter character"
                                        (fn []
                                          (let [prompts [{:text "deploy" :timestamp (js/Date.now) :session-file "s1"}
                                                         {:text "fix"    :timestamp (js/Date.now) :session-file "s1"}]
                                                picker  (create_history_picker prompts (fn [_]))]
                                            (.onInput picker "d" #js {})
        ;; filter = "d" → only deploy
                                            (.onInput picker nil #js {:delete true})
        ;; filter = "" → both visible
                                            (let [output (.render picker 80 24)]
                                              (-> (expect (.includes output "deploy")) (.toBe true))
                                              (-> (expect (.includes output "fix")) (.toBe true))))))

                                    ;; Regression: Ctrl+N and Ctrl+P are vi-style navigation
                                    ;; aliases for DownArrow/UpArrow. Same silent-failure mode
                                    ;; as key.delete — if the (and ctrl (= input "n")) branch
                                    ;; regresses, the shortcut dies without any error.
                                    (it "Ctrl+N moves selection down (Enter selects second prompt)"
                                        (fn []
                                          (let [resolved (atom :unset)
                                                prompts  [{:text "first"  :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "second" :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "third"  :timestamp (js/Date.now) :session-file "s1"}]
                                                picker   (create_history_picker prompts (fn [v] (reset! resolved v)))]
                                            (.onInput picker "n" #js {:ctrl true})
                                            (.onInput picker nil #js {:return true})
                                            (-> (expect @resolved) (.toBe "second")))))

                                    (it "Ctrl+P moves selection up"
                                        (fn []
                                          (let [resolved (atom :unset)
                                                prompts  [{:text "first"  :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "second" :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "third"  :timestamp (js/Date.now) :session-file "s1"}]
                                                picker   (create_history_picker prompts (fn [v] (reset! resolved v)))]
                                            ;; Down twice → third; Ctrl+P → second.
                                            (.onInput picker "n" #js {:ctrl true})
                                            (.onInput picker "n" #js {:ctrl true})
                                            (.onInput picker "p" #js {:ctrl true})
                                            (.onInput picker nil #js {:return true})
                                            (-> (expect @resolved) (.toBe "second")))))

                                    ;; Regression: boundary clamps. Up at index 0 must stay at 0;
                                    ;; down past the last item must stay on the last item.
                                    (it "upArrow at index 0 stays on the first prompt"
                                        (fn []
                                          (let [resolved (atom :unset)
                                                prompts  [{:text "first"  :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "second" :timestamp (js/Date.now) :session-file "s1"}]
                                                picker   (create_history_picker prompts (fn [v] (reset! resolved v)))]
                                            (.onInput picker nil #js {:upArrow true})
                                            (.onInput picker nil #js {:upArrow true})
                                            (.onInput picker nil #js {:return true})
                                            (-> (expect @resolved) (.toBe "first")))))

                                    (it "downArrow past the last prompt stays on the last prompt"
                                        (fn []
                                          (let [resolved (atom :unset)
                                                prompts  [{:text "first"  :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "second" :timestamp (js/Date.now) :session-file "s1"}
                                                          {:text "third"  :timestamp (js/Date.now) :session-file "s1"}]
                                                picker   (create_history_picker prompts (fn [v] (reset! resolved v)))]
                                            (.onInput picker nil #js {:downArrow true})
                                            (.onInput picker nil #js {:downArrow true})
                                            (.onInput picker nil #js {:downArrow true})
                                            (.onInput picker nil #js {:downArrow true})
                                            (.onInput picker nil #js {:return true})
                                            (-> (expect @resolved) (.toBe "third")))))))
