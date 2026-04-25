(ns agent.modes.interactive
  "Pi-tui based interactive mode."
  (:require ["@mariozechner/pi-tui" :refer [TUI ProcessTerminal Input matchesKey]]
            [agent.loop :refer [run steer run-turn-with-update-handler]]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.autocomplete-builtins :as ac-builtins]
            [agent.ui.chat-pane :refer [create-chat-pane]]
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
;;; Message helpers
;;; ---------------------------------------------------------------------------

(defn- new-id []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

;;; ---------------------------------------------------------------------------
;;; Entry point
;;; ---------------------------------------------------------------------------

(defn ^:async start [agent session resources]
  (ac-builtins/register-all! agent)
  (let [theme    (or (.-theme resources) default-dark)
        terminal (new ProcessTerminal)
        tui      (new TUI terminal)

        ;; ── Message state ──────────────────────────────────────────────────
        messages     (atom [])
        submit-lock  (atom false)

        chat-pane    (create-chat-pane theme)

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
             (fn [msgs] (conj (vec msgs) {:role "error" :content (or msg "unknown error") :id (new-id)})))))

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

        ;; ── Input component ────────────────────────────────────────────────
        input (new Input)

        on-submit
        (fn [text]
          (let [trimmed (.trim text)]
            (when (and (pos? (count trimmed)) (not @submit-lock))
              (reset! submit-lock true)
              (.setValue input "")
              (add-user-msg! trimmed)
              (-> (js/Promise.resolve nil)
                  (.then (fn [_]
                           (run-turn-with-update-handler
                            agent add-chunk!
                            #(run agent trimmed))))
                  (.then (fn [_]
                           (reset! submit-lock false)
                           (sync-pane!)))
                  (.catch (fn [e]
                            (add-error! e)
                            (reset! submit-lock false)))))))]

    ;; Wire input
    (set! (.-onSubmit input) on-submit)

    ;; Subscribe to tool lifecycle events
    ((:on events) "tool_execution_start" on-tool-start)
    ((:on events) "tool_execution_end"   on-tool-end)
    ((:on events) "tool_execution_update" on-tool-update)

    ;; Layout: chat fills from top, input at bottom
    (.addChild tui chat-pane)
    (.addChild tui input)
    (.setFocus tui input)

    ;; Global Ctrl+C → exit
    (.addInputListener tui
                       (fn [data]
                         (when (matchesKey data "ctrl+c")
                           (.stop tui)
                           ((:off events) "tool_execution_start" on-tool-start)
                           ((:off events) "tool_execution_end"   on-tool-end)
                           ((:off events) "tool_execution_update" on-tool-update)
                           (js/process.exit 0))
                         nil))

    (.start tui)))
