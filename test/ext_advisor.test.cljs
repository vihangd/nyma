(ns ext-advisor.test
  "Tests for the advisor extension — pure fns + tool execute roundtrip."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.advisor.index :as adv]))

;;; ─── resolve-advisor-model ──────────────────────────────────────

(defn test-resolve-advisor-explicit []
  (let [r (adv/resolve-advisor-model
           {:roles {:advisor {:provider "kimi" :model "kimi-k2.6"}
                    :deep    {:provider "anthropic" :model "claude-opus-4-7"}}})]
    (-> (expect (:provider r)) (.toBe "kimi"))
    (-> (expect (:model r))    (.toBe "kimi-k2.6"))))

(defn test-resolve-advisor-falls-back-to-deep []
  (let [r (adv/resolve-advisor-model
           {:roles {:deep {:provider "anthropic" :model "claude-opus-4-7"}}})]
    (-> (expect (:provider r)) (.toBe "anthropic"))
    (-> (expect (:model r))    (.toBe "claude-opus-4-7"))))

(defn test-resolve-advisor-nil-when-nothing-set []
  (-> (expect (adv/resolve-advisor-model {})) (.toBeNil))
  (-> (expect (adv/resolve-advisor-model {:roles {}})) (.toBeNil)))

(defn test-resolve-advisor-skips-incomplete []
  ;; Half-set entry should not match — caller wants both provider+model.
  (-> (expect (adv/resolve-advisor-model
               {:roles {:advisor {:provider "kimi"}}}))
      (.toBeNil))
  (-> (expect (adv/resolve-advisor-model
               {:roles {:advisor {:model "x"}}}))
      (.toBeNil)))

;;; ─── format-transcript-for-advisor ──────────────────────────────

(defn test-format-transcript-strips-local-only []
  (let [out (adv/format-transcript-for-advisor
             [{:role "user" :content "hi"}
              {:role "assistant" :content "secret debug" :local-only true}
              {:role "assistant" :content "real reply"}])]
    (-> (expect (count out)) (.toBe 2))
    (-> (expect (:content (first out))) (.toBe "hi"))
    (-> (expect (:content (last out))) (.toBe "real reply"))))

(defn test-format-transcript-summarizes-tools []
  (let [out (adv/format-transcript-for-advisor
             [{:role "tool_call" :tool-name "read" :args {:path "x.cljs"}}
              {:role "tool_result" :content "file contents..."}])]
    (-> (expect (count out)) (.toBe 2))
    (-> (expect (.includes (:content (first out)) "[tool call: read")) (.toBe true))
    (-> (expect (.includes (:content (last out)) "[tool result:")) (.toBe true))))

(defn test-format-transcript-drops-unknown-roles []
  (let [out (adv/format-transcript-for-advisor
             [{:role "shell" :content "noise"}
              {:role "user" :content "real"}])]
    (-> (expect (count out)) (.toBe 1))
    (-> (expect (:content (first out))) (.toBe "real"))))

;;; ─── consult-advisor (tool execute round-trip) ──────────────────

(defn make-fake-api [{:keys [messages settings]}]
  (let [resolved-model #js {:fake true :modelId "fake-model"}]
    #js {:getState     (fn [] {:messages (or messages [])})
         :resolveModel (fn [_p _m] resolved-model)
         :__state_atom (atom {:config {:model resolved-model}})
         :getSettings  (fn [] settings)}))

(defn ^:async test-consult-empty-transcript []
  (let [api (make-fake-api {:messages []})
        result (js-await
                (adv/consult-advisor api
                                     {:roles {:advisor {:provider "x" :model "y"}}}
                                     {}))]
    (-> (expect (.includes result "nothing to review")) (.toBe true))))

(defn ^:async test-consult-hard-cap-refusal []
  ;; 200K+ tokens of "user" message via a 1MB string.
  (let [huge   (.repeat "abcdefghij " 100000) ; ~1.1M chars → ~290K tokens
        api    (make-fake-api {:messages [{:role "user" :content huge}]})
        result (js-await
                (adv/consult-advisor api
                                     {:roles {:advisor {:provider "x" :model "y"}}}
                                     {}))]
    (-> (expect (.includes result "refused")) (.toBe true))
    (-> (expect (.includes result "200000")) (.toBe true))))

(defn ^:async test-consult-success-roundtrip []
  (let [api    (make-fake-api {:messages [{:role "user"      :content "make a plan"}
                                          {:role "assistant" :content "I'll think about it"}]})
        gen    (fn [_cfg]
                 (js/Promise.resolve #js {:text "Advisor says: read the spec first."}))
        result (js-await
                (adv/consult-advisor api
                                     {:roles {:advisor {:provider "x" :model "y"}}}
                                     {:gen-fn gen}))]
    (-> (expect (.includes result "read the spec first")) (.toBe true))))

(defn ^:async test-consult-passes-focus-as-final-user-msg []
  (let [captured (atom nil)
        api      (make-fake-api {:messages [{:role "user" :content "hi"}]})
        gen      (fn [cfg]
                   (reset! captured cfg)
                   (js/Promise.resolve #js {:text "ok"}))
        _ (js-await
           (adv/consult-advisor api
                                {:roles {:advisor {:provider "x" :model "y"}}}
                                {:focus "Should I refactor X?" :gen-fn gen}))
        msgs (.-messages @captured)
        last-msg (aget msgs (dec (.-length msgs)))]
    (-> (expect (.-role last-msg)) (.toBe "user"))
    (-> (expect (.includes (.-content last-msg) "Should I refactor X?")) (.toBe true))))

(defn ^:async test-consult-falls-back-on-resolve-throw []
  ;; Common claude-native OAuth case: advisor role points to "anthropic"
  ;; provider, but ANTHROPIC_API_KEY is unset → resolveModel throws.
  ;; Should fall back to the current active model and still succeed.
  (let [current-model #js {:fake true :modelId "current-model"}
        api #js {:getState     (fn [] {:messages [{:role "user" :content "hi"}]})
                 :resolveModel (fn [_ _] (throw (js/Error. "No credentials")))
                 :__state_atom (atom {:config {:model current-model}})
                 :getSettings  (fn [] {})}
        captured (atom nil)
        gen (fn [cfg]
              (reset! captured cfg)
              (js/Promise.resolve #js {:text "fallback ok"}))
        result (js-await
                (adv/consult-advisor api
                                     {:roles {:advisor {:provider "anthropic"
                                                        :model    "claude-opus-4"}}}
                                     {:gen-fn gen}))]
    (-> (expect (.includes result "fallback ok")) (.toBe true))
    ;; Confirm the gen call used the current model (not nil).
    (-> (expect (.-model @captured)) (.toBe current-model))))

(defn ^:async test-consult-no-model-resolved []
  ;; resolveModel returns nil AND no current-model on agent → graceful refusal.
  (let [api #js {:getState     (fn [] {:messages [{:role "user" :content "hi"}]})
                 :resolveModel (fn [_ _] nil)
                 :__state_atom (atom {:config {:model nil}})
                 :getSettings  (fn [] {})}
        result (js-await (adv/consult-advisor api {} {}))]
    (-> (expect (.includes result "no model resolved")) (.toBe true))))

(describe "advisor — model selection"
          (fn []
            (it "uses settings.roles.advisor when present"
                test-resolve-advisor-explicit)
            (it "falls back to settings.roles.deep"
                test-resolve-advisor-falls-back-to-deep)
            (it "returns nil when neither role configured"
                test-resolve-advisor-nil-when-nothing-set)
            (it "skips half-set entries"
                test-resolve-advisor-skips-incomplete)))

(describe "advisor — transcript formatting"
          (fn []
            (it "strips local-only messages"
                test-format-transcript-strips-local-only)
            (it "summarizes tool calls and results into text"
                test-format-transcript-summarizes-tools)
            (it "drops unknown role entries"
                test-format-transcript-drops-unknown-roles)))

(describe "advisor — consultation roundtrip"
          (fn []
            (it "returns helpful message for empty transcript"
                test-consult-empty-transcript)
            (it "refuses when transcript exceeds 200K-token hard cap"
                test-consult-hard-cap-refusal)
            (it "returns the advisor model's text on success"
                test-consult-success-roundtrip)
            (it "appends focus as final user message"
                test-consult-passes-focus-as-final-user-msg)
            (it "falls back to current model when role resolution throws"
                test-consult-falls-back-on-resolve-throw)
            (it "fails gracefully when no model is resolved"
                test-consult-no-model-resolved)))
