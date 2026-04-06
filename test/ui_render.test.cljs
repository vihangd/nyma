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

(describe "Overlay" (fn []
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
        (-> (expect (lastFrame)) (.toContain "inside overlay")))))))

;;; ─── Header ─────────────────────────────────────────────

(describe "Header" (fn []
  (it "renders with model name"
    (fn []
      (let [agent {:config {:model #js {:modelId "claude-test-1"}}}
            {:keys [lastFrame]} (render #jsx [Header {:agent agent :resources {} :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "nyma"))
        (-> (expect (lastFrame)) (.toContain "claude-test-1")))))))

;;; ─── Footer ─────────────────────────────────────────────

(describe "Footer" (fn []
  (it "renders version and shortcuts"
    (fn []
      (let [{:keys [lastFrame]} (render #jsx [Footer {:agent {} :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "nyma")))))))

;;; ─── MessageBubble ──────────────────────────────────────

(describe "MessageBubble" (fn []
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
        (-> (expect (lastFrame)) (.toContain "something broke")))))))

;;; ─── ToolStartStatus / ToolEndStatus ────────────────────

(describe "ToolStartStatus" (fn []
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
        (-> (expect (lastFrame)) (.toContain "ls")))))))

(describe "ToolEndStatus" (fn []
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

  (it "falls back to built-in when no custom result"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [ToolEndStatus {:tool-name "bash"
                                                       :duration 200
                                                       :result "short output"
                                                       :verbosity "one-line"
                                                       :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "short output")))))))

;;; ─── Editor ─────────────────────────────────────────────

(describe "Editor" (fn []
  (it "renders idle state"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Editor {:onSubmit (fn [])
                                                :streaming false
                                                :steerAcked false
                                                :theme test-theme}])]
        (-> (expect (lastFrame)) (.toBeDefined)))))))
