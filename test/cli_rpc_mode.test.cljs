(ns cli-rpc-mode.test
  "Tests for src/agent/modes/rpc.cljs — the JSONL stdio RPC mode.

   `handle-line` is the protocol unit: it parses an inbound JSON command
   and dispatches on `cmd.type`. We test it directly so we don't have to
   spawn a subprocess. `start` is also exercised by capturing
   console.log output to verify the event → JSONL pipeline works."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.modes.rpc :as rpc-mode]))

(defn- make-agent []
  (create-agent {:model "test" :system-prompt "test"}))

;;; ─── handle-line: dispatch on cmd.type ─────────────────────────────

(defn ^:async test-handle-line-prompt-runs-the-agent []
  (let [agent (make-agent)]
    ;; Block the LLM so we don't need credentials
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] #js {:block true :reason "rpc-blocked"}))
    (js-await (rpc-mode/handle-line agent (js/JSON.stringify
                                           #js {:type "prompt" :message "ping"})))
    (let [msgs (:messages @(:state agent))]
      (-> (expect (count msgs)) (.toBeGreaterThanOrEqual 2))
      (-> (expect (:content (first msgs))) (.toBe "ping"))
      (-> (expect (:role (last msgs))) (.toBe "assistant")))))

(defn ^:async test-handle-line-get-commands-prints-commands []
  (let [agent    (make-agent)
        captured (atom [])
        orig-log (.-log js/console)]
    (try
      (swap! (:commands agent) assoc "test-cmd" {:description "desc"})
      (set! (.-log js/console) (fn [& args] (swap! captured conj (.join (clj->js args) " "))))
      (js-await (rpc-mode/handle-line agent (js/JSON.stringify #js {:type "get_commands"})))
      (set! (.-log js/console) orig-log)
      (-> (expect (count @captured)) (.toBe 1))
      (let [parsed (js/JSON.parse (first @captured))]
        ;; aget by the literal property name ("test-cmd" — squint dot
        ;; access would translate the hyphen to an underscore).
        (-> (expect (some? (aget parsed "test-cmd"))) (.toBe true)))
      (finally
        (set! (.-log js/console) orig-log)))))

(defn ^:async test-handle-line-get-settings-prints-config []
  (let [agent    (make-agent)
        captured (atom [])
        orig-log (.-log js/console)]
    (try
      (set! (.-log js/console) (fn [& args] (swap! captured conj (.join (clj->js args) " "))))
      (js-await (rpc-mode/handle-line agent (js/JSON.stringify #js {:type "get_settings"})))
      (set! (.-log js/console) orig-log)
      (-> (expect (count @captured)) (.toBe 1))
      (let [parsed (js/JSON.parse (first @captured))]
        ;; Config has at least the model field
        (-> (expect (.-model parsed)) (.toBe "test")))
      (finally
        (set! (.-log js/console) orig-log)))))

(defn ^:async test-handle-line-unknown-type-logs-error-but-does-not-crash []
  (let [agent      (make-agent)
        err-out    (atom [])
        orig-error (.-error js/console)]
    (try
      (set! (.-error js/console) (fn [& args] (swap! err-out conj (.join (clj->js args) " "))))
      (js-await (rpc-mode/handle-line agent (js/JSON.stringify #js {:type "nonsense"})))
      (set! (.-error js/console) orig-error)
      ;; Logged "Unknown command: nonsense"
      (-> (expect (some #(.includes % "Unknown command") @err-out)) (.toBeTruthy))
      (finally
        (set! (.-error js/console) orig-error)))))

(defn ^:async test-handle-line-malformed-json-logs-error []
  (let [agent      (make-agent)
        err-out    (atom [])
        orig-error (.-error js/console)]
    (try
      (set! (.-error js/console) (fn [& args] (swap! err-out conj (.join (clj->js args) " "))))
      (js-await (rpc-mode/handle-line agent "this is not JSON {{{"))
      (set! (.-error js/console) orig-error)
      (-> (expect (some #(.includes % "RPC error") @err-out)) (.toBeTruthy))
      (finally
        (set! (.-error js/console) orig-error)))))

(defn ^:async test-handle-line-abort-is-noop []
  ;; abort is currently a no-op; verify it doesn't crash and doesn't touch state
  (let [agent (make-agent)]
    (js-await (rpc-mode/handle-line agent (js/JSON.stringify #js {:type "abort"})))
    (-> (expect (count (:messages @(:state agent)))) (.toBe 0))))

;;; ─── start: event → JSONL stdout pipeline ──────────────────────────

(defn ^:async test-start-subscribes-events-as-jsonl []
  ;; rpc-mode/start subscribes every event type so emissions become
  ;; JSONL lines on stdout. We mock console.log, call start, then
  ;; manually emit a known event and verify it lands as JSON.
  (let [agent     (make-agent)
        captured  (atom [])
        orig-log  (.-log js/console)]
    (try
      (set! (.-log js/console) (fn [& args] (swap! captured conj (.join (clj->js args) " "))))
      ;; start opens readline on stdin — fine, we won't write to it.
      (js-await (rpc-mode/start agent))
      ;; Emit a known event
      ((:emit (:events agent)) "agent_start" {:probe "value"})
      (set! (.-log js/console) orig-log)
      ;; At least one emission should be on stdout as JSON
      (-> (expect (some (fn [line]
                          (try
                            (let [parsed (js/JSON.parse line)]
                              (= (.-type parsed) "agent_start"))
                            (catch :default _ false)))
                        @captured))
          (.toBeTruthy))
      (finally
        (set! (.-log js/console) orig-log)))))

(describe "agent.modes.rpc — handle-line"
          (fn []
            (it "prompt command runs the agent and appends user+assistant messages"
                test-handle-line-prompt-runs-the-agent)
            (it "get_commands prints the commands map as JSON"
                test-handle-line-get-commands-prints-commands)
            (it "get_settings prints the config as JSON"
                test-handle-line-get-settings-prints-config)
            (it "unknown command type logs an error but does not throw"
                test-handle-line-unknown-type-logs-error-but-does-not-crash)
            (it "malformed JSON input logs an error but does not throw"
                test-handle-line-malformed-json-logs-error)
            (it "abort command is a noop on state"
                test-handle-line-abort-is-noop)))

(describe "agent.modes.rpc — start"
          (fn []
            (it "subscribes every event type and emits as JSONL on stdout"
                test-start-subscribes-events-as-jsonl)))
