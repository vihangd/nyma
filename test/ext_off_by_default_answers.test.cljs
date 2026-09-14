(ns ext-off-by-default-answers.test
  "An off extension should say it is off, not vanish.

   openwiki registered `/openwiki` only inside `(when (:enabled config))`, so
   with the extension off the command did not exist — and `Unknown command`
   reads as a broken install rather than a switch nobody flipped.

   budget and verify_gate register no commands at all; they switch on by the
   presence of a cap / of `verify.cmd`. verify_gate has the sharper failure:
   someone who wrote a `verify` section and misspelled `cmd` got exactly the
   same silence as someone who never wanted the gate. It cannot warn on an
   empty `cmd` alone — extension.json declares the section, the loader
   registers it as defaults, so every user has a `verify` section — but an
   unrecognised key in it is proof somebody tried."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.openwiki.shared :as ow]
            ;; openwiki's index.cljs declares the ns `agent.extensions.openwiki`
            ;; while living at .../openwiki/index.cljs, so a ns-based require
            ;; resolves to a package that does not exist. Import the compiled
            ;; file next to this one instead.
            ["./agent/extensions/openwiki/index.mjs" :as ow-index]
            [agent.extensions.verify-gate.shared :as vg]
            [agent.extensions.verify-gate.index :as vg-index]
            [agent.extensions.budget.shared :as budget]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- readme [ext]
  (fs/readFileSync
   (path/join (js/process.cwd) "src" "agent" "extensions" ext "README.md") "utf8"))

(defn- stub-api [{:keys [settings flag]}]
  (let [registered (atom {})
        listeners  (atom {})]
    {:registered registered
     :listeners  listeners
     :api #js {:registerFlag      (fn [_ _] nil)
               :getFlag           (fn [_] flag)
               :registerCommand   (fn [n spec] (swap! registered assoc n spec) nil)
               :unregisterCommand (fn [n] (swap! registered dissoc n) nil)
               :registerTool      (fn [_ _] nil)
               :unregisterTool    (fn [_] nil)
               :on                (fn [evt h & _]
                                    (swap! listeners update evt (fnil conj []) h) nil)
               :off               (fn [evt h]
                                    (swap! listeners update evt
                                           (fn [hs] (filterv #(not= % h) (or hs []))))
                                    nil)
               :settings          (fn [& [section]]
                                    (if section
                                      (or (aget (or settings #js {}) section) #js {})
                                      (or settings #js {})))
               :ui                #js {:available false}}}))

;;; ─── openwiki ─────────────────────────────────────────────────────────────

(describe "openwiki answers /openwiki when it is off"
          (fn []
            (it "names the setting and the flag"
                (fn []
                  (let [hint (ow/disabled-hint "wiki")]
                    (-> (expect (.includes hint "openwiki is off")) (.toBe true))
                    (-> (expect (.includes hint "openwiki.enabled: true")) (.toBe true))
                    (-> (expect (.includes hint ".nyma/settings.json")) (.toBe true))
                    (-> (expect (.includes hint "--ext-openwiki")) (.toBe true))
                    (-> (expect (.includes hint "wiki/")) (.toBe true)))))

            (it "registers the command even with the extension disabled"
                (fn []
                  (let [{:keys [registered api]} (stub-api {:settings #js {} :flag nil})
                        dispose ((.-default ow-index) api)]
                    (-> (expect (contains? @registered "openwiki")) (.toBe true))
                    (-> (expect (.includes ((.-handler (get @registered "openwiki")) "" nil)
                                           "openwiki is off"))
                        (.toBe true))
                    (dispose)
                    (-> (expect (contains? @registered "openwiki")) (.toBe false)))))

            (it "does not register the stub on top of the real command"
                (fn []
                  ;; The stub sits in the post-flag-resolution branch. Registered
                  ;; any earlier, --ext-openwiki would bind both handlers to one
                  ;; name and the stub would be what a user got.
                  (let [{:keys [registered api]} (stub-api {:settings #js {} :flag true})]
                    ((.-default ow-index) api)
                    ;; One registration, and it is the real one — the stub's
                    ;; description is the tell, since calling the real handler
                    ;; here would run a whole init prompt.
                    (-> (expect (.includes (.-description (get @registered "openwiki"))
                                           "is off"))
                        (.toBe false))
                    (-> (expect (.includes (.-description (get @registered "openwiki"))
                                           "init | update"))
                        (.toBe true)))))))

;;; ─── verify_gate ──────────────────────────────────────────────────────────

(describe "verify_gate says so when verify.cmd is misspelled"
          (fn []
            (it "treats an unrecognised key as the signal that someone tried"
                (fn []
                  (-> (expect (vg/unknown-keys #js {"cmd" "bun test"})) (.toEqual #js []))
                  (-> (expect (vg/unknown-keys #js {"cmd" nil "max-attempts" 2 "timeout-ms" 1}))
                      (.toEqual #js []))
                  (-> (expect (vg/unknown-keys #js {"comand" "bun test"}))
                      (.toEqual #js ["comand"]))
                  (-> (expect (vg/unknown-keys nil)) (.toEqual #js []))))

            (it "names the key that works and the ones that do not"
                (fn []
                  (let [msg (vg/off-hint ["comand" "maxAttempts"])]
                    (-> (expect (.includes msg "verify_gate loaded but off")) (.toBe true))
                    (-> (expect (.includes msg "set verify.cmd")) (.toBe true))
                    (-> (expect (.includes msg "comand")) (.toBe true))
                    (-> (expect (.includes msg "maxAttempts")) (.toBe true)))))

            (it "subscribes to session_ready only when a typo is present"
                (fn []
                  ;; A clean manifest-default section must stay silent: every
                  ;; user has one, so an existence check would nag everybody.
                  (let [{:keys [listeners api]}
                        (stub-api {:settings #js {"verify" #js {"cmd" nil "max-attempts" 2}}})]
                    ((.-default vg-index) api)
                    (-> (expect (count (get @listeners "session_ready" []))) (.toBe 0)))

                  (let [{:keys [listeners api]}
                        (stub-api {:settings #js {"verify" #js {"comand" "bun test"}}})]
                    ((.-default vg-index) api)
                    (-> (expect (count (get @listeners "session_ready" []))) (.toBe 1)))))

            (it "does not warn at all when the gate is actually on"
                (fn []
                  (let [{:keys [listeners api]}
                        (stub-api {:settings #js {"verify" #js {"cmd" "bun test"
                                                                "comand" "typo"}}})]
                    ((.-default vg-index) api)
                    (-> (expect (count (get @listeners "session_ready" []))) (.toBe 0))
                    ;; …it wires the real gate instead.
                    (-> (expect (count (get @listeners "turn_finalize" []))) (.toBe 1)))))))

;;; ─── budget and verify_gate: no command to ask, so the README must say ────

(describe "budget and verify_gate document how to switch them on"
          (fn []
            (it "budget is on exactly when a cap is set"
                (fn []
                  (-> (expect (budget/enabled? (budget/config nil))) (.toBe false))
                  (-> (expect (budget/enabled? (budget/config #js {"budget" #js {"turn-tokens" 100}})))
                      (.toBe true))))

            (it "each README leads with a How to enable section"
                (fn []
                  ;; Neither extension registers a command, so the README is the
                  ;; only place a user can find out that an absent cap / absent
                  ;; verify.cmd is the whole switch.
                  (doseq [[ext key-name] [["budget" "turn-tokens"] ["verify_gate" "verify.cmd"]]]
                    (let [r (readme ext)]
                      (-> (expect (.includes r "## How to enable")) (.toBe true))
                      (-> (expect (.includes r key-name)) (.toBe true))
                      ;; …and it comes before the rest of the prose.
                      (-> (expect (< (.indexOf r "## How to enable")
                                     (.indexOf r "## Config")))
                          (.toBe true))))))))
