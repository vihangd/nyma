(ns ext-agent-shell.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.acp.handlers :as handlers]
            [agent.extensions.agent-shell.features.agent-switcher :as agent-switcher]
            [agent.extensions.agent-shell.features.model-switcher :as model-switcher]
            [agent.extensions.agent-shell.features.mode-switcher :as mode-switcher]
            [agent.extensions.agent-shell.features.session-mgmt :as session-mgmt]
            [agent.extensions.agent-shell.acp.notifications :as notifications]
            [agent.extensions.agent-shell.features.permission-ui :as permission-ui]
            [agent.extensions.agent-shell.features.input-router :as input-router]
            [agent.extensions.agent-shell.features.handoff :as handoff]
            [agent.extensions.agent-shell.index :as shell-index]))

;; ── Mock API ────────────────────────────────────────────────────────────────

(defn- make-mock-api
  "Create a mock extension API. Accepts an optional map:
     :select-result — value that api.ui.select resolves to (default nil)
     :ui-available? — whether ui.available is true (default true)"
  ([] (make-mock-api {}))
  ([{:keys [select-result ui-available?]
     :or   {select-result nil ui-available? true}}]
   (let [registered-commands (atom {})
         registered-flags    (atom {})
         event-handlers      (atom {})
         notifications       (atom [])
         select-calls        (atom [])]
     #js {:ui          #js {:available  ui-available?
                            :notify     (fn [msg _level] (swap! notifications conj msg))
                            :setFooter  (fn [_factory] nil)
                            :setHeader  (fn [_factory] nil)
                            :select     (fn [prompt options]
                                          (swap! select-calls conj {:prompt prompt :options options})
                                          (js/Promise.resolve select-result))}
          :registerCommand   (fn [name opts]
                               (swap! registered-commands assoc name opts))
          :unregisterCommand (fn [name]
                               (swap! registered-commands dissoc name))
          :registerFlag      (fn [name opts]
                               (swap! registered-flags assoc name opts))
          ;; Ungated on the real api (extensions.cljs:405). input_router reads
          ;; it inside subscribe to skip thought-streaming when the
          ;; thinking-renderer extension is active.
          :getGlobalFlag     (fn [_name] nil)
          :on                (fn [evt handler _priority]
                               (swap! event-handlers update evt (fnil conj []) handler))
          :off               (fn [evt handler]
                               (swap! event-handlers update evt
                                      (fn [hs] (filterv #(not= % handler) (or hs [])))))
          :emit              (fn [evt data]
                               (doseq [h (get @event-handlers evt [])]
                                 (h data nil)))
          ;; test accessors
          :_commands         registered-commands
          :_flags            registered-flags
          :_events           event-handlers
          :_notifications    notifications
          :_selectCalls      select-calls})))

;; ── Shared state ────────────────────────────────────────────────────────────

(beforeEach
 (fn []
   (reset! shared/active-agent nil)
   (reset! shared/connections {})
   (reset! shared/agent-state {})))

;; ── Registry ────────────────────────────────────────────────────────────────

(describe "agent-shell:registry" (fn []
                                   (it "defines all 6 agents"
                                       (fn []
                                         (-> (expect (count (keys registry/agents))) (.toBe 6))))

                                   (it "claude entry has expected keys"
                                       (fn []
                                         (let [c (get registry/agents :claude)]
                                           (-> (expect (some? c)) (.toBe true))
                                           (-> (expect (:command c)) (.toBe "npx"))
                                           (-> (expect (contains? (:modes c) :plan)) (.toBe true)))))

                                   (it "qwen has init-mode yolo"
                                       (fn []
                                         (-> (expect (:init-mode (get registry/agents :qwen))) (.toBe "yolo"))))

                                   (it "all agents have :command :args :modes :features"
                                       (fn []
                                         (doseq [[k agent] registry/agents]
                                           (-> (expect (string? (:command agent))) (.toBe true))
                                           (-> (expect (vector? (:args agent))) (.toBe true))
                                           (-> (expect (map? (:modes agent))) (.toBe true))
                                           (-> (expect (set? (:features agent))) (.toBe true)))))))

;; ── Shared state helpers ────────────────────────────────────────────────────

(describe "agent-shell:shared" (fn []
                                 (it "format-k formats numbers with k suffix"
                                     (fn []
                                       (-> (expect (shared/format-k 1500)) (.toBe "1.5k"))
                                       (-> (expect (shared/format-k 500)) (.toBe "500"))
                                       (-> (expect (shared/format-k 0)) (.toBe "0"))))

                                 (it "kw-name strips colon prefix from keyword strings"
                                     (fn []
                                       (-> (expect (shared/kw-name ":claude")) (.toBe "claude"))
                                       (-> (expect (shared/kw-name ":plan")) (.toBe "plan"))
                                       (-> (expect (shared/kw-name "plain")) (.toBe "plain"))))

                                 (it "update-agent-state! sets field in atom"
                                     (fn []
                                       (shared/update-agent-state! :claude :model "opus-4")
                                       (-> (expect (shared/get-agent-state :claude :model)) (.toBe "opus-4"))))))

;; ── ACP client helpers ──────────────────────────────────────────────────────

(describe "agent-shell:acp-client" (fn []
                                     (it "parse-ndjson-buffer splits complete lines from remainder"
                                         (fn []
                                           (let [{:keys [complete remainder]}
                                                 (client/parse-ndjson-buffer "{\"a\":1}\n{\"b\":2}\n{\"c\":3")]
                                             (-> (expect (count complete)) (.toBe 2))
                                             (-> (expect remainder) (.toBe "{\"c\":3")))))

                                     (it "parse-ndjson-buffer handles empty string"
                                         (fn []
                                           (let [{:keys [complete remainder]}
                                                 (client/parse-ndjson-buffer "")]
                                             (-> (expect (count complete)) (.toBe 0))
                                             (-> (expect remainder) (.toBe "")))))

                                     (it "next-id increments monotonically"
                                         (fn []
                                           (let [conn {:id-counter (atom 0)}]
                                             (-> (expect (client/next-id conn)) (.toBe 1))
                                             (-> (expect (client/next-id conn)) (.toBe 2))
                                             (-> (expect (client/next-id conn)) (.toBe 3)))))))

;; ── Extension activation smoke test ─────────────────────────────────────────

(describe "agent-shell:activation" (fn []
                                     (it "activates without throwing and returns a deactivator function"
                                         (fn []
                                           (let [api        (make-mock-api)
                                                 activate   (.-default shell-index)
                                                 deactivate (activate api)]
                                             (-> (expect (fn? deactivate)) (.toBe true)))))

                                     (it "registers expected commands on activate"
                                         (fn []
                                           (let [api      (make-mock-api)
                                                 activate (.-default shell-index)
                                                 _        (activate api)
                                                 cmds     @(.-_commands api)]
        ;; mode commands — no /plan: ACP plan mode is /agent mode plan
                                             (-> (expect (contains? cmds "plan")) (.toBe false))
                                             (-> (expect (contains? cmds "yolo")) (.toBe true))
                                             (-> (expect (contains? cmds "approve")) (.toBe true))
        ;; agent switcher
                                             (-> (expect (contains? cmds "agent")) (.toBe true))
        ;; model switcher
                                             (-> (expect (contains? cmds "model")) (.toBe true))
        ;; effort switcher
                                             (-> (expect (contains? cmds "effort")) (.toBe true))
        ;; sessions
                                             (-> (expect (contains? cmds "sessions")) (.toBe true)))))

                                     (it "registers auto-approve flag on activate"
                                         (fn []
                                           (let [api      (make-mock-api)
                                                 activate (.-default shell-index)
                                                 _        (activate api)
                                                 flags    @(.-_flags api)]
                                             (-> (expect (contains? flags "auto-approve")) (.toBe true)))))

                                     (it "deactivator unregisters commands"
                                         (fn []
                                           (let [api        (make-mock-api)
                                                 activate   (.-default shell-index)
                                                 deactivate (activate api)]
                                             (deactivate)
                                             (let [cmds @(.-_commands api)]
                                               (-> (expect (contains? cmds "plan")) (.toBe false))
                                               (-> (expect (contains? cmds "agent")) (.toBe false))))))

                                     (it "hooks session_shutdown event"
                                         (fn []
                                           (let [api      (make-mock-api)
                                                 activate (.-default shell-index)
                                                 _        (activate api)]
                                             (-> (expect (pos? (count (get @(.-_events api) "session_shutdown" [])))) (.toBe true)))))

                                     (it "does not register a top-level /handoff (the handoff extension owns it)"
                                         (fn []
                                           (let [api      (make-mock-api)
                                                 activate (.-default shell-index)
                                                 _        (activate api)
                                                 cmds     @(.-_commands api)]
                                             (-> (expect (contains? cmds "handoff")) (.toBe false))
                                             (-> (expect (contains? cmds "agent")) (.toBe true)))))))

;;; ─── /agent handoff ─────────────────────────────────────────────────────────
;;
;; Agent-scoped handoff moved under /agent. Registering a top-level /handoff
;; here collided with the `handoff` extension's session-brief command: two
;; `__handoff` keys made resolve-command ambiguous, so /handoff answered
;; "Unknown command" whenever agent_shell was loaded.

(defn- agent-handoff-handler
  "The /agent handler, curried so `(h args)` runs `/agent handoff <args>`."
  [api]
  (agent-switcher/activate api)
  (let [h (.-handler (get @(.-_commands api) "agent"))]
    (fn [args ctx] (h (into ["handoff"] (vec args)) ctx))))

(describe "agent-shell:/agent handoff" (fn []
                                         (it "/agent handoff reaches the handoff feature"
                                             (fn []
                                        ;; The subcommand is wired: with no agent connected it must
                                        ;; produce handoff's own error, not "unknown agent: handoff".
                                               (reset! shared/active-agent nil)
                                               (let [api     (make-mock-api)
                                                     handler (agent-handoff-handler api)]
                                                 (handler ["claude"] nil)
                                                 (-> (expect (some #(str/includes? % "No agent connected")
                                                                   @(.-_notifications api)))
                                                     (.toBe true)))))

                                         (it "handoff/activate registers no command of its own"
                                             (fn []
                                               (let [api        (make-mock-api)
                                                     deactivate (handoff/activate api)]
                                                 (-> (expect (contains? @(.-_commands api) "handoff")) (.toBe false))
                                                 (deactivate))))

                                         (it "no arg + UI available → shows interactive picker"
                                             (fn []
                                               (reset! shared/active-agent "qwen")
                                               (let [api     (make-mock-api)
                                                     handler (agent-handoff-handler api)]
                                                 (handler [] nil)
                                                 (-> (expect (count @(.-_selectCalls api))) (.toBe 1)))))

                                         (it "no arg + UI available → picker options include all registered agents"
                                             (fn []
                                               (reset! shared/active-agent "qwen")
                                               (let [api     (make-mock-api)
                                                     handler (agent-handoff-handler api)]
                                                 (handler [] nil)
                                                 (let [call    (first @(.-_selectCalls api))
                                                       options (js/Array.from (:options call))
                                                       values  (mapv #(.-value %) options)]
                                                   (-> (expect (some #(= % "claude") values)) (.toBeTruthy))))))

                                         (it "no arg + user cancels picker (nil) → no handoff attempt"
                                             (fn []
                                               (reset! shared/active-agent "qwen")
                                        ;; select-result nil = user cancelled
                                               (let [api     (make-mock-api {:select-result nil})
                                                     handler (agent-handoff-handler api)]
                                                 (-> (js/Promise.resolve (handler [] nil))
                                                     (.then (fn [_]
                                                       ;; No notification about handing off or errors
                                                              (-> (expect (count @(.-_notifications api)))
                                                                  (.toBe 0))))))))

                                         (it "no arg + UI unavailable → select not called"
                                      ;; When UI is unavailable, the picker is skipped.
                                      ;; notify also guards on ui.available so no output fires — that is
                                      ;; expected: headless/RPC mode has no display channel.
                                             (fn []
                                               (reset! shared/active-agent "qwen")
                                               (let [api     (make-mock-api {:ui-available? false})
                                                     handler (agent-handoff-handler api)]
                                                 (handler [] nil)
                                                 (-> (expect (count @(.-_selectCalls api))) (.toBe 0)))))

                                         (it "handler notifies error for unknown target agent"
                                             (fn []
                                               (reset! shared/active-agent "qwen")
                                               (let [api     (make-mock-api)
                                                     handler (agent-handoff-handler api)]
                                                 (handler ["zzz_no_such_agent_xyz"] nil)
                                                 (-> (expect (some #(str/includes? % "Unknown agent")
                                                                   @(.-_notifications api)))
                                                     (.toBe true)))))

                                         (it "same agent as current → already connected error"
                                             (fn []
                                               (reset! shared/active-agent "qwen")
                                               (let [api     (make-mock-api)
                                                     handler (agent-handoff-handler api)]
                                                 (handler ["qwen"] nil)
                                                 (-> (expect (some #(str/includes? % "Already connected")
                                                                   @(.-_notifications api)))
                                                     (.toBe true)))))

                                         (it "no-agent error check precedes argument check"
                                             (fn []
                                        ;; When active-agent is nil, even empty args give the no-agent error
                                               (reset! shared/active-agent nil)
                                               (let [api     (make-mock-api)
                                                     handler (agent-handoff-handler api)]
                                                 (handler [] nil)
                                                 (-> (expect (some #(str/includes? % "No agent connected")
                                                                   @(.-_notifications api)))
                                                     (.toBe true)))))))

;;; ─── ACP elicitation handler ────────────────────────────────────────────────

(defn- make-test-conn []
  "Minimal connection map with a capturing stdin mock."
  (let [writes (atom [])]
    {:stdin   #js {:write (fn [data] (swap! writes conj data) nil)
                   :flush (fn [] nil)}
     :state   (atom {:pending {} :terminals {}})
     ;; client/send-prompt resets :prompt-state and derefs :session-id before
     ;; writing the request, so a conn without them throws inside subscribe.
     :prompt-state (atom {:text "" :tool-calls []})
     :session-id   (atom "sess_test")
     :id-counter   (atom 0)
     :callbacks    (atom nil)
     :_writes writes}))

(defn- last-result
  "Parse the most recently written JSON-RPC response and return :result."
  [conn]
  (when-let [w (last @(:_writes conn))]
    (.-result (js/JSON.parse w))))

(defn- make-elicitation-parsed
  "Build a minimal mock parsed message for session/elicitation."
  [mode extra-params]
  (let [base #js {:id 42 :method "session/elicitation"
                  :params (clj->js (merge {:mode mode} extra-params))}]
    base))

(describe "agent-shell:acp-elicitation" (fn []
                                          (it "unknown mode sends cancel response"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      parsed (make-elicitation-parsed "bogus_mode" {})
                                                      api    (make-mock-api)]
                                                  (handlers/handle-elicitation conn parsed api)
                                                  (-> (expect (.-action (last-result conn))) (.toBe "cancel")))))

                                          (it "url mode sends accept response"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      parsed (make-elicitation-parsed "url" {:url "https://example.com/auth"})
                                                      api    (make-mock-api)]
                                                  (handlers/handle-elicitation conn parsed api)
                                                  (-> (expect (.-action (last-result conn))) (.toBe "accept")))))

                                          (it "url mode notifies user with the URL when UI is available"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      parsed (make-elicitation-parsed "url" {:url "https://example.com/auth"})
                                                      api    (make-mock-api)]
                                                  (handlers/handle-elicitation conn parsed api)
                                                  (-> (expect (some #(str/includes? % "https://example.com/auth")
                                                                    @(.-_notifications api)))
                                                      (.toBe true)))))

                                          (it "form mode without UI sends decline response"
                                              (fn []
                                                (let [conn   (make-test-conn)
            ;; API with no UI
                                                      api    #js {:ui #js {:available false}}
                                                      parsed (make-elicitation-parsed "form"
                                                                                      {:title "Enter details"
                                                                                       :jsonSchema {:properties {:name {:type "string" :title "Name"}}
                                                                                                    :required   ["name"]}})]
                                                  (handlers/handle-elicitation conn parsed api)
                                                  (-> (expect (.-action (last-result conn))) (.toBe "decline")))))

                                          (it "form mode with null UI sends decline response"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      api    #js {:ui nil}
                                                      parsed (make-elicitation-parsed "form" {:jsonSchema {:properties {:x {:type "string"}}}})]
                                                  (handlers/handle-elicitation conn parsed api)
                                                  (-> (expect (.-action (last-result conn))) (.toBe "decline")))))

                                          (it "form mode with UI but no properties sends decline response"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      notifs (atom [])
                                                      inputs (atom [])
                                                      api    #js {:ui #js {:available true
                                                                           :notify    (fn [m _] (swap! notifs conj m))
                                                                           :input     (fn [l _] (swap! inputs conj l)
                                                                                        (js/Promise.resolve "test-value"))}}
                                                      parsed (make-elicitation-parsed "form" {:jsonSchema nil})]
                                                  (handlers/handle-elicitation conn parsed api)
                                                  (-> (expect (.-action (last-result conn))) (.toBe "decline")))))

                                          (it "form mode with UI and properties returns a promise and collects answers"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      api    #js {:ui #js {:available true
                                                                           :notify    (fn [_ _] nil)
                                                                           :input     (fn [_label _hint]
                                                                                        (js/Promise.resolve "Alice"))}}
                                                      parsed (make-elicitation-parsed "form"
                                                                                      {:title "Who are you?"
                                                                                       :jsonSchema #js {:properties #js {:name #js {:type "string" :title "Your name"}}
                                                                                                        :required   #js ["name"]}})]
                                                  (-> (handlers/handle-elicitation conn parsed api)
                                                      (.then (fn [_]
                                                               (let [r (last-result conn)]
                                                                 (-> (expect (.-action r)) (.toBe "accept"))
                                                                 (-> (expect (.-name (.-content r))) (.toBe "Alice")))))))))

                                          (it "form mode with all-nil answers (user cancels all inputs) sends cancel response" ^:async
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      api    #js {:ui #js {:available true
                                                                           :notify    (fn [_ _] nil)
                                  ;; All inputs return nil (user pressed Escape each time)
                                                                           :input     (fn [_label _hint]
                                                                                        (js/Promise.resolve nil))}}
                                                      parsed (make-elicitation-parsed "form"
                                                                                      {:title "Who are you?"
                                                                                       :jsonSchema #js {:properties #js {:name #js {:type "string" :title "Name"}
                                                                                                                         :age  #js {:type "string" :title "Age"}}
                                                                                                        :required   #js ["name"]}})]
                                                  (-> (handlers/handle-elicitation conn parsed api)
                                                      (.then (fn [_]
                                                               (let [r (last-result conn)]
                       ;; All fields cancelled → should be "cancel", not "accept" with empty content
                                                                 (-> (expect (.-action r)) (.toBe "cancel")))))))))

                                          (it "form mode with partial answers (some nil, some filled) sends accept with filled fields" ^:async
                                              (fn []
                                                (let [conn        (make-test-conn)
                                                      call-count  (atom 0)
                                                      api         #js {:ui #js {:available true
                                                                                :notify    (fn [_ _] nil)
                                       ;; First field filled, second cancelled
                                                                                :input     (fn [_label _hint]
                                                                                             (swap! call-count inc)
                                                                                             (js/Promise.resolve
                                                                                              (if (= @call-count 1) "Alice" nil)))}}
                                                      parsed      (make-elicitation-parsed "form"
                                                                                           {:title "Details"
                                                                                            :jsonSchema #js {:properties #js {:name #js {:type "string" :title "Name"}
                                                                                                                              :age  #js {:type "string" :title "Age"}}
                                                                                                             :required   #js []}})]
                                                  (-> (handlers/handle-elicitation conn parsed api)
                                                      (.then (fn [_]
                                                               (let [r (last-result conn)]
                                                                 (-> (expect (.-action r)) (.toBe "accept"))
                                                                 (-> (expect (.-name (.-content r))) (.toBe "Alice"))
                       ;; age was cancelled (nil), so not in content
                                                                 (-> (expect (.-age (.-content r))) (.toBeUndefined)))))))))

                                          (it "dispatch-reverse-request routes session/elicitation to handle-elicitation"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      api    #js {:ui nil}
                                                      parsed #js {:id 99 :method "session/elicitation"
                                                                  :params #js {:mode "unknown_xyz"}}]
                                                  (handlers/dispatch-reverse-request conn parsed api)
        ;; Should have written a response (cancel for unknown mode)
                                                  (-> (expect (pos? (count @(:_writes conn)))) (.toBe true)))))

                                          (it "dispatch-reverse-request returns -32601 for unknown methods"
                                              (fn []
                                                (let [conn   (make-test-conn)
                                                      api    #js {:ui nil}
                                                      parsed #js {:id 77 :method "nonexistent/method" :params #js {}}]
                                                  (handlers/dispatch-reverse-request conn parsed api)
                                                  (let [w   (last @(:_writes conn))
                                                        msg (js/JSON.parse w)]
                                                    (-> (expect (.. msg -error -code)) (.toBe -32601))))))))

;;; ─── Mode switcher ──────────────────────────────────────────────────────────

(describe "agent-shell:mode-switcher" (fn []
                                        (it "registers /yolo /approve /auto-edit — and never /plan"
                                            (fn []
                                              (let [api  (make-mock-api)
                                                    _    (mode-switcher/activate api)
                                                    cmds @(.-_commands api)]
                                                ;; /plan was claimed here AND by model_roles' native plan
                                                ;; mode, behind mirror-image guards: whoever activated
                                                ;; second skipped, so ownership depended on load order.
                                                ;; ACP plan mode is /agent mode plan.
                                                (-> (expect (contains? cmds "plan")) (.toBe false))
                                                (-> (expect (contains? cmds "yolo")) (.toBe true))
                                                (-> (expect (contains? cmds "approve")) (.toBe true))
                                                (-> (expect (contains? cmds "auto-edit")) (.toBe true)))))

                                        (it "deactivator unregisters all 3 commands"
                                            (fn []
                                              (let [api  (make-mock-api)
                                                    deact (mode-switcher/activate api)]
                                                (deact)
                                                (let [cmds @(.-_commands api)]
                                                  (-> (expect (contains? cmds "approve")) (.toBe false))
                                                  (-> (expect (contains? cmds "yolo")) (.toBe false))))))

                                        (it "with no agent, /yolo says which mode it would have switched"
                                            (fn []
                                              ;; "No agent connected" alone leaves the user thinking /yolo
                                              ;; should have loosened nyma's own approval gate. It does not.
                                              (reset! shared/active-agent nil)
                                              (let [api     (make-mock-api)
                                                    _       (mode-switcher/activate api)
                                                    handler (.-handler (get @(.-_commands api) "yolo"))]
                                                (handler [] nil)
                                                (let [all (str/join " " @(.-_notifications api))]
                                                  (-> (expect (str/includes? all "no agent connected")) (.toBe true))
                                                  (-> (expect (str/includes? all "ACP agent's mode")) (.toBe true))
                                                  (-> (expect (str/includes? all "/mode")) (.toBe true))))))

                                        (it "handler with no connection notifies error"
                                            (fn []
                                              (reset! shared/active-agent "qwen")
                                              (reset! shared/connections {})
                                              (let [api     (make-mock-api)
                                                    _       (mode-switcher/activate api)
                                                    handler (.-handler (get @(.-_commands api) "yolo"))]
                                                (handler [] nil)
                                                (-> (expect (some #(str/includes? % "not connected") @(.-_notifications api)))
                                                    (.toBe true)))))

                                        ;; ACP plan mode's new home.
                                        (it "/agent mode plan routes to the ACP mode switch"
                                            (fn []
                                              (reset! shared/active-agent nil)
                                              (let [api     (make-mock-api)
                                                    _       (agent-switcher/activate api)
                                                    handler (.-handler (get @(.-_commands api) "agent"))]
                                                (handler ["mode" "plan"] nil)
                                                (-> (expect (some #(str/includes? % "no agent connected")
                                                                  @(.-_notifications api)))
                                                    (.toBe true)))))

                                        (it "/agent mode with no id prints usage naming /mode"
                                            (fn []
                                              (reset! shared/active-agent "claude")
                                              (let [api     (make-mock-api)
                                                    _       (agent-switcher/activate api)
                                                    handler (.-handler (get @(.-_commands api) "agent"))]
                                                (handler ["mode"] nil)
                                                (let [all (str/join " " @(.-_notifications api))]
                                                  (-> (expect (str/includes? all "Usage: /agent mode <id>")) (.toBe true))
                                                  (-> (expect (str/includes? all "/mode")) (.toBe true))))))))

;;; ─── Model switcher ─────────────────────────────────────────────────────────

(describe "agent-shell:model-switcher" (fn []
                                         (it "registers /model command on activate"
                                             (fn []
                                               (let [api (make-mock-api)
                                                     _   (model-switcher/activate api)]
                                                 (-> (expect (contains? @(.-_commands api) "model")) (.toBe true)))))

                                         (it "deactivator unregisters /model"
                                             (fn []
                                               (let [api   (make-mock-api)
                                                     deact (model-switcher/activate api)]
                                                 (deact)
                                                 (-> (expect (contains? @(.-_commands api) "model")) (.toBe false)))))

                                         (it "/model <name> with no active agent notifies error"
                                             (fn []
                                               (reset! shared/active-agent nil)
                                               (let [api     (make-mock-api)
                                                     _       (model-switcher/activate api)
                                                     handler (.-handler (get @(.-_commands api) "model"))]
                                                 (handler ["opus-4"] nil)
                                                 (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
                                                     (.toBe true)))))

                                         (it "/model with no args and no agent notifies error"
                                             (fn []
                                               (reset! shared/active-agent nil)
                                               (let [api     (make-mock-api)
                                                     _       (model-switcher/activate api)
                                                     handler (.-handler (get @(.-_commands api) "model"))]
                                                 (handler [] nil)
                                                 (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
                                                     (.toBe true)))))))

;;; ─── Session management ─────────────────────────────────────────────────────

(describe "agent-shell:session-mgmt" (fn []
                                       (it "registers /sessions command on activate"
                                           (fn []
                                             (let [api (make-mock-api)
                                                   _   (session-mgmt/activate api)]
                                               (-> (expect (contains? @(.-_commands api) "sessions")) (.toBe true)))))

                                       (it "deactivator unregisters /sessions"
                                           (fn []
                                             (let [api   (make-mock-api)
                                                   deact (session-mgmt/activate api)]
                                               (deact)
                                               (-> (expect (contains? @(.-_commands api) "sessions")) (.toBe false)))))

                                       (it "/sessions with no active agent notifies error"
                                           (fn []
                                             (reset! shared/active-agent nil)
                                             (let [api     (make-mock-api)
                                                   _       (session-mgmt/activate api)
                                                   handler (.-handler (get @(.-_commands api) "sessions"))]
                                               (handler [] nil)
                                               (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
                                                   (.toBe true)))))

                                       (it "/sessions resume without id notifies usage"
                                           (fn []
                                             (reset! shared/active-agent "claude")
                                             (let [api     (make-mock-api)
                                                   _       (session-mgmt/activate api)
                                                   handler (.-handler (get @(.-_commands api) "sessions"))]
                                               (handler ["resume"] nil)
                                               (-> (expect (some #(str/includes? % "Usage:") @(.-_notifications api)))
                                                   (.toBe true)))))

                                       (it "unknown subcommand notifies usage"
                                           (fn []
                                             (reset! shared/active-agent "claude")
                                             (let [api     (make-mock-api)
                                                   _       (session-mgmt/activate api)
                                                   handler (.-handler (get @(.-_commands api) "sessions"))]
                                               (handler ["bogus_subcmd"] nil)
                                               (-> (expect (some #(str/includes? % "Usage:") @(.-_notifications api)))
                                                   (.toBe true)))))))

;;; ─── Agent switcher ─────────────────────────────────────────────────────────

(describe "agent-shell:agent-switcher" (fn []
                                         (it "registers /agent and /disconnect commands on activate"
                                             (fn []
                                               (let [api (make-mock-api)
                                                     _   (agent-switcher/activate api)]
                                                 (-> (expect (contains? @(.-_commands api) "agent")) (.toBe true))
                                                 (-> (expect (contains? @(.-_commands api) "disconnect")) (.toBe true)))))

                                         (it "deactivator unregisters both commands"
                                             (fn []
                                               (let [api   (make-mock-api)
                                                     deact (agent-switcher/activate api)]
                                                 (deact)
                                                 (-> (expect (contains? @(.-_commands api) "agent")) (.toBe false))
                                                 (-> (expect (contains? @(.-_commands api) "disconnect")) (.toBe false)))))

                                         (it "/agent with no args lists available agents"
                                             (fn []
                                               (let [api     (make-mock-api)
                                                     _       (agent-switcher/activate api)
                                                     handler (.-handler (get @(.-_commands api) "agent"))]
                                                 (handler [] nil)
                                                 (let [msgs (str/join " " @(.-_notifications api))]
                                                   (-> (expect (str/includes? msgs "claude")) (.toBe true))))))

                                         (it "/disconnect with no active agent notifies error"
                                             (fn []
                                               (reset! shared/active-agent nil)
                                               (let [api     (make-mock-api)
                                                     _       (agent-switcher/activate api)
                                                     handler (.-handler (get @(.-_commands api) "disconnect"))]
                                                 (handler [] nil)
                                                 (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
                                                     (.toBe true)))))))

;;; ─── Input router ───────────────────────────────────────────────────────────

(describe "agent-shell:input-router" (fn []
                                       (it "hooks input event on activate"
                                           (fn []
                                             (let [api (make-mock-api)
                                                   _   (input-router/activate api)]
                                               (-> (expect (pos? (count (get @(.-_events api) "input" [])))) (.toBe true)))))

                                       (it "deactivator removes handler"
                                           (fn []
                                             (let [api   (make-mock-api)
                                                   deact (input-router/activate api)]
                                               (deact)
                                               (-> (expect (count (get @(.-_events api) "input" []))) (.toBe 0)))))

                                       (it "returns nil for /slash-commands (pass-through)"
                                           (fn []
                                             (reset! shared/active-agent "claude")
                                             (let [api     (make-mock-api)
                                                   _       (input-router/activate api)
                                                   handler (first (get @(.-_events api) "input"))
                                                   result  (handler #js {:input "/help"} nil)]
                                               (-> (expect (nil? result)) (.toBe true)))))

                                       (it "returns nil when no active agent"
                                           (fn []
                                             (reset! shared/active-agent nil)
                                             (let [api     (make-mock-api)
                                                   _       (input-router/activate api)
                                                   handler (first (get @(.-_events api) "input"))
                                                   result  (handler #js {:input "hello"} nil)]
                                               (-> (expect (nil? result)) (.toBe true)))))

                                       (it "returns stream handler for plain text when agent connected"
                                           (fn []
                                             (let [mock-conn (make-test-conn)]
                                               (reset! shared/active-agent "claude")
                                               (reset! shared/connections {(shared/pool-key "claude" (js/process.cwd)) mock-conn})
                                               (let [api     (make-mock-api)
                                                     _       (input-router/activate api)
                                                     handler (first (get @(.-_events api) "input"))
                                                     result  (handler #js {:input "what is 1+1"} nil)]
                                                 (-> (expect (some? result)) (.toBe true))
                                                 (-> (expect (.-handle result)) (.toBe true))
                                                 (-> (expect (.-streaming result)) (.toBe true))))))

                                       (it "handles //command by forwarding it to the agent"
                                           (fn []
                                             ;; // is the escape for "send this slash command to the ACP agent";
                                             ;; a single / stays local. The interception therefore has to sit ahead
                                             ;; of interactive.cljs's slash branch, which would otherwise swallow it.
                                             (let [mock-conn (make-test-conn)]
                                               (reset! shared/active-agent "claude")
                                               (reset! shared/connections {(shared/pool-key "claude" (js/process.cwd)) mock-conn})
                                               (let [api     (make-mock-api)
                                                     _       (input-router/activate api)
                                                     handler (first (get @(.-_events api) "input"))
                                                     result  (handler #js {:input "//model"} nil)]
                                                 (-> (expect (some? result)) (.toBe true))
                                                 (-> (expect (.-handle result)) (.toBe true))))))

                                       (it "subscribe RETURNS the turn promise so the caller can unlock the editor"
                                           (fn []
                                             ;; Regression: subscribe used to handle .then/.catch internally and
                                             ;; return nil, so interactive.cljs could not tell when the ACP turn had
                                             ;; ended — the submit lock never cleared and the editor stayed wedged.
                                             (let [mock-conn (make-test-conn)]
                                               (reset! shared/active-agent "claude")
                                               (reset! shared/connections {(shared/pool-key "claude" (js/process.cwd)) mock-conn})
                                               (let [api     (make-mock-api)
                                                     _       (input-router/activate api)
                                                     handler (first (get @(.-_events api) "input"))
                                                     result  (handler #js {:input "hi"} nil)
                                                     ret     ((.-subscribe result) (fn [_f] nil))]
                                                 (-> (expect (some? ret)) (.toBe true))
                                                 (-> (expect (fn? (.-then ret))) (.toBe true))))))

                                       (it "append-chunk creates new assistant message for new prompt-id"
                                           (fn []
                                             (let [prev   []
                                                   result (input-router/append-chunk prev "hello" 1)]
                                               (-> (expect (count result)) (.toBe 1))
                                               (-> (expect (:role (first result))) (.toBe "assistant"))
                                               (-> (expect (:content (first result))) (.toBe "hello")))))

                                       (it "append-chunk appends to existing message with same prompt-id"
                                           (fn []
                                             (let [prev   [{:role "assistant" :content "hello" :prompt-id 1}]
                                                   result (input-router/append-chunk prev " world" 1)]
                                               (-> (expect (count result)) (.toBe 1))
                                               (-> (expect (:content (first result))) (.toBe "hello world")))))

                                       (it "append-chunk creates new message for different prompt-id"
                                           (fn []
                                             (let [prev   [{:role "assistant" :content "first" :prompt-id 1}]
                                                   result (input-router/append-chunk prev "second" 2)]
                                               (-> (expect (count result)) (.toBe 2))
                                               (-> (expect (:content (second result))) (.toBe "second")))))

                                       (it "append-thought creates new thinking message for new prompt-id"
                                           (fn []
                                             (let [prev   []
                                                   result (input-router/append-thought prev "thinking..." 1)]
                                               (-> (expect (count result)) (.toBe 1))
                                               (-> (expect (:role (first result))) (.toBe "thinking"))
                                               (-> (expect (:content (first result))) (.toBe "thinking...")))))

                                       (it "append-thought accumulates chunks for same prompt-id"
                                           (fn []
                                             (let [prev   [{:role "thinking" :content "step 1" :prompt-id 2}]
                                                   result (input-router/append-thought prev " step 2" 2)]
                                               (-> (expect (count result)) (.toBe 1))
                                               (-> (expect (:content (first result))) (.toBe "step 1 step 2")))))

                                       (it "append-thought creates new message for different prompt-id"
                                           (fn []
                                             (let [prev   [{:role "thinking" :content "old thought" :prompt-id 1}]
                                                   result (input-router/append-thought prev "new thought" 2)]
                                               (-> (expect (count result)) (.toBe 2))
                                               (-> (expect (:role (second result))) (.toBe "thinking"))
                                               (-> (expect (:prompt-id (second result))) (.toBe 2)))))

                                       (it "append-plan creates plan message with formatted entries"
                                           (fn []
                                             (let [plan-data [{:status "done" :content "step A"}
                                                              {:status "active" :content "step B"}
                                                              {:status "pending" :content "step C"}]
                                                   result    (input-router/append-plan [] plan-data 1)]
                                               (-> (expect (count result)) (.toBe 1))
                                               (-> (expect (:role (first result))) (.toBe "plan"))
                                               (-> (expect (.includes (:content (first result)) "✓ step A")) (.toBe true))
                                               (-> (expect (.includes (:content (first result)) "→ step B")) (.toBe true))
                                               (-> (expect (.includes (:content (first result)) "step C")) (.toBe true)))))

                                       (it "append-plan replaces existing plan with same prompt-id"
                                           (fn []
                                             (let [old-plan {:role "plan" :content "old" :prompt-id 5}
                                                   result   (input-router/append-plan [old-plan]
                                                                                      [{:status "done" :content "updated"}] 5)]
                                               (-> (expect (count result)) (.toBe 1))
                                               (-> (expect (.includes (:content (first result)) "✓ updated")) (.toBe true)))))

                                       (it "append-plan appends new plan for different prompt-id"
                                           (fn []
                                             (let [old-plan {:role "plan" :content "plan A" :prompt-id 3}
                                                   result   (input-router/append-plan [old-plan]
                                                                                      [{:status "active" :content "plan B"}] 4)]
                                               (-> (expect (count result)) (.toBe 2))
                                               (-> (expect (:prompt-id (second result))) (.toBe 4)))))))

;;; ─── ACP permission request handler ────────────────────────────────────────

(defn- make-perm-parsed
  "Build a mock parsed session/request_permission message."
  [id options tool-call]
  #js {:id id
       :method "session/request_permission"
       :params #js {:options (when options (clj->js options))
                    :toolCall (when tool-call (clj->js tool-call))}})

(defn- make-perm-option
  "Build a single permission option JS object."
  [option-id kind name]
  #js {:optionId option-id :kind kind :name name})

(defn- last-write-parsed
  "Parse the most recently written JSON-RPC response."
  [conn]
  (when-let [w (last @(:_writes conn))]
    (js/JSON.parse w)))

(describe "agent-shell:handle-permission-request" (fn []
                                                    (it "auto-approves when UI is not available — picks allow_always first"
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 1
                                                                                         [(make-perm-option "id-allow-once"   "allow_once"   "Allow Once")
                                                                                          (make-perm-option "id-allow-always" "allow_always" "Always Allow")
                                                                                          (make-perm-option "id-deny"         "deny"         "Deny")]
                                                                                         nil)
                                                                api    #js {:ui #js {:available false}}]
                                                            (handlers/handle-permission-request conn parsed api)
                                                            (let [r (.-result (last-write-parsed conn))]
                                                              (-> (expect (.. r -outcome -outcome)) (.toBe "selected"))
                                                              (-> (expect (.. r -outcome -optionId)) (.toBe "id-allow-always"))))))

                                                    (it "auto-approves — picks allow_once when no allow_always"
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 2
                                                                                         [(make-perm-option "id-first"      "deny"        "Deny")
                                                                                          (make-perm-option "id-allow-once" "allow_once"  "Allow Once")]
                                                                                         nil)
                                                                api    #js {:ui #js {:available false}}]
                                                            (handlers/handle-permission-request conn parsed api)
                                                            (let [r (.-result (last-write-parsed conn))]
                                                              (-> (expect (.. r -outcome -outcome)) (.toBe "selected"))
                                                              (-> (expect (.. r -outcome -optionId)) (.toBe "id-allow-once"))))))

                                                    (it "auto-approves — falls back to first option when no allow_always or allow_once"
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 3
                                                                                         [(make-perm-option "first-id" "deny" "Deny")]
                                                                                         nil)
                                                                api    #js {:ui #js {:available false}}]
                                                            (handlers/handle-permission-request conn parsed api)
                                                            (let [r (.-result (last-write-parsed conn))]
                                                              (-> (expect (.. r -outcome -outcome)) (.toBe "selected"))
                                                              (-> (expect (.. r -outcome -optionId)) (.toBe "first-id"))))))

                                                    (it "auto-approves when api is nil"
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 4
                                                                                         [(make-perm-option "opt-1" "allow_once" "Allow")]
                                                                                         nil)]
                                                            (handlers/handle-permission-request conn parsed nil)
                                                            (let [r (.-result (last-write-parsed conn))]
                                                              (-> (expect (.. r -outcome -outcome)) (.toBe "selected"))
                                                              (-> (expect (.. r -outcome -optionId)) (.toBe "opt-1"))))))

                                                    (it "UI path: extracts .value from selected option object" ^:async
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 5
                                                                                         [(make-perm-option "real-opt-id" "allow_once" "Allow Once")]
                                                                                         {:name "bash" :title "Run bash"})
            ;; Mock select resolves with the full option object, as app.cljs does
                                                                api    #js {:ui #js {:available true
                                                                                     :select    (fn [_title _opts]
                                                                                                  (js/Promise.resolve
                                                                                                   #js {:label "Allow Once (allow_once)"
                                                                                                        :value "real-opt-id"}))}}]
                                                            (-> (handlers/handle-permission-request conn parsed api)
                                                                (.then (fn [_]
                                                                         (let [r (.-result (last-write-parsed conn))]
                                                                           (-> (expect (.. r -outcome -outcome)) (.toBe "selected"))
                       ;; Must be the string ID, NOT the JS option object
                                                                           (-> (expect (.. r -outcome -optionId)) (.toBe "real-opt-id")))))))))

                                                    (it "UI path: sends cancelled when dialog resolves nil (Escape pressed)" ^:async
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 6
                                                                                         [(make-perm-option "opt-1" "allow_once" "Allow")]
                                                                                         nil)
                                                                api    #js {:ui #js {:available true
                                                                                     :select    (fn [_title _opts]
                                                                                                  (js/Promise.resolve nil))}}]
                                                            (-> (handlers/handle-permission-request conn parsed api)
                                                                (.then (fn [_]
                                                                         (let [r (.-result (last-write-parsed conn))]
                                                                           (-> (expect (.. r -outcome -outcome)) (.toBe "cancelled")))))))))

                                                    (it "UI path: sends cancelled when dialog rejects" ^:async
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 7
                                                                                         [(make-perm-option "opt-1" "allow_once" "Allow")]
                                                                                         nil)
                                                                api    #js {:ui #js {:available true
                                                                                     :select    (fn [_title _opts]
                                                                                                  (js/Promise.reject (js/Error. "dismissed")))}}]
                                                            (-> (handlers/handle-permission-request conn parsed api)
                                                                (.then (fn [_]
                                                                         (let [r (.-result (last-write-parsed conn))]
                                                                           (-> (expect (.. r -outcome -outcome)) (.toBe "cancelled")))))))))

                                                    (it "title uses tool name when tool-call is provided" ^:async
                                                        (fn []
                                                          (let [conn       (make-test-conn)
                                                                title-seen (atom nil)
                                                                parsed     (make-perm-parsed 8
                                                                                             [(make-perm-option "opt-1" "allow_once" "Allow")]
                                                                                             {:name "bash" :title "Execute bash"})
                                                                api        #js {:ui #js {:available true
                                                                                         :select    (fn [title _opts]
                                                                                                      (reset! title-seen title)
                                                                                                      (js/Promise.resolve nil))}}]
                                                            (-> (handlers/handle-permission-request conn parsed api)
                                                                (.then (fn [_]
                                                                         (-> (expect (str/includes? @title-seen "Execute bash")) (.toBe true))))))))

                                                    (it "dispatch-reverse-request routes session/request_permission"
                                                        (fn []
                                                          (let [conn   (make-test-conn)
                                                                parsed (make-perm-parsed 9
                                                                                         [(make-perm-option "opt-1" "allow_once" "Allow")]
                                                                                         nil)
                                                                api    #js {:ui #js {:available false}}]
                                                            (handlers/dispatch-reverse-request conn parsed api)
                                                            (-> (expect (pos? (count @(:_writes conn)))) (.toBe true)))))))

;;; ─── Session picker value extraction ───────────────────────────────────────

(defn- make-full-conn
  "Connection map with id-counter and session-id for ACP request tests."
  []
  (let [writes (atom [])]
    {:stdin      #js {:write (fn [data] (swap! writes conj data) nil)
                      :flush (fn [] nil)}
     :state      (atom {:pending {} :terminals {}})
     :id-counter (atom 0)
     :session-id (atom nil)
     :_writes    writes}))

(defn- resolve-pending!
  "Resolve the first pending ACP request on conn with result."
  [conn result]
  (let [pending (:pending @(:state conn))
        [id {:keys [resolve]}] (first pending)]
    (when resolve
      (swap! (:state conn) update :pending dissoc id)
      (resolve result))))

(describe "agent-shell:session-picker" (fn []
                                         (it "extracts .value string from option object returned by select dialog" ^:async
                                             (fn []
                                               (reset! shared/active-agent "claude")
                                               (let [conn (make-full-conn)
                                                     _    (reset! shared/connections {(shared/pool-key "claude" (js/process.cwd)) conn})
                                                     api  (make-mock-api)]
        ;; Add .select to the mock UI — simulates app.cljs resolving with full option object
                                                 (set! (.-select (.-ui api))
                                                       (fn [_title _opts]
                                                         (js/Promise.resolve #js {:label "My Session (sess-abc)" :value "sess-abc"})))
                                                 (session-mgmt/activate api)
                                                 (let [handler (.-handler (get @(.-_commands api) "sessions"))]
          ;; Trigger list-sessions
                                                   (handler [] nil)
          ;; Let send-request register its pending entry
                                                   (js-await (js/Promise. (fn [r] (js/setTimeout r 0))))
          ;; Resolve session/list with one session
                                                   (resolve-pending! conn #js {:sessions #js [#js {:sessionId "sess-abc"
                                                                                                   :title "My Session"}]})
          ;; Let the .then chain run (select resolves, resume-session fires)
                                                   (js-await (js/Promise. (fn [r] (js/setTimeout r 0))))
                                                   (js-await (js/Promise. (fn [r] (js/setTimeout r 0))))
          ;; session/resume must be sent first (falls back to session/load if -32601)
                                                   (let [resume-req (some (fn [w]
                                                                            (let [p (js/JSON.parse w)]
                                                                              (when (= (.-method p) "session/resume") p)))
                                                                          @(:_writes conn))]
                                                     (-> (expect (some? resume-req)) (.toBe true))
                                                     (-> (expect (.-sessionId (.-params resume-req))) (.toBe "sess-abc")))))))

                                         (it "does not call resume-session when dialog is cancelled (nil)" ^:async
                                             (fn []
                                               (reset! shared/active-agent "claude")
                                               (let [conn (make-full-conn)
                                                     _    (reset! shared/connections {(shared/pool-key "claude" (js/process.cwd)) conn})
                                                     api  (make-mock-api)]
                                                 (set! (.-select (.-ui api))
                                                       (fn [_title _opts] (js/Promise.resolve nil)))
                                                 (session-mgmt/activate api)
                                                 (let [handler (.-handler (get @(.-_commands api) "sessions"))]
                                                   (handler [] nil)
                                                   (js-await (js/Promise. (fn [r] (js/setTimeout r 0))))
                                                   (resolve-pending! conn #js {:sessions #js [#js {:sessionId "sess-1" :title "S1"}]})
                                                   (js-await (js/Promise. (fn [r] (js/setTimeout r 0))))
                                                   (js-await (js/Promise. (fn [r] (js/setTimeout r 0))))
          ;; No session/resume or session/load should have been written
                                                   (let [resume-req (some (fn [w]
                                                                            (let [p (js/JSON.parse w)]
                                                                              (when (or (= (.-method p) "session/resume")
                                                                                        (= (.-method p) "session/load")) p)))
                                                                          @(:_writes conn))]
                                                     (-> (expect (nil? resume-req)) (.toBe true)))))))))

;;; ─── Permission UI ──────────────────────────────────────────────────────────

(describe "agent-shell:permission-ui" (fn []
                                        (it "registers auto-approve flag on activate"
                                            (fn []
                                              (let [api (make-mock-api)
                                                    _   (permission-ui/activate api)]
                                                (-> (expect (contains? @(.-_flags api) "auto-approve")) (.toBe true)))))

                                        (it "auto-approve flag defaults to false"
                                            (fn []
                                              (let [api (make-mock-api)
                                                    _   (permission-ui/activate api)]
                                                (-> (expect (.-default (get @(.-_flags api) "auto-approve"))) (.toBe false)))))

                                        (it "deactivator returns a function"
                                            (fn []
                                              (let [api   (make-mock-api)
                                                    deact (permission-ui/activate api)]
                                                (-> (expect (fn? deact)) (.toBe true)))))))

;;; ─── Completed-edit tracking ───────────────────────────────────────────────

(describe "agent-shell:edit tracking" (fn []

                                        (it "counts only mutating tool calls that completed"
                                            (fn []
        ;; A denied or failed write changed nothing, and must not make
        ;; /plan-capture claim the agent already did the work.
                                              (shared/clear-transcript! "claude@/x")
                                              (shared/record-tool-call! "claude@/x" "edit" "completed")
                                              (shared/record-tool-call! "claude@/x" "edit" "failed")
                                              (shared/record-tool-call! "claude@/x" "read" "completed")
                                              (shared/record-tool-call! "claude@/x" "search" "completed")
                                              (-> (expect (shared/edit-count "claude@/x")) (.toBe 1))))

                                        (it "counts delete and move as changes too"
                                            (fn []
                                              (shared/clear-transcript! "claude@/y")
                                              (shared/record-tool-call! "claude@/y" "delete" "completed")
                                              (shared/record-tool-call! "claude@/y" "move" "completed")
                                              (-> (expect (shared/edit-count "claude@/y")) (.toBe 2))))

                                        (it "is per pool key and cleared with the transcript"
                                            (fn []
                                              (shared/clear-transcript! "claude@/a")
                                              (shared/clear-transcript! "claude@/b")
                                              (shared/record-tool-call! "claude@/a" "edit" "completed")
                                              (-> (expect (shared/edit-count "claude@/b")) (.toBe 0))
                                              (shared/clear-transcript! "claude@/a")
                                              (-> (expect (shared/edit-count "claude@/a")) (.toBe 0))))))

;;; ─── Transcript accumulation ───────────────────────────────────────────────
;;
;; `/plan-capture` reads this and nothing else: agent-state holds only
;; :plan/:mode/:model/:usage, `(:prompt-state conn)` is reset every prompt, and
;; the ACP stream drops user turns outright (`user_message_chunk` → nil,
;; "replay only"). Three properties decide whether a captured plan is the one
;; you were looking at, and each was a gap found in review rather than a
;; hypothetical.

(describe "agent-shell:transcript" (fn []

                                     (it "keeps both roles, oldest first"
                                         (fn []
        ;; The user turn is only here because send-prompt records it at the
        ;; call site — the stream never yields it, so `request:` in the
        ;; artifact header has no other source.
                                           (shared/clear-transcript! "claude@/p")
                                           (shared/append-turn! "claude@/p" "user" "add OAuth login")
                                           (shared/append-turn! "claude@/p" "assistant" "1. do a thing")
                                           (let [ts (shared/get-transcript "claude@/p")]
                                             (-> (expect (count ts)) (.toBe 2))
                                             (-> (expect (:role (first ts))) (.toBe "user"))
                                             (-> (expect (:text (first ts))) (.toBe "add OAuth login"))
                                             (-> (expect (:role (second ts))) (.toBe "assistant")))))

                                     (it "is keyed by pool key, so two projects do not interleave"
                                         (fn []
        ;; agent-state is keyed by agent ALONE while the pool is keyed by
        ;; [agent, cwd], and the gateway deliberately fans out that way. A
        ;; transcript stored under the agent would splice two projects' plans
        ;; into one capture.
                                           (shared/clear-transcript! "claude@/one")
                                           (shared/clear-transcript! "claude@/two")
                                           (shared/append-turn! "claude@/one" "user" "project one")
                                           (shared/append-turn! "claude@/two" "user" "project two")
                                           (-> (expect (count (shared/get-transcript "claude@/one"))) (.toBe 1))
                                           (-> (expect (:text (first (shared/get-transcript "claude@/one"))))
                                               (.toBe "project one"))
                                           (-> (expect (:text (first (shared/get-transcript "claude@/two"))))
                                               (.toBe "project two"))))

                                     (it "clears, so a reconnect cannot capture the previous session's plan"
                                         (fn []
        ;; pool/disconnect nils active-agent and nothing else; without an
        ;; explicit clear, /plan-capture after a reconnect would happily write
        ;; a stale plan under a fresh session's name.
                                           (shared/append-turn! "claude@/gone" "user" "old plan")
                                           (shared/clear-transcript! "claude@/gone")
                                           (-> (expect (shared/get-transcript "claude@/gone")) (.toEqual #js []))))

                                     (it "ignores blank turns rather than recording phantoms"
                                         (fn []
                                           (shared/clear-transcript! "claude@/blank")
                                           (shared/append-turn! "claude@/blank" "assistant" "   ")
                                           (shared/append-turn! "claude@/blank" "assistant" "")
                                           (shared/append-turn! "claude@/blank" "assistant" nil)
                                           (-> (expect (count (shared/get-transcript "claude@/blank"))) (.toBe 0))))

                                     (it "caps a long planning session by dropping the oldest turns"
                                         (fn []
        ;; Unbounded otherwise, and the useful part of a planning session is
        ;; always the tail — the plan is the last thing said.
                                           (shared/clear-transcript! "claude@/long")
                                           (doseq [i (range 60)]
                                             (shared/append-turn! "claude@/long" "user" (str "turn-" i)))
                                           (let [ts (shared/get-transcript "claude@/long")]
                                             (-> (expect (count ts)) (.toBeLessThanOrEqual 40))
          ;; newest kept, oldest dropped
                                             (-> (expect (:text (last ts))) (.toBe "turn-59"))
                                             (-> (expect (some (fn [t] (= "turn-0" (:text t))) ts)) (.toBeFalsy)))))))

;;; ─── Live tool activity ────────────────────────────────────────────────────
;;
;; `acp_tool_start` / `acp_tool_update` were emitted as events that NOTHING in
;; the repo subscribed to. Claude Code spends most of a turn reading and editing
;; before it emits any text, so the entire working period rendered blank and a
;; turn looked hung.

(describe "agent-shell:tool activity lines" (fn []

                                              (it "labels by ACP kind and prefers the reported location"
                                                  (fn []
        ;; `locations` is what the spec provides for follow-along; a path says
        ;; more in one line than the title's prose.
                                                    (-> (expect (input-router/tool-line {:kind "read" :title "Reading a file"
                                                                                         :path "src/auth/store.ts" :status "completed"}))
                                                        (.toBe "⚒ Read  src/auth/store.ts  ✓"))
                                                    (-> (expect (input-router/tool-line {:kind "execute" :title "bun test" :status "failed"}))
                                                        (.toBe "⚒ Bash  bun test  ✗"))))

                                              (it "shows an unknown kind rather than swallowing it"
                                                  (fn []
                                                    (-> (expect (.includes (input-router/tool-line {:kind "teleport" :title "x" :status "pending"})
                                                                           "teleport"))
                                                        (.toBe true))))

                                              (it "appends a new call and updates it in place"
                                                  (fn []
                                                    (let [a (input-router/upsert-tool [] {:id "c1" :kind "read" :path "a.ts"
                                                                                          :status "pending"} 1)
                                                          b (input-router/upsert-tool a {:id "c1" :status "completed"} 1)]
                                                      (-> (expect (count a)) (.toBe 1))
                                                      (-> (expect (count b)) (.toBe 1))
                                                      (-> (expect (.includes (:content (first b)) "✓")) (.toBe true)))))

                                              (it "MERGES an update, so a status-only frame does not blank the title"
                                                  (fn []
        ;; Every field but toolCallId is optional on tool_call_update.
                                                    (let [a (input-router/upsert-tool [] {:id "c1" :kind "edit" :path "src/x.ts"
                                                                                          :status "pending"} 1)
                                                          b (input-router/upsert-tool a {:id "c1" :title nil :kind nil :path nil
                                                                                         :status "completed"} 1)]
                                                      (-> (expect (.includes (:content (first b)) "Edit")) (.toBe true))
                                                      (-> (expect (.includes (:content (first b)) "src/x.ts")) (.toBe true)))))

                                              (it "keeps separate calls separate, and separate prompts separate"
                                                  (fn []
                                                    (let [v (-> []
                                                                (input-router/upsert-tool {:id "c1" :kind "read" :status "pending"} 1)
                                                                (input-router/upsert-tool {:id "c2" :kind "edit" :status "pending"} 1)
                                                                (input-router/upsert-tool {:id "c1" :kind "read" :status "pending"} 2))]
          ;; c1 from prompt 2 is a different line than c1 from prompt 1 —
          ;; ids are only unique within a session, and re-using a line across
          ;; turns would rewrite history.
                                                      (-> (expect (count v)) (.toBe 3)))))))

;;; ─── Session replay ────────────────────────────────────────────────────────
;;
;; `session/load` streams the ENTIRE prior conversation back as session/update
;; notifications and only then answers the request. Those frames were
;; indistinguishable from live ones: the whole history re-streamed into the UI,
;; historical edits inflated the count /plan-capture warns on, and a replayed
;; plan overwrote the live one. Meanwhile `user_message_chunk` was dropped
;; ("replay only") — so the one context where it carries data discarded it, and
;; the capture transcript stayed EMPTY after a resume.

(defn- replay-conn [pk]
  {:pool-key pk :agent-key "claude"
   :replaying? (atom false)
   :prompt-state (atom {:text "" :tool-calls []})
   :callbacks (atom nil)
   :emit (fn [_ _] nil)})

(defn- upd
  "One session/update frame. Built with aset rather than cond-> because the
   payload is a plain JS object all the way down, exactly as JSON.parse leaves
   it."
  [utype {:keys [text tool-id kind status]}]
  (let [u #js {:sessionUpdate utype}]
    (when text    (aset u "content" #js {:type "text" :text text}))
    (when tool-id (aset u "toolCallId" tool-id))
    (when kind    (aset u "kind" kind))
    (when status  (aset u "status" status))
    #js {:method "session/update" :params #js {:update u}}))

(describe "agent-shell:session replay" (fn []

                                         (it "rebuilds BOTH roles into the capture transcript"
                                             (fn []
                                               (let [pk "claude@/replay-a"
                                                     conn (replay-conn pk)]
                                                 (shared/clear-transcript! pk)
                                                 (reset! (:replaying? conn) true)
                                                 (notifications/dispatch-notification conn (upd "user_message_chunk" {:text "add OAuth"}) nil)
                                                 (notifications/dispatch-notification conn (upd "agent_message_chunk" {:text "1. do it"}) nil)
                                                 (reset! (:replaying? conn) false)
                                                 (let [ts (shared/get-transcript pk)]
                                                   (-> (expect (count ts)) (.toBe 2))
                                                   (-> (expect (:role (first ts))) (.toBe "user"))
                                                   (-> (expect (:text (first ts))) (.toBe "add OAuth"))
                                                   (-> (expect (:role (second ts))) (.toBe "assistant"))))))

                                         (it "still drops user_message_chunk when NOT replaying"
                                             (fn []
        ;; Live, the prompt is recorded at the call site instead; recording it
        ;; here as well would double every turn.
                                               (let [pk "claude@/replay-b"
                                                     conn (replay-conn pk)]
                                                 (shared/clear-transcript! pk)
                                                 (notifications/dispatch-notification conn (upd "user_message_chunk" {:text "hi"}) nil)
                                                 (-> (expect (count (shared/get-transcript pk))) (.toBe 0)))))

                                         (it "does not count replayed edits against the plan-capture warning"
                                             (fn []
                                               (let [pk "claude@/replay-c"
                                                     conn (replay-conn pk)]
                                                 (shared/clear-transcript! pk)
                                                 (reset! (:replaying? conn) true)
                                                 (notifications/dispatch-notification
                                                  conn (upd "tool_call" {:tool-id "t1" :kind "edit" :status "completed"}) nil)
                                                 (-> (expect (shared/edit-count pk)) (.toBe 0))
          ;; …but a live one still counts.
                                                 (reset! (:replaying? conn) false)
                                                 (notifications/dispatch-notification
                                                  conn (upd "tool_call" {:tool-id "t2" :kind "edit" :status "completed"}) nil)
                                                 (-> (expect (shared/edit-count pk)) (.toBe 1)))))

                                         (it "hands replayed turns to the pane renderer"
                                             (fn []
                                               (let [pk "claude@/replay-d"
                                                     conn (replay-conn pk)
                                                     seen (atom [])]
                                                 (shared/clear-transcript! pk)
                                                 (reset! shared/replay-callback (fn [t] (swap! seen conj t)))
                                                 (reset! (:replaying? conn) true)
                                                 (notifications/dispatch-notification conn (upd "agent_message_chunk" {:text "hello"}) nil)
                                                 (reset! (:replaying? conn) false)
                                                 (reset! shared/replay-callback nil)
                                                 (-> (expect (count @seen)) (.toBe 1))
                                                 (-> (expect (:role (first @seen))) (.toBe "assistant")))))

                                         (it "does not treat replay as live output"
                                             (fn []
        ;; The stream callback drives the "agent is answering now" rendering;
        ;; firing it for history would replay the conversation as if it were
        ;; arriving.
                                               (let [conn (replay-conn "claude@/replay-e")
                                                     hits (atom 0)]
                                                 (reset! shared/stream-callback (fn [_] (swap! hits inc)))
                                                 (reset! (:replaying? conn) true)
                                                 (notifications/dispatch-notification conn (upd "agent_message_chunk" {:text "x"}) nil)
                                                 (reset! shared/stream-callback nil)
                                                 (-> (expect @hits) (.toBe 0)))))))

;;; ─── Remembered sessions ───────────────────────────────────────────────────

(describe "agent-shell:remembered sessions" (fn []

                                              (it "round-trips a session id through the state capability"
                                                  (fn []
        ;; agent_shell declared the `state` capability and never used it, so
        ;; every session id died with the process.
                                                    (let [store (atom {})
                                                          api   #js {:state #js {:get (fn [k] (get @store (str k)))
                                                                                 :set (fn [k v] (swap! store assoc (str k) v))
                                                                                 :delete (fn [k] (swap! store dissoc (str k)))}}]
                                                      (shared/remember-session! api "claude" "sess-123" "OAuth work")
                                                      (let [got (shared/recall-session api "claude")]
                                                        (-> (expect (aget got "sessionId")) (.toBe "sess-123"))
                                                        (-> (expect (aget got "title")) (.toBe "OAuth work"))))))

                                              (it "is keyed per project, so two checkouts do not collide"
                                                  (fn []
                                                    (-> (expect (= (shared/store-key "claude" "/a") (shared/store-key "claude" "/b")))
                                                        (.toBe false))))

                                              (it "is silent when the extension has no state capability"
                                                  (fn []
        ;; Must not throw: a read-only or missing ext-state dir cannot be
        ;; allowed to break the session itself.
                                                    (-> (expect (shared/remember-session! #js {} "claude" "s1")) (.toBeNil))
                                                    (-> (expect (shared/recall-session #js {} "claude")) (.toBeNil))))))

(describe "agent-shell:session-mgmt guards" (fn []

                                              (it "detects method-not-found by CODE, not just message text"
                                                  (fn []
        ;; handle-response used to format the error and drop `.-code`, so the
        ;; session/load fallback could never fire on the code.
                                                    (let [e (js/Error. "ACP error: nope")]
                                                      (aset e "code" -32601)
                                                      (-> (expect (session-mgmt/method-not-found? e)) (.toBe true)))
                                                    (-> (expect (session-mgmt/method-not-found? (js/Error. "Method not found")))
                                                        (.toBe true))
                                                    (-> (expect (session-mgmt/method-not-found? (js/Error. "boom"))) (.toBe false))))

                                              (it "refuses in-process runners instead of hanging"
                                                  (fn []
        ;; :stdin is nil and safe-write swallows the failure, so the request
        ;; registered a promise that never settled — a silent hang.
                                                    (-> (expect (.includes (session-mgmt/in-process-refusal {:in-process? true} "claude-sdk")
                                                                           "in-process"))
                                                        (.toBe true))
                                                    (-> (expect (session-mgmt/in-process-refusal {:in-process? false} "claude")) (.toBeNil))))))

;;; ─── Inline thinking ───────────────────────────────────────────────────────
;;
;; The router wired thinking into the transcript only when
;; `thinking-renderer__active` was falsy. That flag defaults TRUE and the
;; extension is commonly installed, so thinking never appeared inline and there
;; was no way to ask for it — an unconditional, undocumented coupling to
;; whichever extension happened to be present.

(defn- api-with-flag [v]
  #js {:getGlobalFlag (fn [_] v)})

(describe "agent-shell:inline-thinking" (fn []

                                          (it "auto defers to a renderer that is already active"
                                              (fn []
                                                (-> (expect (input-router/inline-thinking? (api-with-flag true))) (.toBe false))))

                                          (it "auto inlines when nothing else renders it"
                                              (fn []
                                                (-> (expect (input-router/inline-thinking? (api-with-flag false))) (.toBe true))))

                                          (it "survives an API with no flag support"
                                              (fn []
        ;; Gateway/programmatic APIs have no getGlobalFlag; calling it threw
        ;; inside subscribe and took the whole turn down.
                                                (-> (expect (input-router/inline-thinking? #js {})) (.toBe true))))))
