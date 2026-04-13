(ns agent.extensions.bash-suite.permissions
  (:require [agent.extensions.bash-suite.shared :as shared]
            [clojure.string :as str]))

;; ── Decision logic ───────────────────────────────────────────

(defn- check-list
  "Check if command matches any pattern in a list using glob matching."
  [cmd patterns]
  (some #(shared/glob-match? % cmd) patterns))

(defn check-decision
  "Determine permission decision for a command.
   Returns :denied, :approved, :remembered, or :needs-approval."
  [cmd classification perm-config session-approvals]
  (let [cmd-str (str cmd)]
    (cond
      ;; Denylist always wins
      (check-list cmd-str (:denylist perm-config))
      :denied

      ;; Allowlist explicit match
      (check-list cmd-str (:allowlist perm-config))
      :approved

      ;; Session memory
      (contains? @session-approvals cmd-str)
      (let [stored (get @session-approvals cmd-str)]
        (if (= stored :approved) :remembered :denied))

      ;; Auto-approve safe/read-only classifications
      (and (:auto-approve-safe perm-config)
           classification
           (let [lvl (str (:level classification))]
             (or (= lvl "safe") (= lvl "read-only"))))
      :approved

      ;; Default: needs approval (auto-approve for now)
      :else :needs-approval)))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config              (shared/load-config)
        perm-config         (:permissions config)
        session-approvals   (atom {})
        last-classification (atom nil)]

    ;; Listen for classification from security_analysis via inter-extension bus
    (when-let [events (.-events api)]
      (let [on-fn (.-on events)]
        (when (fn? on-fn)
          (on-fn "bash:classification"
                 (fn [data]
                   (reset! last-classification
                           {:level (.-level data)
                            :reasons (vec (or (.-reasons data) []))
                            :command (.-command data)}))))))

    ;; Permission check via shared permission_request event
    (.on api "permission_request"
         (fn [data]
           (when (and (:enabled perm-config) (= (str (.-tool data)) "bash"))
             (let [args     (.-args data)
                   cmd      (str (or (.-command args) (aget args "command") ""))
                   classif  @last-classification
                   decision (check-decision cmd classif perm-config session-approvals)]
               (case decision
                 :denied
                 (do (swap! shared/suite-stats update-in [:permissions :denied] inc)
                     #js {:decision "deny" :reason "Permission denied: command matches denylist"})

                 :approved
                 (do (swap! session-approvals assoc cmd :approved)
                     (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
                     #js {:decision "allow"})

                 :remembered
                 (do (swap! shared/suite-stats update-in [:permissions :remembered] inc)
                     #js {:decision "allow"})

              ;; :needs-approval — persist to project settings for cross-session approval.
              ;; The middleware fast-path then bypasses permission_request on future runs.
              ;; security_analysis before_tool_call hooks still run — only the prompt is skipped.
                 (do (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
                     #js {:decision "allow_always_project"})))))
         90)

    ;; Return deactivator
    (fn []
      (reset! session-approvals {})
      (reset! last-classification nil))))
