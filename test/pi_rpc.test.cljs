(ns pi-rpc.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.pi-rpc :as pi]))

;; Minimal fake agent sufficient for the no-run command handlers.
(defn- make-agent []
  (let [cfg #js {:model "claude-test"}]
    (aset cfg "active-provider-name" "anthropic")
    {:config         cfg
     :model-registry {:context-window (fn [_] 200000)}
     :thinking-level (atom "off")
     :state          (atom {:messages [{:role "user" :content "hi"}
                                       {:role "assistant" :content "hello there"}]})
     :follow-queue   (atom [])
     :session        (atom {:get-file-path (fn [] "/tmp/s.jsonl")
                            :get-session-name (fn [] "sess1")})
     :abort-controller (atom (js/AbortController.))
     :commands       (atom {"plan" {:description "plan it" :source "skill"}})}))

;; Capture stdout lines emitted by write-event! (squint println → console.log).
(defn- capture [f]
  (let [lines (atom [])
        orig  js/console.log]
    (set! js/console.log (fn [s] (swap! lines conj s)))
    (-> (f)
        (.finally (fn [] (set! js/console.log orig)))
        (.then (fn [_] (mapv #(js/JSON.parse %) @lines))))))

(defn- st []
  {:acc (atom "") :content-index (atom 0)
   :streaming? (atom false) :compacting? (atom false) :pending-ui (atom {})})

(describe "pi-rpc command responses" (fn []

                                       (it "get_state returns a full state envelope" (fn []
                                                                                       (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                           (js/JSON.stringify #js {:type "get_state" :id "1"}))))
                                                                                           (.then (fn [out]
                                                                                                    (let [r (first out)]
                                                                                                      (-> (expect (.-type r)) (.toBe "response"))
                                                                                                      (-> (expect (.-command r)) (.toBe "get_state"))
                                                                                                      (-> (expect (.-id r)) (.toBe "1"))
                                                                                                      (-> (expect (.-success r)) (.toBe true))
                                                                                                      (-> (expect (.. r -data -model -contextWindow)) (.toBe 200000))
                                                                                                      (-> (expect (.. r -data -messageCount)) (.toBe 2))
                                                                                                      (-> (expect (.. r -data -model -provider)) (.toBe "anthropic"))))))))

                                       (it "get_commands always carries a commands vector" (fn []
                                                                                             (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                                 (js/JSON.stringify #js {:type "get_commands" :id "2"}))))
                                                                                                 (.then (fn [out]
                                                                                                          (let [r (first out)]
                                                                                                            (-> (expect (.-command r)) (.toBe "get_commands"))
                                                                                                            (-> (expect (js/Array.isArray (.. r -data -commands))) (.toBe true))
                                                                                                            (-> (expect (.-name (aget (.. r -data -commands) 0))) (.toBe "plan"))))))))

                                       (it "get_available_models returns a non-empty models vector" (fn []
                                                                                                      (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                                          (js/JSON.stringify #js {:type "get_available_models" :id "3"}))))
                                                                                                          (.then (fn [out]
                                                                                                                   (let [r (first out)]
                                                                                                                     (-> (expect (js/Array.isArray (.. r -data -models))) (.toBe true))
                                                                                                                     (-> (expect (> (.-length (.. r -data -models)) 0)) (.toBe true))))))))

                                       (it "cycle_thinking_level advances and echoes :command" (fn []
                                                                                                 (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                                     (js/JSON.stringify #js {:type "cycle_thinking_level" :id "4"}))))
                                                                                                     (.then (fn [out]
                                                                                                              (let [r (first out)]
                                                                                                                (-> (expect (.-command r)) (.toBe "cycle_thinking_level"))
                                                                                                                (-> (expect (.. r -data -level)) (.toBe "low"))))))))

                                       (it "get_last_assistant_text extracts the last assistant message" (fn []
                                                                                                           (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                                               (js/JSON.stringify #js {:type "get_last_assistant_text" :id "5"}))))
                                                                                                               (.then (fn [out]
                                                                                                                        (-> (expect (.. (first out) -data -text)) (.toBe "hello there")))))))

                                       (it "unknown command still answers (no hang) with success:false" (fn []
                                                                                                          (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                                              (js/JSON.stringify #js {:type "bogus_cmd" :id "6"}))))
                                                                                                              (.then (fn [out]
                                                                                                                       (let [r (first out)]
                                                                                                                         (-> (expect (.-success r)) (.toBe false))
                                                                                                                         (-> (expect (.-id r)) (.toBe "6"))))))))

                                       (it "abort responds and does not throw" (fn []
                                                                                 (-> (capture (fn [] (pi/handle-line (make-agent) (st)
                                                                                                                     (js/JSON.stringify #js {:type "abort" :id "7"}))))
                                                                                     (.then (fn [out]
                                                                                              (-> (expect (.-success (first out))) (.toBe true)))))))))

;;; ─── Resolved model objects (regression) ────────────────────────
;;; The harness above uses a STRING model, which is why this went unnoticed:
;;; once a provider registry resolves a model, `config.model` holds the AI SDK
;;; model OBJECT. That was passed straight through as the id, so the client
;;; received "[object Object]" as id and name, and the registry lookup
;;; stringified to the same — pinning contextWindow to the 100000 default for
;;; every model.

(defn- make-agent-with-resolved-model []
  (let [cfg #js {:model #js {:modelId "kimi-k2.5" :provider "openai.chat"}}]
    (aset cfg "active-provider-name" "kimi")
    {:config         cfg
     ;; Only the provider-qualified key carries the real window, exactly as the
     ;; registry behaves once two providers declare the same bare id.
     :model-registry {:context-window (fn [k]
                                        (if (= k "kimi/kimi-k2.5") 262144 100000))}
     :thinking-level (atom "off")
     :state          (atom {:messages []})
     :follow-queue   (atom [])
     :session        (atom {:get-file-path (fn [] "/tmp/s.jsonl")
                            :get-session-name (fn [] "sess1")})
     :abort-controller (atom (js/AbortController.))
     :commands       (atom {})}))

(describe "pi-rpc with a resolved model object"
  (fn []
    (it "reports the model id, not [object Object]"
        (fn []
          (-> (capture (fn []
                         (pi/handle-line (make-agent-with-resolved-model) (st)
                                         (js/JSON.stringify #js {:type "get_state" :id "1"}))))
              (.then (fn [rs]
                       (let [r (first (filter #(= "get_state" (.-command %)) rs))]
                         (-> (expect (.. r -data -model -id)) (.toBe "kimi-k2.5"))
                         (-> (expect (.. r -data -model -name)) (.toBe "kimi-k2.5"))
                         (-> (expect (.includes (str (.. r -data -model -id)) "object Object"))
                             (.toBe false))))))))

    (it "resolves the window through the provider-qualified key"
        (fn []
          (-> (capture (fn []
                         (pi/handle-line (make-agent-with-resolved-model) (st)
                                         (js/JSON.stringify #js {:type "get_state" :id "2"}))))
              (.then (fn [rs]
                       (let [r (first (filter #(= "get_state" (.-command %)) rs))]
                         ;; 100000 here would mean the bare id (or the object) was used.
                         (-> (expect (.. r -data -model -contextWindow)) (.toBe 262144))))))))))
