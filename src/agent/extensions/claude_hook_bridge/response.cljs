(ns agent.extensions.claude-hook-bridge.response
  "Parse a Claude Code hook's JSON response into a normalized map and
   resolve precedence when multiple hooks reply.

   CC precedence rules (per the spec):
     - PreToolUse permissionDecision:  deny > defer > ask > allow
     - decision \"block\" anywhere wins over silence
     - additionalContext entries are concatenated in firing order
     - updatedInput is last-writer-wins (the LAST hook to set it
       overrides earlier ones)
     - continue: false anywhere short-circuits

   Each hook may provide:
     - exit code (0 ok, 2 blocking error, other = non-blocking error)
     - stdout (parsed as JSON if it begins with `{` or `[`, else
       treated as plain `additionalContext` text)
     - stderr (used as the reason string when exit-code is 2)
   This module owns the per-hook parse and the cross-hook merge."
  (:require [clojure.string :as str]))

(def ^:private permission-rank
  {"deny"  3
   "defer" 2
   "ask"   1
   "allow" 0})

(defn parse-one
  "Parse a single hook invocation result into a CLJS map.

   Input shape:
     {:exit-code int :stdout string :stderr string}

   Returns:
     {:permission-decision    \"allow\"|\"deny\"|\"ask\"|\"defer\"|nil
      :permission-reason      string-or-nil
      :decision-block?        boolean
      :decision-block-reason  string-or-nil
      :continue?              true|false|nil
      :stop-reason            string-or-nil
      :system-message         string-or-nil
      :suppress-output?       boolean
      :additional-context     string-or-nil
      :updated-input          object-or-nil
      :session-title          string-or-nil
      :hook-specific          object-or-nil
      :worktree-path          string-or-nil}"
  [{:keys [exit-code stdout stderr]}]
  (let [trimmed (str/trim (or stdout ""))
        ;; Try to parse JSON when the trimmed body looks like an
        ;; object or array. Otherwise treat as plain context text.
        looks-json? (and (seq trimmed)
                         (or (.startsWith trimmed "{")
                             (.startsWith trimmed "[")))
        parsed-json (when looks-json?
                      (try (js/JSON.parse trimmed) (catch :default _e nil)))
        ;; Convenience accessor that tolerates the response not being
        ;; an object.
        kget (fn [k] (when (and parsed-json (object? parsed-json))
                       (aget parsed-json k)))
        hso  (kget "hookSpecificOutput")]

    (cond
      ;; Exit 2 with no JSON body: stderr is the deny reason.
      (and (= exit-code 2) (not parsed-json))
      {:permission-decision   "deny"
       :permission-reason     (str/trim (or stderr ""))
       :decision-block?       true
       :decision-block-reason (str/trim (or stderr ""))}

      ;; No JSON body: stdout, if any, is additionalContext only when
      ;; the exit code is 0.
      (not parsed-json)
      (when (and (zero? (or exit-code 0)) (seq trimmed))
        {:additional-context trimmed})

      :else
      (let [;; Merge top-level decision + hookSpecificOutput.
            top-decision (kget "decision")
            decision-block? (= top-decision "block")
            top-reason   (kget "reason")
            cont         (kget "continue")
            stop-reason  (kget "stopReason")
            system-msg   (kget "systemMessage")
            suppress?    (boolean (kget "suppressOutput"))
            ;; hookSpecificOutput pulls
            hsout (when (and hso (object? hso))
                    {:permission-decision   (aget hso "permissionDecision")
                     :permission-reason     (aget hso "permissionDecisionReason")
                     :additional-context    (aget hso "additionalContext")
                     :updated-input         (aget hso "updatedInput")
                     :session-title         (aget hso "sessionTitle")
                     :worktree-path         (aget hso "worktreePath")
                     :elicitation-action    (aget hso "action")
                     :elicitation-content   (aget hso "content")
                     :retry?                (boolean (aget hso "retry"))})]
        (merge
         (or hsout {})
         {:decision-block?       decision-block?
          :decision-block-reason (when decision-block? top-reason)
          :continue?             (when (some? cont) (boolean cont))
          :stop-reason           stop-reason
          :system-message        system-msg
          :suppress-output?      suppress?
          :hook-specific         hso})))))

(defn merge-many
  "Merge a vector of parsed responses into a single resolution map per
   CC's precedence rules."
  [parsed-list]
  (reduce
   (fn [acc r]
     (cond-> acc
       ;; permissionDecision: keep the highest-rank one seen so far.
       (:permission-decision r)
       (update :permission-decision
               (fn [cur]
                 (let [new-d (:permission-decision r)]
                   (if (or (nil? cur)
                           (> (or (get permission-rank new-d) -1)
                              (or (get permission-rank cur) -1)))
                     new-d
                     cur))))

       ;; Capture the reason that came WITH the winning decision.
       (and (:permission-decision r)
            (let [new-d (:permission-decision r)
                  cur   (:permission-decision acc)]
              (or (nil? cur)
                  (> (or (get permission-rank new-d) -1)
                     (or (get permission-rank cur) -1)))))
       (assoc :permission-reason (:permission-reason r))

       ;; decision-block? wins on first occurrence (and stays).
       (and (:decision-block? r)
            (not (:decision-block? acc)))
       (-> (assoc :decision-block? true)
           (assoc :decision-block-reason (:decision-block-reason r)))

       ;; continue: false short-circuits — also wins on first.
       (and (some? (:continue? r))
            (not (:continue? r))
            (not (false? (:continue? acc))))
       (-> (assoc :continue? false)
           (assoc :stop-reason (:stop-reason r)))

       ;; additional-context: concatenate.
       (seq (:additional-context r))
       (update :additional-context
               (fn [cur]
                 (if cur
                   (str cur "\n" (:additional-context r))
                   (:additional-context r))))

       ;; updated-input: last-writer-wins.
       (some? (:updated-input r))
       (assoc :updated-input (:updated-input r))

       ;; system-message: concatenate.
       (seq (:system-message r))
       (update :system-message
               (fn [cur]
                 (if cur (str cur "\n" (:system-message r))
                     (:system-message r))))

       ;; suppress-output: any true wins.
       (:suppress-output? r) (assoc :suppress-output? true)

       ;; session-title: last-writer-wins.
       (seq (:session-title r))
       (assoc :session-title (:session-title r))

       ;; worktree-path: first-writer-wins.
       (and (seq (:worktree-path r))
            (not (seq (:worktree-path acc))))
       (assoc :worktree-path (:worktree-path r))))

   {:permission-decision   nil
    :permission-reason     nil
    :decision-block?       false
    :decision-block-reason nil
    :continue?             nil
    :stop-reason           nil
    :additional-context    nil
    :updated-input         nil
    :system-message        nil
    :suppress-output?      false
    :session-title         nil
    :worktree-path         nil}
   parsed-list))
