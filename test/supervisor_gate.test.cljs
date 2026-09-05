(ns supervisor-gate.test
  "The supervisor's pre-commit gate is one of three triggers that decide when a
   second, stronger model gets consulted — and it was silently dead for half
   the tools it claimed to cover.

   `commit-tools` was `#{\"bash\" \"write\"}`, but the body then guarded
   `(when (= tool-name \"bash\") …)`. For a write the `when` yielded nil, so the
   enclosing `and` was falsey and the gate could never fire. The set advertised
   a capability the code did not have, and nothing tested it."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.supervisor :as sup :refer [commit-like?]]))

(describe "supervisor pre-commit gate"
          (fn []
            (it "fires on commands that publish work"
                (fn []
                  (doseq [cmd ["git commit -m 'x'"
                               "git push origin main"
                               "git merge develop"
                               "cd /tmp && git commit --amend"]]
                    (-> (expect (commit-like? "bash" #js {:command cmd})) (.toBe true)))))

            (it "ignores ordinary commands"
                (fn []
                  ;; Too loose and every turn summons the advisor.
                  (doseq [cmd ["ls -la" "bun test" "git status" "git diff" "echo commit"]]
                    (-> (expect (commit-like? "bash" #js {:command cmd})) (.toBe false)))))

            (it "returns a boolean for non-bash tools rather than nil"
                (fn []
                  ;; The original returned nil here, and nil in the `and` is
                  ;; what made the whole predicate dead for `write`. Nothing
                  ;; defines a commit-like write, and treating every write as
                  ;; one would consult the advisor on nearly every turn — so
                  ;; false is the answer, explicitly.
                  (doseq [t ["write" "edit" "read" "glob"]]
                    (-> (expect (commit-like? t #js {:command "git commit -m x"}))
                        (.toBe false)))))

            (it "does not throw on missing or malformed args"
                (fn []
                  (-> (expect (commit-like? "bash" nil)) (.toBe false))
                  (-> (expect (commit-like? "bash" #js {})) (.toBe false))
                  (-> (expect (commit-like? nil nil)) (.toBe false))))))

;;; ─── Finding the advisor tool ──────────────────────────────────────────────
;;
;; Tools are registered NAMESPACED — extension_scope prefixes every
;; registerTool name with "<ns>__" — while getTool passes the name straight
;; through. So `getTool "advisor"` could never match what `advisor` registered,
;; and the supervisor fell back to a canned string on every intervention.
;;
;; Observed live: three interventions in one session, each of them
;; "Supervisor: advisor tool not available", while the agent was stuck in a
;; read loop that was exactly what an advisor is for. Nothing said the lookup
;; had failed rather than the advisor having nothing to say.

(defn- api-with-tools
  "A getTool that only answers to the names in `registry`."
  [registry]
  #js {:getTool (fn [n] (get registry (str n)))})

(defn ^:async test-finds-namespaced-advisor []
  (let [called (atom nil)
        tool #js {:execute (fn [args] (reset! called (.-focus args))
                             (js/Promise.resolve "advice text"))}
        out (js-await (sup/call-advisor-tool
                       (api-with-tools {"advisor__advisor" tool}) "why stuck?"))]
    (-> (expect out) (.toBe "advice text"))
    (-> (expect @called) (.toBe "why stuck?"))))

(defn ^:async test-falls-back-to-bare-name []
  ;; overrideTool registers under the REAL name, with no prefix.
  (let [tool #js {:execute (fn [_] (js/Promise.resolve "bare advice"))}
        out (js-await (sup/call-advisor-tool
                       (api-with-tools {"advisor" tool}) "q"))]
    (-> (expect out) (.toBe "bare advice"))))

(defn ^:async test-says-so-when-really-absent []
  (let [out (js-await (sup/call-advisor-tool (api-with-tools {}) "q"))]
    (-> (expect (.includes out "not available")) (.toBe true))))

(defn ^:async test-survives-a-throwing-getTool []
  ;; getTool is capability-gated: without :tools it is a thrower, and that must
  ;; degrade to the fallback rather than take the intervention down.
  (let [api #js {:getTool (fn [_] (throw (js/Error. "missing capability")))}
        out (js-await (sup/call-advisor-tool api "q"))]
    (-> (expect (.includes out "not available")) (.toBe true))))

(describe "supervisor: advisor lookup" (fn []
  (it "finds the advisor under its namespaced name" test-finds-namespaced-advisor)
  (it "still accepts an unprefixed registration" test-falls-back-to-bare-name)
  (it "reports absence when there is genuinely no advisor" test-says-so-when-really-absent)
  (it "degrades instead of throwing when getTool is gated" test-survives-a-throwing-getTool)))
