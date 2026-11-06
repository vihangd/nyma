(ns subagent-run.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.extensions.subagent.index :refer [final-text run-isolated-agent run-parallel run-chain tool-execute child-tools start-async-job clip-reply]]
            [agent.pricing :as pricing]))

;; ── B2 regression: final-text returns the last ASSISTANT message ──
(describe "subagent:final-text" (fn [])
          (it "returns the last assistant text, not a trailing tool result"
              (fn []
                (let [msgs [{:role "user" :content "go"}
                            {:role "assistant" :content "the summary"}
                            {:role "tool" :content "raw tool output"}]]
                  (-> (expect (final-text msgs)) (.toBe "the summary")))))

          (it "handles array content (text parts)"
              (fn []
                (let [msgs [{:role "assistant"
                             :content [#js {:type "text" :text "part A"}
                                       #js {:type "text" :text "part B"}]}]]
                  (-> (expect (final-text msgs)) (.toContain "part A"))
                  (-> (expect (final-text msgs)) (.toContain "part B")))))

          (it "returns empty string when there is no assistant message"
              (fn []
                (-> (expect (final-text [{:role "user" :content "x"}])) (.toBe "")))))

;; ── orchestration shape (no network; stub api lacks resolveModel) ──
(def ^:private stub-api #js {})
(def ^:private roles {"scout"   {:provider "anthropic" :model "m1"}
                      "planner" {:provider "anthropic" :model "m2"}})
(defn- base [] {:api stub-api :settings-mgr nil :agents roles})

(describe "subagent:run-parallel" (fn [])
          (it "preserves order and returns one result per task"
              (fn []
                (-> (run-parallel (base) [#js {:agent "scout" :task "a"}
                                          #js {:agent "planner" :task "b"}])
                    (.then (fn [rs]
                             (-> (expect (count rs)) (.toBe 2))
                             (-> (expect (:agent (nth rs 0))) (.toBe "scout"))
                             (-> (expect (:agent (nth rs 1))) (.toBe "planner"))
                   ;; no resolveModel on the stub → structured failure, no throw
                             (-> (expect (:ok (nth rs 0))) (.toBe false)))))))

          (it "rejects more than the max parallel tasks"
              (fn []
                (let [many (vec (repeat 9 #js {:agent "scout" :task "x"}))]
                  (-> (run-parallel (base) many)
                      (.then (fn [rs]
                               (-> (expect (:ok (first rs))) (.toBe false))
                               (-> (expect (:text (first rs))) (.toContain "Too many")))))))))

(describe "subagent:run-chain" (fn [])
          (it "short-circuits on the first failed step"
              (fn []
      ;; step 1 fails (no model) → chain stops; step 2 never runs
                (-> (run-chain (base) [#js {:agent "scout" :task "first"}
                                       #js {:agent "scout" :task "use {previous}"}])
                    (.then (fn [rs]
                             (-> (expect (count rs)) (.toBe 1))
                             (-> (expect (:ok (first rs))) (.toBe false))))))))

;; ── recursion guard: a child agent never gets the `subagent` tool ──
(describe "subagent:recursion-guard" (fn [])
          (it "a freshly created child has no subagent tool (depth capped at 1)"
              (fn []
                (let [child (create-agent {:model "test" :system-prompt "x"})
                      tools ((:all (:tool-registry child)))
                      names (set (keys tools))]
                  (-> (expect (contains? names "subagent")) (.toBe false))
                  (-> (expect (contains? names "subagent__subagent")) (.toBe false))))))

;; ── what a child spends reaches the parent's totals ──
(def ^:private tool-chunks
  #js [#js {:type "tool-call" :toolCallId "c1" :toolName "think"
            :input "{\"thought\":\"working\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"}
            :usage #js {:inputTokens  #js {:total 1000000 :cacheRead 400 :cacheWrite 0}
                        :outputTokens #js {:total 0}}}])

(def ^:private text-chunks
  #js [#js {:type "text-start" :id "t1"}
       #js {:type "text-delta" :id "t1" :delta "done"}
       #js {:type "text-end" :id "t1"}
       #js {:type "finish" :finishReason #js {:unified "stop" :raw "stop"}
            :usage #js {:inputTokens  #js {:total 1000000 :cacheRead 400 :cacheWrite 0}
                        :outputTokens #js {:total 0}}}])

(defn- scripted [& steps]
  (let [left (atom steps)]
    (new MockLanguageModelV4
         #js {:doStream (fn [_]
                          (let [s (first @left)]
                            (swap! left rest)
                            (js/Promise.resolve #js {:stream (convertArrayToReadableStream s)})))})))

(defn ^:async bill-of
  "Run a child on `model`, priced at $1/M, and return [result dispatches]."
  [model]
  (let [bills (atom [])
        ;; Provider-qualified only, as real rates are: a bare key would hide a
        ;; child that does not know its provider.
        k     (str "anthropic/" (.-modelId model))
        api   #js {:resolveModel (fn [_ _] model)
                   :dispatchState (fn [t d] (when (= t "extension-usage") (swap! bills conj [t d])))}]
    (swap! pricing/token-costs assoc k [1 1])
    (let [r (js-await (run-isolated-agent (assoc (base) :api api :agent-name "scout" :task "go")))]
      (swap! pricing/token-costs dissoc k)
      [r @bills])))

(defn ^:async test-child-billed []
  (let [[r bills] (js-await (bill-of (scripted text-chunks)))
        [t d]     (first bills)]
    (-> (expect (:ok r)) (.toBe true))
    (-> (expect (count bills)) (.toBe 1))
    (-> (expect t) (.toBe "extension-usage"))
    (-> (expect (:model d)) (.toBe "anthropic/m1"))
    (-> (expect (:input-tokens d)) (.toBe 1000000))
    (-> (expect (:cache-read-tokens d)) (.toBe 400))
    (-> (expect (:cost d)) (.toBeCloseTo 1 6))))

(defn ^:async test-failed-child-billed []
  (let [[r bills] (js-await (bill-of (scripted tool-chunks
                                               #js [#js {:type "error" :error "boom"}])))
        [_ d]     (first bills)]
    (-> (expect (:ok r)) (.toBe false))
    (-> (expect (:cost d)) (.toBeCloseTo 1 6))))

(defn ^:async test-billed-per-step []
  (let [[r bills] (js-await (bill-of (scripted tool-chunks tool-chunks text-chunks)))]
    (-> (expect (:ok r)) (.toBe true))
    (-> (expect (count bills)) (.toBe 3))
    (-> (expect (reduce + (map (fn [[_ d]] (:input-tokens d)) bills))) (.toBe 3000000))
    (-> (expect (reduce + (map (fn [[_ d]] (:cost d)) bills))) (.toBeCloseTo 3 4))))

(defn ^:async test-budget-abort-stops-child []
  ;; What a budget does: abort the parent from inside a billing dispatch.
  (let [ctrl  (js/AbortController.)
        calls (atom 0)
        model (new MockLanguageModelV4
                   #js {:doStream (fn [o]
                                    (when-not (.. o -abortSignal -aborted) (swap! calls inc))
                                    (js/Promise.resolve
                                     #js {:stream (convertArrayToReadableStream tool-chunks)}))})
        api   #js {:resolveModel (fn [_ _] model)
                   :dispatchState (fn [t _] (when (= t "extension-usage") (.abort ctrl)))}
        r     (js-await (run-isolated-agent (assoc (base) :api api :agent-name "scout" :task "go"
                                                   :signal (.-signal ctrl))))]
    (-> (expect (:text r)) (.toBe "cancelled"))
    (-> (expect @calls) (.toBe 1))))

(describe "subagent:billing" (fn []
                               (it "bills as it goes, so a budget sees a running child" test-billed-per-step)
                               (it "stops at the step that crossed the cap" test-budget-abort-stops-child)
                               (it "adds a child's spend to the parent, once" test-child-billed)
                               (it "bills steps a failed child already paid for" test-failed-child-billed)))

;; ── abort: the parent's signal stops a child ──
(defn- hanging
  "A model that answers nothing until it is aborted."
  []
  (new MockLanguageModelV4
       #js {:doStream (fn [o]
                        (js/Promise. (fn [_ reject]
                                       (.addEventListener (.-abortSignal o) "abort"
                                                          (fn [] (reject (js/Error. "aborted")))))))}))

(defn- wait [ms] (js/Promise. (fn [ok] (js/setTimeout ok ms))))

(defn ^:async test-abort-stops-child []
  (let [ctrl (js/AbortController.)
        api  #js {:resolveModel (fn [_ _] (hanging)) :dispatchState (fn [_ _] nil)}
        p    (run-isolated-agent (assoc (base) :api api :agent-name "scout" :task "go"
                                        :signal (.-signal ctrl)))]
    (js-await (wait 20))
    (.abort ctrl)
    (let [r (js-await p)]
      (-> (expect (:ok r)) (.toBe false))
      (-> (expect (:text r)) (.toBe "cancelled")))))

(defn ^:async test-aborted-never-starts []
  (let [ctrl  (js/AbortController.)
        asked (atom 0)
        api   #js {:resolveModel (fn [_ _] (swap! asked inc) (hanging))}]
    (.abort ctrl)
    (let [rs (js-await (run-parallel (assoc (base) :api api :signal (.-signal ctrl))
                                     [#js {:agent "scout" :task "a"}
                                      #js {:agent "planner" :task "b"}]))]
      (-> (expect (mapv :text rs)) (.toEqual #js ["cancelled" "cancelled"]))
      (-> (expect @asked) (.toBe 0)))))

(defn ^:async test-cancel-job []
  (let [sent (atom 0)
        api  #js {:resolveModel (fn [_ _] (hanging)) :dispatchState (fn [_ _] nil)
                  :settings (fn [] {}) :sendUserMessage (fn [& _] (swap! sent inc))}
        out  (js-await (tool-execute api #js {:agent "scout" :task "go" :async true}))
        id   (first (.match out (js/RegExp. "job-\\d+")))]
    (js-await (wait 20))
    (-> (expect (js-await (tool-execute api #js {:action "cancel" :job id}))) (.toContain "Cancelling"))
    (js-await (wait 20))
    (-> (expect (js-await (tool-execute api #js {:action "status"}))) (.toContain (str id ": cancelled")))
    (-> (expect (js-await (tool-execute api #js {:action "cancel" :job id}))) (.toContain "already"))
    (-> (expect @sent) (.toBe 0))))

(defn ^:async tools-sent [role]
  (let [sent  (atom nil)
        model (new MockLanguageModelV4
                   #js {:doStream (fn [o]
                                    (reset! sent (mapv #(.-name %) (or (.-tools o) #js [])))
                                    (js/Promise.resolve #js {:stream (convertArrayToReadableStream text-chunks)}))})
        api   #js {:resolveModel (fn [_ _] model) :dispatchState (fn [_ _] nil)}]
    (js-await (run-isolated-agent {:api api :settings-mgr nil :agents {"mine" role}
                                   :agent-name "mine" :task "go"}))
    (set @sent)))

(defn ^:async test-custom-role-tools []
  (let [plain (js-await (tools-sent {:provider "p" :model "m"}))
        all   (js-await (tools-sent {:provider "p" :model "m" :allowed-tools ["*"]}))]
    (-> (expect (contains? plain "read")) (.toBe true))
    (-> (expect (contains? plain "bash")) (.toBe false))
    (-> (expect (contains? all "bash")) (.toBe true))))

(describe "subagent:custom-role" (fn []
                                   (it "reaches the model read-only unless it asks for *" test-custom-role-tools)))

(def ^:private bash-step
  #js [#js {:type "tool-call" :toolCallId "c1" :toolName "bash"
            :input "{\"command\":\"touch gate-ran.txt\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"}
            :usage #js {:inputTokens #js {:total 10} :outputTokens #js {:total 1}}}])

(defn ^:async gated-run
  "A child that calls bash in a temp dir, under a parent whose gate answers
   `decision`. Returns [ran? requests]."
  [decision]
  (let [dir    (fs/mkdtempSync (path/join (os/tmpdir) "nyma-gate-"))
        prev   (js/process.cwd)
        asked  (atom [])
        parent (create-agent {:model "m" :system-prompt "x"})
        api    (create-scoped-api (create-extension-api parent) "subagent" #{:tools :model :state :session})]
    ((:on (:events parent)) "permission_request"
                            (fn [d] (swap! asked conj (.-tool d)) #js {:decision decision}))
    (aset api "resolveModel" (fn [_ _] (scripted bash-step text-chunks)))
    (js/process.chdir dir)
    (try
      (js-await (run-isolated-agent {:api api :settings-mgr nil
                                     :agents {"mine" {:provider "p" :model "m" :allowed-tools ["bash"]}}
                                     :agent-name "mine" :task "go"}))
      [(fs/existsSync (path/join dir "gate-ran.txt")) @asked]
      (finally
        (js/process.chdir prev)
        (fs/rmSync dir #js {:recursive true :force true})))))

(defn ^:async test-parent-gate-denies []
  (let [[ran? asked] (js-await (gated-run "deny"))]
    (-> (expect ran?) (.toBe false))
    (-> (expect (vec asked)) (.toEqual #js ["bash"]))))

(defn ^:async test-parent-gate-allows []
  (let [[ran? _] (js-await (gated-run "allow"))]
    (-> (expect ran?) (.toBe true))))

(defn ^:async test-ask-without-ui-denies []
  (let [[ran? _] (js-await (gated-run "ask"))]
    (-> (expect ran?) (.toBe false))))

(describe "subagent:permission-gate" (fn []
                                       (it "the parent's deny stops a child's tool" test-parent-gate-denies)
                                       (it "the parent's allow lets it run" test-parent-gate-allows)
                                       (it "an ask with no one to answer is a deny" test-ask-without-ui-denies)
                                       (it "a plan-mode parent offers nothing that writes"
                                           (fn []
                                             (-> (expect (child-tools ["read" "bash" "edit"] "plan")) (.toEqual #js ["read"]))
                                             (-> (expect (.includes (child-tools ["*"] "plan") "bash")) (.toBe false))))))

(defn ^:async test-missing-tool-refused []
  (let [calls (atom 0)
        model (new MockLanguageModelV4
                   #js {:doStream (fn [_] (swap! calls inc)
                                    (js/Promise.resolve #js {:stream (convertArrayToReadableStream text-chunks)}))})
        r     (js-await (run-isolated-agent
                         {:api #js {:resolveModel (fn [_ _] model)} :settings-mgr nil
                          :agents {"mine" {:provider "p" :model "m" :allowed-tools ["read" "mcp__db__query"]}}
                          :agent-name "mine" :task "go"}))]
    (-> (expect (:ok r)) (.toBe false))
    (-> (expect (:text r)) (.toContain "mcp__db__query"))
    (-> (expect @calls) (.toBe 0))))

(describe "subagent:tools" (fn []
                             (it "a tool the child cannot have is refused before spending"
                                 test-missing-tool-refused)))

(defn ^:async run-on [model extra]
  (js-await (run-isolated-agent
             (merge {:api #js {:resolveModel (fn [_ _] model) :dispatchState (fn [_ _] nil)}
                     :settings-mgr nil :agents {"mine" {:provider "p" :model "m" :allowed-tools ["think"]}}
                     :agent-name "mine" :task "go"}
                    extra))))

(defn ^:async test-step-cap-status []
  (let [r (js-await (run-on (scripted tool-chunks text-chunks) {:max-steps 1}))]
    (-> (expect (:status r)) (.toBe "step-capped"))
    (-> (expect (:ok r)) (.toBe true))
    (-> (expect (:text r)) (.toBe "done"))))

(def ^:private silent-chunks
  #js [#js {:type "finish" :finishReason #js {:unified "stop" :raw "stop"}
            :usage #js {:inputTokens #js {:total 1} :outputTokens #js {:total 0}}}])

(defn ^:async test-empty-status []
  (let [r (js-await (run-on (scripted silent-chunks) {}))]
    (-> (expect (:status r)) (.toBe "empty"))
    (-> (expect (:ok r)) (.toBe false))))

(defn ^:async test-timeout-status []
  (let [r (js-await (run-on (hanging) {:timeout-ms 20}))]
    (-> (expect (:status r)) (.toBe "timed-out"))
    (-> (expect (:ok r)) (.toBe false))))

(defn ^:async test-done-status []
  (let [r (js-await (run-on (scripted text-chunks) {}))]
    (-> (expect (:status r)) (.toBe "done"))))

(defn ^:async test-header-says-capped []
  (let [api #js {:resolveModel (fn [_ _] (scripted tool-chunks text-chunks)) :dispatchState (fn [_ _] nil)
                 :settings (fn [] {:subagent {} :roles {:mine {:provider "p" :model "m" :allowed-tools ["think"]}}})}
        out (js-await (tool-execute api #js {:agent "mine" :task "go" :steps 1}))]
    (-> (expect out) (.toContain "(STEP CAP"))))

(defn ^:async test-failed-job-reports []
  (let [sent (atom [])
        api  #js {:sendUserMessage (fn [m _] (swap! sent conj m))}]
    (js-await (start-async-job api (fn [] (js/Promise.reject (js/Error. "broke"))) "x" (js/AbortController.)))
    (js-await (wait 5))
    (-> (expect (first @sent)) (.toContain "broke"))))

(describe "subagent:status" (fn []
                              (it "a clean finish is done" test-done-status)
                              (it "a step-capped child says so and keeps its report" test-step-cap-status)
                              (it "no reply is not a success" test-empty-status)
                              (it "a child past its deadline is stopped" test-timeout-status)
                              (it "the parent reads the cap in the header" test-header-says-capped)
                              (it "a failed background job tells the parent" test-failed-job-reports)))

(defn ^:async test-transcript-saved []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-tr-"))
        api #js {:resolveModel (fn [_ _] (scripted tool-chunks text-chunks))
                 :dispatchState (fn [_ _] nil)
                 :checkPermission (fn [& _] (js/Promise.resolve #js {:allowed true}))
                 :getSessionFile (fn [] (path/join dir "parent.jsonl"))}
        r   (js-await (run-on nil {:api api}))
        t   (:transcript r)]
    (-> (expect (.includes t "/subagents/parent-mine-")) (.toBe true))
    (let [text (str (fs/readFileSync t "utf8"))]
      (-> (expect text) (.toContain "\"go\""))
      (-> (expect text) (.toContain "done"))
      (-> (expect text) (.toContain "think")))
    ;; The parent's own file is untouched.
    (-> (expect (fs/existsSync (path/join dir "parent.jsonl"))) (.toBe false))
    (fs/rmSync dir #js {:recursive true :force true})))

(describe "subagent:transcript" (fn []
                                  (it "a child is a session of its own" test-transcript-saved)
                                  (it "a huge reply is cut, and says where the rest is"
                                      (fn []
                                        (let [big (.repeat "x" 20000)
                                              out (clip-reply big "/t.jsonl")]
                                          (-> (expect (< (count out) 12000)) (.toBe true))
                                          (-> (expect out) (.toContain "in the transcript"))
                                          (-> (expect (clip-reply "short" nil)) (.toBe "short")))))))

(defn ^:async test-progress []
  (let [seen   (atom [])
        counts (atom [])
        api    #js {:resolveModel (fn [_ _] (scripted tool-chunks text-chunks))
                    :checkPermission (fn [& _] (js/Promise.resolve #js {:allowed true}))
                    :dispatchState (fn [t d] (when (= t "subagent-count") (swap! counts conj (:delta d))))}]
    (js-await (run-on nil {:api api :on-progress #(swap! seen conj %)}))
    (-> (expect (first @seen)) (.toBe "mine · step 1 · think"))
    (-> (expect (vec @counts)) (.toEqual #js [1 -1]))))

(describe "subagent:progress" (fn []
                                (it "a running child reports each step and is counted" test-progress)))

(defn- allowing-api [model]
  #js {:resolveModel (fn [_ _] model) :dispatchState (fn [_ _] nil)
       :checkPermission (fn [& _] (js/Promise.resolve #js {:allowed true}))
       :settings (fn [] {:roles {:mine {:provider "p" :model "m" :allowed-tools ["think"]}}})})

(defn- id-in [s] (second (.match s (js/RegExp. "\\[(mine-\\d+)\\]"))))

(defn ^:async test-message-finished []
  (let [prompts (atom [])
        left    (atom [text-chunks text-chunks])
        model   (new MockLanguageModelV4
                     #js {:doStream (fn [o]
                                      (swap! prompts conj (js/JSON.stringify (.-prompt o)))
                                      (let [s (first @left)]
                                        (swap! left rest)
                                        (js/Promise.resolve #js {:stream (convertArrayToReadableStream s)})))})
        api     (allowing-api model)
        out     (js-await (tool-execute api #js {:agent "mine" :task "first task"}))
        id      (id-in out)
        again   (js-await (tool-execute api #js {:action "message" :job id :task "second task"}))]
    (-> (expect again) (.toContain id))
    (-> (expect (nth @prompts 1)) (.toContain "first task"))
    (-> (expect (nth @prompts 1)) (.toContain "second task"))))

(defn ^:async test-message-running []
  (let [prompts (atom [])
        release (atom nil)
        model   (new MockLanguageModelV4
                     #js {:doStream (fn [o]
                                      (swap! prompts conj (js/JSON.stringify (.-prompt o)))
                                      (if (= 1 (count @prompts))
                                        (js/Promise. (fn [ok _]
                                                       (reset! release
                                                               #(ok #js {:stream (convertArrayToReadableStream tool-chunks)}))))
                                        (js/Promise.resolve #js {:stream (convertArrayToReadableStream text-chunks)})))})
        api     (allowing-api model)
        _       (js-await (tool-execute api #js {:agent "mine" :task "go" :async true}))
        _       (js-await (wait 20))
        listing (js-await (tool-execute api #js {:action "status"}))
        id      (second (.match listing (js/RegExp. "(mine-\\d+): running")))]
    (-> (expect (js-await (tool-execute api #js {:action "message" :job id :task "look at b too"})))
        (.toContain "Sent to"))
    (@release)
    (js-await (wait 40))
    (-> (expect (last @prompts)) (.toContain "look at b too"))))

(describe "subagent:message" (fn []
                               (it "a finished child continues with what it knows" test-message-finished)
                               (it "a running child is steered at its next step" test-message-running)
                               (it "an unknown id says so"
                                   (fn []
                                     (-> (tool-execute #js {} #js {:action "message" :job "nope-1" :task "x"})
                                         (.then (fn [s] (-> (expect s) (.toContain "No subagent")))))))))

(defn ^:async test-spawn-cap []
  (let [start  (fn [] (run-on (scripted text-chunks) {:max-children 1}))
        before (js-await (run-on (scripted text-chunks) {:max-children 100000}))
        _      before
        r      (js-await (start))]
    (-> (expect (:ok r)) (.toBe false))
    (-> (expect (:text r)) (.toContain "limit reached"))))

(describe "subagent:spawn-cap" (fn [] (it "a run cannot start children without end" test-spawn-cap)))

(describe "subagent:abort" (fn []
                             (it "the parent's abort stops a running child" test-abort-stops-child)
                             (it "an aborted fan-out starts nothing" test-aborted-never-starts)
                             (it "a background job is cancelled by id, without a follow-up" test-cancel-job)))
