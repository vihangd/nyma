(ns retry-config.test
  "The loop hardcoded `:maxRetries 5` directly above a comment claiming it
   'Matches :retry :max-retries in settings defaults'. It did not: a user with
   {\"retry\": {\"max-retries\": 3}} still got 6 attempts, and {\"enabled\":
   false} was ignored entirely.

   This is not cosmetic. Rate limits are the largest single failure category in
   the benchmark corpus — 92 occurrences of 'Failed after 6 attempts. Last
   error: AI_APICallError: Rate limit exceeded' — and each one burns ~68s
   exhausting attempts that cannot succeed while a quota is spent. One of them
   destroyed a whole A/B arm."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :as core]))

(defn- retries-for [settings]
  (:max-retries (:config (core/create-agent
                          {:model #js {} :system-prompt "" :tools {}
                           :settings settings}))))

(describe "retry/max-retries reaches the loop"
  (fn []
    (it "defaults to 5 when nothing is configured"
        (fn [] (-> (expect (retries-for {})) (.toBe 5))))

    (it "honours the number the user actually set"
        (fn [] (-> (expect (retries-for {:retry {:max-retries 3}})) (.toBe 3))))

    (it "reads the string-keyed form settings.json produces"
        (fn [] (-> (expect (retries-for {"retry" {"max-retries" 2}})) (.toBe 2))))

    (it "enabled false means no retries, not the default five"
        (fn [] (-> (expect (retries-for {:retry {:enabled false}})) (.toBe 0))))

    (it "zero is a real value, not a missing one"
        (fn [] (-> (expect (retries-for {:retry {:max-retries 0}})) (.toBe 0))))))
