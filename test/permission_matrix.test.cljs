(ns permission-matrix.test
  "Every permission mode × every tool category, through the REAL gate:
   model_roles activated by the loader answers `permission_request`, and
   `permission-check-enter` turns that into allow / a prompt / a cancel.
   The real gate, not a re-implementation: a copy cannot drift-test the
   original.

   Also: an active skill's `allowed-tools` answers the ASK for the user and
   nothing more — an explicit deny, from a handler or from a settings role,
   still cancels the call."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.builtin-extensions :refer [registry]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.middleware :refer [permission-check-enter reset-session-allows!]]))

(def ^:private model-roles-entry
  (first (filter #(= (:namespace %) "model-roles") registry)))

(defn- ^:async gate-with-model-roles
  "Agent + api with model_roles loaded alone, the UI answering every prompt
   with Deny and recording that it was asked. Returns {:agent :loaded :asked}."
  [& [opts]]
  (let [agent  (create-agent (merge {:model "test" :system-prompt "matrix"} opts))
        api    (create-extension-api agent)
        asked  (atom 0)]
    (set! (.-extension-api agent) api)
    (set! (.-ui api) #js {:available true
                          :select    (fn [_q _opts] (swap! asked inc) (js/Promise.resolve "Deny"))})
    (let [loaded (js-await (discover-and-load [] api [model-roles-entry]))]
      {:agent agent :loaded loaded :asked asked})))

(defn- ^:async decide
  "\"allow\" | \"ask\" | \"deny\" for one tool under one permission mode."
  [mode tool-name]
  (reset-session-allows!)
  (let [{:keys [agent loaded asked]} (js-await (gate-with-model-roles))]
    (try
      (swap! (:state agent) assoc :permission-mode mode)
      (let [ctx (js-await (permission-check-enter (:events agent) nil
                                                  {:tool-name tool-name :args {:path "f"}
                                                   :cancelled false :agent agent}))]
        (cond
          (pos? @asked)    "ask"
          (:cancelled ctx) "deny"
          :else            "allow"))
      (finally
        (js-await (deactivate-all loaded))))))

;; Category → the tool the gate would actually see. "other" is any tool with
;; no safety metadata; an MCP tool is one of those, so its column documents
;; that no mode gates it today.
(def ^:private probes
  [["read" "read"] ["write" "write"] ["exec" "bash"] ["other" "frobnicate"] ["mcp" "mcp__srv__tool"]])

(def ^:private expected
  {"default"      {"read" "allow" "write" "ask"   "exec" "ask"   "other" "allow" "mcp" "allow"}
   "accept-edits" {"read" "allow" "write" "allow" "exec" "ask"   "other" "allow" "mcp" "allow"}
   "plan"         {"read" "allow" "write" "deny"  "exec" "deny"  "other" "allow" "mcp" "allow"}
   "full-auto"    {"read" "allow" "write" "allow" "exec" "allow" "other" "allow" "mcp" "allow"}})

(describe "permission gate matrix (mode × category, real gate)"
          (fn []
            (beforeEach (fn [] (reset-session-allows!)))
            (doseq [[mode row] expected
                    [category tool] probes]
              (it (str mode " × " category " (" tool ") → " (get row category))
                  (fn []
                    (-> (decide mode tool)
                        (.then (fn [d] (-> (expect d) (.toBe (get row category)))))))))))

;;; ─── skill allowed-tools cannot lift a deny ────────────────────────────

(defn- skill-state! [agent tool]
  (swap! (:state agent) assoc
         :active-skills #{"deploy"}
         :skill-allowed-tools {"deploy" #{tool}}))

(defn ^:async test-handler-deny-survives-skill []
  (reset-session-allows!)
  (let [agent  (create-agent {:model "test" :system-prompt "skill"})
        events (:events agent)
        _      (skill-state! agent "bash")
        _      ((:on events) "permission_request" (fn [_] #js {:decision "deny" :reason "hook says no"}))
        ctx    (js-await (permission-check-enter events nil
                                                 {:tool-name "bash" :args {:command "rm -rf x"}
                                                  :cancelled false :agent agent}))]
    (-> (expect (:cancelled ctx)) (.toBe true))
    (-> (expect (:cancel-reason ctx)) (.toBe "hook says no"))))

(defn ^:async test-handler-ask-is-answered-by-skill []
  ;; The control: the same skill DOES answer an ask, so the deny test above
  ;; is not passing because skills are ignored altogether.
  (reset-session-allows!)
  (let [agent  (create-agent {:model "test" :system-prompt "skill"})
        events (:events agent)
        _      (skill-state! agent "bash")
        _      ((:on events) "permission_request" (fn [_] #js {:decision "ask"}))
        ctx    (js-await (permission-check-enter events nil
                                                 {:tool-name "bash" :args {} :cancelled false :agent agent}))]
    (-> (expect (boolean (:cancelled ctx))) (.toBe false))))

(defn ^:async test-settings-role-deny-survives-skill []
  ;; `roles.default.permissions.bash = "deny"` in .nyma/settings.json is the
  ;; user's standing rule; a skill shipped with a repo must not override it.
  (reset-session-allows!)
  (let [tmp  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-skill-deny-"))
        proj (path/join tmp "settings.json")
        _    (fs/writeFileSync proj (js/JSON.stringify
                                     #js {:roles #js {:default #js {:permissions #js {:bash "deny"}}}}))
        mgr  (create-settings-manager {:global-path  (path/join tmp "none.json")
                                       :project-path proj})
        {:keys [agent loaded asked]} (js-await (gate-with-model-roles {:settings mgr}))]
    (try
      (skill-state! agent "bash")
      ;; full-auto is the most permissive MODE; the role's deny still wins.
      (swap! (:state agent) assoc :permission-mode "full-auto")
      (let [ctx (js-await (permission-check-enter (:events agent) nil
                                                  {:tool-name "bash" :args {:command "ls"}
                                                   :cancelled false :agent agent}))]
        (-> (expect (:cancelled ctx)) (.toBe true))
        (-> (expect @asked) (.toBe 0)))
      (finally
        (js-await (deactivate-all loaded))
        (fs/rmSync tmp #js {:recursive true :force true})))))

(describe "skill allowed-tools answers the ask, never the deny"
          (fn []
            (it "a permission_request handler's deny still cancels a skill-allowed tool"
                test-handler-deny-survives-skill)
            (it "the same skill does answer an ask (control)"
                test-handler-ask-is-answered-by-skill)
            (it "a settings role deny still cancels a skill-allowed tool"
                test-settings-role-deny-survives-skill)))
