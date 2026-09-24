(ns small-model-edit-routing.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-metadata :as tm]
            [agent.extensions.small-model.profiles :as profiles :refer [edit-tools-to-hide]]))

;; editStrategy → which edit tools are hidden (Aider/OpenCode-style routing).
(describe "small-model:edit-tools-to-hide" (fn []
                                             (it "\"whole\" hides both exact edit and multi_edit (write only)"
                                                 (fn []
                                                   (let [h (edit-tools-to-hide "whole")]
                                                     (-> (expect (contains? h "edit")) (.toBe true))
                                                     (-> (expect (contains? h "multi_edit")) (.toBe true)))))

                                             (it "\"fuzzy\" hides only the brittle exact edit"
                                                 (fn []
                                                   (let [h (edit-tools-to-hide "fuzzy")]
                                                     (-> (expect (contains? h "edit")) (.toBe true))
                                                     (-> (expect (contains? h "multi_edit")) (.toBe false)))))

                                             (it "\"patch\" behaves like fuzzy (hides exact edit)"
                                                 (fn []
                                                   (-> (expect (contains? (edit-tools-to-hide "patch") "edit")) (.toBe true))))

                                             (it "\"exact\" and unset hide nothing"
                                                 (fn []
                                                   (-> (expect (count (edit-tools-to-hide "exact"))) (.toBe 0))
                                                   (-> (expect (count (edit-tools-to-hide nil))) (.toBe 0))))))

;; ── profile lookup: the whole module was unreachable ─────────────────────
;; `.provider` on an AI SDK model is the STRING "openai.chat" for every
;; OpenAI-compatible provider, so `.provider.providerId` is undefined and the
;; key collapsed to the bare modelId. Every documented profile key matched
;; nothing, silently, so temperature/allowedTools/editStrategy never applied.
(describe "small-model/profiles key matching"
          (fn []
            (it "offers provider/model, bare model, and short name"
                (fn []
                  (-> (expect (profiles/model-keys "openrouter" "qwen/qwen3.5-9b"))
                      (.toEqual #js ["openrouter/qwen/qwen3.5-9b" "qwen/qwen3.5-9b" "qwen3.5-9b"]))))

            (it "still yields usable keys when the provider name is missing"
                (fn []
                  (-> (expect (profiles/model-keys "" "Qwen3.6-27B-oQ4-mtp"))
                      (.toEqual #js ["Qwen3.6-27B-oQ4-mtp"]))))

            (it "does not emit duplicates when model id has no slash"
                (fn []
                  (-> (expect (count (profiles/model-keys "omlx" "big-pickle"))) (.toBe 2))))))

;; ── the allowlist must not strand the deferred MCP tools ─────────────────
;;
;; `tool_access_check` merges by INTERSECTION. A profile allowlist names the
;; tools the model is good at, and it was written before MCP deferral existed,
;; so it names neither gateway tool. Naming neither removes both — leaving the
;; deferred MCP tools withheld AND unreachable, which is strictly worse than
;; either deferring or not deferring. Measured stake: 9,993 tokens of the
;; 18,816-token standing prefix.

(def ^:private offered
  ["read" "write" "edit" "bash" "glob" "grep" "think" "mcp_search" "mcp_call"])

(describe "profiles/resolve-allowed — gateway tools survive" (fn []

  (it "a profile allowlist keeps the gateways even though it never names them"
      (fn []
        (let [out (set (profiles/resolve-allowed offered ["read" "write" "bash"] nil))]
          (-> (expect (contains? out "mcp_search")) (.toBe true))
          (-> (expect (contains? out "mcp_call")) (.toBe true))
          ;; and still narrows everything else
          (-> (expect (contains? out "think")) (.toBe false))
          (-> (expect (contains? out "read")) (.toBe true)))))

  (it "does not invent a gateway that is not on offer this turn"
      (fn []
        ;; deferral off, or MCP not configured — nothing to reach, nothing to add
        (let [out (set (profiles/resolve-allowed ["read" "write"] ["read"] nil))]
          (-> (expect (contains? out "mcp_search")) (.toBe false))
          (-> (expect (count out)) (.toBe 1)))))

  (it "no profile allowlist and no hiding means no opinion at all"
      (fn []
        ;; nil, not an empty vector: an empty allowlist would intersect to
        ;; nothing and strip every tool
        (-> (expect (profiles/resolve-allowed offered nil nil)) (.toBeNil))))

  (it "edit-strategy hiding still applies, and still keeps the gateways"
      (fn []
        (let [out (set (profiles/resolve-allowed offered nil #{"edit"}))]
          (-> (expect (contains? out "edit")) (.toBe false))
          (-> (expect (contains? out "write")) (.toBe true))
          (-> (expect (contains? out "mcp_search")) (.toBe true)))))

  (it "an allowlist that hides an edit tool cannot smuggle it back"
      (fn []
        (let [out (set (profiles/resolve-allowed offered ["read" "edit"] #{"edit"}))]
          (-> (expect (contains? out "edit")) (.toBe false))
          (-> (expect (contains? out "read")) (.toBe true)))))

  (it "the set names every tool that is the only route to withheld content"
      (fn []
        ;; the two deferral introduces, plus the recall for a truncated result
        (-> (expect (contains? tm/gateway-tool-names "mcp_search")) (.toBe true))
        (-> (expect (contains? tm/gateway-tool-names "mcp_call")) (.toBe true))
        (-> (expect (contains? tm/gateway-tool-names "retrieve_result")) (.toBe true))
        (-> (expect (count tm/gateway-tool-names)) (.toBe 3))))

  (it "a profile allowlist that forgets the truncation recall still gets it"
      (fn []
        ;; tool_result_policy caps a large result and hands back a handle; an
        ;; allowlist naming `read` but not the recall leaves the model holding
        ;; an id it cannot spend. Every built-in role carries it by convention;
        ;; a user-written profile has no such protection.
        (let [out (set (profiles/resolve-allowed
                        ["read" "bash" "retrieve_result" "mcp_search" "mcp_call"]
                        ["read" "bash"] nil))]
          (-> (expect (contains? out "retrieve_result")) (.toBe true)))))))

;;; ─── the strategy's target must survive the allowlist ─────────

(def ^:private offered-all
  ["read" "write" "edit" "multi_edit" "bash" "glob" "grep" "mcp_search" "mcp_call"])

(describe "profiles/resolve-allowed — a strategy can reach the tool it routes to" (fn []

  (it "\"patch\" surfaces multi_edit even when the allowlist never named it"
      (fn []
        ;; The benchmark profile: edit-strategy "patch", allowlist naming `edit`
        ;; and not `multi_edit`. "patch" hides `edit`, so the allowlist lost the
        ;; only edit tool it had and nothing could add the one the strategy
        ;; wanted — the profile read "patch" and behaved as "whole". Measured as
        ;; 0 edit calls and 2.5x the writes across 25 tasks.
        (let [out (set (profiles/resolve-allowed
                        offered-all
                        ["read" "write" "edit" "bash" "glob" "grep"]
                        (profiles/edit-tools-to-hide "patch")
                        (profiles/edit-tools-to-keep "patch")))]
          (-> (expect (contains? out "multi_edit")) (.toBe true))
          (-> (expect (contains? out "edit")) (.toBe false))
          (-> (expect (contains? out "write")) (.toBe true)))))

  (it "no strategy takes away the last way to change a file"
      (fn []
        ;; The allowlist below DOES name a mutation tool, so whatever the
        ;; strategy hides it must leave something that can write. An allowlist
        ;; naming none is a different thing — that is the user declining to
        ;; grant one, not a strategy removing it — so it is not asserted here.
        (doseq [strat ["whole" "fuzzy" "patch" "exact"]]
          (let [out (set (profiles/resolve-allowed
                          offered-all ["read" "bash" "edit"]
                          (profiles/edit-tools-to-hide strat)
                          (profiles/edit-tools-to-keep strat)))]
            (-> (expect (boolean (some out ["write" "edit" "multi_edit"]))) (.toBe true))))))

  (it "\"whole\" still hides both fuzzy tools while keeping write"
      (fn []
        (let [out (set (profiles/resolve-allowed
                        offered-all nil
                        (profiles/edit-tools-to-hide "whole")
                        (profiles/edit-tools-to-keep "whole")))]
          (-> (expect (contains? out "edit")) (.toBe false))
          (-> (expect (contains? out "multi_edit")) (.toBe false))
          (-> (expect (contains? out "write")) (.toBe true)))))

  (it "hide still beats keep, so a strategy cannot contradict itself"
      (fn []
        (let [out (set (profiles/resolve-allowed
                        offered-all ["read"] #{"multi_edit"} #{"multi_edit"}))]
          (-> (expect (contains? out "multi_edit")) (.toBe false)))))

  (it "a tool the strategy wants but the turn does not offer is not invented"
      (fn []
        ;; cands is the live active set; adding a name that is not on offer
        ;; would put a tool in the allowlist that cannot be called
        (let [out (set (profiles/resolve-allowed
                        ["read" "write" "bash"] ["read" "bash"]
                        (profiles/edit-tools-to-hide "patch")
                        (profiles/edit-tools-to-keep "patch")))]
          (-> (expect (contains? out "multi_edit")) (.toBe false)))))

  (it "\"exact\" and unset keep the old no-opinion behaviour"
      (fn []
        (-> (expect (count (profiles/edit-tools-to-keep "exact"))) (.toBe 0))
        (-> (expect (count (profiles/edit-tools-to-keep nil))) (.toBe 0))
        (-> (expect (profiles/resolve-allowed offered-all nil #{} #{})) (.toBeNil))))))
