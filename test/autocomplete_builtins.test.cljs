(ns autocomplete-builtins.test
  "Unit + small integration tests for the three built-in autocomplete
   providers that ship with nyma (slash-commands, at-file-mentions,
   path-complete). Before this file the built-ins had zero coverage:
   the provider registry (autocomplete_provider.cljs) and the app-level
   wiring (app.cljs) both had tests, but the providers in between — the
   actual code that answers `/`, `@`, and path queries — were only
   exercised implicitly. A typo in any provider's shape or trigger key
   would silently return no results with no error.

   The @file provider in particular has a cross-module delegation into
   :mention-providers that's easy to break during refactors. See
   extension_registration_e2e.test.cljs for the full-wire test that
   goes through the scoped API; this file tests the providers in
   isolation with a minimal fake agent."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.autocomplete-builtins :refer [register-all!]]
            [agent.ui.autocomplete-provider :refer [create-provider-registry complete-all]]))

;;; ─── Fake agent fixture ────────────────────────────────

(defn- fake-agent
  "Minimal agent shape that the built-in providers read from. Takes
   optional overrides for :commands and :mention-providers so each
   test can populate just what it cares about."
  [{:keys [commands mention-providers]}]
  {:autocomplete-registry (create-provider-registry)
   :commands              (atom (or commands {}))
   :mention-providers     (atom (or mention-providers {}))})

;;; ─── register-all! ─────────────────────────────────────
;;; This is the entry-point every startup runs. If it ever forgot to
;;; register one of the three providers, the corresponding trigger
;;; would stop working entirely.

(describe "register-all!" (fn []
                            (it "registers all three built-in providers with the correct trigger keys"
                                (fn []
                                  (let [agent (fake-agent {})]
                                    (register-all! agent)
                                    (let [reg      (:autocomplete-registry agent)
                                          slash    ((:get reg) "builtin.slash")
                                          forward  ((:get reg) "builtin.slash-forward")
                                          at       ((:get reg) "builtin.at-file")
                                          path     ((:get reg) "builtin.path")]
                                      (-> (expect (some? slash))   (.toBe true))
                                      (-> (expect (some? forward)) (.toBe true))
                                      (-> (expect (some? at))      (.toBe true))
                                      ;; Phase 21: path provider was dropped entirely.
                                      (-> (expect (nil? path)) (.toBe true))
                                      (-> (expect (:trigger slash))   (.toBe "slash"))
                                      (-> (expect (:trigger forward)) (.toBe "slash-forward"))
                                      (-> (expect (:trigger at))      (.toBe "at"))))))

                            (it "does not throw when :autocomplete-registry is nil"
                                (fn []
                                  (let [agent {:autocomplete-registry nil
                                               :commands (atom {})
                                               :mention-providers (atom {})}]
                                    ;; Host without autocomplete support — must no-op gracefully.
                                    (register-all! agent)
                                    (-> (expect true) (.toBe true)))))

                            (it "is idempotent: calling it twice overwrites cleanly"
                                (fn []
                                  (let [agent (fake-agent {})]
                                    (register-all! agent)
                                    (register-all! agent)
                                    (let [all ((:list (:autocomplete-registry agent)))]
                                      (-> (expect (count all)) (.toBe 3))))))))

;;; ─── slash-commands provider ───────────────────────────

(defn ^:async test-slash-returns-registered-commands []
  (let [agent (fake-agent {:commands {"clear" {:description "Clear chat"}
                                      "help"  {:description "Show help"}
                                      "model" {:description "Switch model"}}})]
    (register-all! agent)
    (let [results (js-await (complete-all (:autocomplete-registry agent) "/cl"))]
      ;; All three commands pass through the provider; the fuzzy filter
      ;; downstream narrows them, but at least "clear" must survive
      ;; a "cl" query.
      (-> (expect (pos? (count results))) (.toBe true))
      (-> (expect (some (fn [r]
                          (.includes (or (get r :label) (get r "label") "") "clear"))
                        results))
          (.toBe true)))))

(defn ^:async test-slash-empty-command-registry []
  (let [agent (fake-agent {})]
    (register-all! agent)
    (let [results (js-await (complete-all (:autocomplete-registry agent) "/"))]
      ;; No commands registered — provider returns an empty list, not
      ;; a nil/throw. Other providers may contribute but the slash
      ;; provider contract is "empty vec is OK".
      (-> (expect (some? results)) (.toBe true)))))

(defn ^:async test-slash-attaches-description-to-items []
  (let [agent (fake-agent {:commands {"clear" {:description "Reset session"}}})]
    (register-all! agent)
    (let [results (js-await (complete-all (:autocomplete-registry agent) "/cl"))
          match   (first (filter (fn [r]
                                   (.includes (or (get r :label) (get r "label") "") "clear"))
                                 results))]
      (-> (expect (some? match)) (.toBe true))
      (-> (expect (or (get match :description) (get match "description")))
          (.toBe "Reset session")))))

(describe "slash-commands provider" (fn []
                                      (it "lists every registered /command" test-slash-returns-registered-commands)
                                      (it "handles empty command registry without throwing" test-slash-empty-command-registry)
                                      (it "attaches :description from the command definition" test-slash-attaches-description-to-items)))

;;; ─── slash-forward provider (//command) ───────────────
;;; Phase 21: `//` fires an agent-forward picker. Items are labelled
;;; `//name` and filtered to commands tagged :forward-to "agent-shell"
;;; (registered by the ACP notifications handler when an agent
;;; connects). When no agent is connected, the provider falls back to
;;; the full visible command list so the picker is never silently
;;; empty.

(defn ^:async test-slash-forward-filters-to-agent-commands []
  (let [agent (fake-agent {:commands
                           {"plan"  {:description "Agent plan"
                                     :forward-to  "agent-shell"}
                            "help"  {:description "Nyma help"}
                            "other" {:description "Other agent cmd"
                                     :forward-to  "agent-shell"}}})]
    (register-all! agent)
    (let [results (js-await (complete-all (:autocomplete-registry agent) "//"))]
      (-> (expect (pos? (count results))) (.toBe true))
      ;; Every label must start with `//` (the forward prefix).
      (-> (expect (every? (fn [r]
                            (let [lbl (or (get r :label) (get r "label") "")]
                              (.startsWith lbl "//")))
                          results))
          (.toBe true))
      ;; And only the forward-to commands should be present.
      (-> (expect (boolean (some (fn [r]
                                   (.includes (or (get r :label) (get r "label") "") "plan"))
                                 results)))
          (.toBe true))
      (-> (expect (boolean (some (fn [r]
                                   (.includes (or (get r :label) (get r "label") "") "help"))
                                 results)))
          (.toBe false)))))

(defn ^:async test-slash-forward-falls-back-when-no-agent-commands []
  (let [agent (fake-agent {:commands {"help"  {:description "Nyma help"}
                                      "clear" {:description "Reset"}}})]
    (register-all! agent)
    ;; No commands are tagged :forward-to — the picker should fall back
    ;; to the full visible list so the user still sees something useful.
    (let [results (js-await (complete-all (:autocomplete-registry agent) "//"))]
      (-> (expect (pos? (count results))) (.toBe true)))))

(defn ^:async test-slash-forward-does-not-run-on-single-slash []
  (let [agent (fake-agent {:commands {"plan" {:description "Agent plan"
                                              :forward-to  "agent-shell"}}})]
    (register-all! agent)
    ;; Typing just `/p` should hit the nyma slash picker, not the
    ;; forward picker — label must be `/plan`, not `//plan`.
    (let [results (js-await (complete-all (:autocomplete-registry agent) "/p"))]
      (-> (expect (pos? (count results))) (.toBe true))
      (-> (expect (every? (fn [r]
                            (let [lbl (or (get r :label) (get r "label") "")]
                              (and (.startsWith lbl "/")
                                   (not (.startsWith lbl "//")))))
                          results))
          (.toBe true)))))

(describe "slash-forward provider"
          (fn []
            (it "filters to commands tagged :forward-to agent-shell"
                test-slash-forward-filters-to-agent-commands)
            (it "falls back to full visible commands when none are tagged"
                test-slash-forward-falls-back-when-no-agent-commands)
            (it "does not fire on a single `/` trigger (that's the nyma picker)"
                test-slash-forward-does-not-run-on-single-slash)))

;;; ─── at-file-mentions provider ─────────────────────────
;;; Delegates to the first mention provider's :search fn. This is the
;;; cross-module hop that the ACP-registry bug would have hit.

(defn ^:async test-at-file-delegates-to-first-mention-provider []
  (let [searches (atom [])
        provider {:trigger "@"
                  :label   "test-files"
                  :search  (fn [q]
                             (swap! searches conj q)
                             (js/Promise.resolve
                              #js [#js {:label "README.md" :value "README.md"}
                                   #js {:label "NOTES.md"  :value "NOTES.md"}]))}
        agent    (fake-agent {:mention-providers {"test-mp" provider}})]
    (register-all! agent)
    ;; The @ picker opens the moment the editor text ends with "@" —
    ;; that's detect-trigger's contract. Subsequent filtering happens
    ;; inside the picker UI, not by re-running complete-all. So the
    ;; right input is the instant the picker first opens.
    (let [results (js-await (complete-all (:autocomplete-registry agent) "hello @"))]
      ;; The provider's search fn was invoked — proves delegation
      ;; actually reached across the autocomplete_builtins →
      ;; :mention-providers atom boundary.
      (-> (expect (pos? (count @searches))) (.toBe true))
      (-> (expect (some (fn [r]
                          (.includes (or (get r :label) (get r "label") "") "README"))
                        results))
          (.toBe true)))))

(defn ^:async test-at-file-empty-mention-providers []
  (let [agent (fake-agent {})]
    (register-all! agent)
    ;; No mention providers registered — the at-file provider's
    ;; (first (vals {})) is nil, must return an empty Promise not throw.
    (let [results (js-await (complete-all (:autocomplete-registry agent) "hello @"))]
      (-> (expect (some? results)) (.toBe true)))))

(defn ^:async test-at-file-search-rejection-is-swallowed []
  (let [provider {:trigger "@"
                  :search  (fn [_q]
                             (js/Promise.reject (js/Error. "search backend down")))}
        agent    (fake-agent {:mention-providers {"flaky" provider}})]
    (register-all! agent)
    ;; A broken provider must not tank complete-all; it should return
    ;; an empty list for the at branch. (complete-all has its own
    ;; catch, and the at-file-provider has a .catch too.)
    (let [results (js-await (complete-all (:autocomplete-registry agent) "hello @foo"))]
      (-> (expect (some? results)) (.toBe true)))))

(describe "at-file-mentions provider" (fn []
                                        (it "delegates to the first registered mention provider's search fn"
                                            test-at-file-delegates-to-first-mention-provider)
                                        (it "returns gracefully when no mention providers are registered"
                                            test-at-file-empty-mention-providers)
                                        (it "swallows a rejecting provider without crashing complete-all"
                                            test-at-file-search-rejection-is-swallowed)))

;;; Phase 21: path-complete provider was removed entirely. File
;;; references now go through @-mentions exclusively. See
;;; autocomplete_provider.test.cljs for the trigger-detection
;;; negative assertions that pin this in.
