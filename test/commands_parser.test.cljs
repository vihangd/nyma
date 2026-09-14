(ns commands-parser.test
  "Pure unit tests for commands/parser.cljs — the suggestion and
   display-name helpers ported from cc-kit's command-registry.test.ts
   (adapted to nyma's map-based command shape) plus nyma-specific
   regression coverage for the bugs we've already hit in the
   slash-completion path."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.parser :refer [command-suggestions
                                           compute-display-names
                                           visible-commands
                                           hidden? enabled? visible?]]))

;;; ─── Fixture helpers ──────────────────────────────────
;;; Mirrors cc-kit's makeLocalCommand at command-registry.test.ts:9-16.
;;; Every field defaults are sensible so test sites only override what
;;; they care about.

(defn- mk-cmd
  "Build a command spec for tests. Name is the map key, not a field."
  ([] (mk-cmd {}))
  ([overrides]
   (merge {:description "test command"
           :handler     (fn [_ _] nil)}
          overrides)))

(defn- registry
  "Build a command map from a list of [name overrides] pairs."
  [& pairs]
  (into {} (for [[name override] pairs] [name (mk-cmd (or override {}))])))

;;; ─── command-suggestions (cc-kit tests 8, 9, 14) ──────

(defn- names-set
  "Return the set of command :name values from a suggestions seq."
  [suggs]
  (set (map :name suggs)))

(describe "command-suggestions" (fn []
                                  (it "returns commands matching the partial prefix"
                                      (fn []
                                        (let [cmds  (registry ["help" nil] ["history" nil] ["exit" nil])
                                              names (names-set (command-suggestions "/h" cmds))]
                                          (-> (expect (contains? names "help"))    (.toBe true))
                                          (-> (expect (contains? names "history")) (.toBe true))
                                          (-> (expect (contains? names "exit"))    (.toBe false)))))

                                  (it "returns an empty seq for a non-slash prefix"
                                      (fn []
                                        (let [cmds (registry ["help" nil])]
                                          (-> (expect (count (command-suggestions "hel" cmds))) (.toBe 0)))))

                                  (it "includes commands whose alias matches"
                                      (fn []
                                        (let [cmds  (registry ["clear" {:aliases ["cls"]}])
                                              names (names-set (command-suggestions "/cl" cmds))]
                                          (-> (expect (contains? names "clear")) (.toBe true)))))

                                  (it "excludes hidden commands from suggestions"
                                      (fn []
                                        (let [cmds  (registry ["visible" nil]
                                                              ["secret" {:hidden? true}])
                                              names (names-set (command-suggestions "/" cmds))]
                                          (-> (expect (contains? names "visible")) (.toBe true))
                                          (-> (expect (contains? names "secret"))  (.toBe false)))))

                                  (it "excludes disabled commands from suggestions"
                                      (fn []
                                        (let [cmds  (registry ["on" nil]
                                                              ["off" {:enabled? (fn [] false)}])
                                              names (names-set (command-suggestions "/" cmds))]
                                          (-> (expect (contains? names "on"))  (.toBe true))
                                          (-> (expect (contains? names "off")) (.toBe false)))))

                                  (it "is case-insensitive on the search prefix"
                                      (fn []
                                        (let [cmds  (registry ["Help" nil] ["history" nil])
                                              names (names-set (command-suggestions "/H" cmds))]
                                          (-> (expect (contains? names "Help"))    (.toBe true))
                                          (-> (expect (contains? names "history")) (.toBe true)))))

                                  (it "returns results in stable alphabetical order"
                                      (fn []
                                        (let [cmds  (registry ["zebra" nil] ["apple" nil] ["mango" nil])
                                              names (mapv :name (command-suggestions "/" cmds))]
                                          (-> (expect (= names ["apple" "mango" "zebra"])) (.toBe true)))))

                                  (it "bare / returns every visible command"
                                      (fn []
                                        (let [cmds (registry ["a" nil] ["b" nil] ["c" nil])]
                                          (-> (expect (count (command-suggestions "/" cmds))) (.toBe 3)))))

                                  (it "survives a broken :enabled? predicate (swallows exception)"
                                      (fn []
      ;; A rogue extension must not crash the picker.
                                        (let [cmds  (registry ["flaky" {:enabled? (fn [] (throw (js/Error. "boom")))}])
                                              names (names-set (command-suggestions "/" cmds))]
                                          (-> (expect (contains? names "flaky")) (.toBe false)))))))

;;; ─── compute-display-names + display-name suggestions ─

(describe "compute-display-names"
          (fn []
            (it "returns the canonical name unchanged when there is no namespace prefix"
                (fn []
                  (let [d (compute-display-names ["help" "clear"])]
                    (-> (expect (get d "help"))  (.toBe "help"))
                    (-> (expect (get d "clear")) (.toBe "clear")))))

            (it "strips the namespace__ prefix when the short form is unique"
                (fn []
                  (let [d (compute-display-names ["agent-shell__agent" "help"])]
                    (-> (expect (get d "agent-shell__agent")) (.toBe "agent"))
                    (-> (expect (get d "help"))               (.toBe "help")))))

            (it "falls back to the canonical name when short forms collide"
                (fn []
                  (let [d (compute-display-names ["foo__plan" "bar__plan" "help"])]
                    ;; Both canonical forms are 9 chars — no padding needed.
                    (-> (expect (.trimEnd (get d "foo__plan"))) (.toBe "foo__plan"))
                    (-> (expect (.trimEnd (get d "bar__plan"))) (.toBe "bar__plan"))
                    ;; The non-colliding one is still shortened.
                    (-> (expect (get d "help")) (.toBe "help")))))

            (it "right-pads collision-group members to the group max width"
                (fn []
                  (let [d (compute-display-names ["a__plan" "longer__plan"])]
                    ;; "longer__plan" is 12; "a__plan" should be padded to 12.
                    (-> (expect (count (get d "a__plan")))      (.toBe 12))
                    (-> (expect (count (get d "longer__plan"))) (.toBe 12))
                    ;; Padding is trailing spaces — the prefix of the shorter
                    ;; one is still its canonical form.
                    (-> (expect (.startsWith (get d "a__plan") "a__plan")) (.toBe true)))))

            (it "does not pad non-colliding commands just because another group has collisions"
                (fn []
                  (let [d (compute-display-names ["foo__plan" "bar__plan" "agent-shell__agent"])]
                    ;; agent-shell__agent is in a collision-free group → short form, no padding.
                    (-> (expect (get d "agent-shell__agent")) (.toBe "agent")))))))

(describe "command-suggestions with display names"
          (fn []
            (it "attaches :display-name — short form for unique commands"
                (fn []
                  (let [cmds (registry ["agent-shell__agent" nil] ["help" nil])
                        sug  (command-suggestions "/ag" cmds)
                        hit  (first (filter (fn [s] (= (:name s) "agent-shell__agent")) sug))]
                    (-> (expect (some? hit)) (.toBe true))
                    (-> (expect (:display-name hit)) (.toBe "agent")))))

            (it "matches when the user types the short form"
                (fn []
                  (let [cmds (registry ["agent-shell__agent" nil])
                        sug  (command-suggestions "/agent" cmds)]
                    (-> (expect (pos? (count sug))) (.toBe true))
                    (-> (expect (:name (first sug))) (.toBe "agent-shell__agent")))))

            (it "matches when the user types the canonical namespaced form"
                (fn []
                  (let [cmds (registry ["agent-shell__agent" nil])
                        sug  (command-suggestions "/agent-shell" cmds)]
                    (-> (expect (pos? (count sug))) (.toBe true)))))

            (it "keeps the canonical :name for collision-group members"
                (fn []
                  (let [cmds (registry ["foo__plan" nil] ["bar__plan" nil])
                        sug  (command-suggestions "/plan" cmds)
                        nms  (set (map :name sug))]
                    (-> (expect (contains? nms "foo__plan")) (.toBe true))
                    (-> (expect (contains? nms "bar__plan")) (.toBe true))
                    ;; Display name falls back to the canonical (with padding).
                    (-> (expect (.startsWith (:display-name (first sug)) (:name (first sug))))
                        (.toBe true)))))))

;;; ─── visible-commands / hidden? / enabled? / visible? ──

(describe "visibility predicates" (fn []
                                    (it "hidden? defaults to false"
                                        (fn []
                                          (-> (expect (hidden? (mk-cmd))) (.toBe false))))

                                    (it "hidden? respects :hidden? true"
                                        (fn []
                                          (-> (expect (hidden? (mk-cmd {:hidden? true}))) (.toBe true))))

                                    (it "enabled? defaults to true"
                                        (fn []
                                          (-> (expect (enabled? (mk-cmd))) (.toBe true))))

                                    (it "enabled? calls the predicate"
                                        (fn []
                                          (-> (expect (enabled? (mk-cmd {:enabled? (fn [] true)}))) (.toBe true))
                                          (-> (expect (enabled? (mk-cmd {:enabled? (fn [] false)}))) (.toBe false))))

                                    (it "enabled? is false when the predicate throws"
                                        (fn []
                                          (-> (expect (enabled? (mk-cmd {:enabled? (fn [] (throw (js/Error. "x")))})))
                                              (.toBe false))))

                                    (it "visible? is (and (not hidden) enabled)"
                                        (fn []
                                          (-> (expect (visible? (mk-cmd))) (.toBe true))
                                          (-> (expect (visible? (mk-cmd {:hidden? true}))) (.toBe false))
                                          (-> (expect (visible? (mk-cmd {:enabled? (fn [] false)}))) (.toBe false))))

                                    (it "visible-commands filters out hidden and disabled entries"
                                        (fn []
                                          (let [cmds   (registry ["ok" nil]
                                                                 ["hide" {:hidden? true}]
                                                                 ["off"  {:enabled? (fn [] false)}])
                                                result (visible-commands cmds)]
                                            (-> (expect (count result)) (.toBe 1))
                                            (-> (expect (contains? result "ok")) (.toBe true)))))))
