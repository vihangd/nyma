(ns extension-api-v2.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            [agent.middleware :refer [create-pipeline normalize-tool-result]]))

;;; ─── Event handler context injection ────────────────────

(describe "event handler receives context" (fn []
  (it "handler gets data and ctx as arguments"
    (fn []
      (let [agent    (create-agent {:model "mock" :system-prompt "test"})
            api      (create-extension-api agent)
            captured (atom nil)]
        (.on api "test_evt"
          (fn [data ctx]
            (reset! captured {:data data :has-ctx (some? ctx) :has-cwd (some? (.-cwd ctx))})))
        ((:emit (:events agent)) "test_evt" {:hello "world"})
        (-> (expect (:has-ctx @captured)) (.toBe true))
        (-> (expect (:has-cwd @captured)) (.toBe true)))))

  (it "scoped handler also receives context"
    (fn []
      (let [agent    (create-agent {:model "mock" :system-prompt "test"})
            api      (create-extension-api agent)
            scoped   (create-scoped-api api "test-ext" #{:all})
            captured (atom nil)]
        (.on scoped "test_evt"
          (fn [data ctx]
            (reset! captured (.-cwd ctx))))
        ((:emit (:events agent)) "test_evt" {})
        (-> (expect @captured) (.toBe (js/process.cwd))))))))

;;; ─── Tool result normalization ──────────────────────────

(describe "normalize-tool-result" (fn []
  (it "passes through plain strings"
    (fn []
      (-> (expect (normalize-tool-result "hello")) (.toBe "hello"))))

  (it "extracts text from pi-compatible content array"
    (fn []
      (let [result #js {:content #js [#js {:type "text" :text "hello world"}]
                        :details #js {}}]
        (-> (expect (normalize-tool-result result)) (.toBe "hello world")))))

  (it "joins multiple content items"
    (fn []
      (let [result #js {:content #js [#js {:type "text" :text "line1"}
                                       #js {:type "text" :text "line2"}]
                        :details #js {}}]
        (-> (expect (normalize-tool-result result)) (.toBe "line1\nline2")))))

  (it "stringifies non-string non-content results"
    (fn []
      (-> (expect (normalize-tool-result 42)) (.toBe "42"))))))

;;; ─── tool_call event blocking ───────────────────────────

(defn ^:async test-tool-call-blocking []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        events   (:events agent)
        pipeline (:middleware agent)
        called   (atom false)]
    ((:on events) "tool_call"
      (fn [evt] (set! (.-blocked evt) true)
                (set! (.-reason evt) "Dangerous")))
    (let [tool #js {:execute (fn [_] (reset! called true) "nope") :description "t"}
          ctx  (js-await ((:execute pipeline) "bash" tool {:command "rm -rf /"}))]
      (-> (expect @called) (.toBe false))
      (-> (expect (:result ctx)) (.toContain "Dangerous")))))

(describe "tool_call event blocking" (fn []
  (it "blocked flag prevents execution" test-tool-call-blocking)))

;;; ─── exec capability ────────────────────────────────────

(describe "exec capability gating" (fn []
  (it "exec is available with :all capability"
    (fn []
      (let [agent  (create-agent {:model "mock" :system-prompt "test"})
            api    (create-extension-api agent)
            scoped (create-scoped-api api "test" #{:all})]
        (-> (expect (fn? (.-exec scoped))) (.toBe true)))))

  (it "exec is blocked without :exec capability"
    (fn []
      (let [agent  (create-agent {:model "mock" :system-prompt "test"})
            api    (create-extension-api agent)
            scoped (create-scoped-api api "test" #{:tools})]
        (-> (expect (fn [] (.exec scoped "echo" #js ["hi"]))) (.toThrow "capability")))))))
