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

    ;; Permission check on bash tool calls
    (.on api "before_tool_call"
      (fn [evt-ctx]
        (when (and (:enabled perm-config)
                   (shared/is-bash-tool? (.-toolName evt-ctx))
                   (not (.-cancelled evt-ctx)))
          (let [cmd      (.-command (.-input evt-ctx))
                classif  @last-classification
                decision (check-decision cmd classif perm-config session-approvals)]
            (case decision
              :denied
              (do (swap! shared/suite-stats update-in [:permissions :denied] inc)
                  (set! (.-cancelled evt-ctx) true)
                  (set! (.-reason evt-ctx) "Permission denied: command matches denylist"))

              :approved
              (do (swap! session-approvals assoc (str cmd) :approved)
                  (swap! shared/suite-stats update-in [:permissions :auto-approved] inc))

              :remembered
              (swap! shared/suite-stats update-in [:permissions :remembered] inc)

              ;; :needs-approval — auto-approve for now, track as auto-approved
              (do (swap! session-approvals assoc (str cmd) :approved)
                  (swap! shared/suite-stats update-in [:permissions :auto-approved] inc))))))
      90)

    ;; Return deactivator
    (fn []
      (reset! session-approvals {})
      (reset! last-classification nil))))
