(ns ui-render.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Box Text]]
            ["./agent/ui/overlay.jsx" :refer [Overlay]]
            ["./agent/ui/header.jsx" :refer [Header]]
            ["./agent/ui/footer.jsx" :refer [Footer]]
            ["./agent/ui/chat_view.jsx" :refer [MessageBubble]]
            ["./agent/ui/tool_status.jsx" :refer [ToolStartStatus ToolEndStatus]]
            ["./agent/ui/editor.jsx" :refer [Editor]]))

(def test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"}})

(afterEach (fn [] (cleanup)))

;;; ─── Overlay ────────────────────────────────────────────

(describe "Overlay" (fn [])
          (it "renders string children without crashing"
              (fn []
                (let [{:keys [lastFrame]} (render #jsx [Overlay {:onClose (fn [])} "hello world"])]
                  (-> (expect (lastFrame)) (.toContain "hello world")))))

          (it "renders JSX children without crashing"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [Overlay {:onClose (fn [])}
                                                 [Box [Text "jsx content"]]])]
                  (-> (expect (lastFrame)) (.toContain "jsx content")))))

          (it "conditional overlay renders correctly (Squint #jsx pitfall)"
              (fn []
                (let [show    true
                      content #jsx [Text "inside overlay"]
                      tree    (when show
                                #jsx [Overlay {:onClose (fn [])} content])
                      {:keys [lastFrame]} (render tree)]
                  (-> (expect (lastFrame)) (.toContain "inside overlay"))))))

;;; ─── Header ─────────────────────────────────────────────

(describe "Header" (fn [])
          (it "renders with model name"
              (fn []
                (let [agent {:config {:model #js {:modelId "claude-test-1"}}}
                      {:keys [lastFrame]} (render #jsx [Header {:agent agent :resources {} :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "nyma"))
                  (-> (expect (lastFrame)) (.toContain "claude-test-1"))))))

;;; ─── Footer ─────────────────────────────────────────────

(describe "Footer" (fn [])
          (it "renders version and shortcuts"
              (fn []
                (let [{:keys [lastFrame]} (render #jsx [Footer {:agent {} :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "nyma"))))))

;;; ─── MessageBubble ──────────────────────────────────────

(describe "MessageBubble" (fn [])
          (it "renders user message with prefix"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [MessageBubble {:message {:role "user" :content "hello"}
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "hello")))))

          (it "renders assistant message with prefix"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [MessageBubble {:message {:role "assistant" :content "hi there"}
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "hi there")))))

          (it "renders error message"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [MessageBubble {:message {:role "error" :content "something broke"}
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "something broke"))))))

;;; ─── ToolStartStatus / ToolEndStatus ────────────────────

(describe "ToolStartStatus" (fn [])
          (it "renders tool name"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolStartStatus {:tool-name "read"
                                                                  :args {:path "/tmp/test"}
                                                                  :verbosity "collapsed"
                                                                  :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "read")))))

          (it "renders custom-one-line-args in one-line mode"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolStartStatus {:tool-name "scraper"
                                                                  :args {}
                                                                  :verbosity "one-line"
                                                                  :custom-one-line-args "https://example.com"
                                                                  :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "https://example.com")))))

          (it "renders custom-status-text over custom-one-line-args"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolStartStatus {:tool-name "scraper"
                                                                  :args {}
                                                                  :verbosity "one-line"
                                                                  :custom-one-line-args "https://example.com"
                                                                  :custom-status-text "Fetching page 2/5..."
                                                                  :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "Fetching page 2/5...")))))

          (it "renders custom-icon instead of spinner"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolStartStatus {:tool-name "scraper"
                                                                  :args {}
                                                                  :verbosity "collapsed"
                                                                  :custom-icon "🌐"
                                                                  :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "🌐")))))

          (it "falls back to built-in when no custom fields"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolStartStatus {:tool-name "bash"
                                                                  :args {:command "ls"}
                                                                  :verbosity "one-line"
                                                                  :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "ls")))))

          (it "REGRESSION: uses a static glyph, not a ticking spinner"
              ;; Same structural bug vector as ReasoningBlock — a tool
              ;; start row is often the top of a tall dynamic region
              ;; (reasoning + tool args + editor + status + footer). An
              ;; 80 ms Ink tick would scroll the top line out of
              ;; log-update's erase reach every frame. Visual liveness
              ;; is provided by the status-line activity segment.
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolStartStatus {:tool-name "bash"
                                                                  :args {:command "ls"}
                                                                  :verbosity "collapsed"
                                                                  :theme test-theme}])
                      frame (lastFrame)
                      spinner-chars ["⠋" "⠙" "⠹" "⠸" "⠼"
                                     "⠴" "⠦" "⠧" "⠇" "⠏"]]
                  (-> (expect frame) (.toContain "bash"))
                  ;; Static running glyph must be present.
                  (-> (expect frame) (.toContain "◌"))
                  ;; Spinner frames must NOT.
                  (doseq [c spinner-chars]
                    (-> (expect (.includes frame c)) (.toBe false)))))))

(describe "ToolEndStatus" (fn [])
          (it "renders tool name with checkmark"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "read"
                                                                :duration 150
                                                                :result "file contents"
                                                                :verbosity "collapsed"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "read")))))

          (it "renders custom-one-line-result in one-line mode"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "scraper"
                                                                :duration 500
                                                                :result "lots of html"
                                                                :verbosity "one-line"
                                                                :custom-one-line-result "42 pages scraped"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "42 pages scraped")))))

          (it "renders custom-icon instead of checkmark"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "scraper"
                                                                :duration 100
                                                                :result "ok"
                                                                :verbosity "collapsed"
                                                                :custom-icon "🌐"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "🌐")))))

          (it "falls back to built-in result summary when no custom result"
              ;; ToolEndStatus uses format-one-line-result-for-tool (not raw text).
              ;; For bash, a 3-line result becomes "3 lines".
              ;; Use a let binding so the \n is a real newline (not a JSX escaped attr).
              (fn []
                (let [r3 "line1\nline2\nline3"
                      {:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "bash"
                                                                :duration 200
                                                                :result r3
                                                                :verbosity "one-line"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "3 lines")))))

          (it "shows glob pattern in one-line mode"
    ;; Regression: glob used to display '✓ glob 6ms' with no pattern.
    ;; The pattern is the most useful info (tells the user what was searched).
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "glob"
                                                                :args {:pattern "**/.gitignore"}
                                                                :duration 6
                                                                :result ".gitignore\n"
                                                                :verbosity "one-line"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "**/.gitignore")))))

          (it "shows grep pattern in one-line mode"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "grep"
                                                                :args {:pattern "TODO" :path "src/"}
                                                                :duration 12
                                                                :result "a.cljs:1:TODO\n"
                                                                :verbosity "one-line"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "TODO")))))

          (it "shows read path in one-line mode"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [ToolEndStatus {:tool-name "read"
                                                                :args {:path "/src/main.cljs"}
                                                                :duration 4
                                                                :result "line1\nline2\n"
                                                                :verbosity "one-line"
                                                                :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toContain "/src/main.cljs"))))))

;;; ─── Editor ─────────────────────────────────────────────

(describe "Editor" (fn [])
          (it "renders idle state"
              (fn []
                (let [{:keys [lastFrame]} (render
                                           #jsx [Editor {:onSubmit (fn [])
                                                         :streaming false
                                                         :steerAcked false
                                                         :theme test-theme}])]
                  (-> (expect (lastFrame)) (.toBeDefined))))))
