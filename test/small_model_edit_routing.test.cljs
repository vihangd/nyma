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

  (it "the gateway set names exactly the two tools deferral introduces"
      (fn []
        (-> (expect (contains? tm/gateway-tool-names "mcp_search")) (.toBe true))
        (-> (expect (contains? tm/gateway-tool-names "mcp_call")) (.toBe true))
        (-> (expect (count tm/gateway-tool-names)) (.toBe 2))))))
