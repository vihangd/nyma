(ns compact-focus-test
  "`/compact <focus>` — the words reach the summariser.

   The handler took `_args`: `/compact keep the auth refactor details` was
   accepted and the focus dropped, though compaction has always taken
   custom-instructions."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.sessions.manager :as sm]
            [agent.commands.builtins :refer [register-builtins]]))

(defn- recording-model [prompts]
  (new MockLanguageModelV4
       #js {:doGenerate (fn [opts]
                          (swap! prompts conj (js/JSON.stringify (.-prompt opts)))
                          (js/Promise.resolve
                           #js {:content #js [#js {:type "text" :text "## 1. Previous Conversation\nsummary"}]
                                :finishReason #js {:unified "stop" :raw "stop"}
                                :usage #js {:inputTokens 1 :outputTokens 1 :totalTokens 2}
                                :warnings #js []}))}))

(defn ^:async test-focus-reaches-prompt []
  (let [dir     (fs/mkdtempSync (path/join (os/tmpdir) "nyma-compact-focus-"))
        prompts (atom [])
        sess    (sm/create-session-manager (path/join dir "s.jsonl"))
        agent   (create-agent {:model (recording-model prompts) :system-prompt "s"})]
    (doseq [i (range 6)]
      ((:append sess) {:role "user" :content (str "question " i " " (.repeat "words " 200))})
      ((:append sess) {:role "assistant" :content (str "answer " i)}))
    (reset! (:session agent) sess)
    (register-builtins agent sess {})
    (let [h (:handler (get @(:commands agent) "compact"))]
      (try (js-await (h ["keep" "the" "auth" "details"] #js {:ui #js {:notify (fn [& _] nil)}}))
           (catch :default _ nil)))
    (fs/rmSync dir #js {:recursive true :force true})
    (-> (expect (pos? (count @prompts))) (.toBe true))
    (-> (expect (.includes (first @prompts) "Focus for this summary (from the user): keep the auth details"))
        (.toBe true))))

(describe "/compact <focus>"
          (fn [] (it "passes the focus to the summariser" test-focus-reaches-prompt)))
