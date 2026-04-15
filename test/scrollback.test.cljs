(ns scrollback.test
  "Unit + integration tests for src/agent/ui/scrollback.cljs.

   These tests codify the user's complaint about multi-turn rendering:
   `ink-testing-library` runs Ink in debug mode (`ink.js:255`) which writes
   `fullStaticOutput + output` atomically every frame, so lastFrame-based
   tests can never detect bugs where Static emits a large batch and scrolls
   the terminal. This file uses a fresh mock stdout and ink's headless
   `renderToString` to test the real ANSI output directly."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/scrollback.jsx" :refer [render_message_to_string
                                                 commit_to_scrollback_BANG_]]))

(def ^:private test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :border    "#3b4261"}})

;;; ─── render-message-to-string ──────────────────────────────────────

(describe "render-message-to-string: each message kind renders"
          (fn []

            (it "user message contains its content text"
                (fn []
                  (let [out (render_message_to_string
                             {:role "user" :content "HELLO_USER_MSG" :id "u1"}
                             test-theme nil 80)]
                    (-> (expect (.includes out "HELLO_USER_MSG")) (.toBe true)))))

            (it "assistant message contains its content text"
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :content "THIS_IS_THE_REPLY" :id "a1"}
                             test-theme nil 80)]
                    (-> (expect (.includes out "THIS_IS_THE_REPLY")) (.toBe true)))))

            (it "bash message renders command + stdout"
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :kind :bash :id "b1"
                              :command "ls -la" :stdout "total 42\nfoo.txt"
                              :stderr "" :exit-code 0}
                             test-theme nil 80)]
                    (-> (expect (.includes out "ls -la")) (.toBe true))
                    (-> (expect (.includes out "total 42")) (.toBe true)))))

            (it "eval message renders expression + stdout"
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :kind :eval :id "e1"
                              :expr "(+ 1 2)" :stdout "3"
                              :stderr "" :exit-code 0}
                             test-theme nil 80)]
                    (-> (expect (.includes out "(+ 1 2)")) (.toBe true))
                    (-> (expect (.includes out "3")) (.toBe true)))))

            (it "returns a non-empty string for simple user message"
                (fn []
                  (let [out (render_message_to_string
                             {:role "user" :content "x" :id "u1"}
                             test-theme nil 80)]
                    (-> (expect (> (count out) 0)) (.toBe true)))))

            (it "accepts the columns option without throwing"
                ;; We don't assert specific wrap behavior — useStreamingMarkdown
                ;; handles wrapping its own way, and the point of the columns
                ;; option is that it's forwarded to ink's renderer successfully.
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :content "some text" :id "a1"}
                             test-theme nil 40)]
                    (-> (expect (> (count out) 0)) (.toBe true)))))))

;;; ─── commit-to-scrollback! ─────────────────────────────────────────

(describe "commit-to-scrollback!: writes to the stream exactly once"
          (fn []

            (it "calls write with the rendered message content"
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "user" :content "FIND_ME" :id "u1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (count @calls)) (.toBe 1))
                    (-> (expect (.includes (first @calls) "FIND_ME")) (.toBe true)))))

            (it "is a single batched write, not progressive"
                ;; This is the key regression check: committing must be ONE
                ;; write, not many small fragmented writes. Otherwise log-update
                ;; gets confused about dynamic-region height.
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))
                        long-text (apply str (interpose "\n" (repeat 20 "line")))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "assistant" :content long-text :id "a1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (count @calls)) (.toBe 1)))))

            (it "appends a trailing newline so writes stack correctly"
                ;; Each committed message must end with \n so subsequent
                ;; commits start on a fresh line rather than being glued
                ;; to the previous one.
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "user" :content "abc" :id "u1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (.endsWith (first @calls) "\n")) (.toBe true)))))

            (it "is a no-op when write is nil"
                ;; During unit tests or early render passes, write may be
                ;; unavailable. Don't crash.
                (fn []
                  (commit_to_scrollback_BANG_
                   #js {:write nil
                        :message {:role "user" :content "x" :id "u1"}
                        :theme test-theme
                        :block-renderers nil
                        :columns 80})
                  ;; Reaching here without throwing is the assertion.
                  (-> (expect true) (.toBe true))))))
