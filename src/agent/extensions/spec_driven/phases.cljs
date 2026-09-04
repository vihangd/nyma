(ns agent.extensions.spec-driven.phases
  "Pure core of the spec phase loop: profile → phase → role resolution, and
   the decision of what to do at the end of a turn.

   Everything here is a pure function of data. No fs, no api, no model — so
   the loop's decisions are testable without running an agent, which matters
   because the surface this feeds (`on-agent-end`) has historically had no
   test coverage at all.

   Two decisions are load-bearing and deliberate:

   1. **The model's finish reason is not a completion signal.** Premature
      termination — the agent announcing it is done when tasks remain — is the
      documented failure mode of loops of this shape. The unchecked count in
      tasks.md is the only thing we trust.

   2. **An empty task list is NOT completion.** `parse-tasks` drops every line
      that is not a checkbox, so a corrupted, truncated or reformatted tasks.md
      yields zero tasks — which naively reads as `all done`. That would have the
      loop declare victory on a broken file. `:no-tasks` is a distinct outcome
      and it stops the loop."
  (:require [clojure.string :as str]))

(def default-phase-order ["plan" "execute" "verify" "ship"])

(def fallback-role
  "Where an unresolvable role lands. Deliberately the same name model_roles
   treats as the base role."
  "default")

(def default-profiles
  "Shipped presets. Users override via settings#spec.profiles — these are the
   floor, not a closed set, so a user profile map does not have to redefine
   every phase to be usable."
  ;; Every role named here must exist WITHOUT the user declaring one, or the
  ;; shipped profile degrades on first use and reports its own failure. Two
  ;; near-misses already caught: `reviewer` is a SUBAGENT role
  ;; (subagent/index.cljs:48-64), and `build` is not a role at all — neither
  ;; model_roles/index.cljs:19-38 nor settings/manager.cljs ships one. Cross-
  ;; check additions against `builtin-role-names` below.
  {"routed"  {"plan" "advisor" "execute" "fast"    "verify" "deep"    "ship" "commit"}
   "thrifty" {"plan" "advisor" "execute" "default" "verify" "fast"    "ship" "default"}
   "free"    {"plan" "default" "execute" "default" "verify" "default" "ship" "default"}})

(def builtin-role-names
  "Roles that exist without the user declaring them: model_roles' shipped table
   plus the advisor default in settings/manager.cljs. Used to sanity-check a
   profile without spec_driven having to reach into model_roles."
  #{"default" "fast" "deep" "plan" "commit" "advisor" "accept-edits" "full-auto"})

(defn armed-by-settings?
  "True when settings#spec.loop.mode opts the loop in permanently. Without this
   `:mode` was parsed and never read, so a user setting \"on\" got silence."
  [cfg]
  (= "on" (str (:mode (:loop cfg)))))

(def default-loop
  ;; fresh-context is OFF by default and that is not timidity. Clearing the
  ;; conversation between tasks is what keeps a long run cheap and rot-free
  ;; (measured 8.6x on a 60-task run), but it also discards anything the user
  ;; said in chat. Ralph's premise is that ALL intent lives in the files; the
  ;; moment someone adds "…but skip the OAuth part" in conversation, that
  ;; premise is false and the instruction is gone.
  {:mode "off" :profile "routed" :max-iterations 25 :fresh-context false})

;; ── Config ───────────────────────────────────────────────────────

(defn- ->clj-map
  "Settings arrive as plain JS objects from JSON.parse. Normalize one level
   into a CLJS map of string→string, tolerating an already-CLJS map."
  [o]
  (cond
    (nil? o)  {}
    (map? o)  (reduce-kv (fn [m k v] (assoc m (name k) v)) {} o)
    (object? o) (reduce (fn [m k] (assoc m (str k) (aget o k)))
                        {} (js/Object.keys o))
    :else {}))

(defn config
  "Read settings#spec into {:profiles :loop}. User profiles are merged OVER the
   shipped ones by name, so redefining `routed` replaces it while `thrifty`
   survives — the same shape relay uses for gateway presets."
  [settings]
  (let [spec  (->clj-map (when settings (or (aget settings "spec") (get settings :spec))))
        profs (->clj-map (get spec "profiles"))
        lp    (->clj-map (get spec "loop"))
        user  (reduce-kv (fn [m k v] (assoc m k (->clj-map v))) {} profs)]
    {:profiles (merge default-profiles user)
     :loop     (merge default-loop
                      (cond-> {}
                        (some? (get lp "mode"))    (assoc :mode (str (get lp "mode")))
                        (some? (get lp "profile")) (assoc :profile (str (get lp "profile")))
                        (number? (get lp "max-iterations"))
                        (assoc :max-iterations (get lp "max-iterations"))
                        (some? (get lp "fresh-context"))
                        (assoc :fresh-context (boolean (get lp "fresh-context")))))}))

(defn phase-order
  "Phases in order for `profile-map`, preserving `default-phase-order` for the
   names it knows and appending any extra the user declared."
  [profile-map]
  (let [known (set (keys (or profile-map {})))
        base  (filterv known default-phase-order)
        extra (vec (sort (remove (set default-phase-order) known)))]
    (vec (concat base extra))))

(defn resolve-role
  "Role bound to `phase` under `profile-name`, checked against the roles that
   actually exist.

   Returns {:role r :phase p :fell-back? bool :reason s}. An unknown profile or
   an unknown role degrades to `default` and SAYS SO — the caller is expected to
   surface it once. Silent degradation is exactly the trap the advisor already
   has (advisor/index.cljs:164-168), where an unresolvable provider quietly
   yields the main-loop model and the user never learns their role did nothing."
  [cfg profile-name phase known-roles]
  (let [profiles (:profiles cfg)
        known    (set (map name (or known-roles [])))
        pmap     (get profiles (str profile-name))]
    (cond
      (nil? pmap)
      {:role fallback-role :phase phase :fell-back? true
       :reason (str "unknown profile \"" profile-name "\" — using " fallback-role)}

      :else
      (let [r (get pmap (str phase))]
        (cond
          (str/blank? (str r))
          {:role fallback-role :phase phase :fell-back? true
           :reason (str "profile \"" profile-name "\" declares no role for phase \""
                        phase "\" — using " fallback-role)}

          (and (seq known) (not (contains? known (str r))))
          {:role fallback-role :phase phase :fell-back? true
           :reason (str "role \"" r "\" (phase \"" phase "\") is not defined — using "
                        fallback-role)}

          :else {:role (str r) :phase phase :fell-back? false :reason nil})))))

;; ── Progress ─────────────────────────────────────────────────────

(defn progress
  "Task progress from an ALREADY-PARSED task list plus the raw file text.

   `parsed` is what index/parse-tasks returned; `raw` is the file contents, and
   it is what lets us tell `no checkboxes in this file` from `every checkbox is
   ticked`. Both produce an empty open-list; only one of them means done."
  [parsed raw]
  (let [ts      (vec (or parsed []))
        total   (count ts)
        checked (count (filter :checked? ts))
        blank?  (str/blank? (str (or raw "")))]
    {:total   total
     :checked checked
     :texts   (mapv :text ts)
     :open    (- total checked)
     :status  (cond
                (zero? total) :no-tasks       ; unparseable, empty, or reformatted
                (= checked total) :complete
                :else :in-progress)
     :empty-file? blank?}))

;; ── The decision ─────────────────────────────────────────────────

(def template-task-texts
  "Placeholder tasks `/spec new` and `/spec import` scaffold. A tasks.md that
   still contains only these has not been filled in yet — importantly it is NOT
   the same as a corrupted file, and not something to run a loop against."
  #{"first task" "second task"})

(defn template-tasks?
  "True when every task is still a scaffold placeholder."
  [progress]
  (let [ts (vec (or (:texts progress) []))]
    ;; boolean, not the `and` result: an empty list yields nil, and a predicate
    ;; that answers nil/false/true for three states is a trap for callers.
    (boolean
     (and (seq ts)
          (every? (fn [t] (contains? template-task-texts
                                     (str/lower-case (str/trim (str t))))) ts)))))

(defn decide
  "What the loop should do at the end of a turn. Pure.

   Input {:armed? :phase :profile :iteration :max-iterations :progress
          :verify-pending? :phases}
   Output {:action :continue|:advance|:done|:hold|:stop :reason s :next-phase p}

   Order matters. Each guard maps to a failure this loop is known to have:
     armed?          — it never runs unless explicitly armed
     max-iterations  — the follow-queue drain is an unbounded recur upstream
                       (loop.cljs:586-594), so the bound has to live here
     verify-pending? — verify_gate enqueues its own fix follow-up; advancing
                       past an unresolved failure is how a loop 'finishes'
                       broken work
     :no-tasks       — a broken tasks.md must stop, never read as complete
     open > 0        — the only real completion signal"
  [{:keys [armed? phase profile iteration max-iterations progress
           verify-pending? phases]}]
  (let [order  (vec (or phases default-phase-order))
        idx    (.indexOf order (str phase))
        nxt    (when (and (>= idx 0) (< (inc idx) (count order)))
                 (nth order (inc idx)))
        st     (:status progress)]
    (cond
      (not armed?)
      {:action :stop :reason "loop not armed"}

      (and (number? max-iterations) (>= (or iteration 0) max-iterations))
      {:action :stop
       :reason (str "iteration cap reached (" iteration "/" max-iterations ")")}

      ;; HOLD, not stop: verify_gate has its own fix follow-up in flight and
      ;; will drive the next turn. Disarming here would mean the loop never
      ;; resumes once the build goes green, which is the opposite of the guard's
      ;; purpose — it exists to stop us ADVANCING past a red build, not to end
      ;; the run.
      verify-pending?
      {:action :hold :reason "verify is red — deferring to the fix loop"}

      (= st :no-tasks)
      {:action :stop
       :reason (if (:empty-file? progress)
                 "tasks file is empty — nothing to run"
                 "no checkboxes found in tasks file — refusing to treat as complete")}

      (= st :in-progress)
      {:action :continue
       :reason (str (:checked progress) "/" (:total progress) " tasks")}

      ;; A phase that is not in this profile's order yields idx -1, so `nxt` is
      ;; nil and a completed task list would fall through to :done — announcing
      ;; "all phases complete" for a phase the profile never contained. That is
      ;; the premature-completion class this ns exists to prevent. Reachable by
      ;; switching to a profile with a different phase set mid-run.
      (neg? idx)
      {:action :stop
       :reason (str "phase \"" phase "\" is not in profile \"" profile
                    "\" (" (str/join ", " order) ") — set one with /spec phase")}

      ;; complete: advance, or finish if this was the last phase
      (some? nxt)
      {:action :advance :next-phase nxt
       :reason (str "phase \"" phase "\" complete → " nxt)}

      :else
      {:action :done :reason (str "all phases complete (" profile ")")})))
