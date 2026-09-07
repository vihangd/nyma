(ns agent.extensions.agent-shell.features.plan-capture
  "`/plan-capture` — take the plan out of an ACP planning session and hand it to
   nyma's own loop.

   The point is cost: Claude Code runs on your subscription, so planning with it
   is free at the margin, while implementation runs locally on a cheap model.
   The bridge is a file — `.nyma/plans/plan-<iso>.md`, the same directory and
   naming model_roles/plan_mode already writes — because that is the one
   interface both halves already understand. `/spec import` reads it; without
   spec_driven, `--execute` just points the model at it.

   Capture DETACHES rather than disconnecting. `pool/disconnect` kills the
   subprocess, and that process holds the only copy of the planning
   conversation — so tearing it down would mean a follow-up refinement starts
   cold. Detaching returns typed input to nyma while leaving the session warm
   and reattachable."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.model-roles.features.plan-mode :as plan-mode]))

;;; ─── Pure ──────────────────────────────────────────────────

(defn parse-args
  "Split `/plan-capture` args into {:name :all? :any-mode? :execute? :role
   :dry-run? :disconnect?}. Name is the first non-flag token.

   No --force: artifact filenames carry a timestamp so they never collide, and
   overwriting an existing SPEC is /spec import's decision, not capture's."
  [args]
  (let [as   (mapv str (or args []))
        flag (fn [f] (boolean (some #(= % f) as)))
        role (some (fn [a] (when (.startsWith a "--role=") (.slice a 7))) as)]
    {:name        (first (remove (fn [a] (.startsWith a "--")) as))
     :all?        (flag "--all")
     :any-mode?   (flag "--any-mode")   ; silence the already-edited warning
     :execute?    (flag "--execute")
     :dry-run?    (flag "--dry-run")
     ;; The refine round-trip is one extra turn on the planning agent — free
     ;; at the margin on a subscription, but not free in time.
     :no-refine?  (flag "--no-refine")
     :disconnect? (flag "--disconnect")
     ;; Not "default": `model_roles/on-resolve` guards
     ;; `(not= role "default")`, so a role by that name is a silent no-op and
     ;; the handoff would run on settings.model rather than anything chosen
     ;; here. `fast` is the role that exists for exactly this — cheap execution
     ;; of a plan someone else wrote.
     :role        (or role "fast")}))

(def ^:private stopwords
  "Dropped when deriving a name — grammar and meta-instruction, neither of
   which identifies the work.

   Two rounds of real output shaped this list. \"add OAuth login to the API\"
   first became `add-oauth-login-to-the`, all budget on grammar. Then
   \"let's plan to write a script…\" became `plan-write-script-that`, because
   people open with how they want to work (\"let's plan\", \"help me\") before
   saying what the work is."
  #{;; grammar
    "a" "an" "the" "to" "for" "of" "in" "on" "at" "by" "with" "and" "or"
    "into" "from" "that" "this" "it" "its" "is" "are" "be" "as" "so"
    ;; possessives / filler
    "our" "my" "your" "some" "any" "just" "please" "s"
    ;; meta-instruction: how to work, not what to build
    "let" "lets" "us" "we" "i" "me" "you" "can" "plan" "plans" "planning" "help" "want"
    "need" "like" "would" "should" "could" "add" "make" "do" "does"})

(defn- stem
  "Crude stem for dedupe: first four characters. Enough to collapse
   write/writes/writing, which a single request routinely contains."
  [w]
  (.slice (str w) 0 4))

(defn- dedupe-stems
  "Keep the first word of each stem, preserving order."
  [words]
  (:out (reduce (fn [acc w]
                  (let [k (stem w)]
                    (if (contains? (:seen acc) k)
                      acc
                      {:seen (conj (:seen acc) k) :out (conj (:out acc) w)})))
                {:seen #{} :out []}
                words)))

(defn slug
  "A spec-safe name from free text: lowercase, alphanumerics and single
   hyphens, stopwords dropped, capped at 4 words. Matches what create-spec!
   accepts, so a derived name never fails validation downstream."
  [text]
  (let [cleaned (-> (str (or text ""))
                    .toLowerCase
                    (.replace (js/RegExp. "[^a-z0-9]+" "g") " ")
                    str/trim)
        words   (->> (str/split cleaned #"\s+")
                     (remove str/blank?)
                     (remove stopwords)
                     dedupe-stems)
        ;; If it was ALL stopwords, keep the originals rather than returning
        ;; nothing — a bad name beats no name, and the caller can override.
        words   (if (seq words)
                  words
                  (remove str/blank? (str/split cleaned #"\s+")))]
    (str/join "-" (take 4 words))))

(defn last-assistant
  "Text of the last assistant turn, or nil."
  [transcript]
  (some->> (reverse (vec (or transcript [])))
           (filter (fn [t] (= "assistant" (str (:role t)))))
           first
           :text))

(defn first-request
  "Text of the first user turn — the originating ask, used for the artifact
   header and for deriving a name."
  [transcript]
  (some->> (vec (or transcript []))
           (filter (fn [t] (= "user" (str (:role t)))))
           first
           :text))

(defn plan-text
  "The plan to capture: the last assistant turn, or the whole conversation
   when `all?`. `--all` exists for a plan that was built up across turns
   rather than restated at the end."
  [transcript all?]
  (if all?
    (->> (vec (or transcript []))
         (map (fn [t] (str (if (= "user" (str (:role t))) "## Request\n\n" "## Response\n\n")
                           (:text t))))
         (str/join "\n\n"))
    (last-assistant transcript)))

(def self-contained-note
  "Appended to the artifact, not sent to the agent. The executing model has
   none of the planning session's exploration, so a plan written for the author
   is not executable by the reader."
  (str "\n\n---\n\n"
       "_Executing this plan: you have no access to the conversation that "
       "produced it. Work only from what is written here and from the "
       "repository. If a step does not name a file path, find it before "
       "editing._\n"))

(defn build-artifact
  "Assemble the artifact text. The header records provenance because
   `.nyma/plans/` now has two writers (plan_mode is the other)."
  [{:keys [agent mode request plan captured-at]}]
  (str "---\n"
       "source: agent-shell/" (or agent "unknown") "\n"
       "mode: " (or mode "unknown") "\n"
       "captured: " (or captured-at "") "\n"
       (when-not (str/blank? (str request))
         (str "request: " (str/replace (str request) #"\n" " ") "\n"))
       "---\n\n"
       (str plan)
       self-contained-note))

(defn artifact-filename
  "`plan-<iso>.md`, matching plan_mode/write-artifact! so both writers sort
   together and `/spec import` can pick the newest without caring who wrote it."
  [now]
  (str "plan-" (str/replace (.toISOString now) #"[:.]" "-") ".md"))

(defn edits-warning
  "A caution when the agent actually changed files during the session, or nil.

   Mode was the first attempt at this and it is only a proxy: an agent in
   default mode may be denied every write (nyma prompts, you decline), while
   one in an auto-approving mode quietly makes the very changes the plan is
   meant to describe. Counting COMPLETED mutating tool calls answers the real
   question — has this already been done? — and a plan for finished work sends
   the executing model over the same ground.

   A warning, not a refusal: the agent may legitimately have edited something
   unrelated, and only you can tell."
  [n mode]
  (when (pos? (or n 0))
    (str "the agent completed " n " file change" (when (> n 1) "s")
         " during this session"
         (when (not= "plan" (str mode)) (str " (mode: " (or mode "unknown") ")"))
         " — check the plan does not describe work already done")))

(def refine-prompt
  "Sent back to the planning agent when the plan is not executable cold.

   The plan the agent writes is addressed to YOU, mid-conversation: it can say
   \"eyeball the output\" or \"the usual place\" because you were both there.
   The model that executes it has none of that. This asks for the missing
   half."
  (str "Rewrite that plan so an engineer with no access to this conversation "
       "can execute it. For each step: name the exact file path (and line "
       "range where it matters), state the current behaviour before the "
       "change, and give the command that verifies the step succeeded — a "
       "command with an exit code, not an instruction to look at something. "
       "Number the steps. Output only the plan."))

(def ^:private path-like
  ;; A path or a dotted filename. Deliberately loose: the question is whether
  ;; the plan points anywhere at all, not whether the path resolves.
  (js/RegExp. "(^|[\\s`'\"(])(?:[.~]?/[\\w.-]+|[\\w.-]+/[\\w./-]+|[\\w-]+\\.[a-z]{2,4})\\b"))

(def ^:private verify-like
  ;; A command that could exit non-zero. `verify_gate` needs one of these;
  ;; "run it and check the output" gives it nothing to gate on.
  ;;
  ;; Anchored at a token start rather than \\b: bare `sh` and `go` matched
  ;; inside `today.sh` and inside ordinary prose, so a plan verified by eyeball
  ;; looked verified. Dropped both in favour of the forms people actually type.
  (js/RegExp. (str "(^|[\\s`'\"(])"
                   "(bun|npm|npx|yarn|pnpm|cargo|pytest|python3?|make|bash"
                   "|git|tsc|eslint|jest|vitest|go (?:test|build|run))"
                   "\\b")))

(defn thin-plan?
  "Why `plan` cannot be executed cold, or nil when it can.

   Two signals, both drawn from a real capture. That plan named an absolute
   path and gave the file body inline — genuinely executable — but its
   verification step was \"run it, eyeball `2026-09-04`\", which no gate can
   act on. A plan naming no file at all is the worse case and the more common
   one.

   Only consulted to decide whether to ASK for a rewrite; it never refuses."
  [plan steps]
  (let [t (str (or plan ""))]
    (cond
      (zero? (or steps 0))       nil ; capture-refusal already covers this
      (not (.test path-like t))  "no step names a file"
      (not (.test verify-like t)) "no step names a command that verifies it"
      :else nil)))

(defn capture-refusal
  "Pure: the reason capture must not proceed, or nil. Order matters — the
   cheapest, most-likely-wrong condition first.

   Mode is deliberately NOT here. Capturing outside plan mode used to be a hard
   refusal, on the theory that the agent might have edited files while you
   thought you were planning. In practice nyma's permission layer already gates
   writes — a real session showed Claude Code asking and being denied — so the
   refusal only blocked a working flow. `mode-warning` covers it instead, and
   the artifact records the mode either way."
  [{:keys [agent-key transcript steps]}]
  (cond
    (nil? agent-key)
    "no ACP agent connected — /agent claude first"

    (empty? (vec (or transcript [])))
    "nothing captured yet — send a prompt to the agent first"

    (str/blank? (str (last-assistant transcript)))
    "the agent has not answered yet — nothing to capture"

    (zero? (or steps 0))
    "that reads like a question, not a plan — answer it and capture again"

    :else nil))

;;; ─── Impure ────────────────────────────────────────────────

(defn- notify [api msg & [level]]
  (when-let [ui (.-ui api)]
    (when (.-notify ui) (.notify ui msg (or level "info")))))

(defn- write-artifact!
  "Write to .nyma/plans/, creating it. Returns the path."
  [cwd text now]
  (let [dir (path/join cwd ".nyma" "plans")]
    (fs/mkdirSync dir #js {:recursive true})
    (let [file (path/join dir (artifact-filename now))]
      (fs/writeFileSync file text "utf8")
      file)))

(defn ^:async capture!
  "The command body, split from registration so tests can drive it.

   `send-fn` is the refine round-trip, injected so tests need no ACP."
  [api args & [send-fn]]
  (let [{:keys [name all? any-mode? execute? role dry-run? disconnect? no-refine?]}
        (parse-args args)
        agent-key  @shared/active-agent
        cwd        (js/process.cwd)
        p-key      (when agent-key (shared/pool-key agent-key cwd))
        transcript (when p-key (shared/get-transcript p-key))
        mode       (when agent-key (shared/get-agent-state agent-key :mode))
        plan0      (plan-text transcript all?)
        steps0     (count (plan-mode/extract-todos (str plan0)))
        ;; Ask once for a rewrite when the plan reads well but cannot be
        ;; executed by a stranger. Skipped for --all (which captures the whole
        ;; conversation, so a single turn's thinness says nothing), for
        ;; --dry-run, and when there is no sender.
        thin       (when-not (or no-refine? all? dry-run?)
                     (thin-plan? plan0 steps0))
        refined?   (atom false)
        _          (when (and thin send-fn p-key)
                     (notify api (str "plan-capture: " thin
                                      " — asking " (shared/kw-name agent-key)
                                      " to rewrite it for a reader with no "
                                      "access to this conversation"))
                     (js-await (-> (js/Promise.resolve (send-fn refine-prompt))
                                   (.then (fn [_] (reset! refined? true)))
                                   (.catch (fn [e]
                                             ;; A failed rewrite is not a failed
                                             ;; capture — the original plan is
                                             ;; still on the transcript.
                                             (notify api (str "plan-capture: rewrite failed ("
                                                              (.-message e)
                                                              "); capturing the plan as written")
                                                     "warning"))))))
        ;; Re-read: send-prompt appends both turns, so the rewrite is now the
        ;; last assistant turn.
        transcript (if @refined? (shared/get-transcript p-key) transcript)
        plan       (if @refined? (plan-text transcript all?) plan0)
        steps      (if @refined? (count (plan-mode/extract-todos (str plan))) steps0)
        refusal    (capture-refusal {:agent-key agent-key :transcript transcript
                                     :steps steps})
        edits      (when p-key (shared/edit-count p-key))
        warning    (when-not any-mode? (edits-warning edits mode))]
    (if refusal
      (notify api (str "plan-capture: " refusal) "error")
      (let [_         (when warning (notify api (str "plan-capture: " warning) "warning"))
            spec-name (let [n (or name (slug (first-request transcript)))]
                        (if (str/blank? n) "plan" n))
            now       (js/Date.)
            text      (build-artifact {:agent (shared/kw-name agent-key)
                                       :mode  mode
                                       :request (first-request transcript)
                                       :plan  plan
                                       :captured-at (.toISOString now)})]
        (if dry-run?
          (notify api (str "plan-capture (dry run): " spec-name " · " steps " steps · "
                           (count text) " chars\n"
                           "would write .nyma/plans/" (artifact-filename now)))
          (let [file (write-artifact! cwd text now)
                rel  (path/relative cwd file)]
            ;; Detach, never disconnect — see the ns docstring. --disconnect is
            ;; for a genuine one-shot where the session is not wanted again.
            (reset! shared/active-agent nil)
            (notify api (str "✓ " spec-name " · " steps " steps → " rel
                             "\n  detached; /agent " (shared/kw-name agent-key)
                             " to resume planning"
                             (when-not execute?
                               (str "\n  next: /spec import " spec-name " --run"))))
            (when execute?
              (swap! (aget api "__state_atom") assoc :active-role role)
              (notify api (str "▸ role: " role " · executing"))
              ((.-sendUserMessage api)
               (str "Execute the plan in `" rel "`. Read it first, then work "
                    "through the numbered steps in order, one at a time. Stop "
                    "if you need input.")
               #js {:deliverAs "followUp"}))
            (when disconnect?
              (shared/clear-transcript! p-key))))))
    nil))

(defn activate
  "Register /plan-capture. Returns a deactivator."
  [api]
  (.registerCommand
   api "plan-capture"
   #js {:description (str "Capture the ACP agent's plan to .nyma/plans/. "
                          "Usage: /plan-capture [<name>] [--all] [--any-mode] "
                          "[--execute --role=<r>] [--dry-run] [--no-refine] "
                          "[--disconnect]")
        :handler (fn [args _ctx]
                   (capture! api args
                             (fn [text]
                               (when-let [conn (some-> @shared/active-agent
                                                       shared/find-conn-by-agent)]
                                 (client/send-prompt conn text)))))})
  (fn [] (.unregisterCommand api "plan-capture")))
