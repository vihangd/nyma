(ns event-payload-divergence-test
  "An event emitted from several sites must not quietly carry different keys at
   each.

   This is the defect that killed the provider fallback chain. `provider_error`
   is emitted from three places; two throw paths carry `:error` and the
   stream-error path carries `:source` instead — and, before the fix, carried
   no `:config` at all. The handler needed `:config`, so it bailed on every
   real outage. The generated event map could not show this, because it printed
   the UNION of keys across sites: the row read
   `config, error, message, model, source`, which made `config` look
   guaranteed.

   `docs/event-map.md` now has a Payload divergence table, and this test pins
   it. New divergence fails; the existing entries are listed below with what is
   known about each, so the list is a record rather than a silencer."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.dev.event-map :as em]))

(def ^:private known
  "Events whose emit sites already disagree, and why.

   Two of these are worth fixing and are NOT fixed here — they are recorded so
   the next person starts from a list rather than a grep. The rest are benign:
   an optional field, or an extra breadcrumb one producer happens to have."
  {"provider_error"
   "`:error` on the two throw paths, `:source` on the stream path — there is no
    exception object to attach when the failure arrives as a stream part. Both
    carry message/model/config, which is what consumers read."

   "acp_tool_start"
   "WORTH FIXING. Two producers with different vocabularies for the same thing:
    `agent_shell/acp/notifications` sends `tool-id`/`title`/`kind`/`status`,
    `agent_runner_claude_sdk` sends `toolCallId`/`toolName`. No listener today,
    so it is a trap rather than a bug — the first consumer will read one and get
    undefined from the other."

   "acp_tool_update"
   "WORTH FIXING. Same split as acp_tool_start: `tool-id` versus `toolCallId`."

   "acp_message"      "Same two producers, different chunk vocabularies. No listener."
   "acp_thought"      "`text` versus `thought`, same two producers. No listener."
   "acp_subagent"     "`name` only from one producer. No listener."
   "agent_end"        "`finishReason` only on the normal completion path; the abort and
                       step-cap paths have no reason to report."
   "session_start"    "`previousSessionFile` only from /resume, which is the only path
                       that has a previous file."
   "session_ready"    "`reason` only from one site."
   "user_bash"        "`reason` only when the command was refused."
   "turn_request"     "`echo` only from the steering path."
   "spec_task_complete" "`line`/`line-idx` plus `source`/`text` differ between the
                         parse-time and runtime producers."})

(describe "payload divergence"
          (fn []
            (it "finds a believable number of events to compare"
                (fn []
        ;; Guard the guard: if the scan returned nothing, every assertion below
        ;; would pass against an empty map.
                  (let [scanned (em/scan)]
                    (-> (expect (count (keys (:payload-sites scanned)))) (.toBeGreaterThan 20)))))

            (it "no event diverges without being recorded"
                (fn []
                  (let [d (em/divergent-payloads (em/scan))
                        unrecorded (vec (remove known (sort (keys d))))]
          ;; Named, so a failure says which event and the author can either fix
          ;; the producer or record why it differs.
                    (-> (expect (clj->js unrecorded)) (.toEqual (clj->js []))))))

            (it "every recorded entry still diverges"
                (fn []
        ;; The other direction: once a producer is fixed, its entry here must
        ;; go, or the list becomes folklore. This is the check `settings_reader`
        ;; calls an allowlist-revived test.
                  (let [d (em/divergent-payloads (em/scan))
                        stale (vec (remove (set (map str (keys d))) (keys known)))]
                    (-> (expect (clj->js stale)) (.toEqual (clj->js []))))))

            (it "provider_error carries message, model and config at EVERY site"
                (fn []
        ;; The three keys the fallback handler reads. This is the specific
        ;; regression: `config` was absent from the stream-error site, so the
        ;; chain could not fire for an unreachable provider.
                  (let [d (em/divergent-payloads (em/scan))
                        common (:common (get d "provider_error"))]
                    (doseq [k ["message" "model" "config"]]
                      (-> (expect #js [k (contains? common k)]) (.toEqual #js [k true]))))))))

(describe "the generated doc shows it"
          (fn []
            (it "has a divergence section, because the union column cannot express this"
                (fn []
                  (let [doc (em/render-event-map)]
                    (-> (expect doc) (.toContain "## Payload divergence"))
                    (-> (expect doc) (.toContain "provider_error")))))))
