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

   Entry is /planmode, or `/mode plan`. There is no /plan: this ns and
   agent_shell's ACP mode switcher both used to claim it behind mirror-image
   guards, so ownership depended on load order, and two registrations made the
   resolver ambiguous. The ACP agent's plan mode is `/agent mode plan`.

   Squint notes: async handlers are top-level defns called by sync wrappers
   (named, so testable; `^:async (fn …)` with the meta on the FORM also
   works); .-__state-atom compiles to .__state_atom."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.utils.ui :refer [ui-prompt-ready?]]))

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

(defn- settings [api] (.settings api))

(defn- auto-approve? [api]
  ;; Tolerate keyword (CLJS defaults) and string (user JSON) keys.
  (let [s  (settings api)
        pm (:plan-mode s)]
    (boolean (or (:auto-approve pm) (and pm (get pm "auto-approve"))))))

(defn role-model-spec
  "Pure: settings + role -> 'provider/model' string (or nil). Tolerates
   keyword (CLJS defaults) and string (user JSON) keys. Exposed for tests."
  [settings role]
  ;; Squint: keywords ARE strings, so :default == "default" — one lookup
  ;; covers both keyword (CLJS defaults) and string (user JSON) roles.
  (let [roles (:roles settings)
        cfg   (or (get roles role) (get roles (str role)))
        provider (:provider cfg)
        model-id (:model cfg)]
    (when (and provider model-id) (str provider "/" model-id))))

(defn default-model-spec
  "The configured DEFAULT model spec: the startup base (cli stashes
   :base-model-spec = -m / settings :model / fallback), falling back to the
   :default role's own :model. NOT a hardcoded sonnet — so -m / settings :model
   is honored. Exposed for tests."
  [api]
  (or (:base-model-spec (cur-state api))
      (role-model-spec (settings api) :default)))

(defn effective-model-spec
  "The 'provider/model' spec a role resolves to: the CONFIGURED default for the
   \"default\" role (so -m / settings :model is honored — model_resolve SKIPS
   :default, so callers restoring it must compute this), else the role's own
   model. The single resolver for /role, plan-exit restore, and the role list —
   so the (= role \"default\") branch lives in ONE place. Exposed for /role + tests."
  [api role]
  (if (= (str role) "default")
    (default-model-spec api)
    (role-model-spec (settings api) role)))

(defn- restore-role-model!
  "Re-apply the model for `role` via setModel. Needed because model_resolve
   SKIPS :default, so leaving plan mode back to :default would otherwise strand
   config.model on the planner model."
  [api role]
  (when-let [spec (effective-model-spec api role)]
    (when (.-setModel api) (.setModel api spec))))

(defn planner-spec
  "'opusplan': the model used for PLANNING turns. Reads :plan-mode
   :planner-role (default :advisor), resolves it to 'provider/model' via
   role-model-spec, falling back to :deep. Returns nil when :planner-role is
   false/nil (→ no switch, plan uses the :plan role's own model). Exposed
   for tests."
  [settings]
  (let [pm   (:plan-mode settings)
        ;; key absent (nil) → default :advisor; explicit false → disable.
        raw  (if (some? (:planner-role pm)) (:planner-role pm) (get pm "planner-role"))
        role (if (nil? raw) :advisor raw)]
    (when (and role (not (false? role)))
      (or (role-model-spec settings role)
          (role-model-spec settings :deep)))))

(defn- ui-available? [api]
  (ui-prompt-ready? (.-ui api)))

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
    (when a (content->text (:content a)))))

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
  "Engage plan mode: set the :permission-mode to 'plan' (read-only policy +
   approval gate) and remember the prior MODE so execute!/cancel! can restore
   it. The model role (:active-role) is left untouched — opusplan switches the
   model during planning via on-plan-resolve (keyed on :plan-mode)."
  [api]
  (let [s         (cur-state api)
        prev-mode (or (:permission-mode s) "default")]
    (swap! (state-atom api) assoc
           :plan-prev-mode  (if (= prev-mode "plan") "default" prev-mode)
           :permission-mode "plan"
           :plan-mode       true
           :plan-executing  false
           ;; fresh each plan session: re-enable the planner override even if a
           ;; prior session disabled it after an auth failure (flaw A).
           :plan-planner-unusable false
           :plan-todos      [])
    (notify api "Plan mode ON — read-only. Draft a plan; approve before execution." "info")))

(defn execute!
  "Leave plan mode, restore the prior permission mode, re-apply the model
   role's model (opusplan left it on the planner), and kick off execution."
  [api]
  (let [s         (cur-state api)
        prev-mode (or (:plan-prev-mode s) "default")
        role      (or (:active-role s) :default)
        todos     (:plan-todos s)]
    (swap! (state-atom api) assoc
           :plan-mode       false
           :permission-mode prev-mode
           :plan-executing  (boolean (seq todos)))
    ;; restore the MODEL role's model (model_resolve skips :default, so the
    ;; planner override would otherwise strand config.model on the planner).
    (restore-role-model! api role)
    (notify api "Executing plan — full tool access restored." "info")
    (.sendUserMessage api
                      (if (seq todos)
                        (str "Execute the plan. Start with step 1: " (:text (first todos)))
                        "Execute the plan we just discussed.")
                      #js {:deliverAs "followUp"})))

(defn cancel!
  "Leave plan mode without executing; restore the prior permission mode + the
   model role's model."
  [api]
  (let [s         (cur-state api)
        prev-mode (or (:plan-prev-mode s) "default")
        role      (or (:active-role s) :default)]
    (swap! (state-atom api) assoc
           :plan-mode false :plan-executing false :permission-mode prev-mode)
    (restore-role-model! api role)
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
  [api data]
  (let [s (cur-state api)]
    (when (:plan-mode s)
      (if (and data (or (.-error data) (get data "error")))
        ;; The planning turn errored (e.g. flaky provider). Don't auto-execute a
        ;; non-existent plan — stay in plan mode and surface it so the gate is
        ;; never silently skipped. The user can retry or /planmode cancel.
        (notify api "Plan turn failed — still in plan mode. Retry, or /planmode cancel." "warn")
        (let [text  (last-assistant-text (:messages s))
              todos (extract-todos text)]
          (when text
            (swap! (state-atom api) assoc :plan-todos todos)
            ;; Say where the plan landed. The file was written and never
            ;; mentioned, so the one durable artifact of a planning session
            ;; was only findable by listing .nyma/plans/ and guessing.
            (when-let [file (write-artifact! text)]
              (notify api (str "Plan written to " file) "info")))
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

            ;; No UI and not auto-approve — notify instead of a silent no-op so
            ;; the plan isn't left finished-but-invisible. Manual escape hatch.
            :else (notify api "Plan ready — no UI to approve. Run /planmode execute or /planmode cancel." "info")))))))

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

(defn outstanding-summary
  "Nil when a plan finished (or none ran); otherwise \"3 of 7 steps\".

   Pure so the reporting rule is testable without a session."
  [state]
  (let [todos (:plan-todos state)]
    (when (and (:plan-executing state) (seq todos))
      (let [remaining (count (remove :completed todos))]
        (when (pos? remaining)
          (str remaining " of " (count todos) " steps"))))))

(defn on-session-end
  "Say so if the session ends with plan steps outstanding.

   Advisory on purpose — no block, no re-prompt. Nothing checked this before, so
   a plan could quietly stall: the per-turn reminder stops once the list empties
   and says nothing when it does not. Told to the USER rather than pushed back at
   the model, because plan structure the model is not following makes things
   worse, not better (arXiv 2604.12147)."
  [api _data]
  (when-let [summary (outstanding-summary (cur-state api))]
    (when-let [ui (.-ui api)]
      (when (.-notify ui)
        (.notify ui (str "Plan incomplete — " summary " were never marked done.")
                 "warning")))))

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
        ;; Was swallowed, and the comment below asserted "setModel just set" it.
        ;; A silent failure degraded opusplan to the execution model with no
        ;; message and no :plan-planner-unusable, so nothing recovered.
        ;; warn-quiet: model_resolve fires mid-render.
        (try (.setModel api spec)
             (catch :default e
               (d/warn-quiet "plan-mode"
                             (str "planner setModel failed for " spec ": "
                                  (or (.-message e) (str e))))))))
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
      ;; ...and put it where the RETRY will actually look. st-config is built
      ;; once per outer loop iteration (loop.cljs:238) and re-sent as-is on
      ;; retry, so setModel alone would re-issue on the same dead planner model.
      ;; Tolerates a missing :config — tests call this handler without one.
      (when-let [st-config (.-config data)]
        (when-let [spec (effective-model-spec api :plan)]
          (let [i (.indexOf (str spec) "/")]
            (when (pos? i)
              (try
                (when-let [obj ((.-resolveModel api) (.substring (str spec) 0 i)
                                                     (.substring (str spec) (inc i)))]
                  (aset st-config "model" obj)
                  (aset st-config "providerOptions" #js {}))
                (catch :default _e nil))))))
      #js {:retry true})))

;; ---------------------------------------------------------------------------
;; activate — wired from model_roles/index.cljs
;; ---------------------------------------------------------------------------

(defn activate
  "Register plan-mode handlers + commands. Returns a deactivate fn."
  [api]
  (let [on-bas   (fn [data] (on-before-agent-start api data))
        on-final (fn [data] (on-turn-finalize api data))
        on-tend  (fn [data] (on-turn-end api data))
        on-mres  (fn [data] (on-plan-resolve api data))
        on-perr  (fn [data] (on-plan-provider-error api data))
        plan-handler
        ;; Bare toggle, plus explicit subcommands so the user can drive the
        ;; transition manually when the auto-gate didn't fire (e.g. the turn
        ;; errored, or there's no interactive UI): /planmode execute | /planmode cancel.
        (fn [args _ctx]
          (let [sub (.toLowerCase (str (or (first args) "")))
                s   (cur-state api)]
            (cond
              (= sub "execute")
              (if (:plan-mode s) (execute! api) (notify api "Not in plan mode." "info"))
              (or (= sub "cancel") (= sub "stop"))
              (if (:plan-mode s) (cancel! api) (notify api "Not in plan mode." "info"))
              :else (if (:plan-mode s) (cancel! api) (enter! api)))))
        on-send (fn [data] (on-session-end api data))]

    (.on api "before_agent_start" on-bas)
    (.on api "turn_finalize" on-final)
    (.on api "turn_end" on-tend)
    ;; opusplan: planner-model override. Priority -10 so it runs AFTER
    ;; model_roles (priority 0) and is the last writer of config.model.
    (.on api "model_resolve" on-mres -10)
    (.on api "provider_error" on-perr)
    (.on api "session_end" on-send)

    ;; /planmode, and only /planmode. There is no /plan: both this extension
    ;; and agent_shell's ACP mode switcher used to claim it behind mirror-image
    ;; guards, so which one you got depended on load order — and if both ever
    ;; registered, the resolver saw two "__plan" keys and /plan resolved to
    ;; nothing. `/mode plan` also enters native plan mode.
    (.registerCommand api "planmode"
                      #js {:description "Toggle native plan mode (read-only → approve → execute)"
                           :handler plan-handler})

    (fn []
      (.unregisterCommand api "planmode"))))
