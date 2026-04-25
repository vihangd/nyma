(ns agent.modes.interactive
  "Pi-tui based interactive mode. Replaces Ink."
  {:squint/extension "jsx"}
  (:require ["@mariozechner/pi-tui" :refer [TUI ProcessTerminal matchesKey]]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.autocomplete-builtins :as ac-builtins]))

;;; ---------------------------------------------------------------------------
;;; Pure helpers — kept for compatibility while app.cljs/cli.mjs still use them
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
;;; Stub App component — Phase 1 skeleton, replaced in Phase 2
;;; ---------------------------------------------------------------------------

(defn make-stub-app [_theme]
  #js {:render     (fn [_w] #js ["" "  nyma starting..."])
       :invalidate (fn [])})

;;; ---------------------------------------------------------------------------
;;; Entry point
;;; ---------------------------------------------------------------------------

(defn ^:async start [agent session resources]
  (ac-builtins/register-all! agent)
  (let [theme    default-dark
        terminal (new ProcessTerminal)
        tui      (new TUI terminal)
        app      (make-stub-app theme)]

    (.addChild tui app)

    ;; Ctrl+C exits
    (.addInputListener tui
                       (fn [data]
                         (when (matchesKey data "ctrl+c")
                           (.stop tui)
                           (js/process.exit 0))
                         nil))

    (.start tui)))
