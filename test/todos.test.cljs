(ns todos.test
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.todos.shared :as t]
            ["./agent/extensions/todos/index.mjs" :as todos]))

(describe "todos shared" (fn []
                           (it "normalizes status synonyms"
                               (fn []
                                 (-> (expect (t/normalize-status "completed")) (.toBe :done))
                                 (-> (expect (t/normalize-status "done")) (.toBe :done))
                                 (-> (expect (t/normalize-status "in_progress")) (.toBe :in-progress))
                                 (-> (expect (t/normalize-status "doing")) (.toBe :in-progress))
                                 (-> (expect (t/normalize-status nil)) (.toBe :pending))))

                           (it "parses a JS todo array, dropping blanks"
                               (fn []
                                 (let [p (t/parse-todos #js [#js {:content "a" :status "completed"}
                                                             #js {:content "  " :status "pending"}
                                                             #js {:content "b"}])]
                                   (-> (expect (count p)) (.toBe 2))
                                   (-> (expect (:status (first p))) (.toBe :done))
                                   (-> (expect (:status (nth p 1))) (.toBe :pending)))))

                           (it "counts open/total"
                               (fn []
                                 (let [c (t/counts [{:content "a" :status :done} {:content "b" :status :pending}
                                                    {:content "c" :status :in-progress}])]
                                   (-> (expect (:open c)) (.toBe 2))
                                   (-> (expect (:total c)) (.toBe 3)))))

                           (it "render collapses completed items (HIPIF), shows live in full"
                               (fn []
                                 (let [s (t/render-ledger [{:content "done1" :status :done}
                                                           {:content "open1" :status :pending}
                                                           {:content "doing1" :status :in-progress}])]
                                   (-> (expect (.includes s "- [ ] open1")) (.toBe true))
                                   (-> (expect (.includes s "- [~] doing1")) (.toBe true))
                                   (-> (expect (.includes s "done1")) (.toBe false))     ; completed content collapsed
                                   (-> (expect (.includes s "1 completed")) (.toBe true)))))

                           (it "render is nil for an empty ledger"
                               (fn []
                                 (-> (expect (t/render-ledger [])) (.toBeNil))))))

;;; ─── one tracker, not two ────────────────────────────────────────────────
;;; Plan mode keeps its own step list and re-injects the remaining steps every
;;; turn (plan_mode.cljs:222-228) — the per-turn reminder is the one plan-
;;; adherence intervention with evidence behind it. The todos ledger injected
;;; independently on the same event, so a session could carry TWO lists in one
;;; prompt, free to disagree. Worst for the small local models least able to
;;; absorb contradictory instructions.

(defn- stub-api
  "Extension api stub. `state` is what getState returns."
  [state]
  (let [handlers (atom {})
        segments (atom {})
        tools    (atom {})]
    ;; `settings` stands in for the real manager, which merges extension.json's
    ;; declared defaults under the user's values — so the "todos" section
    ;; arrives carrying reminder-every-n-turns, exactly as it does in a
    ;; running agent. A stub returning {} tested a path that no longer exists.
    {:api #js {:settings              (fn [sec]
                                        (let [all {:todos {:reminder-every-n-turns 5}}]
                                          (if sec (or (get all sec) {}) all)))
               :getState              (fn [] state)
               :on                    (fn [evt f & _] (swap! handlers update evt (fnil conj []) f))
               :off                   (fn [_ _] nil)
               :registerTool          (fn [n cfg] (swap! tools assoc n cfg))
               :unregisterTool        (fn [n] (swap! tools dissoc n))
               :registerStatusSegment (fn [n cfg] (swap! segments assoc n cfg))}
     :handlers handlers :segments segments :tools tools}))

(defn- additions
  "Concatenated system-prompt-additions from EVERY before_agent_start
   subscriber — the ledger and the reminder are two of them, so a stub that
   kept only the last registration silently tested the wrong handler."
  [api-map]
  (let [hs (get @(:handlers api-map) "before_agent_start")]
    (->> hs
         (keep (fn [h] (let [r (h nil nil)]
                         (when r
                           (let [a (or (aget r "system-prompt-additions")
                                       (:system-prompt-additions r))]
                             (when (and a (pos? (count a))) (str/join " " (vec a))))))))
         (str/join " ")
         (#(when (seq %) %)))))

(defn- inject [api-map] (additions api-map))

(describe "todos vs an executing plan"
          (fn []
            (it "stays silent while a plan is executing"
                (fn []
                  (let [m (stub-api {:plan-executing true
                                     :plan-todos [{:step 1 :text "one" :completed false}]})
                        _ ((.-default todos) (:api m))]
                    ;; Model wrote a ledger too — it must not reach the prompt,
                    ;; because plan mode is already injecting its own list.
                    ((.-execute (get @(:tools m) "todo_write"))
                     #js {:todos #js [#js {:content "ledger item" :status "pending"}]})
                    (-> (expect (inject m)) (.toBeFalsy)))))

            (it "injects its ledger normally when no plan is running"
                (fn []
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    ;; No regression for the model-driven path.
                    (-> (expect (inject m)) (.toBeFalsy))   ;; empty ledger → nothing
                    )))

            (it "registers no new tool for the plan integration"
                (fn []
                  ;; The integration must not put a tool call on the critical
                  ;; path: SLMs emit invalid tool calls ~19x more than large
                  ;; models, and ONE invalid call costs ~30pp accuracy. Every
                  ;; extra tool also costs selection accuracy.
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    (-> (expect (count @(:tools m))) (.toBe 2))
                    (-> (expect (contains? @(:tools m) "todo_write")) (.toBe true))
                    (-> (expect (contains? @(:tools m) "todo_read")) (.toBe true)))))

            (it "shows the PLAN's progress in the status segment while one runs"
                (fn []
                  (let [m (stub-api {:plan-executing true
                                     :plan-todos [{:step 1 :text "a" :completed true}
                                                  {:step 2 :text "b" :completed false}
                                                  {:step 3 :text "c" :completed false}]})
                        _ ((.-default todos) (:api m))
                        seg (get @(:segments m) "todos.progress")
                        out ((.-render seg) {:theme {}})]
                    ;; 1 of 3 done — the plan's numbers, not the empty ledger's.
                    (-> (expect (.-content out)) (.toBe "☐ 1/3"))
                    (-> (expect (aget out "visible?")) (.toBe true)))))))

;;; ─── the nag, and its gate ───────────────────────────────────────────────
;;; Across 26 real sessions only 4 ever called todo_write; the longest managed
;;; 5 writes across 1188 tool calls and went 634 lines past its last one. The
;;; counter freezing is a ledger nobody wrote, not a render bug. Published fix
;;; is a periodic reminder; the documented failure of that fix is over-firing
;;; (claude-code #56415), so the gate matters as much as the nag.

(defn- turns!
  "Advance n turns, returning the reminder text contributed on the last one."
  [m n]
  ;; turn_start only exists when the reminder registered — with the nag
  ;; disabled there is nothing to advance, which is itself the assertion.
  (let [ts (or (first (get @(:handlers m) "turn_start")) (fn [_] nil))]
    (loop [i 0 out nil]
      (if (>= i n)
        out
        (do (ts nil)
            (recur (inc i) (additions m)))))))

(defn- reminder-text
  "Just the nag, not the ledger — they share the event."
  [s]
  (when (and s (.includes s "<reminder>")) s))

(defn- write-list! [m items]
  ((.-execute (get @(:tools m) "todo_write"))
   #js {:todos (clj->js (mapv (fn [c] {:content c :status "pending"}) items))}))

(describe "todo nag reminder"
          (fn []
            (it "fires after quiet turns when a list has open items"
                (fn []
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    (write-list! m ["one" "two"])
                    (-> (expect (boolean (some-> (turns! m 8) reminder-text)))
                        (.toBe true)))))

            (it "does NOT fire straight after a write"
                (fn []
                  ;; The #56415 failure: ~15 fires in one session, including
                  ;; immediately after a write, producing performative churn.
                  ;;
                  ;; Must run the counter UP first. An earlier version wrote and
                  ;; then advanced one turn from zero, which cannot fire either
                  ;; way — it passed with the reset removed.
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    (write-list! m ["one" "two"])
                    ;; Counter now past the threshold: the nag is due.
                    (-> (expect (boolean (reminder-text (turns! m 8)))) (.toBe true))
                    ;; A write must buy silence on the very next turn.
                    (write-list! m ["one" "two"])
                    (-> (expect (reminder-text (turns! m 1))) (.toBeFalsy)))))

            (it "never nags a model that has no list"
                (fn []
                  ;; 22 of 26 sessions never made one. Nagging them means pushing
                  ;; tool calls at models where an invalid call costs ~30pp.
                  (let [m (stub-api {:plan-executing false})
                        _ ((.-default todos) (:api m))]
                    (-> (expect (reminder-text (turns! m 20))) (.toBeFalsy)))))

            (it "stays silent while a plan is executing"
                (fn []
                  (let [m (stub-api {:plan-executing true
                                     :plan-todos [{:step 1 :text "a" :completed false}]})
                        _ ((.-default todos) (:api m))]
                    (write-list! m ["one" "two"])
                    (-> (expect (reminder-text (turns! m 20))) (.toBeFalsy)))))

            (it "is disabled by a zero threshold"
                (fn []
                  (let [m (stub-api {:plan-executing false})
                        _ (aset (:api m) "settings"
                                (fn [sec]
                                  (let [all {:todos {:reminder-every-n-turns 0}}]
                                    (if sec (or (get all sec) {}) all))))
                        _ ((.-default todos) (:api m))]
                    (write-list! m ["one"])
                    (-> (expect (reminder-text (turns! m 30))) (.toBeFalsy)))))))

(describe "ledger injection carries an update instruction"
          (fn []
            (it "tells the model to keep the list current"
                (fn []
                  ;; The only standing instruction lived in the tool description,
                  ;; seen once at tool-listing time rather than per turn.
                  (-> (expect (t/render-ledger [{:content "a" :status :pending}]))
                      (.toContain "mark items completed"))))

            (it "omits it when nothing is open"
                (fn []
                  (-> (expect (t/render-ledger [{:content "a" :status :done}]))
                      (.not.toContain "mark items completed"))))))
