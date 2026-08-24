(ns temperature-config.test
  "nyma sent no temperature at all until this landed. streamText's config in
   loop.cljs listed model, system, messages, tools, maxRetries, stopWhen and
   providerOptions — and nothing about sampling — so every request ran at
   whatever the provider defaulted to, typically 0.7-1.0.

   The cost was measured, not assumed. Three trials over five tasks on one
   model gave pass^3 0% against pass@3 60%: three of five tasks passed twice in
   three attempts, and javascript/bowling was recorded as timeout, error and
   pass at runtimes from 183s to 1149s on identical config.

   Two traps this file guards, both of which this codebase has already fallen
   into: a value that is read from settings but never sent (thinking_budget
   wrote providerOptions.thinkingBudget, which nothing reads), and a value
   hardcoded in the loop under a comment claiming it came from settings
   (maxRetries was 5 regardless of what the user set)."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            [agent.core :refer [create-agent]]))

(defn- temp-for [settings]
  (:temperature (:config (create-agent {:model #js {} :system-prompt "" :tools {}
                                        :settings settings}))))

(describe "temperature reaches the agent config"
  (fn []
    (it "defaults low rather than leaving it to the provider"
        (fn [] (-> (expect (temp-for {})) (.toBe 0.2))))

    (it "honours what the user set"
        (fn [] (-> (expect (temp-for {:temperature 0.7})) (.toBe 0.7))))

    (it "reads the string-keyed form settings.json produces"
        (fn [] (-> (expect (temp-for {"temperature" 0.5})) (.toBe 0.5))))

    (it "treats 0 as a real value, not a missing one"
        ;; squint's `or` is a nil-check helper rather than JS ||, but a plain
        ;; truthiness test here would silently turn 0 into 0.2
        (fn [] (-> (expect (temp-for {:temperature 0})) (.toBe 0))))

    (it "falls back when the value is not a number"
        (fn [] (-> (expect (temp-for {:temperature "hot"})) (.toBe 0.2))))))

(describe "temperature is actually sent"
  (fn []
    (it "appears in the streamText config, not just the agent config"
        ;; The failure this catches: carrying a value all the way to :config and
        ;; never putting it on the request. Asserting the compiled loop is crude,
        ;; but the suite has no streamText mock and a value that never ships is
        ;; exactly the bug that shipped twice before.
        (fn []
          (let [src (fs/readFileSync "dist/agent/loop.mjs" "utf8")]
            (-> (expect src) (.toContain "temperature")))))))


;; ── output cap ───────────────────────────────────────────────────
;; Traced, not guessed: on rust/decimal the model emitted 70,565 characters of
;; prose hand-simulating the arithmetic ("For i=2: product = 0 * 10^18 + 0 = 0")
;; and was cut off mid-loop having never called a tool. That task failed 7/7 as
;; "agent never modified the stub", and three earlier remedies — respond-tool,
;; rescueParsing, temperature — all targeted a malformed or suppressed tool
;; call. None could help: the model never reached the tool-calling stage.
;;
;; nyma set no output cap at all, so the provider decided.
(defn- cap-for [settings]
  (:max-output-tokens (:config (create-agent {:model #js {} :system-prompt "" :tools {}
                                              :settings settings}))))

(describe "max-output-tokens reaches the agent config"
  (fn []
    (it "defaults to a cap rather than leaving it to the provider"
        (fn [] (-> (expect (cap-for {})) (.toBe 8000))))

    (it "honours what the user set"
        (fn [] (-> (expect (cap-for {:max-output-tokens 4000})) (.toBe 4000))))

    (it "reads the string-keyed form settings.json produces"
        (fn [] (-> (expect (cap-for {"max-output-tokens" 2000})) (.toBe 2000))))

    (it "is actually sent, not merely carried"
        ;; the thinking_budget failure: that module has always written
        ;; providerOptions.thinkingBudget, a key nothing reads, so the cap it
        ;; claimed to apply never existed
        (fn []
          (let [src (fs/readFileSync "dist/agent/loop.mjs" "utf8")]
            (-> (expect src) (.toContain "maxOutputTokens")))))))


;; ── the settings MANAGER, not a settings map ─────────────────────
;; cli.cljs:445 passes `:settings settings`, where settings is the manager
;; returned by create-settings-manager — a record of :get / :set-override /
;; :apply-overrides. Reading a config key straight off it returns nil, so every
;; value fell back to its default and NOTHING a user configured applied:
;; retry {max-retries 3} still made 6 attempts, and a configured temperature or
;; output cap was ignored. The unit tests missed it because they pass a plain
;; map, which is the one shape production never uses.
(describe "settings reach config through the manager"
  (fn []
    (it "resolves a manager via its :get, the shape cli.cljs actually passes"
        (fn []
          (let [mgr #js {:get (fn [] #js {"max-output-tokens" 1234
                                          "temperature" 0.55
                                          "retry" #js {"max-retries" 2}})}
                cfg (:config (create-agent {:model #js {} :system-prompt "" :tools {}
                                            :settings mgr}))]
            (-> (expect (:max-output-tokens cfg)) (.toBe 1234))
            (-> (expect (:temperature cfg))       (.toBe 0.55))
            (-> (expect (:max-retries cfg))       (.toBe 2)))))

    (it "still accepts a plain map"
        (fn []
          (let [cfg (:config (create-agent {:model #js {} :system-prompt "" :tools {}
                                            :settings {:temperature 0.9}}))]
            (-> (expect (:temperature cfg)) (.toBe 0.9)))))

    (it "survives no settings at all"
        (fn []
          (let [cfg (:config (create-agent {:model #js {} :system-prompt "" :tools {}}))]
            (-> (expect (:temperature cfg)) (.toBe 0.2)))))))
