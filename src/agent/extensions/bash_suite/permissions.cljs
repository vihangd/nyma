(ns agent.extensions.bash-suite.permissions
  (:require [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.credentials :as creds]
            [agent.utils.sandbox :as sandbox]
            [agent.utils.shell-grant :as shell-grant]
            [clojure.string :as str]))

;; ── Decision logic ───────────────────────────────────────────

(defn- check-list
  "Check if command matches any pattern in a list using glob matching."
  [cmd patterns]
  (some #(shared/glob-match? % cmd) patterns))

(def simple-command?
  "See agent.utils.shell-grant — shared with the prompt's \"Allow always\"."
  shell-grant/simple-command?)

(defn check-decision
  "Determine permission decision for a command.
   Returns :denied, :approved, :remembered, or :needs-approval."
  [cmd classification perm-config session-approvals]
  (let [cmd-str (str cmd)]
    (cond
      ;; Denylist always wins
      (check-list cmd-str (:denylist perm-config))
      :denied

      ;; Allowlist explicit match — simple commands only (see simple-command?)
      (and (simple-command? cmd-str) (check-list cmd-str (:allowlist perm-config)))
      :approved

      ;; Session memory
      (contains? @session-approvals cmd-str)
      (let [stored (get @session-approvals cmd-str)]
        (if (= stored :approved) :remembered :denied))

      ;; Auto-approve safe/read-only classifications
      (and (:auto-approve-safe perm-config)
           classification
           (let [lvl (str (:level classification))]
             (or (= lvl "safe") (= lvl "read-only")
                 ;; the OS sandbox holds what nobody listed, as in Codex
                 (and (= lvl "unknown") (sandbox/active?)))))
      :approved

      ;; Default: needs approval (auto-approve for now)
      :else :needs-approval)))

;; ── Judge for unlisted commands ──────────────────────────────
;; Opt-in: `bash-suite.permissions.judge` in ~/.nyma/settings.json (never a
;; project's) and TYPESAFE_API_KEY or credentials.json "typesafe". The command is sent to TypeSafe. Only
;; turns an ask into an allow; any doubt, error or timeout still asks.

(defn judge-request [cmd model]
  {:model (or model "jev-1.13.0")
   :state (str "Shell command a coding agent wants to run in the user's project:\n" cmd)
   :questions {:risk {:type "choice"
                      :instructions "How risky is running this command unattended?"
                      :criteria {:safe "Only reads, builds or tests; changes nothing outside build output"
                                 :risky "Changes or deletes files, state or settings, or reaches the network"
                                 :destructive "Can destroy data, leak secrets or take over the machine"}}}})

(defn judge-safe?
  "Is a Jev response a `safe` verdict at or above `threshold`?"
  [resp threshold]
  (let [p (some-> resp .-answers .-risk .-probabilities .-safe)]
    (and (number? p) (>= p (or threshold 0.8)))))

(defn ^:async judge-allows?
  [cmd {:keys [model threshold timeout-ms]}]
  (if-let [key (or (.. js/process -env -TYPESAFE_API_KEY) (creds/read-credential "typesafe"))]
    (try
      (let [r (js-await (js/fetch "https://api.typesafe.ai/v1/systemone"
                                  #js {:method "POST"
                                       :headers #js {:Authorization (str "Bearer " key)
                                                     :Content-Type "application/json"}
                                       :body (js/JSON.stringify (clj->js (judge-request cmd model)))
                                       :signal (js/AbortSignal.timeout (or timeout-ms 1500))}))]
        (and (.-ok r) (judge-safe? (js-await (.json r)) threshold)))
      (catch :default _ false))
    false))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config              (shared/load-config)
        perm-config         (:permissions config)
        session-approvals   (atom {})
        ;; By command: the one being asked about is not always the last one
        ;; classified, with parallel calls or a subagent's.
        ;; ponytail: one entry per distinct command, cleared on deactivate
        classifications     (atom {})]

    ;; Listen for classification from security_analysis via inter-extension bus
    (when-let [events (.-events api)]
      (let [on-fn (.-on events)]
        (when (fn? on-fn)
          (on-fn "bash:classification"
                 (fn [data]
                   (swap! classifications assoc (.-command data)
                          {:level (.-level data)
                           :reasons (vec (or (.-reasons data) []))
                           :command (.-command data)}))))))

    ;; Permission check via shared permission_request event
    (.on api "permission_request"
         (fn [data]
           (when (and (:enabled perm-config) (= (str (.-tool data)) "bash"))
             (let [args     (.-args data)
                   cmd      (str (or (.-command args) (aget args "command") ""))
                   ;; env_filter's rewrite comes after the classifier
                   classif  (get @classifications
                                 (or (some-> (.-originalArgs data) .-command) cmd))
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

              ;; :needs-approval — a command we have NOT vetted. Defer to the
              ;; permission gate by asking the user (the permission-mode policy).
              ;; The gate prompts (Allow / Allow always / Deny); "Allow always"
              ;; persists so future runs skip via the middleware fast-path. (An
              ;; allow VETTED above — :approved/:remembered — pre-empts this ask;
              ;; full-auto mode's allow also pre-empts it, so headless still runs.)
                 (let [ask (fn []
                             (swap! shared/suite-stats update-in [:permissions :needs-approval] (fnil inc 0))
                             #js {:decision "ask"})]
                   (if (and (:judge perm-config) (= "unknown" (str (:level classif))))
                     (.then (judge-allows? cmd (:judge perm-config))
                            (fn [ok]
                              (if ok
                                (do (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
                                    #js {:decision "allow"})
                                (ask))))
                     (ask)))))))
         90)

    ;; Return deactivator
    (fn []
      (reset! session-approvals {})
      (reset! classifications {}))))
