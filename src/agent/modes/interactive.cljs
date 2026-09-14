(ns agent.modes.interactive
  "Pi-tui based interactive mode."
  (:require [agent.model-info :as model-info]
            ["@earendil-works/pi-tui" :refer [TuiMainScreen ProcessTerminal Editor
                                              CombinedAutocompleteProvider
                                              matchesKey]]
            [agent.loop :refer [run steer run-turn-with-update-handler]]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.theme-catalog :as theme-catalog]
            [agent.ui.chat-pane :refer [create-chat-pane]]
            [agent.ui.status-bar :refer [create-status-bar]]
            [agent.ui.app-reducers :as reducers]
            [agent.ui.editor-bash :as editor-bash]
            [agent.ui.editor-eval :as editor-eval]
            [agent.ui.overlay-host :as overlay-host]
            [agent.ui.width-guard :refer [attach-guarded-children!]]
            [agent.ui.crash-recovery :as crash-recovery]
            [agent.sessions.manager :refer [session->seed-messages]]
            [agent.keybindings :as keybindings]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Pure helpers — used by app.cljs / cli.cljs
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
   before focus dispatch (pi-tui tui.js:379-394), so without the overlay check
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
           (has "context" "too long" "maximum context" "context_length")
           " — run /compact"

           (has "401" "invalid api key" "invalid x-api-key" "invalid_api_key"
                "api key" "api_key" "unauthorized" "authentication")
           " — check ANTHROPIC_API_KEY or /login <provider>"

           (has "429" "rate limit" "rate_limit" "rate-limit")
           (if retrying? " — rate limited; retrying" " — try again")

           (has "maximum")
           " — run /compact"

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
        ;; config.model is the whole story: setModel writes it, and the
        ;; `:runtime-model` state key this also consulted has no writer anywhere.
        active  (some-> config .-model)]
    (cond
      (nil? active)    "–"
      (string? active) active
      :else            (str (or (.-modelId active) (.-id active) "–")))))

(defn- provider-name [agent]
  "Return the user-friendly provider label captured at setModel time
   (`anthropic`, `minimax`, `openrouter`, …). Empty string when nyma
   was started with a bare model id (no `provider/` prefix) or when
   the config is uninitialised. Status-bar consumers render
   `<provider>/<model>` only when this is non-empty."
  (let [config (:config agent)
        v      (and config (aget config "active-provider-name"))]
    (or v "")))

(defn- make-editor-theme
  "`border-atom` holds the current border colour so the prefix mode can
   change it live: `!` and `!!` (shell), `$` and `$$` (eval) used to look
   exactly like a prompt until Enter."
  [theme & [border-atom]]
  (let [ESC   (js/String.fromCharCode 27)
        RESET (str ESC "[0m")
        BOLD  (str ESC "[1m")
        DIM   (str ESC "[2m")
        fg    (fn [hex]
                (let [r (js/parseInt (.slice hex 1 3) 16)
                      g (js/parseInt (.slice hex 3 5) 16)
                      b (js/parseInt (.slice hex 5 7) 16)]
                  (str ESC "[38;2;" r ";" g ";" b "m")))

        primary   (get-in theme [:colors :primary]   "#7aa2f7")
        muted     (get-in theme [:colors :muted]     "#565f89")
        error-c   (get-in theme [:colors :error]     "#f7768e")
        border    (get-in theme [:colors :border]    "#3b4261")]

    #js {:borderColor (fn [s] (str (fg (or (some-> border-atom deref) border)) s RESET))
         :selectList  #js {:selectedPrefix (fn [s] (str (fg primary) BOLD s RESET))
                           :selectedText   (fn [s] (str (fg primary) BOLD s RESET))
                           :description    (fn [s] (str (fg muted) DIM s RESET))
                           :scrollInfo     (fn [s] (str (fg muted) DIM s RESET))
                           :noMatch        (fn [s] (str (fg error-c) s RESET))}}))

(defn- build-slash-commands [agent]
  (clj->js
   (map (fn [[name cmd]]
          #js {:name        (str name)
               :description (or (:description cmd) "")})
        @(:commands agent))))

;;; ---------------------------------------------------------------------------
;;; Command execution (mirrors app.cljs handle-command)
;;; ---------------------------------------------------------------------------

(defn- run-command! [agent text update-messages!]
  ;; Split on RUNS of whitespace, and drop empties. Splitting on a single " "
  ;; turned `/spec analyze  foo` (double space) into
  ;; ["spec" "analyze" "" "foo"], so the handler saw "" as its first argument
  ;; and reported its usage string — for every command, not just this one.
  ;; Tabs and a trailing space were broken the same way.
  (let [parts    (->> (.split (.trim (.slice text 1)) #"\s+")
                      (remove (fn [p] (= "" p)))
                      vec)
        cmd      (first parts)
        args     (rest parts)
        commands @(:commands agent)]
    (if-let [entry (resolve-command commands cmd)]
      (let [h (:handler entry)]
        (h args #js {:ui             (when-let [ext (.-extension-api agent)] (.-ui ext))
                     ;; Aborted by Escape while the command runs.
                     :signal         (when-let [c (:abort-controller agent)] (.-signal @c))
                     :agent          agent
                     :set-messages   update-messages!
                     :append-message (fn [msg]
                                       (update-messages!
                                        (fn [prev]
                                          (conj (vec prev) (assoc msg :id (new-id))))))}))
      ;; Was a bare `when-let`: an unrecognised command did nothing at all,
      ;; which is indistinguishable from one that ran and had nothing to say.
      ;; Suggest near misses by prefix — the usual cause is a half-remembered
      ;; name, not an invented one.
      (let [near (->> (keys commands)
                      (map str)
                      (filter (fn [n] (or (.startsWith n (str cmd))
                                          (.startsWith (str cmd) n))))
                      sort
                      (take 5))]
        (update-messages!
         (fn [prev]
           (conj (vec prev)
                 {:role       "error"
                  :id         (new-id)
                  :local-only true
                  :content    (str "Unknown command: /" cmd
                                   (when (seq near)
                                     (str "\nDid you mean: "
                                          (str/join ", " (map #(str "/" %) near))))
                                   "\nRun /help to list commands.")})))))))

;;; ---------------------------------------------------------------------------
;;; Entry point
;;; ---------------------------------------------------------------------------

(defn ^:async start [agent session resources]
  (let [theme     (or (.-theme resources)
                      (theme-catalog/active-theme (.-themes resources) default-dark))
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

        chat-pane    (create-chat-pane theme)
        status-bar   (create-status-bar theme)

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
        verbosity "collapsed"
        max-lines 500
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
        editor-theme   (make-editor-theme theme border-atom)
        editor         (new Editor tui editor-theme #js {:paddingX 1})

        do-run!
        (fn [text]
          (set-streaming! true)
          (-> (js/Promise.resolve nil)
              (.then (fn [_]
                       (run-turn-with-update-handler
                        agent add-chunk!
                        #(run agent text))))
              (.then (fn [_]
                       (set-streaming! false)
                       (swap! turn-count inc)
                       (reset! submit-lock false)
                       (sync-status!)
                       (sync-pane!)))
              (.catch (fn [e]
                        (add-error! e)
                        (set-streaming! false)
                        (reset! submit-lock false)
                        (sync-status!)))))

        ;; The original submit dispatch, unchanged. Lifted out of `on-submit`
        ;; so the routing interception below can decide whether to run it.
        dispatch-submit!
        (fn [trimmed]
          (cond
                ;; ── Steer: mid-stream follow-up ──────────────────────────
            @streaming
            (do (.addToHistory editor trimmed)
                (steer agent {:role "user" :content trimmed})
                (add-user-msg! (str trimmed " ↩")))

                ;; ── Slash command ────────────────────────────────────────
            (and (.startsWith trimmed "/") (not @submit-lock))
            (do (reset! submit-lock true)
                (.addToHistory editor trimmed)
                ;; Fresh controller: Escape aborts it while the lock is held
                ;; (a handler reads it as ctx.signal), and the loop only mints
                ;; one per run, so a previously-aborted one would arrive dead.
                (when-let [c (:abort-controller agent)]
                  (reset! c (js/AbortController.)))
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
                           (fn [] (run-command! agent trimmed update-messages!)))
                    (.catch (fn [e]
                              (add-error! (js/Error. (str "/" (command-name trimmed) " failed: "
                                                          (or (.-message e) (str e)))))))
                    (.finally (fn []
                                (set-busy! nil)
                                (reset! submit-lock false)))))

                ;; ── !cmd / !!cmd — shell exec ─────────────────────────────
            (and (not @submit-lock)
                 (not= :not-bash (:kind (editor-bash/parse-bash-input trimmed))))
            (let [{:keys [kind command]} (editor-bash/parse-bash-input trimmed)]
              (reset! submit-lock true)
              (.addToHistory editor trimmed)
              (add-user-msg! trimmed)
              (-> (editor-bash/run-bash! agent command)
                  (.then (fn [result]
                           (let [content (editor-bash/format-bash-output result)
                                 role    (if (:blocked? result) "error" "shell")]
                             (update-messages!
                              (fn [msgs]
                                (conj (vec msgs)
                                      {:role       role
                                       :content    content
                                       :id         (new-id)
                                       :local-only (= kind :run-hidden)})))
                                 ;; Inject into LLM context for !cmd (not !!cmd)
                             (when (= kind :run)
                               (swap! (:state agent) update :messages conj
                                      {:role "user" :content (str trimmed "\n" content)}))
                             (reset! submit-lock false)
                             (sync-pane!))))
                  (.catch (fn [e]
                            (add-error! e)
                            (reset! submit-lock false)))))

                ;; ── $expr / $$expr — Babashka eval ───────────────────────
            (and (not @submit-lock)
                 (not= :not-eval (:kind (editor-eval/parse-eval-input trimmed))))
            (let [{:keys [kind expr]} (editor-eval/parse-eval-input trimmed)]
              (reset! submit-lock true)
              (.addToHistory editor trimmed)
              (add-user-msg! trimmed)
              (-> (editor-eval/run-eval! expr events)
                  (.then (fn [result]
                           (let [content (editor-eval/format-eval-output result)
                                 role    (if (or (:unavailable? result) (:blocked? result))
                                           "error" "shell")]
                             (update-messages!
                              (fn [msgs]
                                (conj (vec msgs)
                                      {:role       role
                                       :content    content
                                       :id         (new-id)
                                       :local-only (= kind :eval-hidden)})))
                             ;; A blocked expression never ran — do not tell
                             ;; the model it did.
                             (when (and (= kind :eval) (not (:blocked? result)))
                               (swap! (:state agent) update :messages conj
                                      {:role "user" :content (str trimmed "\n" content)}))
                             (reset! submit-lock false)
                             (sync-pane!))))
                  (.catch (fn [e]
                            (add-error! e)
                            (reset! submit-lock false)))))

            ;; ── Normal LLM prompt ────────────────────────────────────
            (not @submit-lock)
            (do (reset! submit-lock true)
                (.addToHistory editor trimmed)
                (add-user-msg! trimmed)
                (do-run! trimmed))))

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
        (fn [text]
          (let [trimmed (.trim text)]
            (when (pos? (count trimmed))
              ((:emit-async events) "input_submit" #js {:text trimmed})
              ;; `input` is an INTERCEPTION hook, not a notification: a handler
              ;; returns {handle, streaming, subscribe} to take the turn over.
              ;; emit-collect is async, so gate on handler-count — with nothing
              ;; subscribed (the common case) submit stays exactly as
              ;; synchronous as it was.
              (if (zero? ((:handler-count events) "input"))
                (dispatch-submit! trimmed)
                (-> ((:emit-collect events) "input" #js {:input trimmed})
                    (.then (fn [res]
                             (if (and res (get res "handle"))
                               (route-to-agent! res trimmed)
                               (dispatch-submit! trimmed))))
                    (.catch (fn [e]
                              ;; A broken router must not eat the user's input.
                              (add-error! e)
                              (dispatch-submit! trimmed))))))))]

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
              (let [ramp (get-in theme [:colors :editor-border] {})
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
    (.setAutocompleteProvider editor
                              (new CombinedAutocompleteProvider
                                   (build-slash-commands agent)
                                   (js/process.cwd)
                                   nil))

    ;; ── Session-level event handlers ──────────────────────────────────────
    (let [;; The pane's ONLY seeding path. Everything that changes which branch
          ;; is current goes through here, so the transcript cannot drift from
          ;; the session the way it used to: startup seeded the pane with one
          ;; filter, cli seeded the agent's context with `session->seed-messages`,
          ;; and /resume wrote to the store — which the pane does not subscribe
          ;; to — so it restored nothing at all.
          ;;
          ;; Uses `session->seed-messages`, the same function that builds the
          ;; model's context, so the two cannot disagree. The old inline filter
          ;; kept raw user/assistant entries and dropped `compaction` /
          ;; `branch-summary`, which seed-messages folds into a summary message
          ;; — on a compacted session the screen showed less than the model saw.
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

          ;; An extension asking to START a turn. `sendUserMessage` only
          ;; queues — steer needs a run in flight, follow-up drains at a turn
          ;; boundary — so anything wanting to begin work could do nothing but
          ;; print "send any message to start". Three separate features shipped
          ;; with that instruction before it was worth fixing properly.
          ;;
          ;; Routed through `dispatch-submit!` rather than `run` directly, so a
          ;; requested turn gets the same lock, streaming state and pane wiring
          ;; a typed one does. Declined while a turn is already in flight: the
          ;; follow-up queue is the right mechanism then, and it is what the
          ;; caller falls back to.
          on-turn-request
          (fn [data]
            (let [text (str (or (and data (.-text data)) ""))]
              (when (and (seq (.trim text)) (not @submit-lock))
                (when-let [echo (and data (.-echo data))]
                  (when echo (add-user-msg! text)))
                (dispatch-submit! text))
              nil))]

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
      ;; the run's AbortSignal). Only while a submit is active so pickers and
      ;; the editor keep their own Esc behavior when idle.
      ;;
      ;; …and only when no overlay is open. Input listeners run BEFORE focus
      ;; dispatch (tui.js:379-394), so without this check one Esc did two
      ;; things at once: killed the turn AND dismissed the picker. A permission
      ;; prompt is shown precisely while a submit is in flight, so cancelling
      ;; the prompt aborted the run it was asking about.
      (.addInputListener tui
                         (fn [data]
                           (when (abort-on-escape?
                                  data @submit-lock
                                  (.-length (.-overlayStack tui)))
                             (when-let [ctrl-atom (:abort-controller agent)]
                               (.abort @ctrl-atom "user-interrupt")))
                           nil))

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

      ;; Global Ctrl+C → graceful exit
      (.addInputListener tui
                         (fn [data]
                           (when (matchesKey data "ctrl+c")
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
                             (js/process.exit 0))
                           nil))

      (.start tui)

      ;; The prompt cli handed us (`nyma "do the thing"` with no -p). Submitted
      ;; AFTER .start so the first frame has painted and the turn's output has
      ;; somewhere to land.
      (submit-seed-prompt! resources
                           {:set-text (fn [t] (.setText editor t))
                            :submit   on-submit}))))
