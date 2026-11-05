(ns agent.extensions.model-roles.features.progress
  "Did the steps since the last step cap get anywhere?

   Answers the loop's `step_cap` event, which decides whether a capped run gets
   another block of steps. Evidence comes from outside the model, which skews
   positive when it grades its own work: files changed, a verify pass, a todo or
   spec task closed. How much a run called its tools says nothing; in real
   sessions nearly every long run was all-new calls, finished or not."
  (:require [agent.debug :as d]
            [agent.tool-metadata :as tool-metadata]
            [agent.utils.git-files :as git]))

(def ^:private edit-tools #{"edit" "write" "multi_edit"})

(def ^:private empty-block {:edits 0 :verified 0 :tasks 0 :todos 0})

(defn progress-reason
  "Pure: what `block` achieved, or nil. `fingerprint` is the worktree now, nil
   outside git. A worktree seen at an earlier cap, after edits, is a run
   undoing itself, whatever else it did."
  [block fingerprint seen]
  (let [{:keys [edits verified tasks todos]} block]
    (cond
      (and (pos? edits) fingerprint (contains? seen fingerprint)) nil
      (pos? verified) "verify passed"
      (pos? tasks)    "spec task closed"
      (pos? todos)    "todo closed"
      (pos? edits)    "files changed"
      :else           nil)))

(defn worktree-fingerprint
  "Identity of the worktree's content, or nil outside a repo with a commit.
   Taken once per cap, so the cost of `git diff` never lands on a step."
  []
  (let [head (git/run-git "rev-parse HEAD")]
    (when (seq head)
      (str (js/Bun.hash (str head
                             (git/run-git-raw "diff HEAD")
                             (git/run-git-raw "status --porcelain=v1 --untracked-files=all")))))))

(defn- done-todos [args]
  (count (filter (fn [t] (contains? #{"completed" "done" "complete"} (str (.-status t)))) (or (some-> args .-todos) #js []))))

(defn create-ledger
  "The run's progress so far, as handlers for the events that show it.
   `fingerprint-fn` is the seam tests use."
  [api fingerprint-fn]
  (let [ledger (atom {:block empty-block :seen #{} :todos-done 0})
        bump   (fn [k] (swap! ledger update-in [:block k] inc) nil)]
    {:on-start  (fn [_] (reset! ledger {:block empty-block :seen #{} :todos-done 0}) nil)
     :on-verify (fn [_] (bump :verified))
     :on-task   (fn [_] (bump :tasks))
     :on-files  (fn [_] (bump :edits))
     :on-tool
     (fn [data]
       (let [tool (str (.-toolName data))]
         (when-not (or (.-isError data) (.-cancelled data))
           (cond
             (tool-metadata/matches-any-tool-name? tool edit-tools)
             (bump :edits)

             (tool-metadata/matches-any-tool-name? tool #{"todo_write"})
             (let [n (done-todos (.-args data))]
               (when (> n (:todos-done @ledger))
                 (swap! ledger assoc :todos-done n)
                 (bump :todos))))))
       nil)
     :on-cap
     (fn [_]
       ;; A plan is the deliverable of a plan-mode turn: more steps there are
       ;; the planner overrunning, not progress.
       (when-not (:plan-mode (.getState api))
         (let [{:keys [block seen]} @ledger
               fp     (fingerprint-fn)
               reason (progress-reason block fp seen)]
           (swap! ledger (fn [l] (cond-> (assoc l :block empty-block)
                                   fp (update :seen conj fp))))
           (d/info "progress" (str "step cap: " (or reason "no progress")) (clj->js block))
           (when reason #js {:continue true :reason reason}))))}))

(defn activate [api]
  (let [{:keys [on-start on-verify on-task on-files on-tool on-cap]}
        (create-ledger api worktree-fingerprint)
        ev (.-events api)]
    (.on api "agent_start" on-start)
    (.on api "tool_complete" on-tool)
    (.on api "small-model/verify-pass" on-verify)
    (.on api "step_cap" on-cap)
    ;; Other extensions' own channels are namespaced, and only reach `.-events`.
    (when (and ev (.-on ev))
      (.on ev "spec-driven__spec_task_complete" on-task)
      (.on ev "bash-suite__bash:files-changed" on-files))
    (fn []
      (when (and ev (.-off ev))
        (.off ev "spec-driven__spec_task_complete" on-task)
        (.off ev "bash-suite__bash:files-changed" on-files)))))
