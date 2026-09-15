(ns agent.modes.interactive
  "Pi-tui based interactive mode."
  (:require [agent.model-info :as model-info]
            ["@earendil-works/pi-tui" :refer [TuiMainScreen ProcessTerminal Editor
                                              CombinedAutocompleteProvider
                                              matchesKey]]
            [agent.loop :refer [run steer follow-up run-turn-with-update-handler]]
            [agent.commands.resolver :refer [resolve-command split-skill-invocation]]
            [agent.commands.parser :as parser]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.theme-catalog :as theme-catalog]
            [agent.ui.picker-frame :as picker-frame]
            [agent.ui.chat-pane :refer [create-chat-pane]]
            [agent.ui.status-bar :refer [create-status-bar]]
            [agent.ui.app-reducers :as reducers]
            [agent.ui.editor-bash :as editor-bash]
            [agent.ui.editor-eval :as editor-eval]
            [agent.ui.file-mentions :as file-mentions]
            [agent.ui.overlay-host :as overlay-host]
            [agent.ui.width-guard :refer [attach-guarded-children!]]
            [agent.ui.crash-recovery :as crash-recovery]
            [agent.sessions.manager :refer [session->seed-messages]]
            [agent.keybindings :as keybindings]
            [agent.keybinding-registry :as kbr]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Pure helpers — used by `start` below and by test/interactive_helpers.test.cljs
;;;
;;; The factories (make-turn-request-handler, make-submit-handler,
;;; make-submit-dispatcher, make-interrupt-handler) take their dependencies as a map so
;;; test/interactive_input_routing.test.cljs runs the real branches with
;;; fakes — `start` builds them from its locals and wires the result.
;;; ---------------------------------------------------------------------------

(defn alt-screen-enabled?
  [env-value]
  (boolean (and env-value
                (not= env-value "")
                (not= env-value "0")
                (not= env-value "false"))))

(defn abort-on-escape?
  "Should a global Escape abort the in-flight run?

   Only while a submit is active — otherwise Esc is the editor's and the
   pickers' own key — and only when NO overlay is open. Input listeners run
   before focus dispatch (pi-tui tui.js `handleTerminalInput`), so without the overlay check
   a single Esc did two things: killed the turn AND dismissed the picker. A
   permission prompt is shown precisely while a submit is in flight, so
   cancelling the prompt aborted the very run it was asking about.

   Pure so it can be tested without a TUI."
  [data submitting? overlay-count]
  (boolean (and (matchesKey data "escape")
                submitting?
                (zero? (or overlay-count 0)))))

(def ^:private notify-levels
  "The notify levels a `ui.notify(msg, type)` call may name, mapped to the
   transcript role that renders them. Anything unknown is info — an
   unrecognised role would fall through chat-renderer's generic branch and
   print as `whatever: text`."
  {"info" "info" "warn" "warn" "warning" "warn"
   "error" "error" "success" "success"})

(defn notify-role
  "Transcript role for a `ui.notify` level. `notify` used to drop its second
   argument entirely, so an extension reporting a failure and one reporting
   progress produced the identical cyan info line."
  [level]
  (or (get notify-levels (str/lower-case (str (or level "info")))) "info"))

(defn classify-error-message
  "A provider error, plus the one thing the user can do about it.

   The raw message comes first — it is the only text that identifies WHICH
   call failed — and the hint is appended. `retrying?` says whether the run
   loop will try again on its own; the AI SDK exhausts its own retries before
   the error ever reaches the transcript, so the call site passes false.

   Pure: the classification is a substring match on the lower-cased message,
   first hit wins, in the order context length → auth → rate limit.

   That order, and the narrow auth patterns, are both deliberate. Anthropic
   returns a context overflow as an `invalid_request_error`, so a bare
   \"invalid\" test — or putting auth first — sends someone whose prompt is too
   long off to check their API key. Likewise \"rate\" alone matches
   \"generate\"."
  [msg & [retrying?]]
  (let [raw (str (or msg ""))
        m   (str/lower-case raw)
        has (fn [& subs] (boolean (some (fn [x] (.includes m x)) subs)))]
    (str raw
         (cond
           (has "context" "too long" "maximum" "context_length")
           " — run /compact"

           (has "401" "invalid api key" "invalid x-api-key" "invalid_api_key"
                "api key" "api_key" "unauthorized" "authentication")
           " — check ANTHROPIC_API_KEY or /login <provider>"

           (has "429" "rate limit" "rate_limit" "rate-limit")
           (if retrying? " — rate limited; retrying" " — try again")

           :else ""))))

(defn seed-prompt-of
  "The first-turn prompt cli hands `interactive/start` in `resources`, or nil.

   Read with `aget`, not `.-seed-prompt`: a hyphenated JS property name is
   exactly the squint trap that silently reads undefined. Both the kebab and
   the camel spelling are accepted so the CLI contract cannot be broken by a
   casing choice."
  [resources]
  (let [v (when resources
            (or (aget resources "seed-prompt") (aget resources "seedPrompt")))]
    (when (and (string? v) (pos? (count (.trim v)))) v)))

(defn submit-seed-prompt!
  "Put the seed prompt in the editor, submit it as the first user turn, then
   blank the editor. Returns the prompt, or nil when there was none.

   The blanking is not cosmetic: the Editor clears its own buffer as part of
   ITS submit path, and we are calling the handler directly — without this the
   seed text sits in the input box after the turn starts and the next Enter
   runs it a second time.

   Callbacks are injected so this is testable without a TUI."
  [resources {:keys [set-text submit]}]
  (when-let [seed (seed-prompt-of resources)]
    (when set-text (set-text seed))
    (when submit (submit seed))
    (when set-text (set-text ""))
    seed))

(defn command-name
  "`/spec import x --run` → \"spec\". The label the status line shows while a
   slash command runs, and the name a failure is reported under."
  [trimmed]
  (first (str/split (subs (str trimmed) 1) #"\s+")))

(defn make-turn-request-handler
  "Handler for `turn_request`: an extension (or a prompt-template command)
   asking to START a turn. Goes through `dispatch!` so the turn gets the same
   lock, streaming state and pane wiring a typed message does.

   The lock is the subtle part. A slash command runs with `locked?` held and
   releases it in its .finally, and a prompt template or `/spec import --run`
   emits the request FROM INSIDE that handler — so checking the lock once and
   dropping the request meant every such turn vanished with no message. While
   locked, `schedule` retries (50 ms in production; the test passes a queue),
   giving up after `max-retries` so a stuck lock cannot spin forever. Giving
   up hands the text to `fallback!` — the follow-up queue, so it still runs
   as the next turn instead of vanishing."
  [{:keys [locked? dispatch! echo! schedule max-retries fallback!]
    :or   {max-retries 600}}]
  (fn handle
    ([data] (handle data 0))
    ([data attempt]
     (let [text  (str (or (and data (.-text data)) ""))
           echo? (boolean (and data (.-echo data)))]
       (when (seq (.trim text))
         (cond
           (not @locked?)          (do (when echo? (echo! text))
                                       (dispatch! text))
           (< attempt max-retries) (schedule (fn [] (handle data (inc attempt))))
           fallback!               (fallback! text)
           :else                   nil))
       nil))))

(defn make-submit-dispatcher
  "The submit dispatcher: where a trimmed line goes once no `input` handler
   has claimed it. Five branches — steer, `/cmd`, `!cmd`/`!!cmd`,
   `$expr`/`$$expr`, plain prompt — each with its own lock and streaming
   rules. Built from a map of dependencies so the tests drive the REAL
   branches with fakes — a test that mirrors this function cannot catch it
   dropping a turn.

   `locked?` / `streaming?` are atoms. `run!` starts a turn and returns its
   promise; `run-command!` runs a slash line; `exec-bash!` / `eval-expr!`
   take the parsed command / expression and return a promise of the result
   map; `expand-mentions` maps typed text to what the model sees;
   `abort-controller` is the agent's controller atom (or nil); `add-msg!`
   appends a message map (it stamps the id); `append-context!` adds a user
   message to the model's context without showing it."
  [{:keys [locked? streaming? editor abort-controller
           add-user-msg! add-local-msg! add-msg! add-error! append-context!
           set-busy! set-streaming! sync-status! sync-pane! on-turn-done!
           run! steer! run-command! exec-bash! eval-expr! expand-mentions]}]
  (let [do-run!
        (fn [text]
          (set-streaming! true)
          (-> (js/Promise.resolve nil)
              (.then (fn [_] (run! text)))
              (.then (fn [_]
                       (set-streaming! false)
                       (on-turn-done!)
                       (reset! locked? false)
                       (sync-status!)
                       (sync-pane!)))
              (.catch (fn [e]
                        (add-error! e)
                        (set-streaming! false)
                        (reset! locked? false)
                        (sync-status!)))))

        ;; `!cmd` and `$expr` differ only in what runs, how a result is judged
        ;; and when it reaches the model; the lock, echo, output message and
        ;; release were the same 25 lines twice.
        side-channel!
        (fn [trimmed {:keys [exec format failed? hidden? inject?]}]
          (reset! locked? true)
          (.addToHistory editor trimmed)
          (add-user-msg! trimmed)
          (-> (exec)
              (.then (fn [result]
                       (let [content (format result)]
                         (add-msg! {:role       (if (failed? result) "error" "shell")
                                    :content    content
                                    :local-only hidden?})
                         (when (inject? result)
                           (append-context! (str trimmed "\n" content)))
                         (reset! locked? false)
                         (sync-pane!))))
              (.catch (fn [e]
                        (add-error! e)
                        (reset! locked? false)))))]
    (fn dispatch-submit! [trimmed]
      (cond
        ;; ── Steer: mid-stream follow-up ──────────────────────────
        @streaming?
        (do (.addToHistory editor trimmed)
            (steer! trimmed)
            (add-user-msg! (str trimmed " ↩")))

        ;; ── Slash command ────────────────────────────────────────
        (and (.startsWith trimmed "/") (not @locked?))
        (do (reset! locked? true)
            (.addToHistory editor trimmed)
            ;; Fresh controller: Escape aborts it while the lock is held
            ;; (a handler reads it as ctx.signal), and the loop only mints
            ;; one per run, so a previously-aborted one would arrive dead.
            (when abort-controller
              (reset! abort-controller (js/AbortController.)))
            (set-busy! (command-name trimmed))
            ;; Echo the command. Every other branch does; this one did not,
            ;; so a command's output — a notification, an import summary —
            ;; appeared with no visible cause, and a session in which
            ;; several had been run rendered as if it were empty.
            ;; :local-only, like `!!cmd`: on screen, out of the model's
            ;; context, since the model did not run it and the handler
            ;; already reports whatever it needs to.
            (add-local-msg! trimmed)
            ;; run-command! inside .then so SYNCHRONOUS throws are
            ;; absorbed into the same rejection path as async ones —
            ;; one .catch/.finally, one lock reset, nothing to drift.
            (-> (.then (js/Promise.resolve)
                       (fn [] (run-command! trimmed)))
                (.catch (fn [e]
                          (add-error! (js/Error. (str "/" (command-name trimmed) " failed: "
                                                      (or (.-message e) (str e)))))))
                (.finally (fn []
                            (set-busy! nil)
                            (reset! locked? false)))))

        ;; ── !cmd / !!cmd — shell exec ─────────────────────────────
        (and (not @locked?)
             (not= :not-bash (:kind (editor-bash/parse-bash-input trimmed))))
        (let [{:keys [kind command]} (editor-bash/parse-bash-input trimmed)]
          (side-channel! trimmed
                         {:exec    (fn [] (exec-bash! command))
                          :format  editor-bash/format-bash-output
                          :failed? (fn [r] (:blocked? r))
                          :hidden? (= kind :run-hidden)
                          ;; Inject into LLM context for !cmd (not !!cmd)
                          :inject? (fn [_] (= kind :run))}))

        ;; ── $expr / $$expr — Babashka eval ───────────────────────
        (and (not @locked?)
             (not= :not-eval (:kind (editor-eval/parse-eval-input trimmed))))
        (let [{:keys [kind expr]} (editor-eval/parse-eval-input trimmed)]
          (side-channel! trimmed
                         {:exec    (fn [] (eval-expr! expr))
                          :format  editor-eval/format-eval-output
                          :failed? (fn [r] (or (:unavailable? r) (:blocked? r)))
                          :hidden? (= kind :eval-hidden)
                          ;; A blocked expression never ran — do not tell
                          ;; the model it did.
                          :inject? (fn [r] (and (= kind :eval) (not (:blocked? r))))}))

        ;; ── Normal LLM prompt ────────────────────────────────────
        (not @locked?)
        ;; `@path` mentions expand here and nowhere else: steer, `/`, `!`
        ;; and `$` keep their text verbatim. The expanded text is what the
        ;; pane shows — it is what the model saw.
        (let [expanded (expand-mentions trimmed)]
          (reset! locked? true)
          (.addToHistory editor trimmed)
          (add-user-msg! expanded)
          (do-run! expanded))))))

(defn make-submit-handler
  "The editor's submit: trim, announce, then let an `input` handler intercept
   before `dispatch!` runs the line locally. Returns the handler `start`
   hands the editor (and the seed prompt).

   `input` is an INTERCEPTION hook, not a notification: a handler returns
   {handle, streaming, subscribe} to take the turn over, and `route!` gets
   that result and the text. emit-collect is async, so gate on handler-count
   — with nothing subscribed (the common case) submit stays exactly as
   synchronous as it was."
  [{:keys [events dispatch! route! add-error!]}]
  (fn on-submit [text]
    (let [trimmed (.trim (str text))]
      (when (pos? (count trimmed))
        ((:emit-async events) "input_submit" #js {:text trimmed})
        (if (zero? ((:handler-count events) "input"))
          (dispatch! trimmed)
          (-> ((:emit-collect events) "input" #js {:input trimmed})
              (.then (fn [res]
                       (if (and res (get res "handle"))
                         (route! res trimmed)
                         (dispatch! trimmed))))
              (.catch (fn [e]
                        ;; A broken router must not eat the user's input.
                        (add-error! e)
                        (dispatch! trimmed)))))))))

(def ^:private interrupt-window-ms
  "How long the first Ctrl-C's 'press again to exit' offer stands. The same
   window as cli's SIGINT handler."
  2000)

(defn make-interrupt-handler
  "Ctrl-C in the TUI: the first press during a turn aborts it and says how to
   exit; a second press inside the window, or any press while idle, exits.

   cli's SIGINT handler already decides this (`sigint-action`) — but pi-tui
   puts stdin in raw mode, so in the TUI Ctrl-C arrives as a keypress and
   SIGINT never fires. The TUI listener exited on the first press, full stop,
   which is exactly the reflex-kills-the-session bug the cli half was written
   to fix. Same rule here, on the TUI's own state.

   `streaming?` is a 0-arg fn; `abort!` cancels the run; `notify!` posts the
   re-arm line; `exit!` shuts down. Returns the 0-arg handler."
  [{:keys [streaming? abort! notify! exit! now window-ms]
    :or   {window-ms interrupt-window-ms}}]
  (let [now        (or now (fn [] (js/Date.now)))
        last-press (atom nil)]
    (fn interrupt! []
      (let [t (now)]
        (if (and (streaming?)
                 (or (nil? @last-press)
                     (> (- t @last-press) window-ms)))
          (do (reset! last-press t)
              (abort!)
              (notify! "interrupted — press Ctrl-C again to exit"))
          (exit!))))))

(defn mark-streaming!
  "Mirror the streaming flag into the agent's state atom.

   cli's SIGINT handler reads `:streaming?` there to tell whether a turn is in
   flight; the flag lived only in this mode's local atom, so the handler could
   never see one. No-op for a fake agent with no :state."
  [agent on?]
  (when-let [st (:state agent)]
    (swap! st assoc :streaming? (boolean on?)))
  (boolean on?))

(defn pane-messages
  "Renderable messages for `session`'s current branch, or [] when there is no
   session or nothing in it.

   Built with `session->seed-messages` — the same function that builds the
   MODEL's context — so the transcript and the context cannot disagree. The
   inline filter this replaced kept raw user/assistant entries and dropped
   `compaction` / `branch-summary`, which seed-messages folds into a summary
   message, so a compacted session rendered less than the model actually saw.

   Pure so it can be tested without a TUI."
  [session]
  (or (when session
        (when-let [build (:build-context session)]
          (vec (session->seed-messages (build)))))
      []))

(defn resumed-banner
  "The line a resumed session opens with, or nil for a fresh one.

   Starting nyma with -c / -r / --session repainted the previous
   conversation with nothing to say which session it was, how much of it
   there is, or what it has cost so far — the three things you check before
   typing into a session you left yesterday.

   `name` falls back to the file's basename, then to \"previous session\".
   Cost is printed only when the state carries one. Pure: takes values, not
   the agent."
  [{:keys [message-count name file-path total-cost]}]
  (when (pos? (or message-count 0))
    (let [label (or (when (seq (str (or name ""))) (str name))
                    (when (seq (str (or file-path "")))
                      (last (.split (str file-path) "/")))
                    "previous session")
          cost  (when (and (number? total-cost) (pos? total-cost))
                  (str " — $" (.toFixed total-cost 2) " so far"))]
      (str "Resumed " label " — " message-count
           (if (= 1 message-count) " message" " messages")
           (or cost "")))))

(defn session-start-clears?
  "Should a `session_start` blank the transcript instead of re-seeding it?

   Only for `/new`. Every other emitter — /resume, /import, /fork — changes
   which branch is current BEFORE it emits, so re-seeding shows what the user
   asked for. /new emits this without switching files, so re-seeding would
   repaint the conversation it was asked to clear. That is the whole reason
   this predicate exists rather than the handler simply always re-seeding."
  [reason]
  (= "new" (str (or reason ""))))

(defn make-session-start-handler
  "The `session_start` handler, built from callbacks so it can be tested
   without a TUI.

   `get-session` returns the CURRENT session — read at call time, not captured,
   because /resume and /import switch files before emitting. `on-messages`
   receives the messages to render; `on-clear` blanks the transcript."
  [{:keys [get-session on-messages on-clear]}]
  (fn [data]
    (if (session-start-clears? (and data (.-reason data)))
      (on-clear)
      (on-messages (pane-messages (get-session))))))

(defn pager-mode-enabled?
  [{:keys [alt-screen? scrollback-mode-setting]}]
  (boolean (and (not alt-screen?)
                (= scrollback-mode-setting "pager"))))

(defn effective-scrollback-on?
  [{:keys [alt-screen? scrollback-mode-setting]
    :as opts}]
  (boolean (and (not alt-screen?)
                (not (pager-mode-enabled? opts))
                (cond
                  (nil? scrollback-mode-setting) true
                  (= scrollback-mode-setting "pager") false
                  :else scrollback-mode-setting))))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn model-id
  "Label for the active model, or an em dash when there isn't one.

   This read `.-modelId` off `(or runtime m)` without checking it was there.
   Both are nil whenever model resolution failed — an unknown provider, a
   renamed one, a missing credential — and the status line then threw
   `TypeError: null is not an object` from sync-status!, taking the whole
   interactive session down. A provider that cannot be resolved is a message,
   not a crash. Exposed for tests."
  [agent]
  (let [config  (:config agent)
        ;; config.model is the whole story: setModel writes it, nothing else does.
        active  (some-> config .-model)]
    (cond
      (nil? active)    "–"
      (string? active) active
      :else            (str (or (.-modelId active) (.-id active) "–")))))

(defn- provider-name
  "Return the user-friendly provider label captured at setModel time
   (`anthropic`, `minimax`, `openrouter`, …). Empty string when nyma
   was started with a bare model id (no `provider/` prefix) or when
   the config is uninitialised. Status-bar consumers render
   `<provider>/<model>` only when this is non-empty."
  [agent]
  (let [config (:config agent)
        v      (and config (aget config "active-provider-name"))]
    (or v "")))

(defn- make-editor-theme
  "`border-atom` holds the current border colour so the prefix mode can
   change it live: `!` and `!!` (shell), `$` and `$$` (eval) used to look
   exactly like a prompt until Enter.

   `theme-fn` is a thunk: pi-tui's Editor takes its theme once at
   construction and has no setter, but every field is a function it calls at
   render, so reading the colour inside each one is what makes `/theme` reach
   the editor without a restart."
  [theme-fn & [border-atom]]
  (let [ESC   (js/String.fromCharCode 27)
        RESET (str ESC "[0m")
        BOLD  (str ESC "[1m")
        DIM   (str ESC "[2m")
        fg    (fn [hex]
                (let [r (js/parseInt (.slice hex 1 3) 16)
                      g (js/parseInt (.slice hex 3 5) 16)
                      b (js/parseInt (.slice hex 5 7) 16)]
                  (str ESC "[38;2;" r ";" g ";" b "m")))

        colour  (fn [k default] (get-in (theme-fn) [:colors k] default))
        primary (fn [] (colour :primary "#7aa2f7"))
        muted   (fn [] (colour :muted   "#565f89"))
        error-c (fn [] (colour :error   "#f7768e"))
        border  (fn [] (colour :border  "#3b4261"))]

    #js {:borderColor (fn [s] (str (fg (or (some-> border-atom deref) (border))) s RESET))
         :selectList  #js {:selectedPrefix (fn [s] (str (fg (primary)) BOLD s RESET))
                           :selectedText   (fn [s] (str (fg (primary)) BOLD s RESET))
                           :description    (fn [s] (str (fg (muted)) DIM s RESET))
                           :scrollInfo     (fn [s] (str (fg (muted)) DIM s RESET))
                           :noMatch        (fn [s] (str (fg (error-c)) s RESET))}}))

(defn slash-command-items
  "The entries the editor's slash picker shows, from a commands map.

   One vocabulary with `/help`: both go through `parser/visible-commands`
   (hidden and disabled commands are not offered) and
   `parser/compute-display-names` (so the picker says `/role`, not
   `/model-roles__role`). Only a command whose short name is ambiguous
   keeps its full `ns__name` key — the short form would be inert there,
   because `resolve-command` refuses an ambiguous suffix match.

   Display names are right-padded by `compute-display-names` for
   alignment inside a collision group; trimmed here, because this string
   is inserted into the editor and a trailing space breaks the next
   prefix match.

   Pure — takes the map, not the agent — so it is testable without a TUI."
  [commands]
  (let [visible  (parser/visible-commands commands)
        displays (parser/compute-display-names (vec (keys visible)))]
    (->> visible
         (map (fn [[name cmd]]
                {:name        (.trim (str (get displays name name)))
                 :description (or (:description cmd) "")}))
         (sort-by :name)
         vec)))

(defn- build-slash-commands [agent]
  (clj->js
   (map (fn [m] #js {:name (:name m) :description (:description m)})
        (slash-command-items @(:commands agent)))))

(defn unknown-command-text
  "The message an unrecognised `/cmd` gets. Suggestions come from
   `parser/command-suggestions+fuzzy` — prefix matches first, and an
   edit-distance ≤ 2 fallback so `/sepc` offers `/spec` — and are printed
   as DISPLAY names, the same names `/help` and the picker show.

   The hand-rolled prefix filter this replaces listed raw `ns__name`
   keys, offered hidden and disabled commands, and could not see a
   transposition at all."
  [cmd commands]
  (let [near (->> (parser/command-suggestions+fuzzy (str "/" cmd) commands)
                  (map (fn [m] (.trim (str (:display-name m)))))
                  distinct
                  (take 5))]
    (str "Unknown command: /" cmd
         (when (seq near)
           (str "\nDid you mean: " (str/join ", " (map #(str "/" %) near))))
         "\nRun /help to list commands.")))

;;; ---------------------------------------------------------------------------
;;; Command execution
;;; ---------------------------------------------------------------------------

(defn- run-command! [agent text update-messages!]
  ;; ONE argument splitter for every slash command: `parse-command-args`
  ;; understands double quotes and `--flag` / `--flag=value` / `--no-flag`.
  ;;
  ;; `args` is still every token, in order, exactly as the old whitespace
  ;; split produced it — the commands that hand-parse their own flags keep
  ;; working untouched. What is new is `ctx.flags` (also readable as
  ;; `(:flags ctx)`) and `ctx.positional`, which a migrated command reads
  ;; instead of pattern-matching on `--`.
  (let [after-cmd (.trim (.slice text 1))
        typed     (first (.split after-cmd #"\s+"))
        [cmd rest-text] (split-skill-invocation typed (.slice after-cmd (count (str typed))))
        parsed    (parser/parse-command-args rest-text)
        args      (:args parsed)
        commands  @(:commands agent)]
    (if-let [entry (resolve-command commands cmd)]
      (let [h (:handler entry)]
        (h args #js {:ui             (when-let [ext (.-extension-api agent)] (.-ui ext))
                     ;; Aborted by Escape while the command runs.
                     :signal         (when-let [c (:abort-controller agent)] (.-signal @c))
                     :agent          agent
                     :flags          (clj->js (:flags parsed))
                     :positional     (clj->js (:positional parsed))
                     :set-messages   update-messages!
                     :append-message (fn [msg]
                                       (update-messages!
                                        (fn [prev]
                                          (conj (vec prev) (assoc msg :id (new-id))))))}))
      ;; Was a bare `when-let`: an unrecognised command did nothing at all,
      ;; which is indistinguishable from one that ran and had nothing to say.
      (update-messages!
       (fn [prev]
         (conj (vec prev)
               {:role       "error"
                :id         (new-id)
                :local-only true
                :content    (unknown-command-text cmd commands)}))))))

;;; ---------------------------------------------------------------------------
;;; Entry point
;;; ---------------------------------------------------------------------------

(defn ^:async start [agent session resources]
  (let [theme     (or (.-theme resources)
                      (theme-catalog/active-theme (.-themes resources) default-dark))
        ;; Every consumer reads through this thunk rather than the map above,
        ;; so `/theme` and `/reload` (which write theme-catalog/current) swap
        ;; it under them. There is no second copy to fall out of step.
        _          (reset! theme-catalog/current theme)
        theme-fn   (fn [] @theme-catalog/current)
        terminal  (new ProcessTerminal)
        ;; pi-tui 0.85 split the concrete TUI class in two — `TUI` is now a
        ;; type-only export, with TuiMainScreen (scrollback, what nyma uses) and
        ;; TuiAltScreen (full-screen) as the implementations.
        tui       (new TuiMainScreen terminal)

        ;; ── Message + UI state ─────────────────────────────────────────────
        messages     (atom [])
        widgets      (atom {})   ;; widget-name → message-id
        streaming    (atom false)
        ;; When the current turn or slash command started; the status line's
        ;; elapsed clock derives from it at render, so the 100 ms tick moves it.
        stream-start (atom nil)
        ;; A slash command in flight: shows `<cmd>…` with the clock, without
        ;; setting `streaming` (which would change steer routing).
        busy         (atom false)
        cmd-label    (atom nil)
        turn-count   (atom 0)
        submit-lock  (atom false)

        chat-pane    (create-chat-pane theme-fn)
        status-bar   (create-status-bar theme-fn)

        sync-status! (fn []
                       (let [st @(:state agent)]
                         (.setState status-bar
                                    #js {:model      (model-id agent)
                                         :provider   (provider-name agent)
                                         :role       (let [r (str (or (:active-role st) ":default"))]
                                                       (if (.startsWith r ":") (.slice r 1) r))
                                         :streaming  @streaming
                                         :busy       @busy
                                         :verb       @cmd-label
                                         :streaming-since @stream-start
                                         :turn-count @turn-count
                                         ;; Once per turn (the store's :usage-updated
                                         ;; is per run); the provider's own input
                                         ;; count is the context fill, not the
                                         ;; tree-walking estimate.
                                         :cost-usd   (:total-cost st)
                                         :token-in   (:total-input-tokens st)
                                         :token-out  (:total-output-tokens st)
                                         :ctx-used   (:last-input-tokens st)
                                         :ctx-window (try ((:context-window (:model-registry agent))
                                                           (model-info/config-model-key (:config agent)))
                                                          (catch :default _ nil))
                                         :session-id (when-let [sess @(:session agent)]
                                                       (when (fn? (:session-file sess))
                                                         (last (.split (str ((:session-file sess))) "/"))))})))

        sync-pane!   (fn []
                       (.setMessages chat-pane @messages)
                       (.requestRender tui))

        ;; The activity spinner advances once per RENDER, and renders happen
        ;; when output arrives. nyma's own turns stream tokens continuously so
        ;; that was invisible, but an ACP agent can spend a minute inside one
        ;; tool call emitting nothing — and a frozen spinner reads exactly like
        ;; a hung process, which is the complaint this whole change started
        ;; from. Tick only while working: at idle the status line must stop
        ;; re-rendering so the terminal scroll position holds.
        tick-timer   (atom nil)
        set-tick!    (fn [on?]
                       (if on?
                         (when-not @tick-timer
                           (reset! tick-timer
                                   (js/setInterval (fn [] (sync-status!) (.requestRender tui)) 100)))
                         (when-let [t @tick-timer]
                           (js/clearInterval t)
                           (reset! tick-timer nil))))
        set-streaming!
        (fn [on?]
          (reset! streaming (boolean on?))
          (reset! stream-start (when on? (js/Date.now)))
          ;; Also into the agent's state atom — cli's SIGINT handler reads it
          ;; there to tell an in-flight turn from an idle prompt.
          (mark-streaming! agent on?)
          (set-tick! (or on? @busy))
          (sync-status!))
        set-busy!
        (fn [label]
          (reset! cmd-label label)
          (reset! busy (boolean label))
          (reset! stream-start (when label (js/Date.now)))
          (set-tick! (or @streaming (boolean label)))
          (sync-status!))

        ;; ── Message mutations ──────────────────────────────────────────────
        update-messages!
        (fn [f]
          (swap! messages f)
          (sync-pane!))

        add-user-msg!
        (fn [text]
          (update-messages!
           (fn [msgs] (conj (vec msgs) {:role "user" :content text :id (new-id)}))))

        ;; Rendered in the pane, hidden from the model. `build-context` drops
        ;; :local-only entries, so this is the same tag `!!cmd` uses.
        add-local-msg!
        (fn [text]
          (update-messages!
           (fn [msgs] (conj (vec msgs) {:role "user" :content text
                                        :id (new-id) :local-only true}))))

        add-chunk!
        (fn [chunk]
          (let [delta (.-text chunk)]
            (update-messages!
             (fn [msgs]
               (let [v    (vec msgs)
                     tail (last v)]
                 (if (= "assistant" (:role tail))
                   (update v (dec (count v)) update :content str delta)
                   (conj v {:role "assistant" :content (or delta "") :id (new-id)})))))))

        add-error!
        (fn [err]
          (let [msg (.-message err)]
            (update-messages!
             (fn [msgs]
               (conj (vec msgs)
                     {:role    "error"
                      ;; Raw message first, then the one actionable next step.
                      :content (classify-error-message (or msg "unknown error"))
                      :id      (new-id)})))))

        ;; ── Tool events ────────────────────────────────────────────────────
        ;; From settings — both keys were defaults nothing read.
        ui-settings (when-let [s (:settings agent)]
                      (when (fn? (:get s)) ((:get s))))
        verbosity (str (or (:tool-display ui-settings) "collapsed"))
        max-lines (let [n (:tool-display-max-lines ui-settings)]
                    (if (and (number? n) (pos? n)) n 40))
        events    (:events agent)

        on-tool-start
        (fn [data]
          (update-messages!
           (fn [msgs] (reducers/apply-tool-start msgs data verbosity max-lines))))

        on-tool-end
        (fn [data]
          (update-messages!
           (fn [msgs] (reducers/apply-tool-end msgs data verbosity max-lines))))

        on-tool-update
        (fn [data]
          (update-messages!
           (fn [msgs] (reducers/apply-tool-update msgs data))))

        ;; ── Submit / steer ────────────────────────────────────────────────
        border-atom    (atom nil)
        editor-theme   (make-editor-theme theme-fn border-atom)
        editor         (new Editor tui editor-theme #js {:paddingX 1})

        ;; The submit dispatch — see make-submit-dispatcher. Lifted out of
        ;; `on-submit` so the routing interception below can decide whether
        ;; to run it.
        dispatch-submit!
        (make-submit-dispatcher
         {:locked?          submit-lock
          :streaming?       streaming
          :editor           editor
          :abort-controller (:abort-controller agent)
          :add-user-msg!    add-user-msg!
          :add-local-msg!   add-local-msg!
          :add-msg!         (fn [m]
                              (update-messages!
                               (fn [msgs] (conj (vec msgs) (assoc m :id (new-id))))))
          :add-error!       add-error!
          :append-context!  (fn [text]
                              (swap! (:state agent) update :messages conj
                                     {:role "user" :content text}))
          :set-busy!        set-busy!
          :set-streaming!   set-streaming!
          :sync-status!     sync-status!
          :sync-pane!       sync-pane!
          :on-turn-done!    (fn [] (swap! turn-count inc))
          :run!             (fn [text]
                              (run-turn-with-update-handler
                               agent add-chunk!
                               #(run agent text)))
          :steer!           (fn [text] (steer agent {:role "user" :content text}))
          :run-command!     (fn [trimmed] (run-command! agent trimmed update-messages!))
          :exec-bash!       (fn [command] (editor-bash/run-bash! agent command))
          :eval-expr!       (fn [expr] (editor-eval/run-eval! expr events))
          :expand-mentions  (fn [text]
                              (:text (file-mentions/expand-mentions text (js/process.cwd))))})

        ;; An extension handling "input" streams straight into the pane. Its
        ;; messages carry :role/:content/:prompt-id but no :id, and chat-pane
        ;; keys on :id — so stamp one on rather than making every producer
        ;; remember.
        ensure-ids
        (fn [f]
          (update-messages!
           (fn [msgs]
             (mapv (fn [m] (if (:id m) m (assoc m :id (new-id)))) (f msgs)))))

        ;; An extension claimed this input (agent_shell routing it to an ACP
        ;; agent). It owns the turn: it sends the prompt and streams back.
        route-to-agent!
        (fn [res trimmed]
          (reset! submit-lock true)
          ;; An ACP turn is a turn. Without this the status bar read idle for
          ;; its whole duration — the one place that could have said "something
          ;; is happening" while the agent worked in silence.
          (set-streaming! true)
          (.addToHistory editor trimmed)
          ;; Echo NOW, not when the agent answers. The router used to prefix
          ;; "❯ <text>" onto its own first chunk, so the prompt appeared only
          ;; once a reply arrived — and an ACP agent answers last, after all
          ;; its tool work. Until then the screen showed nothing at all.
          (add-user-msg! trimmed)
          (let [sub (get res "subscribe")]
            (if-not sub
              (do (reset! submit-lock false)
                  (set-streaming! false))
              (-> (js/Promise.resolve (sub ensure-ids))
                  (.catch (fn [e] (add-error! e)))
                  (.finally (fn []
                              (reset! submit-lock false)
                              (set-streaming! false)
                              (sync-pane!)))))))

        on-submit
        (make-submit-handler {:events     events
                              :dispatch!  dispatch-submit!
                              :route!     route-to-agent!
                              :add-error! add-error!})]

    ;; Wire extension UI hooks
    (when-let [ext (.-extension-api agent)]
      (let [ui (.-ui ext)]
        (set! (.-available ui) true)
        (set! (.-notify ui)
              (fn [msg type]
                (update-messages!
                 (fn [msgs]
                   (conj (vec msgs) {:role    (notify-role type)
                                     :content (str msg)
                                     :id      (new-id)})))
                (.requestRender tui)))
        (set! (.-setWidget ui)
              (fn [widget-name lines _position]
                (let [content (.join lines "\n")
                      mid     (or (get @widgets widget-name) (new-id))]
                  (swap! widgets assoc widget-name mid)
                  (update-messages!
                   (fn [msgs]
                     (let [new-msg {:role "widget" :content content :id mid}
                           idx     (first (keep-indexed #(when (= (:id %2) mid) %1) msgs))]
                       (if (some? idx)
                         (assoc (vec msgs) idx new-msg)
                         (conj (vec msgs) new-msg)))))
                  (.requestRender tui))))
        (set! (.-clearWidget ui)
              (fn [widget-name]
                (when-let [mid (get @widgets widget-name)]
                  (swap! widgets dissoc widget-name)
                  (update-messages!
                   (fn [msgs] (filterv #(not= (:id %) mid) msgs)))
                  (.requestRender tui))))
        (set! (.-setEditorValue ui) (fn [v] (.setText editor v)))
        (set! (.-getEditorValue ui) (fn [] (.getText editor)))
        ;; Real overlays: showOverlay/custom/select/confirm/input on top of
        ;; pi-tui's native overlay stack. Until this call these slots were nil,
        ;; so every picker in the repo fell through to its text fallback.
        (overlay-host/install! ui tui
                               {:restore-focus (fn [] (.setFocus tui editor))
                                :request-render (fn [] (.requestRender tui))
                                :theme theme
                                ;; A thunk, so a /settings change to
                                ;; :ui/:overlay applies to the next overlay
                                ;; without needing a restart.
                                :overlay-options-fn
                                (fn []
                                  (when-let [settings (:settings agent)]
                                    (overlay-host/settings->overlay-options
                                     ((:get settings)))))})))

    ;; `/theme <name>` and `/reload` call theme-catalog/activate!; this is the
    ;; TUI's half: re-theme the pickers (they share one process-wide theme),
    ;; drop the chat pane's cached lines (they were rendered in the old
    ;; colours) and paint. theme-fn already reads the catalog's atom.
    (theme-catalog/on-apply!
     (fn [new-theme]
       (picker-frame/set-theme! new-theme)
       (.invalidate chat-pane)
       (.requestRender tui)))

    ;; Wire editor
    (set! (.-onSubmit editor) on-submit)

    ;; `editor_change` had no emitter anywhere in src, so token_suite's live
    ;; token-count widget — subscribed, debounced, tested — never once drew.
    ;; Throttled HERE rather than in the consumer: rpc mode subscribes a
    ;; println-JSON handler to every event name, so an unthrottled per-keystroke
    ;; emit would be one JSON line per keystroke on that channel.
    (let [last-emit (atom 0)
          pending   (atom nil)
          emit-text (fn [text]
                      (reset! last-emit (js/Date.now))
                      ((:emit (:events agent)) "editor_change" #js {:text (str text)}))]
      (set! (.-onChange editor)
            (fn [text]
              ;; Border colour by prefix: the theme's editor-border ramp was
              ;; defined and never read.
              (let [ramp (get-in (theme-fn) [:colors :editor-border] {})
                    t    (str text)]
                (reset! border-atom
                        (cond (.startsWith t "!!") (:high ramp)
                              (.startsWith t "!")  (:medium ramp)
                              (.startsWith t "$")  (:high ramp)
                              (.startsWith t "/")  (:low ramp)
                              :else                nil)))
              (let [now (js/Date.now)]
                (when @pending (js/clearTimeout @pending) (reset! pending nil))
                (if (>= (- now @last-emit) 100)
                  (emit-text text)
                  ;; Trailing edge, so the last keystroke of a fast burst still
                  ;; reaches the widget.
                  (reset! pending (js/setTimeout #(emit-text text) 100)))))))
    ;; `@file` completion is upstream's, but it is dead without an `fd` path;
    ;; the nyma fallback fills that in from `git ls-files` / a directory walk.
    (.setAutocompleteProvider editor
                              (file-mentions/configure-provider!
                               (new CombinedAutocompleteProvider
                                    (build-slash-commands agent)
                                    (js/process.cwd)
                                    nil)
                               (js/process.cwd)
                               (file-mentions/detect-fd)))

    ;; ── Session-level event handlers ──────────────────────────────────────
    (let [;; The pane's ONLY seeding path. Everything that changes which branch
          ;; is current goes through here, so the transcript cannot drift from
          ;; the session the way it used to: startup seeded the pane with one
          ;; filter, cli seeded the agent's context with `session->seed-messages`,
          ;; and /resume wrote to the store — which the pane does not subscribe
          ;; to — so it restored nothing at all. `pane-messages` says why it
          ;; shares the model's seed function.
          seed-pane! (fn []
                       (reset! messages
                               (mapv (fn [m] (assoc m :id (new-id)))
                                     (pane-messages @(:session agent))))
                       (reset! widgets {})
                       (sync-pane!))

          on-model-select   (fn [_] (sync-status!))
          on-session-clear  (fn [_]
                              (reset! messages [])
                              (reset! widgets {})
                              (sync-pane!))
          ;; Re-seeds rather than only clearing. /resume and /import call
          ;; switch-file BEFORE emitting and /fork re-points the leaf, so by the
          ;; time this runs `build-context` is already the branch the user asked
          ;; for. /new is the exception — see session-start-clears?.
          on-session-start
          (let [h (make-session-start-handler
                   {:get-session (fn [] @(:session agent))
                    :on-messages (fn [msgs]
                                   (reset! messages
                                           (mapv (fn [m] (assoc m :id (new-id))) msgs))
                                   (reset! widgets {})
                                   (sync-pane!))
                    :on-clear    (fn []
                                   (reset! messages [])
                                   (reset! widgets {})
                                   (sync-pane!))})]
            (fn [data]
              (reset! turn-count 0)
              (h data)
              (sync-status!)))

          ;; An extension asking to START a turn — see make-turn-request-handler
          ;; for why it waits out the lock instead of declining.
          on-turn-request
          (make-turn-request-handler
           {:locked?   submit-lock
            :dispatch! dispatch-submit!
            :echo!     add-user-msg!
            :schedule  (fn [f] (js/setTimeout f 50))
            ;; Same queue sendUserMessage deliverAs=followUp uses.
            :fallback! (fn [text] (follow-up agent {:role "user" :content text}))})]

      ;; Subscribe to tool lifecycle events
      ((:on events) "tool_execution_start"  on-tool-start)
      ((:on events) "tool_execution_end"    on-tool-end)
      ((:on events) "tool_execution_update" on-tool-update)
      ((:on events) "model_select"          on-model-select)
      ((:on events) "session_clear"         on-session-clear)
      ((:on events) "session_start"         on-session-start)
      ((:on events) "turn_request"          on-turn-request)

      ;; Prior conversation, for a session resumed with -c / -r / --session.
      ;; Same path the runtime events use — see seed-pane! above.
      (seed-pane!)

      ;; Layout: chat → status-bar → editor.
      ;;
      ;; Attached through attach-guarded-children! so every base child's
      ;; render is width-clamped. These three are the whole crash surface:
      ;; overlays are composited by pi-tui, which truncates them itself, but
      ;; base children go straight into the diff loop that throws. Adding a
      ;; fourth child here gets the guard for free — forgetting to wrap one
      ;; is how the status bar stayed unguarded.
      (attach-guarded-children! tui [["chat-pane" chat-pane]
                                     ["status-bar" status-bar]
                                     ["editor" editor]])
      (.setFocus tui editor)

      ;; Net under the guard. The guard clamps component output, which is what
      ;; actually prevents this; but pi-tui throws from inside its own render
      ;; timer with no catch point, so if anything still gets through, the
      ;; alternative to this handler is a dead session. pi-tui stops the
      ;; terminal before throwing and `start()` clears its stopped flag, so
      ;; the same instance can be brought back up in place.
      (crash-recovery/install!
       {:tui tui
        :session-path-fn (fn [] (when session
                                  (try ((:get-file-path session))
                                       (catch :default _ nil))))
        :on-recovered (fn [msg]
                        (update-messages!
                         (fn [msgs]
                           (conj (vec msgs)
                                 {:role "info" :content msg :id (new-id)}))))})

      ;; Initial status render
      (sync-status!)

      ;; Global Esc → abort the in-flight run (stream + tools listening on
      ;; the run's AbortSignal). The gating is `abort-on-escape?`; its
      ;; docstring says why overlays and idle both opt out.
      (.addInputListener tui
                         (fn [data]
                           (when (abort-on-escape?
                                  data @submit-lock
                                  (.-length (.-overlayStack tui)))
                             (when-let [ctrl-atom (:abort-controller agent)]
                               (.abort @ctrl-atom "user-interrupt")))
                           nil))

      ;; ctrl+o: expand/collapse the last tool's output. The registry
      ;; documented this action for a long time with no handler; it lives in
      ;; `(:shortcuts agent)` like every other dispatched key, under whatever
      ;; combo keybindings.json mapped to `app.tools.expand`.
      (let [combo (or (kbr/get-binding @(:keybinding-registry agent) "app.tools.expand")
                      "ctrl+o")]
        (swap! (:shortcuts agent) assoc combo
               {:action      "app.tools.expand"
                :description (get-in kbr/default-actions ["app.tools.expand" :description])
                :handler     (fn [] (update-messages! reducers/tool-toggle-expanded))}))

      ;; Registered shortcuts — extensions' `registerShortcut` and every
      ;; keybindings.json binding. Both landed in `(:shortcuts agent)` and
      ;; NOTHING read it at keypress time, so prompt_history's ctrl+r, the
      ;; role-cycle key and every user binding were inert. Input listeners run
      ;; before focus dispatch, and `{consume: true}` stops the editor also
      ;; seeing the key, so a bound combo does one thing rather than two.
      ;;
      ;; Not while an overlay is open: a picker owns the keyboard, exactly as
      ;; the Esc guard above assumes.
      (.addInputListener tui
                         (fn [data]
                           (when (and (zero? (.-length (.-overlayStack tui)))
                                      (keybindings/dispatch-shortcut!
                                       @(:shortcuts agent) data matchesKey))
                             #js {:consume true})))

      ;; Global Ctrl+C: abort the turn first, exit on the second press or
      ;; while idle — see make-interrupt-handler.
      (let [interrupt!
            (make-interrupt-handler
             {:streaming? (fn [] @streaming)
              :abort!     (fn []
                            (when-let [ctrl-atom (:abort-controller agent)]
                              (.abort @ctrl-atom "user-interrupt")))
              :notify!    (fn [text]
                            (update-messages!
                             (fn [msgs]
                               (conj (vec msgs) {:role "info" :content text :id (new-id)}))))
              :exit!      (fn []
                            (.stop tui)
                            ;; Or the render ticker keeps the event loop alive
                            ;; and the process never exits.
                            (set-streaming! false)
                            ((:off events) "tool_execution_start"  on-tool-start)
                            ((:off events) "tool_execution_end"    on-tool-end)
                            ((:off events) "tool_execution_update" on-tool-update)
                            ((:off events) "model_select"          on-model-select)
                            ((:off events) "session_clear"         on-session-clear)
                            ((:off events) "session_start"         on-session-start)
                            ((:off events) "turn_request"          on-turn-request)
                            ((:emit-async events) "session_shutdown" #js {:reason "user-exit"})
                            (js/process.exit 0))})]
        (.addInputListener tui
                           (fn [data]
                             (when (matchesKey data "ctrl+c")
                               (interrupt!))
                             nil)))

      (.start tui)

      ;; Two lines a session opens with, AFTER .start for the same reason the
      ;; seed prompt is submitted here: the first frame has painted, so they
      ;; land in the transcript instead of being overwritten by it.
      ;;
      ;; The banner only appears for a session that was resumed (-c / -r /
      ;; --session seeded the pane above); the hint only when it was not, so a
      ;; resumed session is not told how to abort a turn it is not running.
      (let [note (fn [text]
                   (update-messages!
                    (fn [msgs]
                      (conj (vec msgs) {:role "info" :content text :id (new-id)})))
                   (.requestRender tui))
            st   @(:state agent)
            sess @(:session agent)]
        (if-let [banner (resumed-banner
                         {:message-count (count @messages)
                          :name          (when sess (try ((:get-session-name sess))
                                                         (catch :default _ nil)))
                          :file-path     (when sess (try ((:get-file-path sess))
                                                         (catch :default _ nil)))
                          :total-cost    (:total-cost st)})]
          (note banner)
          (note kbr/first-launch-hint)))

      ;; The prompt cli handed us (`nyma "do the thing"` with no -p). Submitted
      ;; AFTER .start so the first frame has painted and the turn's output has
      ;; somewhere to land.
      (submit-seed-prompt! resources
                           {:set-text (fn [t] (.setText editor t))
                            :submit   on-submit}))))
