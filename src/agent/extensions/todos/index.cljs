(ns agent.extensions.todos
  "Persistent todo ledger — session-scoped task tracking that counters
   long-horizon drift (SOTA: explicit plan tracking is worth ~10+ pp on
   long-horizon benchmarks). The agent owns the list via todo_write/todo_read;
   a status segment shows progress; the open ledger is injected each turn with
   completed items collapsed (HIPIF).

   Always on; the injection/segment are no-ops until the agent writes a list."
  (:require [agent.extensions.todos.shared :as shared]))

(defn ^:export default [api]
  (let [ledger   (atom [])            ; session-scoped [{:content :status}]
        handlers (atom [])

        plan-steps
        (fn []
          ;; Plan mode's step list, when one is executing. Read through getState
          ;; (extensions.cljs:79) rather than requiring plan_mode — two state
          ;; keys is the whole coupling.
          (let [st (when (.-getState api) (.getState api))]
            (when (:plan-executing st)
              (:plan-todos st))))

        on-before-start
        (fn [_data _ctx]
          ;; Silent while a plan is executing. Plan mode already re-injects the
          ;; remaining steps every turn (plan_mode.cljs:222-228), and that
          ;; per-turn reminder is the one intervention with evidence behind it
          ;; (arXiv 2604.12147, 21k SWE-agent trajectories). Injecting this
          ;; ledger as well put TWO independently-maintained lists in the same
          ;; prompt, free to disagree — pure downside, and worst for the small
          ;; local models least able to absorb contradictory instructions.
          (when-not (seq (plan-steps))
            (when-let [block (shared/render-ledger @ledger)]
              #js {:system-prompt-additions #js [block]})))]

    (.on api "before_agent_start" on-before-start)
    (swap! handlers conj ["before_agent_start" on-before-start])

    ;; Progress segment: ☐ open/total (hidden when empty)
    (when-let [reg (.-registerStatusSegment api)]
      (reg "todos.progress"
           #js {:category "todos"
                :autoAppend true
                :position "left"
                :render (fn [ctx]
                          ;; One visible tracker. While a plan runs this shows the
                          ;; PLAN's progress, so the segment never contradicts the
                          ;; list actually in the prompt.
                          (let [plan  (plan-steps)
                                {:keys [open total]}
                                (if (seq plan)
                                  {:open  (count (remove :completed plan))
                                   :total (count plan)}
                                  (shared/counts @ledger))
                                theme (:theme ctx)]
                            #js {:content  (str "☐ " (- total open) "/" total)
                                 :color    (get-in theme [:colors :muted] "#565f89")
                                 :visible? (pos? total)}))}))

    (.registerTool api "todo_write"
                   #js {:description "Set/replace your todo list for the current task (pass the FULL list each time). Track multi-step work so you don't drift or drop steps. status: pending | in_progress | completed. Keep exactly one item in_progress. Update as you finish steps."
                        :parameters
                        #js {:type "object"
                             :required #js ["todos"]
                             :properties
                             #js {:todos
                                  #js {:type "array"
                                       :description "The full ordered todo list"
                                       :items
                                       #js {:type "object"
                                            :required #js ["content"]
                                            :properties
                                            #js {:content #js {:type "string" :description "The task"}
                                                 :status  #js {:type "string" :enum #js ["pending" "in_progress" "completed"]
                                                               :description "Defaults to pending"}}}}}}
                        :execute
                        (fn [args]
                          (reset! ledger (shared/parse-todos (.-todos args)))
                          (let [{:keys [open total]} (shared/counts @ledger)]
                            (str "Todo list updated: " (- total open) "/" total " done.\n"
                                 (or (shared/render-ledger @ledger) "(empty)"))))})

    (.registerTool api "todo_read"
                   #js {:description "Read your current todo list."
                        :parameters #js {:type "object" :properties #js {}}
                        :execute
                        (fn [_args]
                          (or (shared/render-ledger @ledger) "No todos yet."))})

    (fn []
      (.unregisterTool api "todo_write")
      (.unregisterTool api "todo_read")
      (when-let [unreg (.-unregisterStatusSegment api)]
        (try (unreg "todos.progress") (catch :default _ nil)))
      (doseq [[e h] @handlers] (.off api e h)))))
