(ns agent.extensions.token-suite.context-folding
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.extensions.token-suite.shared :as shared]
            [agent.token-estimation :as te]
            [clojure.string :as str]))

;; ── Focus Instructions ─────────────────────────────────────────

(def ^:private focus-instructions
  "## Context Management
When doing exploratory work (searching files, reading documents, running tests) where intermediate steps won't be needed later:
1. Call start_focus({objective: \"what you're looking for\"})
2. Do the exploratory work (read files, grep, run commands)
3. Call complete_focus({summary: \"what you found\", key_artifacts: [\"path/to/file.ts\", \"ErrorCode: 42\"]})
Your intermediate steps will be compressed into the summary, freeing context for future work.
Rules: Only use for 3+ intermediate steps. Include ALL file paths and error messages in key_artifacts.")

;; ── Tool Handlers ──────────────────────────────────────────────

(defn- generate-focus-id []
  (str "f" (js/Date.now) "-" (js/Math.floor (* (js/Math.random) 100000))))

(defn ^:async start-focus-execute [focus-stack max-depth args]
  (let [objective (or (.-objective args) (aget args "objective") "")
        hint (or (.-retention_hint args) (aget args "retention_hint") "findings_only")]
    (if (>= (count @focus-stack) max-depth)
      (str "Error: Maximum focus depth (" max-depth ") exceeded. Complete an existing focus first.")
      (let [focus-id (generate-focus-id)]
        (swap! focus-stack conj {:focus-id focus-id
                                 :objective objective
                                 :retention-hint hint})
        (swap! shared/suite-stats update-in [:context-folding :foci-started] inc)
        (str "[FOCUS_START:" focus-id "] Focus started: " objective
             ". Do your exploratory work, then call complete_focus with a summary.")))))

(defn ^:async complete-focus-execute [focus-stack pending-folds args]
  (if (empty? @focus-stack)
    "Error: No active focus session. Call start_focus first."
    (let [frame (last @focus-stack)
          summary (or (.-summary args) (aget args "summary") "")
          artifacts-raw (or (.-key_artifacts args) (aget args "key_artifacts") #js [])
          artifacts (if (sequential? artifacts-raw)
                      artifacts-raw
                      (vec artifacts-raw))
          focus-id (:focus-id frame)]
      (swap! focus-stack pop)
      (swap! pending-folds conj
        {:focus-id focus-id
         :start-marker (str "[FOCUS_START:" focus-id "]")
         :summary summary
         :artifacts artifacts
         :objective (:objective frame)})
      (swap! shared/suite-stats update-in [:context-folding :foci-completed] inc)
      (str "[FOCUS_END:" focus-id "] Focus completed. Intermediate steps will be folded on next turn."))))

;; ── Context Assembly: Apply Pending Folds ──────────────────────

(defn- find-marker-index [messages marker]
  (let [total (.-length messages)]
    (loop [i 0]
      (if (>= i total) -1
        (let [msg (aget messages i)
              content (str (shared/msg-content msg))]
          (if (.includes content marker)
            i
            (recur (inc i))))))))

(defn- apply-folds [pending-folds event]
  (let [messages (.-messages event)
        folds @pending-folds]
    (when (seq folds)
      ;; Process folds newest-first to preserve earlier indices
      (doseq [fold (reverse folds)]
        (let [start-marker (:start-marker fold)
              end-marker (str "[FOCUS_END:" (:focus-id fold) "]")
              start-idx (find-marker-index messages start-marker)
              end-idx (find-marker-index messages end-marker)]
          (when (and (>= start-idx 0) (>= end-idx 0) (> end-idx start-idx))
            (let [range-count (inc (- end-idx start-idx))
                  ;; Estimate tokens in the range
                  tokens-in-range (atom 0)]
              (doseq [i (range start-idx (inc end-idx))]
                (let [msg (aget messages i)]
                  (swap! tokens-in-range + (te/estimate-tokens (str (shared/msg-content msg))))))

              ;; Build replacement message
              (let [artifacts-str (if (seq (:artifacts fold))
                                   (str "\nKey artifacts: " (str/join ", " (:artifacts fold)))
                                   "")
                    replacement #js {:role "assistant"
                                     :content (str "[Focus: " (:objective fold) "]\n"
                                                   (:summary fold)
                                                   artifacts-str
                                                   "\n[" range-count " messages folded, ~"
                                                   @tokens-in-range " tokens freed]")}]
                ;; Splice: remove range and insert replacement
                (.splice messages start-idx range-count replacement)

                ;; Update stats
                (swap! shared/suite-stats update-in [:context-folding :messages-folded] + range-count)
                (swap! shared/suite-stats update-in [:context-folding :tokens-freed] + @tokens-in-range))))))
      (reset! pending-folds []))
    nil))

;; ── Activate / Deactivate ──────────────────────────────────────

(defn activate [api]
  (let [config (shared/load-config)
        cf-cfg (:context-folding config)
        max-depth (or (:max-depth cf-cfg) 3)
        focus-stack (atom [])
        pending-folds (atom [])]

    ;; Tool A: start_focus
    (.registerTool api "start_focus"
      (tool
        #js {:description "Begin a focused sub-task. All subsequent actions will be collected. When done, call complete_focus with a summary — intermediate steps will be folded away to free context."
             :inputSchema (.object z
                            #js {:objective (-> (.string z)
                                                (.describe "What you aim to discover or accomplish"))
                                 :retention_hint (-> (.enum z #js ["findings_only" "findings_and_evidence" "detailed"])
                                                     (.optional)
                                                     (.describe "How much detail to preserve"))})
             :execute (fn [args] (start-focus-execute focus-stack max-depth args))}))

    ;; Tool B: complete_focus
    (.registerTool api "complete_focus"
      (tool
        #js {:description "End the current focus. Provide a summary of findings. Intermediate steps since start_focus will be replaced with your summary on the next turn."
             :inputSchema (.object z
                            #js {:summary (-> (.string z)
                                              (.describe "Concise summary of findings and outcomes"))
                                 :key_artifacts (-> (.array z (.string z))
                                                    (.optional)
                                                    (.describe "Important values to preserve: file paths, error codes, variable names"))})
             :execute (fn [args] (complete-focus-execute focus-stack pending-folds args))}))

    ;; Hook: Apply pending folds (context_assembly, priority 95 — before observation mask at 90)
    (.on api "context_assembly"
      (fn [event _ctx]
        (apply-folds pending-folds event))
      95)

    ;; Hook: Inject focus instructions (before_agent_start, priority 35)
    (when (:inject-instructions cf-cfg)
      (.on api "before_agent_start"
        (fn [_event _ctx]
          #js {:prompt-sections
               #js [#js {:content focus-instructions :priority 25}]})
        35))

    ;; Return deactivator
    (fn []
      (.unregisterTool api "start_focus")
      (.unregisterTool api "complete_focus")
      (reset! focus-stack [])
      (reset! pending-folds []))))
