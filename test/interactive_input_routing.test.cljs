(ns interactive-input-routing.test
  "The TUI's submit path, exercised through the REAL factories in
   `agent.modes.interactive` with fake dependencies.

   This file used to mirror the dispatch logic: a copy of the decision
   function, tested in isolation. Every test passed while the code under it
   diverged — that is how a `turn_request` dropped under the submit lock
   shipped. `make-submit-handler`, `make-submit-dispatcher`,
   `make-interrupt-handler` and `make-turn-request-handler` take their
   dependencies as a map, so what runs here is what runs in `start`."
  (:require ["bun:test" :refer [describe it expect]]
            ["@earendil-works/pi-tui" :refer [matchesKey]]
            [agent.modes.interactive :as interactive]
            [clojure.string :as str]
            [agent.events :refer [create-event-bus]]))

(defn- flush!
  "One macrotask, so every settled .then/.catch/.finally has run."
  []
  (js/Promise. (fn [res] (js/setTimeout res 0))))

;;; ─── input interception ────────────────────────────────────────────────────
;;
;; `agent_shell/input_router` subscribes to `input` to take a turn over and
;; forward it to an ACP agent. Nothing emitted that event — the submit path
;; emitted `input_submit` (a different name, a different payload key, and
;; fire-and-forget), so typing at a connected ACP agent silently fell through
;; to nyma's own model.

(defn- submit-state []
  (let [events (create-event-bus)
        log    (atom [])]
    {:events events
     :log    log
     :submit (interactive/make-submit-handler
              {:events     events
               :dispatch!  (fn [t] (swap! log conj [:dispatched t]))
               :route!     (fn [res t]
                             (swap! log conj [:routed t])
                             (when-let [sub (get res "subscribe")] (sub identity)))
               :add-error! (fn [e] (swap! log conj [:error (.-message e)]))})}))

(defn ^:async test-no-handlers []
  (let [{:keys [events log submit]} (submit-state)]
    ;; The property that makes this safe to land in the core input path: with
    ;; nothing subscribed, submit behaves exactly as it did before.
    (-> (expect ((:handler-count events) "input")) (.toBe 0))
    (submit "  hello  ")
    (-> (expect @log) (.toEqual #js [#js ["dispatched" "hello"]]))))

(defn ^:async test-handler-claims []
  (let [{:keys [events log submit]} (submit-state)
        subscribed (atom false)]
    ((:on events) "input"
                  (fn [data]
                    (-> (expect (.-input data)) (.toBe "do the thing"))
                    #js {:handle true :streaming true
                         :subscribe (fn [_set-messages]
                                      (reset! subscribed true)
                                      (js/Promise.resolve nil))}))
    (js-await (submit "do the thing"))
    (-> (expect @subscribed) (.toBe true))
    ;; nyma's own loop must NOT also run the prompt
    (-> (expect @log) (.toEqual #js [#js ["routed" "do the thing"]]))))

(defn ^:async test-handler-declines []
  (let [{:keys [events log submit]} (submit-state)]
    ;; input_router returns nil for a single-slash command, which is how
    ;; /model still reaches nyma's own command handler while //model routes.
    ((:on events) "input" (fn [_data] nil))
    (js-await (submit "/model"))
    (-> (expect @log) (.toEqual #js [#js ["dispatched" "/model"]]))))

(defn ^:async test-handler-throws []
  (let [{:keys [events log submit]} (submit-state)]
    ;; A broken router must not swallow the user's input.
    ((:on events) "input" (fn [_data] (throw (js/Error. "router exploded"))))
    (js-await (submit "hello"))
    (-> (expect (last @log)) (.toEqual #js ["dispatched" "hello"]))))

(defn ^:async test-payload-key []
  (let [{:keys [events submit]} (submit-state)
        seen (atom nil)]
    ;; The bug in miniature: the router reads :input, and interactive.cljs was
    ;; only ever emitting {:text} on a different event name.
    ((:on events) "input" (fn [data] (reset! seen data) nil))
    (js-await (submit "payload check"))
    (-> (expect (.-input @seen)) (.toBe "payload check"))
    (-> (expect (.-text @seen)) (.toBeUndefined))))

(describe "input interception contract" (fn []
                                          (it "stays synchronous and dispatches locally when nothing subscribes" test-no-handlers)
                                          (it "routes to the handler and skips local dispatch when it claims the turn" test-handler-claims)
                                          (it "falls through to local dispatch when the handler declines" test-handler-declines)
                                          (it "falls through when the handler throws" test-handler-throws)
                                          (it "delivers the text under :input, not :text" test-payload-key)
                                          (it "ignores a blank line"
                                              (fn []
                                                (let [{:keys [log submit]} (submit-state)]
                                                  (submit "   ")
                                                  (-> (expect (count @log)) (.toBe 0)))))))

;;; ─── the submit dispatcher ─────────────────────────────────────────────────

(defn- dispatcher
  "The real dispatcher over recording fakes. `overrides` replaces any dep.
   `:log` is every side effect in order; `:msgs` the pane; `:context` what
   was injected into the model's context."
  ([] (dispatcher {}))
  ([overrides]
   (let [st   {:locked?   (atom false)
               :streaming (atom false)
               :log       (atom [])
               :msgs      (atom [])
               :context   (atom [])
               :history   (atom [])
               :abort     (atom nil)}
         log! (fn [k v] (swap! (:log st) conj [k v]))
         deps (merge
               {:locked?          (:locked? st)
                :streaming?       (:streaming st)
                :editor           #js {:addToHistory (fn [t] (swap! (:history st) conj t))}
                :abort-controller (:abort st)
                :add-user-msg!    (fn [t] (swap! (:msgs st) conj {:role "user" :content t}))
                :add-local-msg!   (fn [t] (swap! (:msgs st) conj {:role "user" :content t :local-only true}))
                :add-msg!         (fn [m] (swap! (:msgs st) conj m))
                :add-error!       (fn [e] (swap! (:msgs st) conj {:role "error" :content (.-message e)}))
                :append-context!  (fn [t] (swap! (:context st) conj t))
                :set-busy!        (fn [label] (log! :busy label))
                :set-streaming!   (fn [on?]
                                    (reset! (:streaming st) (boolean on?))
                                    (log! :streaming (boolean on?)))
                :sync-status!     (fn [] nil)
                :sync-pane!       (fn [] nil)
                :on-turn-done!    (fn [] (log! :turn-done true))
                :run!             (fn [t] (log! :run t) (js/Promise.resolve nil))
                :steer!           (fn [t] (log! :steer t))
                :run-command!     (fn [t] (log! :command t) nil)
                :exec-bash!       (fn [c] (log! :bash c) (js/Promise.resolve {:stdout "out" :exit-code 0}))
                :eval-expr!       (fn [e] (log! :eval e) (js/Promise.resolve {:stdout "42" :exit-code 0}))
                :expand-mentions  (fn [t] (str t " <expanded>"))}
               overrides)]
     (assoc st :dispatch! (interactive/make-submit-dispatcher deps)))))

(defn- calls [st k] (->> @(:log st) (filter (fn [[kk _]] (= kk k))) (mapv second)))
(defn- roles [st] (mapv :role @(:msgs st)))

;; A promise the test settles by hand, so the lock can be inspected mid-run.
(defn- deferred []
  (let [h (atom nil)
        p (js/Promise. (fn [res rej] (reset! h {:resolve res :reject rej})))]
    (assoc @h :promise p)))

(defn ^:async test-plain-run []
  (let [d  (deferred)
        st (dispatcher {:run! (fn [_] (:promise d))})]
    ((:dispatch! st) "look at @file")
    ;; What the model sees is what the pane shows.
    (-> (expect (mapv :content @(:msgs st))) (.toEqual #js ["look at @file <expanded>"]))
    (-> (expect @(:history st)) (.toEqual #js ["look at @file"]))
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe true))
    (-> (expect @(:streaming st)) (.toBe true))
    ((:resolve d) nil)
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe false))
    (-> (expect @(:streaming st)) (.toBe false))
    (-> (expect (calls st :turn-done)) (.toEqual #js [true]))))

(defn ^:async test-plain-run-rejected []
  (let [st (dispatcher {:run! (fn [_] (js/Promise.reject (js/Error. "provider down")))})]
    ((:dispatch! st) "hello")
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe false))
    (-> (expect @(:streaming st)) (.toBe false))
    (-> (expect (roles st)) (.toEqual #js ["user" "error"]))
    (-> (expect (:content (last @(:msgs st)))) (.toBe "provider down"))))

(defn ^:async test-plain-run-expands-only-plain []
  ;; Steer, `/`, `!` and `$` keep their text verbatim.
  (let [st (dispatcher)]
    (reset! (:streaming st) true)
    ((:dispatch! st) "steer @x")
    (reset! (:streaming st) false)
    ((:dispatch! st) "!echo @x")
    (js-await (flush!))
    (-> (expect (calls st :steer)) (.toEqual #js ["steer @x"]))
    (-> (expect (calls st :bash)) (.toEqual #js ["echo @x"]))))

(defn ^:async test-steer []
  (let [st (dispatcher)]
    (reset! (:streaming st) true)
    ((:dispatch! st) "also do this")
    (js-await (flush!))
    (-> (expect (calls st :steer)) (.toEqual #js ["also do this"]))
    (-> (expect (:content (last @(:msgs st)))) (.toBe "also do this ↩"))
    ;; A steer joins the running turn; it never starts one or takes the lock.
    (-> (expect (calls st :run)) (.toEqual #js []))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-slash-success []
  (let [d   (deferred)
        ran (atom [])
        st  (dispatcher {:run-command! (fn [t] (swap! ran conj t) (:promise d))})]
    ((:dispatch! st) "/spec import foo --run")
    (-> (expect @(:locked? st)) (.toBe true))
    (-> (expect (calls st :busy)) (.toEqual #js ["spec"]))
    ;; Echoed, and out of the model's context.
    (-> (expect (last @(:msgs st)))
        (.toEqual {:role "user" :content "/spec import foo --run" :local-only true}))
    ;; A slash command is busy, not streaming — streaming would flip steer routing.
    (-> (expect @(:streaming st)) (.toBe false))
    ;; The handler runs inside .then, so a synchronous throw joins the
    ;; rejection path; the lock stays held for as long as it runs.
    (js-await (flush!))
    (-> (expect @ran) (.toEqual #js ["/spec import foo --run"]))
    (-> (expect @(:locked? st)) (.toBe true))
    ((:resolve d) nil)
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe false))
    (-> (expect (calls st :busy)) (.toEqual #js ["spec" nil]))
    (-> (expect (roles st)) (.toEqual #js ["user"]))))

(defn ^:async test-slash-rejected []
  (let [st (dispatcher {:run-command! (fn [_] (js/Promise.reject (js/Error. "no such spec")))})]
    ((:dispatch! st) "/spec import nope")
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe false))
    (-> (expect (calls st :busy)) (.toEqual #js ["spec" nil]))
    (-> (expect (:content (last @(:msgs st)))) (.toBe "/spec failed: no such spec"))))

(defn ^:async test-slash-sync-throw []
  ;; A handler that throws before returning a promise takes the same path.
  (let [st (dispatcher {:run-command! (fn [_] (throw (js/Error. "boom")))})]
    ((:dispatch! st) "/theme")
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe false))
    (-> (expect (:content (last @(:msgs st)))) (.toBe "/theme failed: boom"))))

(defn ^:async test-slash-refused-while-locked []
  (let [st (dispatcher)]
    (reset! (:locked? st) true)
    ((:dispatch! st) "/model")
    (js-await (flush!))
    (-> (expect (calls st :command)) (.toEqual #js []))
    (-> (expect (count @(:msgs st))) (.toBe 0))))

(def ^:private ESC (js/String.fromCharCode 27))

(defn ^:async test-escape-aborts-slash []
  (let [d     (deferred)
        stale (js/AbortController.)
        st    (dispatcher {:run-command! (fn [_] (:promise d))})]
    (reset! (:abort st) stale)
    ((:dispatch! st) "/spec import slow")
    ;; A fresh controller per command: the loop mints one per run, so the
    ;; previous one may already be dead.
    (-> (expect (identical? @(:abort st) stale)) (.toBe false))
    ;; The Esc listener's guard, with the dispatcher's own lock and no overlay.
    (-> (expect (interactive/abort-on-escape? ESC @(:locked? st) 0)) (.toBe true))
    (.abort @(:abort st) "user-interrupt")
    (-> (expect (.-aborted (.-signal @(:abort st)))) (.toBe true))
    ;; The handler saw ctx.signal fire and gave up; the lock still comes back.
    ((:reject d) (js/Error. "aborted"))
    (js-await (flush!))
    (-> (expect @(:locked? st)) (.toBe false))
    ;; …and once idle, Esc is the editor's key again.
    (-> (expect (interactive/abort-on-escape? ESC @(:locked? st) 0)) (.toBe false))))

(defn ^:async test-bash-visible []
  (let [st (dispatcher)]
    ((:dispatch! st) "!ls")
    (-> (expect @(:locked? st)) (.toBe true))
    (js-await (flush!))
    (-> (expect (calls st :bash)) (.toEqual #js ["ls"]))
    (-> (expect (last @(:msgs st)))
        (.toEqual {:role "shell" :content "out" :local-only false}))
    ;; `!cmd` feeds the model; the typed line and the output, together.
    (-> (expect @(:context st)) (.toEqual #js ["!ls\nout"]))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-bash-hidden []
  (let [st (dispatcher)]
    ((:dispatch! st) "!!ls")
    (js-await (flush!))
    (-> (expect (:local-only (last @(:msgs st)))) (.toBe true))
    (-> (expect @(:context st)) (.toEqual #js []))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-bash-blocked []
  (let [st (dispatcher {:exec-bash! (fn [_] (js/Promise.resolve {:blocked? true :reason "rm -rf"}))})]
    ((:dispatch! st) "!rm -rf /")
    (js-await (flush!))
    (-> (expect (:role (last @(:msgs st)))) (.toBe "error"))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-bash-rejected []
  (let [st (dispatcher {:exec-bash! (fn [_] (js/Promise.reject (js/Error. "spawn failed")))})]
    ((:dispatch! st) "!ls")
    (js-await (flush!))
    (-> (expect (roles st)) (.toEqual #js ["user" "error"]))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-eval-visible []
  (let [st (dispatcher)]
    ((:dispatch! st) "$(+ 1 2)")
    (-> (expect @(:locked? st)) (.toBe true))
    (js-await (flush!))
    (-> (expect (calls st :eval)) (.toEqual #js ["(+ 1 2)"]))
    (-> (expect (last @(:msgs st)))
        (.toEqual {:role "shell" :content "42" :local-only false}))
    (-> (expect @(:context st)) (.toEqual #js ["$(+ 1 2)\n42"]))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-eval-hidden []
  (let [st (dispatcher)]
    ((:dispatch! st) "$$(+ 1 2)")
    (js-await (flush!))
    (-> (expect (:local-only (last @(:msgs st)))) (.toBe true))
    (-> (expect @(:context st)) (.toEqual #js []))))

(defn ^:async test-eval-blocked-not-injected []
  ;; A blocked expression never ran — do not tell the model it did.
  (let [st (dispatcher {:eval-expr! (fn [_] (js/Promise.resolve {:blocked? true :reason "shell-out"}))})]
    ((:dispatch! st) "$(sh \"rm\")")
    (js-await (flush!))
    (-> (expect (:role (last @(:msgs st)))) (.toBe "error"))
    (-> (expect @(:context st)) (.toEqual #js []))
    (-> (expect @(:locked? st)) (.toBe false))))

(defn ^:async test-side-channels-honour-lock []
  (let [st (dispatcher)]
    (reset! (:locked? st) true)
    ((:dispatch! st) "!ls")
    ((:dispatch! st) "$(+ 1 2)")
    ((:dispatch! st) "plain")
    (js-await (flush!))
    (-> (expect (count @(:log st))) (.toBe 0))
    (-> (expect (count @(:msgs st))) (.toBe 0))))

(describe "submit dispatcher" (fn []
                                (it "runs a plain message with mentions expanded, holding the lock for the run" test-plain-run)
                                (it "releases the lock and reports the error when the run rejects" test-plain-run-rejected)
                                (it "expands mentions only on the plain branch" test-plain-run-expands-only-plain)
                                (it "steers a running turn instead of starting one" test-steer)
                                (it "runs a slash command: lock, busy label, local-only echo, released in finally" test-slash-success)
                                (it "names the command when it rejects and still releases the lock" test-slash-rejected)
                                (it "treats a synchronous throw from a command like a rejection" test-slash-sync-throw)
                                (it "refuses a slash command while locked" test-slash-refused-while-locked)
                                (it "Escape aborts a running slash command's fresh controller and the lock comes back" test-escape-aborts-slash)
                                (it "!cmd runs the shell and feeds the model the output" test-bash-visible)
                                (it "!!cmd runs the shell and hides the output from the model" test-bash-hidden)
                                (it "reports a blocked !cmd as an error" test-bash-blocked)
                                (it "releases the lock when the shell fails to run" test-bash-rejected)
                                (it "$expr evaluates and feeds the model the value" test-eval-visible)
                                (it "$$expr evaluates and hides the value from the model" test-eval-hidden)
                                (it "does not tell the model a blocked $expr ran" test-eval-blocked-not-injected)
                                (it "!cmd, $expr and plain messages all honour the lock" test-side-channels-honour-lock)))

;;; ─── Ctrl-C ────────────────────────────────────────────────────────────────
;;
;; pi-tui runs stdin raw, so Ctrl-C reaches the TUI as a keypress and cli's
;; SIGINT handler — the one that already knew "first press interrupts" —
;; never fired. The TUI exited on the first press, losing the session the
;; reflex was trying to save.

(defn- interrupter [streaming?]
  (let [st  {:aborted (atom 0) :notes (atom []) :exited (atom 0) :now (atom 1000)}]
    (assoc st :press!
           (interactive/make-interrupt-handler
            {:streaming? (fn [] streaming?)
             :abort!     (fn [] (swap! (:aborted st) inc))
             :notify!    (fn [t] (swap! (:notes st) conj t))
             :exit!      (fn [] (swap! (:exited st) inc))
             :now        (fn [] @(:now st))}))))

(describe "Ctrl-C" (fn []

                     (it "aborts the turn on the first press and says how to exit"
                         (fn []
                           (let [st (interrupter true)]
                             ((:press! st))
                             (-> (expect @(:aborted st)) (.toBe 1))
                             (-> (expect @(:exited st)) (.toBe 0))
                             (-> (expect (count @(:notes st))) (.toBe 1))
                             (-> (expect (.includes (first @(:notes st)) "again")) (.toBe true)))))

                     (it "exits on a second press inside the window"
                         (fn []
                           (let [st (interrupter true)]
                             ((:press! st))
                             (swap! (:now st) + 500)
                             ((:press! st))
                             (-> (expect @(:exited st)) (.toBe 1))
                             (-> (expect @(:aborted st)) (.toBe 1)))))

                     (it "re-arms once the window has passed"
                         (fn []
                           (let [st (interrupter true)]
                             ((:press! st))
                             (swap! (:now st) + 3000)
                             ((:press! st))
                             (-> (expect @(:aborted st)) (.toBe 2))
                             (-> (expect @(:exited st)) (.toBe 0)))))

                     (it "exits at once while idle"
                         (fn []
                           (let [st (interrupter false)]
                             ((:press! st))
                             (-> (expect @(:exited st)) (.toBe 1))
                             (-> (expect @(:aborted st)) (.toBe 0)))))

                     (it "is the key the listener matches"
                         (fn []
        ;; Guard the guard: the listener only calls the handler on ctrl+c.
                           (-> (expect (matchesKey (js/String.fromCharCode 3) "ctrl+c")) (.toBe true))))))

;;; ─── Command argument splitting ────────────────────────────────────────────
;;
;; `/spec analyze  <name>` (double space) reported the command's usage string
;; instead of running: splitting on a single " " yields an empty token, and the
;; handler read "" as its first argument. Affected every command, and the
;; symptom — "usage" despite having typed the argument — reads as the user's
;; mistake rather than the parser's.

(defn- split-cmd
  "Mirrors run-command!'s tokenizer in interactive.cljs."
  [text]
  (->> (str/split (str/trim (.slice text 1)) #"\s+")
       (remove (fn [p] (= "" p)))
       vec))

(describe "command argument splitting" (fn []

                                         (it "collapses a double space instead of emitting an empty argument"
                                             (fn []
                                               (-> (expect (split-cmd "/spec analyze  write-script-today-date"))
                                                   (.toEqual #js ["spec" "analyze" "write-script-today-date"]))))

                                         (it "tolerates leading, trailing and tab whitespace"
                                             (fn []
                                               (-> (expect (split-cmd "/spec  analyze foo"))
                                                   (.toEqual #js ["spec" "analyze" "foo"]))
                                               (-> (expect (split-cmd "/spec analyze foo "))
                                                   (.toEqual #js ["spec" "analyze" "foo"]))
                                               (-> (expect (split-cmd "/spec\tanalyze\tfoo"))
                                                   (.toEqual #js ["spec" "analyze" "foo"]))))

                                         (it "still yields just the command when there are no arguments"
                                             (fn []
                                               (-> (expect (split-cmd "/spec")) (.toEqual #js ["spec"]))))))

;;; ─── Unknown slash commands ────────────────────────────────────────────────
;;
;; run-command! was a bare `when-let`, so an unrecognised command did nothing
;; whatsoever — the same on screen as one that ran silently. (The echo the
;; slash branch appends is covered by the dispatcher tests above.)

(defn- unknown-msg
  "Mirrors run-command!'s else branch: near misses by prefix, either way."
  [commands cmd]
  (let [near (->> commands (map str)
                  (filter (fn [n] (or (.startsWith n (str cmd))
                                      (.startsWith (str cmd) n))))
                  sort (take 5))]
    {:role "error" :local-only true
     :content (str "Unknown command: /" cmd
                   (when (seq near)
                     (str "\nDid you mean: " (str/join ", " (map #(str "/" %) near))))
                   "\nRun /help to list commands.")}))

(describe "unknown slash commands" (fn []

                                     (it "names an unknown command instead of doing nothing"
                                         (fn []
                                           (let [m (unknown-msg ["spec" "model" "help"] "spce")]
                                             (-> (expect (.includes (:content m) "Unknown command: /spce")) (.toBe true))
                                             (-> (expect (:role m)) (.toBe "error")))))

                                     (it "suggests near misses in both directions"
                                         (fn []
        ;; typed a prefix of a real command …
                                           (-> (expect (.includes (:content (unknown-msg ["spec" "model"] "spe"))
                                                                  "/spec"))
                                               (.toBe true))
        ;; … or overshot one
                                           (-> (expect (.includes (:content (unknown-msg ["spec" "model"] "specx"))
                                                                  "/spec"))
                                               (.toBe true))))

                                     (it "still points at /help when nothing is close"
                                         (fn []
                                           (let [c (:content (unknown-msg ["spec" "model"] "zzz"))]
                                             (-> (expect (.includes c "Did you mean")) (.toBe false))
                                             (-> (expect (.includes c "/help")) (.toBe true)))))))

;;; ─── turn_request ──────────────────────────────────────────────────────────
;;
;; `sendUserMessage` only queues: `steer` needs a run already in flight,
;; `followUp` drains at a turn boundary. Neither BEGINS a turn. `turn_request`
;; is the missing verb. The copy this replaced pinned "declines while locked",
;; which is exactly the behaviour that made prompt-template commands and
;; `/spec import --run` vanish: they emit from inside a slash handler, while
;; the dispatcher still holds the lock.

(defn- req-state [locked & [fallback!]]
  (let [st {:locked? (atom locked) :dispatched (atom []) :echoed (atom []) :queue (atom [])}]
    (assoc st :handler
           (interactive/make-turn-request-handler
            {:locked?     (:locked? st)
             :dispatch!   (fn [t] (swap! (:dispatched st) conj t))
             :echo!       (fn [t] (swap! (:echoed st) conj t))
             :schedule    (fn [f] (swap! (:queue st) conj f))
             :fallback!   fallback!
             :max-retries 3}))))

(defn- run-queue! [st]
  (let [fs @(:queue st)] (reset! (:queue st) []) (doseq [f fs] (f))))

(describe "turn_request" (fn []

                           (it "dispatches the requested text"
                               (fn []
                                 (let [st (req-state false)]
                                   ((:handler st) #js {:text "do the next task"})
                                   (-> (expect @(:dispatched st)) (.toEqual #js ["do the next task"])))))

                           (it "waits out a held lock and dispatches once it is released"
                               (fn []
                                 (let [st (req-state true)]
                                   ((:handler st) #js {:text "go" :echo true})
                                   (-> (expect (count @(:dispatched st))) (.toBe 0))
                                   (-> (expect (count @(:queue st))) (.toBe 1))
                                   (reset! (:locked? st) false)
                                   (run-queue! st)
                                   (-> (expect @(:dispatched st)) (.toEqual #js ["go"]))
                                   (-> (expect @(:echoed st)) (.toEqual #js ["go"])))))

                           (it "gives up after max-retries instead of spinning on a stuck lock"
                               (fn []
                                 (let [st (req-state true)]
                                   ((:handler st) #js {:text "go"})
                                   (dotimes [_ 5] (run-queue! st))
                                   (-> (expect (count @(:dispatched st))) (.toBe 0))
                                   (-> (expect (count @(:queue st))) (.toBe 0)))))

                           (it "queues the text as a follow-up when it gives up, so the turn still runs"
                               (fn []
                                 (let [queued (atom [])
                                       st     (req-state true (fn [t] (swap! queued conj t)))]
                                   ((:handler st) #js {:text "go"})
                                   (dotimes [_ 5] (run-queue! st))
                                   (-> (expect (count @(:dispatched st))) (.toBe 0))
                                   (-> (expect @queued) (.toEqual #js ["go"])))))

                           (it "ignores empty and whitespace-only requests"
                               (fn []
                                 (let [st (req-state false)]
                                   ((:handler st) #js {:text "   "})
                                   ((:handler st) #js {:text ""})
                                   ((:handler st) #js {})
                                   (-> (expect (count @(:dispatched st))) (.toBe 0)))))

                           (it "echoes only when asked"
                               (fn []
        ;; The loop's continue-prompt is machinery, not something the user
        ;; typed; echoing it every turn would bury the actual work.
                                 (let [st (req-state false)]
                                   ((:handler st) #js {:text "internal prompt"})
                                   (-> (expect (count @(:echoed st))) (.toBe 0)))
                                 (let [st (req-state false)]
                                   ((:handler st) #js {:text "visible" :echo true})
                                   (-> (expect @(:echoed st)) (.toEqual #js ["visible"])))))))
