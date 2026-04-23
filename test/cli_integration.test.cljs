(ns cli-integration.test
  "Integration test for the CLI's print mode end-to-end.

   Loads every built-in extension via the real `discover-and-load` against
   `dist/agent/extensions/`, then drives a full agent turn through
   `agent.loop/run` with a blocked `before_provider_request` handler so we
   exercise the entire pre-LLM pipeline without needing an API key.

   This catches a class of bug we shipped earlier: extensions subscribed to
   `tool_access_check` (e.g. token-suite's budget filter) read
   `(.-modelId (:model (:config agent)))` and crashed on null when the CLI
   started without credentials. No CLI integration test existed at the
   time, so the regression slipped through. Now it doesn't."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.loop :refer [run]]
            [agent.modes.print :as print-mode]))

(defn- builtin-dir []
  ;; Compiled file lives at dist/cli_integration.test.mjs; built-in
  ;; extensions sit at dist/agent/extensions/.
  (path/resolve (js* "import.meta.dir") "agent" "extensions"))

(defn- make-loaded-agent []
  ;; Mirror what cli.cljs/main does, minus the UI: real agent + real api
  ;; + real loader against the dist tree.
  (let [agent  (create-agent {:model         "test"
                              :system-prompt "you are a test"
                              :max-steps     5})
        api    (create-extension-api agent)
        _      (set! (.-extension-api agent) api)]
    {:agent  agent
     :api    api
     :loaded nil}))

;;; ─── Loading + tool_access_check end-to-end ──────────────────────────

(defn ^:async test-cli-startup-loads-all-builtins-without-crashing []
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded (js-await (discover-and-load [(builtin-dir)] api))]
    (try
      ;; All 20 built-in extensions present
      (-> (expect (count loaded)) (.toBe 20))
      ;; Block the LLM call so we don't need credentials
      ((:on (:events agent)) "before_provider_request"
                             (fn [_] #js {:block true :reason "blocked for test"}))
      ;; Drive a full turn — this is what crashed under the .modelId bug
      (js-await (run agent "ping"))
      ;; State should have the user prompt + the blocked assistant response
      (let [msgs (:messages @(:state agent))]
        (-> (expect (count msgs)) (.toBeGreaterThanOrEqual 2))
        (-> (expect (:role (first msgs))) (.toBe "user"))
        (-> (expect (:content (first msgs))) (.toBe "ping"))
        (-> (expect (:role (last msgs))) (.toBe "assistant")))
      (finally
        (deactivate-all loaded)))))

(defn ^:async test-tool-access-check-handlers-fire-without-modelid-crash []
  ;; The exact regression that bit us: token-suite subscribes to
  ;; tool_access_check at activation; its handler chain hit
  ;; (.-modelId (:model (:config agent))) and crashed when model was nil.
  ;; Verify the event fires through every loaded extension without raising.
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded   (js-await (discover-and-load [(builtin-dir)] api))
        captured (atom nil)]
    (try
      ((:on (:events agent)) "tool_access_check"
                             (fn [data] (reset! captured data) nil))
      ;; Manually fire tool_access_check the way the agent loop does.
      (js-await ((:emit-collect (:events agent))
                 "tool_access_check"
                 #js {:tools #js ["read" "write" "edit" "bash" "web_fetch"]
                      :model "test"}))
      (-> (expect (some? @captured)) (.toBe true))
      (finally
        (deactivate-all loaded)))))

;;; ─── print-mode/start covers the println path ────────────────────────

(defn ^:async test-print-mode-start-runs-a-blocked-turn-and-prints []
  ;; Capture console output so we can assert print-mode/start emitted the
  ;; assistant content. `println` in squint compiles to console.log.
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded   (js-await (discover-and-load [(builtin-dir)] api))
        captured (atom [])
        orig-log (.-log js/console)]
    (try
      ((:on (:events agent)) "before_provider_request"
                             (fn [_] #js {:block true :reason "BLOCK-VIA-PRINT"}))
      (set! (.-log js/console) (fn [& args] (swap! captured conj (.join (clj->js args) " "))))
      (js-await (print-mode/start agent "hello print mode"))
      (set! (.-log js/console) orig-log)
      ;; print-mode prints the last assistant message; with our block that's
      ;; the block reason.
      (-> (expect (some #(.includes % "BLOCK-VIA-PRINT") @captured)) (.toBeTruthy))
      (finally
        (set! (.-log js/console) orig-log)
        (deactivate-all loaded)))))

(defn ^:async test-print-mode-start-noop-on-nil-prompt []
  ;; print-mode/start guards on (when prompt ...) — verify the noop path.
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded (js-await (discover-and-load [(builtin-dir)] api))]
    (try
      (js-await (print-mode/start agent nil))
      ;; State should be untouched — no user message added
      (-> (expect (count (:messages @(:state agent)))) (.toBe 0))
      (finally
        (deactivate-all loaded)))))

;;; ─── JSON mode ───────────────────────────────────────────────────────

(defn ^:async test-print-mode-start-json-emits-valid-json-array []
  ;; start-json prints the full messages array as JSON. Capture stdout and
  ;; assert JSON.parse round-trips it into something with both the user
  ;; prompt and the blocked assistant reply.
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded   (js-await (discover-and-load [(builtin-dir)] api))
        captured (atom [])
        orig-log (.-log js/console)]
    (try
      ((:on (:events agent)) "before_provider_request"
                             (fn [_] #js {:block true :reason "BLOCK-VIA-JSON"}))
      (set! (.-log js/console) (fn [& args] (swap! captured conj (.join (clj->js args) " "))))
      (js-await (print-mode/start-json agent "hello json mode"))
      (set! (.-log js/console) orig-log)
      ;; Exactly one console.log emission, containing the JSON dump
      (-> (expect (count @captured)) (.toBeGreaterThanOrEqual 1))
      (let [json-str (first @captured)
            parsed   (js/JSON.parse json-str)]
        ;; parsed is an array of message objects
        (-> (expect (.-length parsed)) (.toBeGreaterThanOrEqual 2))
        ;; First message: the user prompt
        (-> (expect (.-content (aget parsed 0))) (.toBe "hello json mode"))
        (-> (expect (.-role (aget parsed 0))) (.toBe "user"))
        ;; Last message: the blocked assistant reply
        (let [last-msg (aget parsed (- (.-length parsed) 1))]
          (-> (expect (.-role last-msg)) (.toBe "assistant"))
          (-> (expect (.-content last-msg)) (.toBe "BLOCK-VIA-JSON"))))
      (finally
        (set! (.-log js/console) orig-log)
        (deactivate-all loaded)))))

(defn ^:async test-print-mode-start-json-noop-on-nil-prompt []
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded   (js-await (discover-and-load [(builtin-dir)] api))
        captured (atom [])
        orig-log (.-log js/console)]
    (try
      (set! (.-log js/console) (fn [& args] (swap! captured conj args)))
      (js-await (print-mode/start-json agent nil))
      (set! (.-log js/console) orig-log)
      ;; No log calls — early return on nil
      (-> (expect (count @captured)) (.toBe 0))
      (finally
        (set! (.-log js/console) orig-log)
        (deactivate-all loaded)))))

;;; ─── modelId in tool ctx survives the loaded-extension stack ─────────

(defn ^:async test-modelid-on-tool-ctx-after-real-loader []
  ;; The middleware sets ext-ctx.modelId to the active model id. Verify
  ;; the loaded extension stack doesn't break this — registering a tool
  ;; through the api and invoking it via the middleware pipeline.
  (let [{:keys [agent api]} (make-loaded-agent)
        loaded   (js-await (discover-and-load [(builtin-dir)] api))
        captured (atom nil)]
    (try
      (.registerTool api "probe-modelid"
                     #js {:description "capture modelId from ctx"
                          :parameters  #js {}
                          :execute     (fn [_args ctx]
                                         (reset! captured (.-modelId ctx))
                                         "ok")})
      ;; :all on the registry is a fn, not an atom — call it.
      (let [pipeline (:middleware agent)
            tools    ((:all (:tool-registry agent)))
            tool     (get tools "probe-modelid")]
        (-> (expect (some? tool)) (.toBe true))
        (js-await ((:execute pipeline) "probe-modelid" tool #js {}))
        ;; modelId is "test" because that's our agent config (a string,
        ;; not a model object — the middleware handles both).
        (-> (expect @captured) (.toBe "test")))
      (finally
        (deactivate-all loaded)))))

(describe "CLI integration — print mode + loaded extensions"
          (fn []
            (it "loads all 20 built-ins, runs a blocked turn, no crashes"
                test-cli-startup-loads-all-builtins-without-crashing)
            (it "tool_access_check fires through loaded extensions without modelId crash"
                test-tool-access-check-handlers-fire-without-modelid-crash)
            (it "print-mode/start prints the assistant content via console.log"
                test-print-mode-start-runs-a-blocked-turn-and-prints)
            (it "print-mode/start with nil prompt is a noop"
                test-print-mode-start-noop-on-nil-prompt)
            (it "modelId on tool ctx survives the loaded extension stack"
                test-modelid-on-tool-ctx-after-real-loader)
            (it "print-mode/start-json emits a parseable JSON messages array"
                test-print-mode-start-json-emits-valid-json-array)
            (it "print-mode/start-json with nil prompt is a noop"
                test-print-mode-start-json-noop-on-nil-prompt)))
