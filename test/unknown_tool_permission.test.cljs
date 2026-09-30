(ns unknown-tool-permission.test
  "A tool that declares no `:safety` categorises as \"other\" — and no shipped
   role policy mapped \"other\", so the gate allowed it with no prompt. Default
   mode exists to ask before a write or an exec; an extension tool that edits
   files and says nothing about itself slipped straight past it.

   Every MCP tool is in that position: `mcp_client`'s bridged tools and the two
   deferral gateways declare no safety, and `mcp_call` can invoke anything on any
   connected server.

   Plan mode was never the hole — it restricts via `:allowed-tools`, and the
   `tool_access_check` merge is a set intersection with no gateway union, so an
   MCP tool is withheld there rather than merely unprompted. The tests below pin
   both halves so neither protection can quietly go away."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.model-roles.policy :as policy]
            [agent.settings.manager :as sm]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.middleware :refer [categorize-tool]]))

(defn- roles [] (:roles sm/defaults))
(defn- decide [mode tool category]
  (policy/resolve-decision (get (roles) (keyword mode)) tool category))

;;; ─── the category ──────────────────────────────────────────────

(describe "an undeclared tool's category" (fn []

  (it "is \"other\" — that part was always true"
      (fn []
        (-> (expect (categorize-tool "mcp__some-server__write_file")) (.toBe "other"))
        (-> (expect (categorize-tool "totally_unknown_tool")) (.toBe "other"))))))

;;; ─── the hole ──────────────────────────────────────────────────

(describe "default and accept-edits must not allow an undeclared tool silently" (fn []

  (it "default mode asks about \"other\""
      (fn []
        ;; the bug is an ABSENT mapping, so asserting on the category alone would
        ;; pass while the hole stayed open — assert the DECISION
        (-> (expect (decide "default" "mcp__x__write_file" "other")) (.toBe "ask"))))

  (it "accept-edits asks about \"other\" too"
      (fn []
        ;; accept-edits waives the prompt for KNOWN writes; it does not waive it
        ;; for a tool that never said what it does
        (-> (expect (decide "accept-edits" "mcp__x__write_file" "other")) (.toBe "ask"))))

  (it "full-auto still allows it, or headless runs dead-end on a prompt"
      (fn []
        (-> (expect (decide "full-auto" "mcp__x__write_file" "other")) (.toBe "allow"))))

  (it "the known categories are unchanged in every mode"
      (fn []
        ;; the fix must not move write/exec/network
        (-> (expect (decide "default" "write" "write")) (.toBe "ask"))
        (-> (expect (decide "default" "bash" "exec")) (.toBe "ask"))
        (-> (expect (decide "accept-edits" "write" "write")) (.toBe "allow"))
        (-> (expect (decide "accept-edits" "bash" "exec")) (.toBe "ask"))
        (-> (expect (decide "full-auto" "bash" "exec")) (.toBe "allow"))))))

;;; ─── plan mode's own protection ────────────────────────────────

(describe "plan mode withholds an undeclared tool rather than prompting" (fn []

  (it "its allowed-tools list does not admit an MCP tool"
      (fn []
        (let [allowed (set (:allowed-tools (get (roles) :plan)))]
          (-> (expect (contains? allowed "mcp_call")) (.toBe false))
          (-> (expect (contains? allowed "read")) (.toBe true)))))

  (it "the intersection drops it, and an empty result is still a restriction"
      (fn []
        ;; combine-allowed-tools returning [] must mean "zero tools", not "no
        ;; opinion" — the distinction the gateway-union bug turned on
        (let [plan (:allowed-tools (get (roles) :plan))]
          (-> (expect (vec (policy/combine-allowed-tools plan ["mcp_call"])))
              (.toEqual (clj->js [])))
          (-> (expect (policy/combine-allowed-tools nil nil)) (.toBeNil)))))

  (it "plan still denies the write tools per-tool, belt and braces"
      (fn []
        (-> (expect (decide "plan" "write" "write")) (.toBe "deny"))
        (-> (expect (decide "plan" "bash" "exec")) (.toBe "deny"))))))

;;; ─── declaring safety is the robust half ───────────────────────

(describe "a declared tool escapes \"other\" entirely" (fn []

  (it "an extension tool that declares itself destructive categorises as write"
      (fn []
        ;; Mapping "other" fixes the shipped modes; declaring :safety fixes it for
        ;; a USER-DEFINED role too, which will not have thought about "other".
        ;; This is the path mcp_call now takes.
        (let [agent (create-agent {:model "test" :system-prompt "x"})
              api   (create-extension-api agent)]
          (.registerTool api "danger"
                         #js {:safety #js {:destructive? true
                                           :capabilities #js ["filesystem" "write"]
                                           :category "mcp"}
                              :description "d"
                              :inputSchema #js {:type "object" :properties #js {}}
                              :execute (fn [_] "x")})
          ;; the BASE api does not namespace — only create-scoped-api does
          (-> (expect (categorize-tool "danger")) (.toBe "write"))
          (.unregisterTool api "danger"))))

  (it "a read-only declaration categorises as read"
      (fn []
        (let [agent (create-agent {:model "test" :system-prompt "x"})
              api   (create-extension-api agent)]
          (.registerTool api "harmless"
                         #js {:safety #js {:read-only? true :capabilities #js []
                                           :category "search"}
                              :description "d"
                              :inputSchema #js {:type "object" :properties #js {}}
                              :execute (fn [_] "x")})
          (-> (expect (categorize-tool "harmless")) (.toBe "read"))
          (.unregisterTool api "harmless"))))))
