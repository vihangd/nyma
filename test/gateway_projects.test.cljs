(ns gateway-projects.test
  "Tests for the gateway's multi-project routing config + run_in_project tool.

   We don't exercise the ACP subprocess here — that's already covered by the
   agent_shell e2e suite. Instead we inject a stub run-prompt-fn via the tool
   set factory's :run-prompt-fn opt and assert allow-list checks, argument
   validation, dispatch shape, and stream wiring."
  (:require ["bun:test" :refer [describe it expect]]
            [gateway.config :as cfg]
            [gateway.tools :as gtools]))

;;; ─── projects-from-config ────────────────────────────────────

(describe "gateway.config/projects-from-config"
          (fn []
            (it "returns nil when no :projects section is configured"
                (fn []
                  (-> (expect (some? (cfg/projects-from-config {:gateway {}})))
                      (.toBe false))))

            (it "resolves project names to {:root :agents} maps"
                (fn []
                  (let [r     (cfg/projects-from-config
                               {:gateway {:projects {:vyom {:root   "/tmp/vyom"
                                                            :agents ["claude" "gemini"]}}}})
                        entry (get r "vyom")]
                    (-> (expect (some? entry)) (.toBe true))
                    (-> (expect (:root entry)) (.toBe "/tmp/vyom"))
                    (-> (expect (contains? (:agents entry) "claude")) (.toBe true))
                    (-> (expect (contains? (:agents entry) "gemini")) (.toBe true)))))

            (it "expands a leading ~ in :root"
                (fn []
                  (let [r     (cfg/projects-from-config
                               {:gateway {:projects {:home {:root   "~/foo"
                                                            :agents ["claude"]}}}})
                        entry (get r "home")]
                    (-> (expect (.startsWith (:root entry) "~")) (.toBe false))
                    (-> (expect (.endsWith (:root entry) "/foo")) (.toBe true)))))))

;;; ─── validate-config — projects ──────────────────────────────

(describe "gateway.config/validate-config — projects"
          (fn []
            (it "rejects a project missing :root"
                (fn []
                  (let [r (cfg/validate-config
                           {:agent    {:model "x"}
                            :channels [{:type "http" :name "w"}]
                            :gateway  {:projects {:vyom {:agents ["claude"]}}}})]
                    (-> (expect (:valid? r)) (.toBe false))
                    (-> (expect (some #(.includes % "vyom.root") (:errors r)))
                        (.toBeTruthy)))))

            (it "rejects a project with empty :agents"
                (fn []
                  (let [r (cfg/validate-config
                           {:agent    {:model "x"}
                            :channels [{:type "http" :name "w"}]
                            :gateway  {:projects {:vyom {:root "/tmp" :agents []}}}})]
                    (-> (expect (:valid? r)) (.toBe false))
                    (-> (expect (some #(.includes % "vyom.agents") (:errors r)))
                        (.toBeTruthy)))))

            (it "accepts a fully-formed projects section"
                (fn []
                  (let [r (cfg/validate-config
                           {:agent    {:model "x"}
                            :channels [{:type "http" :name "w"}]
                            :gateway  {:projects {:vyom {:root   "/tmp/vyom"
                                                         :agents ["claude"]}}}})]
                    (-> (expect (:valid? r)) (.toBe true)))))))

;;; ─── run_in_project: tool presence ───────────────────────────

(describe "gateway.tools/create-gateway-tool-set — run_in_project"
          (fn []
            (it "omits run_in_project when :projects is empty or nil"
                (fn []
                  (let [ts (gtools/create-gateway-tool-set)]
                    (-> (expect (some? (get (:tools ts) "run_in_project")))
                        (.toBe false)))))

            (it "includes run_in_project when :projects is non-empty"
                (fn []
                  (let [ts (gtools/create-gateway-tool-set
                            {:projects      {"vyom" {:root   "/tmp/vyom"
                                                     :agents #{"claude"}}}
                             :default-agent "claude"})]
                    (-> (expect (some? (get (:tools ts) "run_in_project")))
                        (.toBe true)))))

            (it "exposes the configured project names via the schema enum"
                (fn []
                  (let [ts (gtools/create-gateway-tool-set
                            {:projects      {"vyom" {:root "/tmp/vyom" :agents #{"claude"}}
                                             "nyma" {:root "/tmp/nyma" :agents #{"claude"}}}
                             :default-agent "claude"})
                        tool (get (:tools ts) "run_in_project")
                        enum (.. tool -parameters -properties -project -enum)]
                    (-> (expect (.includes enum "vyom")) (.toBe true))
                    (-> (expect (.includes enum "nyma")) (.toBe true)))))))

;;; ─── run_in_project: execute ─────────────────────────────────

(defn- make-ctx
  "Minimal IResponseContext-shape map for tool tests."
  [stream-sink]
  {:conversation-id "test-conv"
   :channel-name    "test"
   :capabilities    #{}
   :send!           (fn [_] (js/Promise.resolve nil))
   :stream!         (fn [text] (swap! stream-sink conj text) (js/Promise.resolve nil))
   :meta!           (fn [_ _] (js/Promise.resolve nil))})

(defn- make-stub
  "Build a stub :run-prompt-fn. Records each call into `calls`, emits two
   chunks via on-stream, and resolves to whatever `respond-fn` returns."
  [calls respond-fn]
  (fn [_api opts]
    (swap! calls conj opts)
    (when-let [cb (:on-stream opts)]
      (cb "hello ")
      (cb "world"))
    (respond-fn opts)))

(def projects-fixture
  {"vyom" {:root "/tmp/vyom" :agents #{"claude" "gemini"}}
   "nyma" {:root "/tmp/nyma" :agents #{"claude"}}})

(describe "gateway.tools/run_in_project — execute"
          (fn []
            (it "errors when no response context is set"
                (fn []
                  (let [ts   (gtools/create-gateway-tool-set
                              {:projects projects-fixture :default-agent "claude"})
                        tool (get (:tools ts) "run_in_project")]
                    (-> (.execute tool #js {:project "vyom" :instructions "list files"} nil)
                        (.then (fn [result]
                                 (-> (expect (.-isError result)) (.toBe true))))))))

            (it "rejects an unknown project name"
                (fn []
                  (let [stream-sink (atom [])
                        ts          (gtools/create-gateway-tool-set
                                     {:projects projects-fixture :default-agent "claude"})]
                    ((:set-ctx! ts) (make-ctx stream-sink))
                    (let [tool (get (:tools ts) "run_in_project")]
                      (-> (.execute tool #js {:project "ghost" :instructions "x"} nil)
                          (.then (fn [result]
                                   (-> (expect (.-isError result)) (.toBe true))
                                   (let [msg (.-text (aget (.-content result) 0))]
                                     (-> (expect (.includes msg "Unknown project 'ghost'"))
                                         (.toBe true))))))))))

            (it "rejects an agent not on the project's allow-list"
                (fn []
                  (let [stream-sink (atom [])
                        ts          (gtools/create-gateway-tool-set
                                     {:projects projects-fixture :default-agent "claude"})]
                    ((:set-ctx! ts) (make-ctx stream-sink))
                    (let [tool (get (:tools ts) "run_in_project")]
                      (-> (.execute tool #js {:project "nyma" :agent "gemini"
                                              :instructions "x"} nil)
                          (.then (fn [result]
                                   (-> (expect (.-isError result)) (.toBe true))
                                   (let [msg (.-text (aget (.-content result) 0))]
                                     (-> (expect (.includes msg "not allowed for project"))
                                         (.toBe true))))))))))

            (it "rejects blank instructions"
                (fn []
                  (let [stream-sink (atom [])
                        ts          (gtools/create-gateway-tool-set
                                     {:projects projects-fixture :default-agent "claude"})]
                    ((:set-ctx! ts) (make-ctx stream-sink))
                    (let [tool (get (:tools ts) "run_in_project")]
                      (-> (.execute tool #js {:project "vyom" :instructions ""} nil)
                          (.then (fn [result]
                                   (-> (expect (.-isError result)) (.toBe true)))))))))

            (it "dispatches with the project's :root as cwd and streams output back"
                (fn []
                  (let [stream-sink (atom [])
                        calls       (atom [])
                        stub        (make-stub
                                     calls
                                     (fn [_opts]
                                       (js/Promise.resolve
                                        {:text        "hello world"
                                         :usage       {:input-tokens 5 :output-tokens 2}
                                         :tool-calls  []
                                         :stop-reason "end_turn"})))
                        ts          (gtools/create-gateway-tool-set
                                     {:projects      projects-fixture
                                      :default-agent "claude"
                                      :run-prompt-fn stub})]
                    ((:set-ctx! ts) (make-ctx stream-sink))
                    (let [tool (get (:tools ts) "run_in_project")]
                      (-> (.execute tool
                                    #js {:project      "vyom"
                                         :agent        "claude"
                                         :instructions "list files"}
                                    nil)
                          (.then (fn [result]
                                   (-> (expect (.-isError result)) (.toBeFalsy))
                                   (-> (expect (count @stream-sink)) (.toBe 2))
                                   (-> (expect (first @stream-sink)) (.toBe "hello "))
                                   (-> (expect (last  @stream-sink)) (.toBe "world"))
                                   (-> (expect (count @calls)) (.toBe 1))
                                   (let [call (first @calls)]
                                     (-> (expect (:agent call))  (.toBe "claude"))
                                     (-> (expect (:cwd call))    (.toBe "/tmp/vyom"))
                                     (-> (expect (:prompt call)) (.toBe "list files"))))))))))

            (it "falls back to :default-agent when :agent is omitted"
                (fn []
                  (let [stream-sink (atom [])
                        calls       (atom [])
                        stub        (make-stub
                                     calls
                                     (fn [_opts] (js/Promise.resolve {:text "ok"})))
                        ts          (gtools/create-gateway-tool-set
                                     {:projects      projects-fixture
                                      :default-agent "claude"
                                      :run-prompt-fn stub})]
                    ((:set-ctx! ts) (make-ctx stream-sink))
                    (let [tool (get (:tools ts) "run_in_project")]
                      (-> (.execute tool #js {:project "nyma" :instructions "go"} nil)
                          (.then (fn [_]
                                   (-> (expect (:agent (first @calls)))
                                       (.toBe "claude")))))))))))
