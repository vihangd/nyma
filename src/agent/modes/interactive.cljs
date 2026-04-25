(ns agent.modes.interactive
  "Pi-tui based interactive mode."
  (:require ["@mariozechner/pi-tui" :refer [TUI ProcessTerminal Editor
                                            CombinedAutocompleteProvider
                                            matchesKey]]
            [agent.loop :refer [run steer run-turn-with-update-handler]]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.autocomplete-builtins :as ac-builtins]
            [agent.ui.chat-pane :refer [create-chat-pane]]
            [agent.ui.status-bar :refer [create-status-bar]]
            [agent.ui.app-reducers :as reducers]))

;;; ---------------------------------------------------------------------------
;;; Pure helpers — used by app.cljs / cli.cljs
;;; ---------------------------------------------------------------------------

(defn alt-screen-enabled?
  [env-value]
  (boolean (and env-value
                (not= env-value "")
                (not= env-value "0")
                (not= env-value "false"))))

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

(defn- model-id [agent]
  (let [config  (:config agent)
        m       (.-model config)
        runtime (.-runtime-model (:state agent))]
    (str (or (.-modelId (or runtime m)) (or runtime m) "–"))))

(defn- make-editor-theme [theme]
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

    #js {:borderColor (fn [s] (str (fg border) s RESET))
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
  (let [parts    (.split (.slice text 1) " ")
        cmd      (first parts)
        args     (rest parts)
        commands @(:commands agent)]
    (when-let [entry (resolve-command commands cmd)]
      (let [h (:handler entry)]
        (h args #js {:ui             (when-let [ext (.-extension-api agent)] (.-ui ext))
                     :agent          agent
                     :set-messages   update-messages!
                     :append-message (fn [msg]
                                       (update-messages!
                                        (fn [prev]
                                          (conj (vec prev) (assoc msg :id (new-id))))))})))))

;;; ---------------------------------------------------------------------------
;;; Entry point
;;; ---------------------------------------------------------------------------

(defn ^:async start [agent session resources]
  (ac-builtins/register-all! agent)
  (let [theme     (or (.-theme resources) default-dark)
        terminal  (new ProcessTerminal)
        tui       (new TUI terminal)

        ;; ── Message + UI state ─────────────────────────────────────────────
        messages     (atom [])
        streaming    (atom false)
        turn-count   (atom 0)
        submit-lock  (atom false)

        chat-pane    (create-chat-pane theme)
        status-bar   (create-status-bar theme)

        sync-status! (fn []
                       (.setState status-bar
                                  #js {:model      (model-id agent)
                                       :role       (let [r (str (or (:active-role @(:state agent)) ":default"))]
                                                     (if (.startsWith r ":") (.slice r 1) r))
                                       :streaming  @streaming
                                       :turn-count @turn-count}))

        sync-pane!   (fn []
                       (.setMessages chat-pane @messages)
                       (.requestRender tui))

        ;; ── Message mutations ──────────────────────────────────────────────
        update-messages!
        (fn [f]
          (swap! messages f)
          (sync-pane!))

        add-user-msg!
        (fn [text]
          (update-messages!
           (fn [msgs] (conj (vec msgs) {:role "user" :content text :id (new-id)}))))

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
               (conj (vec msgs) {:role "error" :content (or msg "unknown error") :id (new-id)})))))

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
        editor-theme   (make-editor-theme theme)
        editor         (new Editor tui editor-theme #js {:paddingX 1})

        do-run!
        (fn [text]
          (reset! streaming true)
          (sync-status!)
          (-> (js/Promise.resolve nil)
              (.then (fn [_]
                       (run-turn-with-update-handler
                        agent add-chunk!
                        #(run agent text))))
              (.then (fn [_]
                       (reset! streaming false)
                       (swap! turn-count inc)
                       (reset! submit-lock false)
                       (sync-status!)
                       (sync-pane!)))
              (.catch (fn [e]
                        (add-error! e)
                        (reset! streaming false)
                        (reset! submit-lock false)
                        (sync-status!)))))

        on-submit
        (fn [text]
          (let [trimmed (.trim text)]
            (when (pos? (count trimmed))
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
                    (run-command! agent trimmed update-messages!)
                    (reset! submit-lock false))

                ;; ── Normal LLM prompt ────────────────────────────────────
                (not @submit-lock)
                (do (reset! submit-lock true)
                    (.addToHistory editor trimmed)
                    (add-user-msg! trimmed)
                    (do-run! trimmed))))))]

    ;; Wire editor
    (set! (.-onSubmit editor) on-submit)
    (.setAutocompleteProvider editor
                              (new CombinedAutocompleteProvider
                                   (build-slash-commands agent)
                                   (js/process.cwd)
                                   nil))

    ;; Subscribe to tool lifecycle events
    ((:on events) "tool_execution_start"  on-tool-start)
    ((:on events) "tool_execution_end"    on-tool-end)
    ((:on events) "tool_execution_update" on-tool-update)

    ;; Layout: chat → status-bar → editor
    (.addChild tui chat-pane)
    (.addChild tui status-bar)
    (.addChild tui editor)
    (.setFocus tui editor)

    ;; Initial status render
    (sync-status!)

    ;; Global Ctrl+C → exit
    (.addInputListener tui
                       (fn [data]
                         (when (matchesKey data "ctrl+c")
                           (.stop tui)
                           ((:off events) "tool_execution_start"  on-tool-start)
                           ((:off events) "tool_execution_end"    on-tool-end)
                           ((:off events) "tool_execution_update" on-tool-update)
                           (js/process.exit 0))
                         nil))

    (.start tui)))
