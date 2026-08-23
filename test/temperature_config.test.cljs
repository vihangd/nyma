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
