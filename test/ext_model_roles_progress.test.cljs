(ns ext-model-roles-progress.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.model-roles.features.progress :as progress]))

(defn- ledger [state fingerprint]
  (progress/create-ledger #js {:getState (fn [] state)} (fn [] fingerprint)))

(defn- tool [name & [args extra]]
  (js/Object.assign #js {:toolName name :args args} (or extra #js {})))

(defn- todos [& statuses]
  #js {:todos (clj->js (mapv (fn [s] {:status s}) statuses))})

(describe "progress:reason" (fn []
  (it "a quiet block earns nothing"
      (fn []
        (let [{:keys [on-cap]} (ledger {} "a")]
          (-> (expect (on-cap nil)) (.toBeFalsy)))))
  (it "edits count, by their namespaced name too"
      (fn []
        (let [{:keys [on-tool on-cap]} (ledger {} "a")]
          (on-tool (tool "token-suite__multi_edit"))
          (-> (expect (.-reason (on-cap nil))) (.toBe "files changed")))))
  (it "a failed edit does not"
      (fn []
        (let [{:keys [on-tool on-cap]} (ledger {} "a")]
          (on-tool (tool "edit" nil #js {:isError true}))
          (-> (expect (on-cap nil)) (.toBeFalsy)))))
  (it "bash that changed files counts, outside git too"
      (fn []
        (let [{:keys [on-files on-cap]} (ledger {} nil)]
          (on-files nil)
          (-> (expect (.-continue (on-cap nil))) (.toBe true)))))
  (it "verify pass and spec task count"
      (fn []
        (let [{:keys [on-verify on-task on-cap]} (ledger {} "a")]
          (on-verify nil)
          (-> (expect (.-reason (on-cap nil))) (.toBe "verify passed"))
          (on-task nil)
          (-> (expect (.-reason (on-cap nil))) (.toBe "spec task closed")))))
  (it "only a newly closed todo counts"
      (fn []
        (let [{:keys [on-tool on-cap]} (ledger {} "a")]
          (on-tool (tool "todo_write" (todos "completed" "pending")))
          (-> (expect (.-reason (on-cap nil))) (.toBe "todo closed"))
          (on-tool (tool "todo_write" (todos "completed" "in_progress")))
          (-> (expect (on-cap nil)) (.toBeFalsy)))))
  (it "edits back to a worktree seen at an earlier cap earn nothing"
      (fn []
        (let [{:keys [on-tool on-cap]} (ledger {} "same")]
          (on-tool (tool "edit"))
          (-> (expect (.-continue (on-cap nil))) (.toBe true))
          (on-tool (tool "edit"))
          (-> (expect (on-cap nil)) (.toBeFalsy)))))
  (it "each block is judged on its own"
      (fn []
        (let [{:keys [on-tool on-cap]} (ledger {} nil)]
          (on-tool (tool "edit"))
          (on-cap nil)
          (-> (expect (on-cap nil)) (.toBeFalsy)))))
  (it "a new run starts clean"
      (fn []
        (let [{:keys [on-start on-tool on-cap]} (ledger {} "same")]
          (on-tool (tool "edit"))
          (on-cap nil)
          (on-start nil)
          (on-tool (tool "edit"))
          (-> (expect (.-continue (on-cap nil))) (.toBe true)))))
  (it "plan mode never extends"
      (fn []
        (let [{:keys [on-tool on-cap]} (ledger {:plan-mode true} "a")]
          (on-tool (tool "edit"))
          (-> (expect (on-cap nil)) (.toBeFalsy)))))))
