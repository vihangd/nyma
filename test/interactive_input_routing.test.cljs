(ns interactive-input-routing.test
  "The `input` interception contract, exercised against the real event bus.

   `agent_shell/input_router` subscribes to `input` to take a turn over and
   forward it to an ACP agent. Nothing emitted that event — `interactive.cljs`
   emitted `input_submit` (a different name, a different payload key, and
   fire-and-forget), so typing at a connected ACP agent silently fell through
   to nyma's own model.

   These lock the four behaviours the submit path depends on. The wiring in
   `interactive.cljs` is UI code and not directly loadable here, so the tests
   mirror its decision function exactly — see `submit!` below."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.interactive :as interactive]
            [clojure.string :as str]
            [agent.events :refer [create-event-bus]]))

;; Mirrors interactive.cljs on-submit: gate on handler-count so the common
;; case (nobody subscribed) stays synchronous, then honour :handle.
(defn- submit!
  "Returns a promise of :dispatched | :routed. `log` records what ran."
  [events log trimmed]
  (let [dispatch! (fn [] (swap! log conj :dispatched) :dispatched)
        route!    (fn [res]
                    (swap! log conj :routed)
                    (when-let [sub (get res "subscribe")] (sub identity))
                    :routed)]
    (if (zero? ((:handler-count events) "input"))
      (js/Promise.resolve (dispatch!))
      (-> ((:emit-collect events) "input" #js {:input trimmed})
          (.then (fn [res] (if (and res (get res "handle")) (route! res) (dispatch!))))
          (.catch (fn [_e] (dispatch!)))))))

(defn ^:async test-no-handlers []
  (let [events (create-event-bus) log (atom [])]
    ;; The property that makes this safe to land in the core input path: with
    ;; nothing subscribed, submit behaves exactly as it did before.
    (-> (expect ((:handler-count events) "input")) (.toBe 0))
    (let [r (js-await (submit! events log "hello"))]
      (-> (expect r) (.toBe "dispatched"))
      (-> (expect @log) (.toEqual #js ["dispatched"])))))

(defn ^:async test-handler-claims []
  (let [events (create-event-bus) log (atom []) subscribed (atom false)]
    ((:on events) "input"
                  (fn [data]
                    (-> (expect (.-input data)) (.toBe "do the thing"))
                    #js {:handle true :streaming true
                         :subscribe (fn [_set-messages]
                                      (reset! subscribed true)
                                      (js/Promise.resolve nil))}))
    (let [r (js-await (submit! events log "do the thing"))]
      (-> (expect r) (.toBe "routed"))
      (-> (expect @subscribed) (.toBe true))
      ;; nyma's own loop must NOT also run the prompt
      (-> (expect @log) (.toEqual #js ["routed"])))))

(defn ^:async test-handler-declines []
  (let [events (create-event-bus) log (atom [])]
    ;; input_router returns nil for a single-slash command, which is how
    ;; /model still reaches nyma's own command handler while //model routes.
    ((:on events) "input" (fn [_data] nil))
    (let [r (js-await (submit! events log "/model"))]
      (-> (expect r) (.toBe "dispatched"))
      (-> (expect @log) (.toEqual #js ["dispatched"])))))

(defn ^:async test-handler-throws []
  (let [events (create-event-bus) log (atom [])]
    ;; A broken router must not swallow the user's input.
    ((:on events) "input" (fn [_data] (throw (js/Error. "router exploded"))))
    (let [r (js-await (submit! events log "hello"))]
      (-> (expect r) (.toBe "dispatched"))
      (-> (expect @log) (.toEqual #js ["dispatched"])))))

(defn ^:async test-payload-key []
  (let [events (create-event-bus) seen (atom nil)]
    ;; The bug in miniature: the router reads :input, and interactive.cljs was
    ;; only ever emitting {:text} on a different event name.
    ((:on events) "input" (fn [data] (reset! seen data) nil))
    (js-await (submit! events (atom []) "payload check"))
    (-> (expect (.-input @seen)) (.toBe "payload check"))
    (-> (expect (.-text @seen)) (.toBeUndefined))))

(describe "input interception contract" (fn []
                                          (it "stays synchronous and dispatches locally when nothing subscribes" test-no-handlers)
                                          (it "routes to the handler and skips local dispatch when it claims the turn" test-handler-claims)
                                          (it "falls through to local dispatch when the handler declines" test-handler-declines)
                                          (it "falls through when the handler throws" test-handler-throws)
                                          (it "delivers the text under :input, not :text" test-payload-key)))

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

;;; ─── Slash commands in the transcript ──────────────────────────────────────
;;
;; The slash branch of dispatch-submit! was the only one that did not echo:
;; @streaming and !cmd/!!cmd both call add-user-msg!, it called only
;; addToHistory. So a command's output appeared with no visible cause, and a
;; session in which several had been run rendered as if nothing had happened.
;; And run-command! was a bare `when-let`, so an unrecognised command did
;; nothing whatsoever — the same on screen as one that ran silently.

(defn- echo-msg
  "Mirrors the echo dispatch-submit! now appends for a slash command."
  [trimmed]
  {:role "user" :content trimmed :local-only true})

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

(describe "slash commands are visible but not fed to the model" (fn []

  (it "echoes the typed command"
      (fn []
        (-> (expect (:content (echo-msg "/spec import foo --run")))
            (.toBe "/spec import foo --run"))))

  (it "tags the echo :local-only so build-context drops it"
      (fn []
        ;; The model did not run the command and the handler reports its own
        ;; result; feeding the raw line back would just be noise in context.
        (-> (expect (:local-only (echo-msg "/spec"))) (.toBe true))))

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
;; is the missing verb, and this exercises the REAL handler factory — not a
;; copy of it. The copy this replaced pinned "declines while locked", which is
;; exactly the behaviour that made prompt-template commands and
;; `/spec import --run` vanish: they emit from inside a slash handler, while
;; the dispatcher still holds the lock.

(defn- req-state [locked]
  (let [st {:locked? (atom locked) :dispatched (atom []) :echoed (atom []) :queue (atom [])}]
    (assoc st :handler
           (interactive/make-turn-request-handler
            {:locked?     (:locked? st)
             :dispatch!   (fn [t] (swap! (:dispatched st) conj t))
             :echo!       (fn [t] (swap! (:echoed st) conj t))
             :schedule    (fn [f] (swap! (:queue st) conj f))
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
