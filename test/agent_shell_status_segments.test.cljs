(ns agent-shell-status-segments.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.features.status-segments
             :as seg
             :refer [segments register-all!]]))

(def test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :warning   "#e0af68"}})

(describe "acp status segments: registry shape" (fn []
                                                  (it "exposes every expected id"
                                                      (fn []
                                                        (-> (expect (contains? segments "acp.agent")) (.toBe true))
                                                        (-> (expect (contains? segments "acp.model")) (.toBe true))
                                                        (-> (expect (contains? segments "acp.mode")) (.toBe true))
                                                        (-> (expect (contains? segments "acp.context")) (.toBe true))
                                                        (-> (expect (contains? segments "acp.cost")) (.toBe true))
                                                        (-> (expect (contains? segments "acp.turn-usage")) (.toBe true))))

                                                  (it "segment ids are namespaced under acp."
                                                      (fn []
                                                        (doseq [[id _] segments]
                                                          (-> (expect (.startsWith id "acp.")) (.toBe true)))))))

;;; ─── Visibility behavior: no ACP agent connected ─────

(describe "acp segments when no agent connected" (fn []
                                                   (beforeEach
                                                    (fn []
                                                      (reset! shared/active-agent nil)
                                                      (reset! shared/agent-state {})))

                                                   (it "acp.agent is hidden"
                                                       (fn []
                                                         (let [r ((:render (get segments "acp.agent")) {:theme test-theme})]
                                                           (-> (expect (:visible? r)) (.toBe false)))))

                                                   (it "acp.model is hidden"
                                                       (fn []
                                                         (let [r ((:render (get segments "acp.model")) {:theme test-theme})]
                                                           (-> (expect (:visible? r)) (.toBe false)))))

                                                   (it "acp.mode is hidden"
                                                       (fn []
                                                         (let [r ((:render (get segments "acp.mode")) {:theme test-theme})]
                                                           (-> (expect (:visible? r)) (.toBe false)))))

                                                   (it "acp.context is hidden"
                                                       (fn []
                                                         (let [r ((:render (get segments "acp.context")) {:theme test-theme})]
                                                           (-> (expect (:visible? r)) (.toBe false)))))

                                                   (it "acp.cost is hidden"
                                                       (fn []
                                                         (let [r ((:render (get segments "acp.cost")) {:theme test-theme})]
                                                           (-> (expect (:visible? r)) (.toBe false)))))

                                                   (it "acp.turn-usage is hidden"
                                                       (fn []
                                                         (let [r ((:render (get segments "acp.turn-usage")) {:theme test-theme})]
                                                           (-> (expect (:visible? r)) (.toBe false)))))))

;;; ─── Visibility behavior: agent connected ────────────

(describe "acp segments when agent connected" (fn []
                                                (beforeEach
                                                 (fn []
                                                   (reset! shared/active-agent :claude)
                                                   (reset! shared/agent-state
                                                           {:claude {:model "claude-sonnet-4"
                                                                     :mode  "plan"
                                                                     :usage {:used 42000 :size 200000
                                                                             :cost {:amount 0.123}}
                                                                     :turn-usage {:input-tokens 1500
                                                                                  :output-tokens 800}}})))

                                                (it "acp.agent renders [claude]"
                                                    (fn []
                                                      (let [r ((:render (get segments "acp.agent")) {:theme test-theme})]
                                                        (-> (expect (:visible? r)) (.toBe true))
                                                        (-> (expect (:content r)) (.toBe "[claude]")))))

                                                (it "acp.model renders the model name"
                                                    (fn []
                                                      (let [r ((:render (get segments "acp.model")) {:theme test-theme})]
                                                        (-> (expect (:visible? r)) (.toBe true))
                                                        (-> (expect (:content r)) (.toBe "claude-sonnet-4")))))

                                                (it "acp.mode renders '| plan'"
                                                    (fn []
                                                      (let [r ((:render (get segments "acp.mode")) {:theme test-theme})]
                                                        (-> (expect (:visible? r)) (.toBe true))
                                                        (-> (expect (.includes (:content r) "plan")) (.toBe true)))))

                                                (it "acp.context renders a percentage and progress bar"
                                                    (fn []
                                                      (let [r ((:render (get segments "acp.context")) {:theme test-theme})]
                                                        (-> (expect (:visible? r)) (.toBe true))
                                                        (-> (expect (.includes (:content r) "%")) (.toBe true))
                                                        (-> (expect (.includes (:content r) "ctx")) (.toBe true)))))

                                                (it "acp.cost renders a dollar amount"
                                                    (fn []
                                                      (let [r ((:render (get segments "acp.cost")) {:theme test-theme})]
                                                        (-> (expect (:visible? r)) (.toBe true))
                                                        (-> (expect (.startsWith (:content r) "$")) (.toBe true)))))

                                                (it "acp.turn-usage renders ↑in ↓out"
                                                    (fn []
                                                      (let [r ((:render (get segments "acp.turn-usage")) {:theme test-theme})]
                                                        (-> (expect (:visible? r)) (.toBe true))
                                                        (-> (expect (.includes (:content r) "\u2191")) (.toBe true))
                                                        (-> (expect (.includes (:content r) "\u2193")) (.toBe true)))))))

;;; ─── register-all! contract ─────────────────────────

(describe "register-all!" (fn []
                            (it "calls registerStatusSegment once per segment"
                                (fn []
                                  (let [calls (atom [])
                                        mock-api #js {:registerStatusSegment
                                                      (fn [id config]
                                                        (swap! calls conj {:id id :config config}))}]
                                    (register-all! mock-api)
                                    (-> (expect (count @calls)) (.toBe 7))
                                    (-> (expect (every? (fn [c] (.startsWith (:id c) "acp.")) @calls))
                                        (.toBe true)))))

                            (it "skips when api has no registerStatusSegment"
                                (fn []
      ;; Older hosts without the API should not throw.
                                  (let [mock-api #js {}]
                                    (register-all! mock-api)
                                    (-> (expect true) (.toBe true)))))))

;;; ─── Separators belong to the status line, not the segments ────────────────

(describe "acp status segments: no inline separators" (fn []

  (it "acp.mode renders the bare mode, with no leading pipe"
      (fn []
        ;; These segments replaced a ui.setFooter layout in which everything was
        ;; joined into ONE string, so each piece carried its own " | ". Status
        ;; segments are rendered individually and the status line inserts its
        ;; own divider — the leftover literal rendered as "│ | default".
        (reset! shared/active-agent "claude")
        (reset! shared/agent-state {"claude" {:mode "default"}})
        (let [r ((:render (get segments "acp.mode")) {:theme test-theme})]
          (-> (expect (:visible? r)) (.toBe true))
          (-> (expect (:content r)) (.toBe "default"))
          (-> (expect (.includes (:content r) "|")) (.toBe false)))))

  (it "no visible segment embeds a divider character"
      (fn []
        ;; Guards the whole set, so the next migration cannot reintroduce one.
        (reset! shared/active-agent "claude")
        (reset! shared/agent-state
                {"claude" {:model "claude-opus-4-6"
                           :mode  "plan"
                           :usage {:used 1000 :size 200000}
                           :cost  {:amount 0.33}
                           :turn-usage {:input-tokens 2 :output-tokens 10}}})
        (doseq [[id seg] segments]
          (let [r ((:render seg) {:theme test-theme})]
            (when (:visible? r)
              (-> (expect #js [id (boolean (.includes (str (:content r)) "|"))])
                  (.toEqual #js [id false]))
              (-> (expect #js [id (boolean (.includes (str (:content r)) "│"))])
                  (.toEqual #js [id false])))))))

  (it "acp.mode hides again once the agent disconnects"
      (fn []
        (reset! shared/active-agent nil)
        (reset! shared/agent-state {})
        (-> (expect (:visible? ((:render (get segments "acp.mode")) {:theme test-theme})))
            (.toBe false))))))

;;; ─── acp.tool ──────────────────────────────────────────────────────────────
;;
;; The pane lists every tool call, but during a single long one it is static —
;; and the status line is the row that is always on screen. A minute inside one
;; `execute` with nothing moving is what made a working turn read as hung.

(describe "acp.tool segment" (fn []

  (it "hides when no tool is running"
      (fn []
        (reset! shared/active-agent "claude")
        (shared/update-agent-state! "claude" :current-tool nil)
        (-> (expect (:visible? (seg/acp-tool-segment {:theme {}}))) (.toBe false))))

  (it "names the action and the file"
      (fn []
        (reset! shared/active-agent "claude")
        (shared/update-agent-state! "claude" :current-tool
                                    {:kind "edit" :path "src/auth/store.ts"})
        (let [r (seg/acp-tool-segment {:theme {}})]
          (-> (expect (:visible? r)) (.toBe true))
          (-> (expect (:content r)) (.toBe "editing: src/auth/store.ts")))))

  (it "falls back to the title when no location is reported"
      (fn []
        (shared/update-agent-state! "claude" :current-tool
                                    {:kind "execute" :title "bun test"})
        (-> (expect (:content (seg/acp-tool-segment {:theme {}}))) (.toBe "running: bun test"))))

  (it "describes an unknown kind rather than blanking"
      (fn []
        (shared/update-agent-state! "claude" :current-tool {:kind "teleport" :title "x"})
        (-> (expect (.startsWith (:content (seg/acp-tool-segment {:theme {}})) "working"))
            (.toBe true))))

  (it "hides when no agent is attached"
      (fn []
        (reset! shared/active-agent nil)
        (-> (expect (:visible? (seg/acp-tool-segment {:theme {}}))) (.toBe false))))))
