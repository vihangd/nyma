(ns agent-registry.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.agent-shell.agents.registry :as registry]))

(beforeEach
  (fn []
    (registry/reset-dynamic!)))

;;; ─── Built-in agents ───────────────────────────────────────────

(describe "agent-registry:builtins" (fn []
  (it "has 6 built-in agents"
    (fn []
      (-> (expect (count registry/builtin-agents)) (.toBe 6))))

  (it "get-agent returns builtin by key"
    (fn []
      (let [agent (registry/get-agent :claude)]
        (-> (expect (some? agent)) (.toBe true))
        (-> (expect (:command agent)) (.toBe "npx"))
        (-> (expect (:name agent)) (.toBe "Claude Code")))))

  (it "get-agent returns nil for unknown key"
    (fn []
      (-> (expect (registry/get-agent :nonexistent)) (.toBeUndefined))))

  (it "list-agents includes all 6 builtins"
    (fn []
      (let [agents (registry/list-agents)]
        (-> (expect (count agents)) (.toBe 6))
        (-> (expect (some #(= (:key %) :claude) agents)) (.toBeTruthy))
        (-> (expect (some #(= (:key %) :gemini) agents)) (.toBeTruthy)))))))

;;; ─── Config agents (from .nyma/settings.json) ─────────────────

(describe "agent-registry:config" (fn []
  (it "refresh! loads agents from config"
    (fn []
      (registry/refresh!
        {:agents {"my-agent" {:name    "My Agent"
                              :command "my-cli"
                              :args    ["--acp"]}}})
      (let [agent (registry/get-agent :my-agent)]
        (-> (expect (some? agent)) (.toBe true))
        (-> (expect (:command agent)) (.toBe "my-cli"))
        (-> (expect (:name agent)) (.toBe "My Agent")))))

  (it "config features array converts to keyword set"
    (fn []
      (registry/refresh!
        {:agents {"test" {:command  "test-cli"
                          :features ["model-switch" "cost" "sessions"]}}})
      (let [agent (registry/get-agent :test)]
        (-> (expect (contains? (:features agent) :model-switch)) (.toBe true))
        (-> (expect (contains? (:features agent) :cost)) (.toBe true))
        (-> (expect (contains? (:features agent) :sessions)) (.toBe true)))))

  (it "config modes map has keyword keys"
    (fn []
      (registry/refresh!
        {:agents {"test" {:command "test-cli"
                          :modes   {"yolo" "y" "plan" "p"}}}})
      (let [agent (registry/get-agent :test)]
        (-> (expect (get (:modes agent) :yolo)) (.toBe "y"))
        (-> (expect (get (:modes agent) :plan)) (.toBe "p")))))

  (it "config agent overrides builtin fields"
    (fn []
      (registry/refresh!
        {:agents {:claude {:command "custom-claude"
                           :args    ["--custom"]}}})
      ;; The merged result should have the config command but retain builtin features
      (let [agent (registry/get-agent :claude)]
        (-> (expect (:command agent)) (.toBe "custom-claude")))))

  (it "config agent without command is rejected"
    (fn []
      (registry/refresh!
        {:agents {"bad-agent" {:name "No Command"}}})
      (-> (expect (registry/get-agent :bad-agent)) (.toBeUndefined))))

  (it "config agent gets default name from key when name missing"
    (fn []
      (registry/refresh!
        {:agents {"auto-named" {:command "auto-cli"}}})
      (let [agent (registry/get-agent :auto-named)]
        (-> (expect (:name agent)) (.toBe "auto-named")))))

  (it "config agents appear in list-agents"
    (fn []
      (registry/refresh!
        {:agents {"custom" {:name "Custom Agent" :command "custom-cli"}}})
      (let [agents (registry/list-agents)]
        (-> (expect (some #(= (:key %) :custom) agents)) (.toBeTruthy))
        ;; Still has builtins
        (-> (expect (some #(= (:key %) :claude) agents)) (.toBeTruthy)))))

  (it "refresh! with nil agents clears config agents"
    (fn []
      (registry/refresh! {:agents {"x" {:command "x-cli"}}})
      (-> (expect (some? (registry/get-agent :x))) (.toBe true))
      (registry/refresh! {})
      (-> (expect (registry/get-agent :x)) (.toBeUndefined))))))

;;; ─── Dynamic agents (extension-registered) ─────────────────────

(describe "agent-registry:dynamic" (fn []
  (it "register-agent! adds a dynamic agent"
    (fn []
      (let [ok (registry/register-agent! :ext-agent
                 {:name "Ext Agent" :command "ext-cli" :args ["--acp"]})]
        (-> (expect ok) (.toBe true))
        (let [agent (registry/get-agent :ext-agent)]
          (-> (expect (:name agent)) (.toBe "Ext Agent"))
          (-> (expect (:command agent)) (.toBe "ext-cli"))))))

  (it "unregister-agent! removes a dynamic agent"
    (fn []
      (registry/register-agent! :temp {:command "temp-cli"})
      (-> (expect (some? (registry/get-agent :temp))) (.toBe true))
      (registry/unregister-agent! :temp)
      (-> (expect (registry/get-agent :temp)) (.toBeUndefined))))

  (it "dynamic agents appear in list-agents"
    (fn []
      (registry/register-agent! :dyn {:name "Dynamic" :command "dyn-cli"})
      (let [agents (registry/list-agents)]
        (-> (expect (some #(= (:key %) :dyn) agents)) (.toBeTruthy)))))

  (it "dynamic overrides config overrides builtin (priority order)"
    (fn []
      ;; Config sets custom command for claude
      (registry/refresh!
        {:agents {:claude {:command "config-claude" :args ["--config"]}}})
      (-> (expect (:command (registry/get-agent :claude))) (.toBe "config-claude"))
      ;; Dynamic overrides config
      (registry/register-agent! :claude
        {:name "Dynamic Claude" :command "dyn-claude" :args ["--dyn"]})
      (-> (expect (:command (registry/get-agent :claude))) (.toBe "dyn-claude"))
      (-> (expect (:name (registry/get-agent :claude))) (.toBe "Dynamic Claude"))
      ;; Unregister dynamic → falls back to config
      (registry/unregister-agent! :claude)
      (-> (expect (:command (registry/get-agent :claude))) (.toBe "config-claude"))))

  (it "register-agent! returns false for invalid config (no command)"
    (fn []
      (let [ok (registry/register-agent! :bad {:name "No Command"})]
        (-> (expect ok) (.toBe false))
        (-> (expect (registry/get-agent :bad)) (.toBeUndefined)))))

  (it "register-agent! accepts string key"
    (fn []
      (registry/register-agent! "str-key" {:command "str-cli"})
      (-> (expect (some? (registry/get-agent :str-key))) (.toBe true))))

  (it "reset-dynamic! clears all dynamic and config agents"
    (fn []
      (registry/refresh! {:agents {"cfg" {:command "cfg-cli"}}})
      (registry/register-agent! :dyn {:command "dyn-cli"})
      (-> (expect (some? (registry/get-agent :cfg))) (.toBe true))
      (-> (expect (some? (registry/get-agent :dyn))) (.toBe true))
      (registry/reset-dynamic!)
      (-> (expect (registry/get-agent :cfg)) (.toBeUndefined))
      (-> (expect (registry/get-agent :dyn)) (.toBeUndefined))
      ;; Builtins still there
      (-> (expect (some? (registry/get-agent :claude))) (.toBe true))))))

;;; ─── Partial overrides merge over the builtin ──────────────────────────────
;;
;; `docs/agent-shell.md` documents `agent-shell.agents.<key>` as an OVERRIDE
;; mechanism, with `{"claude": {"init-mode": "plan"}}` as the example. It could
;; not work, twice over:
;;
;;   1. normalize-agent-config returns nil without :command, so the entry was
;;      dropped — silently. The one setting that keeps Claude Code out of edit
;;      mode during a planning session did nothing.
;;   2. Supplying :command to get past that replaced the builtin wholesale
;;      (the registry merge is shallow per agent key), blanking :modes and
;;      :features — so plan mode had no mode id left to resolve.

(describe "agent registry: partial config overrides" (fn []

  (it "applies init-mode without requiring command"
      (fn []
        (registry/refresh! {:agents {"claude" {:init-mode "plan"}}})
        (let [c (registry/get-agent :claude)]
          (-> (expect (:init-mode c)) (.toBe "plan"))
          ;; …and the builtin definition survives.
          (-> (expect (:command c)) (.toBe "npx"))
          (-> (expect (pos? (count (:args c)))) (.toBe true))
          (-> (expect (contains? (:features c) :plan-mode)) (.toBe true))
          (-> (expect (get (:modes c) :plan)) (.toBe "plan")))))

  (it "keeps modes intact even when the override supplies a command"
      (fn []
        ;; The second failure mode: an override that names :command used to
        ;; replace everything, and plan mode lost its mode id.
        (registry/refresh! {:agents {"claude" {:command "my-claude" :init-mode "plan"}}})
        (let [c (registry/get-agent :claude)]
          (-> (expect (:command c)) (.toBe "my-claude"))
          (-> (expect (get (:modes c) :plan)) (.toBe "plan"))
          (-> (expect (contains? (:features c) :plan-mode)) (.toBe true)))))

  (it "still requires a command for an agent that is not builtin"
      (fn []
        ;; A brand-new agent has nothing to merge over, so it must be runnable.
        (registry/refresh! {:agents {"nonesuch" {:init-mode "plan"}}})
        (-> (expect (registry/get-agent :nonesuch)) (.toBeUndefined))
        (registry/refresh! {:agents {"nonesuch" {:command "x" :args ["y"]}}})
        (-> (expect (:command (registry/get-agent :nonesuch))) (.toBe "x"))))

  (it "leaves builtins alone when there is no config"
      (fn []
        (registry/refresh! {})
        (let [c (registry/get-agent :claude)]
          (-> (expect (:init-mode c)) (.toBeNil))
          (-> (expect (:command c)) (.toBe "npx")))))

  (it "normalize-agent-override returns only the keys present"
      (fn []
        ;; Detector self-test: an override that filled in defaults would
        ;; reintroduce the blanking bug via the merge.
        (let [o (registry/normalize-agent-override {:init-mode "plan"})]
          (-> (expect (js/Object.keys (clj->js o))) (.toEqual #js ["init-mode"])))
        (-> (expect (registry/normalize-agent-override nil)) (.toBeNil))))))
