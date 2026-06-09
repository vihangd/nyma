(ns agent.extensions.model-roles.features.plan-mode
  "Native plan mode, layered on the existing :plan role.

   The :plan role already enforces read-only (model_resolve picks the plan
   model; tool_access_check restricts to read tools; permission_request
   denies write/edit/bash). Plan mode adds the four things the role lacks:
     1. a system-prompt nudge telling the model it is planning
     2. a plan artifact written to .nyma/plans/
     3. an approval gate (Execute / Stay / Refine) after the plan turn
     4. an exit→execute handoff that restores the prior role and kicks off
        execution, optionally tracking [DONE:n] step markers

   Entry is a guarded /plan (Decision D): /planmode always works; /plan is
   claimed only when not already taken (e.g. by the ACP agent_shell mode
   switcher), so the two never collide.

   Squint notes: no inline (fn ^:async ...) — async handlers are top-level
   defns called by sync wrappers; .-__state-atom compiles to .__state_atom."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private plan-system-prompt
  "[PLAN MODE ACTIVE — read-only]
You are planning, not executing. You can read/search the codebase but
cannot modify files or run mutating commands. Produce a detailed, numbered
plan under a 'Plan:' header (one step per line). Do NOT make changes yet —
the user will approve the plan before execution begins.")

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- state-atom [api] (.-__state-atom api))
(defn- cur-state [api] (.getState api))

(defn- settings [api]
  (when-let [g (.-getSettings api)] (g)))

(defn- auto-approve? [api]
  ;; Tolerate keyword (CLJS defaults) and string (user JSON) keys.
  (let [s  (settings api)
        pm (or (:plan-mode s) (get s "plan-mode"))]
    (boolean (or (:auto-approve pm) (and pm (get pm "auto-approve"))))))

(defn role-model-spec
  "Pure: settings + role -> 'provider/model' string (or nil). Tolerates
   keyword (CLJS defaults) and string (user JSON) keys. Exposed for tests."
  [settings role]
  ;; Squint: keywords ARE strings, so :default == "default" — one lookup
  ;; covers both keyword (CLJS defaults) and string (user JSON) roles.
  (let [roles (or (:roles settings) (get settings "roles"))
        cfg   (or (get roles role) (get roles (str role)))
        provider (or (:provider cfg) (get cfg "provider"))
        model-id (or (:model cfg) (get cfg "model"))]
    (when (and provider model-id) (str provider "/" model-id))))

(defn- restore-role-model!
  "Re-apply the model for `role` via setModel. Needed because model_roles'
   model_resolve handler SKIPS :default (its guard is (not= role default)),
   so leaving plan mode back to :default would otherwise strand config.model
   on the plan (opus) model."
  [api role]
  (when-let [spec (role-model-spec (settings api) role)]
    (when (.-setModel api) (.setModel api spec))))

(defn planner-spec
  "'opusplan': the model used for PLANNING turns. Reads :plan-mode
   :planner-role (default :advisor), resolves it to 'provider/model' via
   role-model-spec, falling back to :deep. Returns nil when :planner-role is
   false/nil (→ no switch, plan uses the :plan role's own model). Exposed
   for tests."
  [settings]
  (let [pm   (or (:plan-mode settings) (get settings "plan-mode"))
        ;; key absent (nil) → default :advisor; explicit false → disable.
        raw  (if (some? (:planner-role pm)) (:planner-role pm) (get pm "planner-role"))
        role (if (nil? raw) :advisor raw)]
    (when (and role (not (false? role)))
      (or (role-model-spec settings role)
          (role-model-spec settings :deep)))))

(defn- ui-available? [api]
  (boolean (and (.-ui api) (.-available (.-ui api)) (.-select (.-ui api)))))

(defn- notify [api msg level]
  (when (and (.-ui api) (.-notify (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))))

(defn- content->text [c]
  (cond
    (string? c) c
    (and (js/Array.isArray c) (pos? (count c)))
    (let [parts (filter #(= "text" (or (.-type %) (get % "type"))) c)]
      (str/join "\n" (map #(or (.-text %) (get % "text")) parts)))
    :else (str c)))

(defn- last-assistant-text [messages]
  (let [a (last (filter #(= "assistant" (or (:role %) (get % "role"))) messages))]
    (when a (content->text (or (:content a) (get a "content"))))))

(defn extract-todos
  "Parse numbered steps ('1. text', '2) text') into todo maps."
  [text]
  (->> (str/split-lines (or text ""))
       (keep (fn [line]
               (let [m (re-find #"^\s*(\d+)[\.\)]\s+(.+)$" line)]
                 (when m
                   {:step (js/parseInt (nth m 1) 10)
                    :text (str/trim (nth m 2))
                    :completed false}))))
       vec))

(defn mark-done
  "Mark todos whose step number appears in a [DONE:n] tag in `text`."
  [todos text]
  (let [done (set (map (fn [m] (js/parseInt (nth m 1) 10))
                       (re-seq #"\[DONE:(\d+)\]" (or text ""))))]
    (mapv (fn [td] (if (contains? done (:step td)) (assoc td :completed true) td)) todos)))

(defn- write-artifact!
  "Write the plan text to .nyma/plans/<ts>.md. Returns path or nil."
  [plan-text]
  (try
    (let [dir  (path/join (js/process.cwd) ".nyma" "plans")
          _    (fs/mkdirSync dir #js {:recursive true})
          ts   (.toISOString (js/Date.))
          safe (str/replace ts #"[:.]" "-")
          file (path/join dir (str "plan-" safe ".md"))]
      (fs/writeFileSync file plan-text "utf8")
      file)
    (catch :default _e nil)))

;; ---------------------------------------------------------------------------
;; transitions
;; ---------------------------------------------------------------------------

(defn enter!
  "Engage plan mode: switch to the :plan role (engages read-only gating)
   and remember the prior role so execute! can restore it."
  [api]
  (let [s    (cur-state api)
        prev (or (:active-role s) :default)]
    (swap! (state-atom api) assoc
           :plan-prev-role (if (= prev :plan) :default prev)
           :active-role    :plan
           :plan-mode      true
           :plan-executing false
           ;; fresh each plan session: re-enable the planner override even if a
           ;; prior session disabled it after an auth failure (flaw A).
           :plan-planner-unusable false
           :plan-todos     [])
    (notify api "Plan mode ON — read-only. Draft a plan; approve before execution." "info")))

(defn execute!
  "Leave plan mode, restore the prior role + its model, and kick off
   execution. Model is restored explicitly (model_resolve skips :default)."
  [api]
  (let [s     (cur-state api)
        prev  (or (:plan-prev-role s) :default)
        todos (:plan-todos s)]
    (swap! (state-atom api) assoc
           :plan-mode      false
           :active-role    prev
           :plan-executing (boolean (seq todos)))
    (restore-role-model! api prev)
    (notify api "Executing plan — full tool access restored." "info")
    (.sendUserMessage api
                      (if (seq todos)
                        (str "Execute the plan. Start with step 1: " (:text (first todos)))
                        "Execute the plan we just discussed.")
                      #js {:deliverAs "followUp"})))

(defn cancel!
  "Leave plan mode without executing; restore prior role."
  [api]
  (let [s    (cur-state api)
        prev (or (:plan-prev-role s) :default)]
    (swap! (state-atom api) assoc
           :plan-mode false :plan-executing false :active-role prev)
    (restore-role-model! api prev)
    (notify api "Plan mode OFF." "info")))

;; ---------------------------------------------------------------------------
;; event handlers
;; ---------------------------------------------------------------------------

(defn on-before-agent-start
  "Inject the plan / execution system-prompt nudge."
  [api _data]
  (let [s (cur-state api)]
    (cond
      (:plan-mode s)
      #js {"system-prompt-additions" #js [plan-system-prompt]}

      (:plan-executing s)
      (let [remaining (filter #(not (:completed %)) (:plan-todos s))]
        (when (seq remaining)
          #js {"system-prompt-additions"
               #js [(str "[EXECUTING PLAN — full tool access]\nRemaining steps:\n"
                         (str/join "\n" (map #(str (:step %) ". " (:text %)) remaining))
                         "\nAfter completing a step, include a [DONE:n] tag in your reply.")]}))

      :else nil)))

(defn ^:async on-turn-finalize
  "After a plan-mode turn (awaited turn_finalize boundary): capture todos,
   write artifact, run the approval gate. Runs on turn_finalize (not
   agent_end) so the loop awaits it and the follow-up it enqueues is drained
   in the same run — without blocking on unrelated agent_end handlers."
  [api _data]
  (let [s (cur-state api)]
    (when (:plan-mode s)
      (let [text  (last-assistant-text (:messages s))
            todos (extract-todos text)]
        (when text
          (swap! (state-atom api) assoc :plan-todos todos)
          (write-artifact! text))
        (cond
          (auto-approve? api) (execute! api)

          (ui-available? api)
          (let [choice (js-await
                        (.select (.-ui api) "Plan ready — what next?"
                                 (clj->js ["Execute the plan" "Stay in plan mode" "Refine the plan"])))]
            (cond
              (and choice (str/starts-with? choice "Execute")) (execute! api)
              (= choice "Refine the plan")
              (let [refine (js-await (.input (.-ui api) "Refinement:" ""))]
                (when (and refine (seq (.trim refine)))
                  (.sendUserMessage api (.trim refine) #js {:deliverAs "followUp"})))
              :else nil))
          :else nil)))))

(defn step->text
  "Extract assistant text from a turn_end payload. turn_end emits the
   AI-SDK STEP object (has .text/.content), NOT a {message} wrapper — read
   those first, falling back to a {message} shape for safety."
  [data]
  (or (.-text data)
      (when-let [c (or (.-content data) (get data "content"))]
        (content->text c))
      (let [m (or (.-message data) (get data "message"))]
        (when m (content->text (or (.-content m) (get m "content")))))))

(defn on-turn-end
  "During execution, mark steps completed from [DONE:n] markers."
  [api data]
  (let [s (cur-state api)]
    (when (and (:plan-executing s) (seq (:plan-todos s)))
      (when-let [text (step->text data)]
        (swap! (state-atom api) update :plan-todos mark-done text)))))

;; ── opusplan: planning model override (model_resolve) + auth fallback ──

(defn on-plan-resolve
  "model_resolve handler. While `:plan-mode` (and the planner model hasn't been
   marked unusable), apply the planner role's model — 'opusplan': plan with the
   strong model, execute with the selected one. Mirrors model_roles' pattern
   (setModel + return nil); registered at lower priority so it runs LAST and
   wins config.model. Read-only GATING still comes from the :plan role."
  [api _data]
  (let [s (cur-state api)]
    (when (and (:plan-mode s) (not (:plan-planner-unusable s)))
      (when-let [spec (planner-spec (settings api))]
        (try (.setModel api spec) (catch :default _e nil))))
    ;; loop falls back to config.model (which setModel just set)
    nil))

(defn- auth-error?
  "Heuristic: does this provider_error look credential/auth-related?"
  [data]
  (let [e   (or (.-error data) (get data "error"))
        msg (str (or (.-message data) (get data "message")
                     (when e (.-message e)) e))
        m   (.toLowerCase msg)]
    (boolean (some #(.includes m %)
                   ["api key" "apikey" "unauthorized" "401" "403" "forbidden"
                    "credential" "authentication" "no auth" "missing key"]))))

(defn on-plan-provider-error
  "Flaw A: if the planner model's provider lacks credentials (e.g. :advisor =
   anthropic API key for an OAuth-only / Bedrock user), the planning call fails
   with an auth error. Mark the planner unusable so on-plan-resolve stops
   overriding (falls back to the :plan role's working model) and retry."
  [api data]
  (let [s (cur-state api)]
    (when (and (:plan-mode s)
               (not (:plan-planner-unusable s))
               (auth-error? data))
      (swap! (state-atom api) assoc :plan-planner-unusable true)
      ;; restore the :plan role's model for the retry
      (restore-role-model! api :plan)
      #js {:retry true})))

;; ---------------------------------------------------------------------------
;; activate — wired from model_roles/index.cljs
;; ---------------------------------------------------------------------------

(defn activate
  "Register plan-mode handlers + commands. Returns a deactivate fn."
  [api]
  (let [handlers (atom [])
        on-bas   (fn [data] (on-before-agent-start api data))
        on-final (fn [data] (on-turn-finalize api data))
        on-tend  (fn [data] (on-turn-end api data))
        on-mres  (fn [data] (on-plan-resolve api data))
        on-perr  (fn [data] (on-plan-provider-error api data))
        plan-handler
        (fn [_args _ctx]
          (let [s (cur-state api)]
            (if (:plan-mode s) (cancel! api) (enter! api))))]

    (.on api "before_agent_start" on-bas)
    (.on api "turn_finalize" on-final)
    (.on api "turn_end" on-tend)
    ;; opusplan: planner-model override. Priority -10 so it runs AFTER
    ;; model_roles (priority 0) and is the last writer of config.model.
    (.on api "model_resolve" on-mres -10)
    (.on api "provider_error" on-perr)
    (swap! handlers into [["before_agent_start" on-bas]
                          ["turn_finalize" on-final]
                          ["turn_end" on-tend]
                          ["model_resolve" on-mres]
                          ["provider_error" on-perr]])

    ;; /planmode always works.
    (.registerCommand api "planmode"
                      #js {:description "Toggle native plan mode (read-only → approve → execute)"
                           :handler plan-handler})

    ;; /plan only when free (guarded — Decision D).
    ;; getCommands returns the GLOBAL command map with namespace-PREFIXED
    ;; keys ("agent-shell__plan"), so check for any "*__plan" / "plan" key,
    ;; not a bare "plan". If the ACP agent_shell already owns /plan we skip
    ;; ours (native stays reachable via /planmode); the resolver would
    ;; otherwise see two "__plan" suffixes and resolve /plan to nothing.
    (let [taken? (when-let [gc (.-getCommands api)]
                   (let [cmds (gc)
                         ks   (try (vec (keys cmds)) (catch :default _e []))]
                     (some (fn [k] (let [s (str k)]
                                     (or (= s "plan") (.endsWith s "__plan"))))
                           ks)))]
      (when-not taken?
        (.registerCommand api "plan"
                          #js {:description "Toggle plan mode (read-only exploration)"
                               :handler plan-handler})))

    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterCommand api "planmode")
      (.unregisterCommand api "plan"))))
