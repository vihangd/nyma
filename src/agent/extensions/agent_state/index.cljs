(ns agent.extensions.agent-state.index
  "Report what nyma is doing to whatever is supervising it.

   Terminal multiplexers that run several coding agents at once need to know
   which pane is busy, which is idle, and which is waiting on a human. herdr —
   a Rust workspace manager that already integrates 17+ agents — asks for this
   by running a CLI command, and explicitly supports agents it has never heard
   of:

     \"$HERDR_BIN_PATH\" pane report-agent \"$HERDR_PANE_ID\" \\
       --source custom:nyma --agent nyma --state working

   An agent that reports becomes a 'lifecycle authority' and herdr stops
   guessing its state by scraping the screen, which is what it does otherwise.

   This is written as a generic reporter rather than a herdr extension: the
   command is a settings-driven argv template, and herdr is one auto-detected
   default rather than the only thing that works. Anything that can be told
   about a state change by running a program can use it.

     {\"agent-state\": {\"enabled\": true,
                      \"command\": [\"my-supervisor\", \"--state\", \"{state}\"]}}

   Placeholders in the template: {state}, {message}, {seq}, {agent}.

   Inert by default. With no configured command and no supervisor detected in
   the environment, nothing subscribes and nothing is ever spawned — which is
   the path every user who has never heard of herdr takes."
  (:require [agent.debug :as d]
            [clojure.string :as str]))

;;; ─── States ────────────────────────────────────────────────

;; The vocabulary herdr defines. `release` is the teardown call rather than a
;; state, and is kept in the same map so the argv builder has one shape.
(def states #{"idle" "working" "blocked" "release"})

(defn env-get [k] (aget js/process.env k))

(defn detect-herdr
  "The herdr argv template when running inside a herdr pane, else nil.

   Both variables are required: HERDR_BIN_PATH says what to run and
   HERDR_PANE_ID says which pane to report for. One without the other is not a
   herdr session and must not be guessed at."
  [agent-name]
  (let [bin  (env-get "HERDR_BIN_PATH")
        pane (env-get "HERDR_PANE_ID")]
    (when (and bin pane (seq (str bin)) (seq (str pane)))
      {:release [(str bin) "pane" "release-agent" (str pane)
                 "--source" (str "custom:" agent-name) "--agent" agent-name]
       :report  [(str bin) "pane" "report-agent" (str pane)
                 "--source" (str "custom:" agent-name) "--agent" agent-name
                 "--state" "{state}" "--seq" "{seq}"]})))

(defn expand
  "Substitute {state} / {message} / {seq} / {agent} in an argv template.

   Arguments whose placeholder resolves to nothing are dropped along with the
   flag before them, so a missing --message does not become an empty argument
   the receiving CLI has to reject."
  [argv subs]
  (let [resolve-one
        (fn [a]
          (reduce (fn [s [k v]] (str/replace s (str "{" (name k) "}") (str (or v ""))))
                  (str a) subs))
        expanded (mapv (fn [a] [(str a) (resolve-one a)]) argv)]
    (->> expanded
         (reduce (fn [acc [raw done]]
                   ;; Drop a flag/value pair when the value came out empty.
                   (if (and (re-find #"\{[a-z]+\}" raw) (str/blank? done))
                     (if (str/starts-with? (str (last acc)) "--") (vec (butlast acc)) acc)
                     (conj acc done)))
                 [])
         vec)))

;;; ─── Config ────────────────────────────────────────────────

(defn resolve-command
  "The argv templates to use: explicit settings first, then auto-detection,
   else nil — and nil means this extension does nothing at all."
  [config agent-name]
  (let [cmd (:command config)]
    (cond
      (and (sequential? cmd) (seq cmd)) {:report (vec cmd) :release nil}
      (false? (:enabled config))        nil
      :else                             (detect-herdr agent-name))))

;;; ─── Activation ────────────────────────────────────────────

(defn ^:export activate [api]
  (let [config     (.settings api "agent-state")
        agent-name (or (:agent config) "nyma")
        templates  (resolve-command config agent-name)]
    (if-not templates
      ;; Nothing to report to. No handlers, no cost, no spawned processes.
      (fn [] nil)
      (let [seq*    (atom 0)
            ;; session_shutdown, session_end and the deactivate thunk all mean
            ;; "we are going away", and all three fire on a normal exit. Report
            ;; the release once — three spawns to say one thing is noise in the
            ;; supervisor's log and three processes at the moment of teardown.
            released? (atom false)
            ;; herdr accepts an optional --seq so it can discard a report that
            ;; arrives after a newer one; state transitions are async here, so
            ;; without it a slow `working` could land after `idle`.
            report!
            (fn [state & [message]]
              (try
                (let [tmpl (if (= state "release")
                             (or (:release templates) (:report templates))
                             (:report templates))
                      argv (expand tmpl {:state   (when (not= state "release") state)
                                         :message message
                                         :seq     (swap! seq* inc)
                                         :agent   agent-name})]
                  (when (seq argv)
                    ;; Fire and forget. A supervisor that is gone, slow, or
                    ;; broken must never stall or fail a turn.
                    (.spawn api (first argv) (clj->js (vec (rest argv)))
                            #js {:stdout "ignore" :stderr "ignore"})))
                (catch :default e
                  (d/debug "[agent-state] report failed" (str e))
                  nil)))

            on-working (fn [_] (report! "working") nil)
            on-idle    (fn [_] (report! "idle") nil)
            on-blocked (fn [data]
                         (report! "blocked"
                                  (or (some-> (aget data "toolName") str)
                                      "waiting for approval"))
                         nil)
            release!   (fn [] (when (compare-and-set! released? false true)
                                (report! "release")))
            on-release (fn [_] (release!) nil)]

        (.on api "agent_start"       on-working)
        (.on api "agent_end"         on-idle)
        (.on api "permission_request" on-blocked)
        (.on api "session_shutdown"  on-release)
        (.on api "session_end"       on-release)

        ;; Ready and waiting, before the first prompt.
        (report! "idle")

        (fn []
          (release!)
          (doseq [[e h] [["agent_start" on-working] ["agent_end" on-idle]
                         ["permission_request" on-blocked]
                         ["session_shutdown" on-release] ["session_end" on-release]]]
            (try (.off api e h) (catch :default _ nil))))))))

(def ^:export default activate)
