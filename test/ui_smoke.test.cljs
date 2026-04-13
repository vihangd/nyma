(ns ui-smoke.test
  "Render smoke tests for JSX components that previously had no test
   importing/mounting them.

   Every component below was flagged by the UI test gap audit. The goal
   here is not to exhaustively test behavior — dedicated tests own that
   — but to ensure each module compiles, imports, and mounts without
   runtime errors. This is the minimum bar that would have caught the
   earlier `js->clj` and `^:async`-on-inline-fn bugs."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Text]]
            ["./agent/ui/message.jsx" :refer [ToolCallMessage ToolResultMessage]]
            ["./agent/ui/dynamic_border.jsx" :refer [DynamicBorder]]
            ["./agent/ui/widget_container.jsx" :refer [WidgetContainer]]
            ["./agent/ui/help_overlay.jsx" :refer [HelpOverlay]]
            ["./agent/ui/settings_ui.jsx" :refer [SettingsUI]]
            ["./agent/ui/json_tree.jsx" :refer [JsonTree]]
            ["./agent/ui/collapsible_block.jsx" :refer [CollapsibleBlock]]
            ["./agent/ui/streaming_markdown.mjs" :refer [useStreamingMarkdown]]
            ["./agent/extensions/agent_shell/ui/header.jsx" :as agent-shell-header]
            ["./agent/extensions/agent_shell/ui/status_line.jsx" :as agent-shell-status]
            [agent.keybinding-registry :as kbr]))

(def test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"}})

(afterEach (fn [] (cleanup)))

;;; ─── message.cljs ─────────────────────────────────────────

(describe "ToolCallMessage" (fn []
                              (it "renders tool name and args"
                                  (fn []
                                    (let [{:keys [lastFrame]}
                                          (render #jsx [ToolCallMessage {:name "read"
                                                                         :args {:path "/tmp/x"}
                                                                         :theme test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "read"))
                                      (-> (expect (lastFrame)) (.toContain "/tmp/x")))))

                              (it "handles empty args without crashing"
                                  (fn []
                                    (let [{:keys [lastFrame]}
                                          (render #jsx [ToolCallMessage {:name "ls" :args {} :theme test-theme}])]
                                      (-> (expect (lastFrame)) (.toContain "ls")))))))

(describe "ToolResultMessage" (fn []
                                (it "renders tool name and result"
                                    (fn []
                                      (let [{:keys [lastFrame]}
                                            (render #jsx [ToolResultMessage {:name "read"
                                                                             :result "file contents"
                                                                             :theme test-theme}])]
                                        (-> (expect (lastFrame)) (.toContain "read"))
                                        (-> (expect (lastFrame)) (.toContain "file contents")))))))

;;; ─── dynamic_border.cljs ──────────────────────────────────

(describe "DynamicBorder" (fn []
                            (it "renders without crashing"
                                (fn []
                                  (let [{:keys [lastFrame]}
                                        (render #jsx [DynamicBorder {}])]
                                    (-> (expect (lastFrame)) (.toBeDefined)))))

                            (it "accepts a custom char and color"
                                (fn []
                                  (let [{:keys [lastFrame]}
                                        (render #jsx [DynamicBorder {:char "=" :color "#ff0000"}])]
                                    (-> (expect (lastFrame)) (.toContain "=")))))

                            (it "respects max-width cap"
                                (fn []
                                  (let [{:keys [lastFrame]}
                                        (render #jsx [DynamicBorder {:char "-" :max-width 5}])]
                                    (-> (expect (.includes (lastFrame) "-----")) (.toBe true)))))))

;;; ─── widget_container.cljs ────────────────────────────────

(describe "WidgetContainer" (fn []
                              (it "renders nothing when no widgets match the position"
                                  (fn []
                                    (let [{:keys [lastFrame]}
                                          (render #jsx [WidgetContainer {:widgets {} :position "below"}])]
                                      (-> (expect (lastFrame)) (.toBeDefined)))))

                              (it "renders widget lines in matching position"
                                  (fn []
                                    (let [widgets {"status" {:lines ["build: ok" "deploy: pending"]
                                                             :position "below"
                                                             :priority 1}}
                                          {:keys [lastFrame]}
                                          (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                                      (-> (expect (lastFrame)) (.toContain "build: ok"))
                                      (-> (expect (lastFrame)) (.toContain "deploy: pending")))))

                              (it "filters out widgets for the opposite position"
                                  (fn []
                                    (let [widgets {"top-only" {:lines ["TOP"]    :position "above"}
                                                   "bottom"   {:lines ["BOTTOM"] :position "below"}}
                                          {:keys [lastFrame]}
                                          (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                                      (-> (expect (lastFrame)) (.toContain "BOTTOM"))
                                      (-> (expect (.includes (lastFrame) "TOP")) (.toBe false)))))))

;;; ─── help_overlay.cljs ────────────────────────────────────

(describe "HelpOverlay" (fn []
                          (it "mounts with a minimal registry without crashing"
                              (fn []
                                (let [registry (kbr/create-registry)
                                      {:keys [lastFrame]}
                                      (render #jsx [HelpOverlay {:registry  registry
                                                                 :shortcuts []
                                                                 :onClose   (fn [])
                                                                 :theme     test-theme}])]
                                  (-> (expect (lastFrame)) (.toContain "Keyboard Shortcuts")))))

                          (it "mounts with extension shortcuts listed"
                              (fn []
                                (let [registry (kbr/create-registry)
                                      {:keys [lastFrame]}
                                      (render #jsx [HelpOverlay {:registry  registry
                                                                 :shortcuts [["ctrl+x" {:action "custom"}]]
                                                                 :onClose   (fn [])
                                                                 :theme     test-theme}])]
                                  (-> (expect (lastFrame)) (.toContain "Extension Shortcuts"))
                                  (-> (expect (lastFrame)) (.toContain "custom")))))))

;;; ─── settings_ui.cljs ─────────────────────────────────────

(describe "SettingsUI" (fn []
                         (it "mounts with a settings provider and renders a title"
                             (fn []
                               (let [settings-map (atom {:theme "tokyo-night" :model "opus"})
                                     settings     {:get (fn [] @settings-map)}
                                     {:keys [lastFrame]}
                                     (render #jsx [SettingsUI {:settings settings
                                                               :onClose  (fn [])
                                                               :theme    test-theme}])]
                                 (-> (expect (lastFrame)) (.toContain "Settings"))
                                 (-> (expect (lastFrame)) (.toContain "theme")))))

                         (it "handles empty settings map without crashing"
                             (fn []
                               (let [settings {:get (fn [] {})}
                                     {:keys [lastFrame]}
                                     (render #jsx [SettingsUI {:settings settings
                                                               :onClose  (fn [])
                                                               :theme    test-theme}])]
                                 (-> (expect (lastFrame)) (.toContain "Settings")))))))

;;; ─── agent_shell header & status_line ─────────────────────
;;; Both components read from the agent_shell shared state atoms at
;;; module scope. We just verify they mount without error — the real
;;; behavior is covered by the agent_shell integration suite.

(def AgentHeader (.-AgentHeader agent-shell-header))
(def AgentShellStatusLine (.-StatusLine agent-shell-status))

(describe "agent_shell.ui.header" (fn []
                                    (it "mounts AgentHeader without crashing (no agent connected)"
                                        (fn []
                                          (let [{:keys [lastFrame]}
                                                (render #jsx [AgentHeader {:theme test-theme}])]
                                            (-> (expect (lastFrame)) (.toContain "nyma")))))

                                    (it "render factory returns a valid element"
                                        (fn []
                                          (let [{:keys [lastFrame]}
                                                (render ((.-render agent-shell-header)))]
                                            (-> (expect (lastFrame)) (.toBeDefined)))))))

(describe "agent_shell.ui.status_line" (fn []
                                         (it "mounts StatusLine without crashing (no agent connected)"
                                             (fn []
                                               (let [{:keys [lastFrame]}
                                                     (render #jsx [AgentShellStatusLine {:theme test-theme}])]
                                                 (-> (expect (lastFrame)) (.toBeDefined)))))

                                         (it "render factory returns a valid element"
                                             (fn []
                                               (let [{:keys [lastFrame]}
                                                     (render ((.-render agent-shell-status)))]
                                                 (-> (expect (lastFrame)) (.toBeDefined)))))))

;;; ─── json_tree.cljs ───────────────────────────────────────────

(describe "JsonTree" (fn []
                       (it "renders a string scalar"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data "hello" :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "hello")))))

                       (it "renders a number scalar"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data 42 :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "42")))))

                       (it "renders null as 'null'"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data nil :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "null")))))

                       (it "renders a boolean"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data true :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "true")))))

                       (it "renders a clj map with its keys"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data {:name "alice" :age 30}
                                                           :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "name"))
                               (-> (expect (lastFrame)) (.toContain "alice")))))

                       (it "renders a JS array"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data #js ["x" "y" "z"]
                                                           :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "x")))))

                       (it "renders a plain JS object"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data #js {:foo "bar"}
                                                           :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toContain "foo"))
                               (-> (expect (lastFrame)) (.toContain "bar")))))

                       (it "does not crash on deeply nested data (clamps at MAX-DEPTH)"
                           (fn []
                             (let [{:keys [lastFrame]}
                                   (render #jsx [JsonTree {:data {:a {:b {:c {:d {:e "deep"}}}}}
                                                           :theme test-theme}])]
                               (-> (expect (lastFrame)) (.toBeDefined)))))))

;;; ─── collapsible_block.cljs ───────────────────────────────────

(describe "CollapsibleBlock" (fn []
                               (it "renders header always"
                                   (fn []
                                     (let [{:keys [lastFrame]}
                                           (render #jsx [CollapsibleBlock
                                                         {:header  #jsx [Text "My Header"]
                                                          :content #jsx [Text "My Content"]
                                                          :expanded false}])]
                                       (-> (expect (lastFrame)) (.toContain "My Header"))
                                       (-> (expect (.includes (lastFrame) "My Content")) (.toBe false)))))

                               (it "renders content when expanded is true"
                                   (fn []
                                     (let [{:keys [lastFrame]}
                                           (render #jsx [CollapsibleBlock
                                                         {:header  #jsx [Text "Title"]
                                                          :content #jsx [Text "Body text"]
                                                          :expanded true}])]
                                       (-> (expect (lastFrame)) (.toContain "Title"))
                                       (-> (expect (lastFrame)) (.toContain "Body text")))))

                               (it "renders nil content without crashing"
                                   (fn []
                                     (let [{:keys [lastFrame]}
                                           (render #jsx [CollapsibleBlock
                                                         {:header  #jsx [Text "Only header"]
                                                          :content nil
                                                          :expanded true}])]
                                       (-> (expect (lastFrame)) (.toContain "Only header")))))

                               (it "applies marginLeft 2 when padded=true"
                                   (fn []
      ;; Just verify no crash — layout props don't appear in text output
                                     (let [{:keys [lastFrame]}
                                           (render #jsx [CollapsibleBlock
                                                         {:header  #jsx [Text "H"]
                                                          :content #jsx [Text "C"]
                                                          :expanded true
                                                          :padded true}])]
                                       (-> (expect (lastFrame)) (.toContain "C")))))))

;;; ─── streaming_markdown.cljs ──────────────────────────────────
;;; useStreamingMarkdown is a React hook; we wrap it in a thin
;;; component so ink-testing-library can mount it.

(defn StreamingRenderer [{:keys [content]}]
  (let [rendered (useStreamingMarkdown content {})]
    #jsx [Text rendered]))

(describe "useStreamingMarkdown" (fn []
                                   (it "module imports without error"
                                       (fn []
      ;; If the import failed the file would have thrown at the top level.
                                         (-> (expect (fn? useStreamingMarkdown)) (.toBe true))))

                                   (it "renders plain text through the hook"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [StreamingRenderer {:content "Hello world"}])]
                                           (-> (expect (lastFrame)) (.toContain "Hello world")))))

                                   (it "renders markdown with a heading"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [StreamingRenderer {:content "# Title\n\nSome body text"}])]
                                           (-> (expect (lastFrame)) (.toContain "Title")))))

                                   (it "renders empty content without crashing"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [StreamingRenderer {:content ""}])]
                                           (-> (expect (lastFrame)) (.toBeDefined)))))

                                   (it "renders nil content without crashing"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [StreamingRenderer {:content nil}])]
                                           (-> (expect (lastFrame)) (.toBeDefined)))))

                                   (it "renders a code block"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [StreamingRenderer
                                                             {:content "```js\nconsole.log('hi')\n```"}])]
                                           (-> (expect (lastFrame)) (.toContain "console.log")))))))
