(ns theme-apply.test
  "/theme applies without a restart: the catalog's current theme changes, the
   TUI hook fires, and every consumer that used to bake colours at
   construction reads the new ones on its next render."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.ui.theme-catalog :as tc]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.status-bar :refer [create-status-bar]]
            [agent.ui.chat-renderer :refer [render-message]]
            [agent.utils.ansi :refer [fg]]))

;; `apply-theme!` persists to `.nyma/settings.json` under cwd; run in a temp dir
;; so the repo's own settings are never touched.
(def ^:private orig-cwd (atom nil))
(def ^:private tmp (atom nil))

(beforeEach (fn []
              (reset! orig-cwd (js/process.cwd))
              (reset! tmp (fs/mkdtempSync (path/join (os/tmpdir) "nyma-theme-")))
              (js/process.chdir @tmp)
              (tc/on-apply! nil)
              (reset! tc/current nil)))

(afterEach (fn []
             (tc/on-apply! nil)
             (js/process.chdir @orig-cwd)
             (try (fs/rmSync @tmp #js {:recursive true :force true})
                  (catch :default _ nil))))

(defn- agent-with-builtins []
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})]
    (register-builtins agent {} {})
    agent))

(defn- ctx [notes]
  #js {:ui #js {:available true
                :notify (fn [m l] (swap! notes conj {:msg (str m) :level (or l "info")}))}})

(def ^:private nord (get tc/catalog "nord"))

(describe "/theme applies live" (fn []
                                  (it "leaves the catalog holding the chosen theme and says nothing about restarting"
                                      (fn []
                                        (let [agent (agent-with-builtins)
                                              notes (atom [])]
                                          ((get-in @(:commands agent) ["theme" :handler]) ["nord"] (ctx notes))
                                          (-> (expect @tc/current) (.toEqual nord))
                                          (-> (expect (:msg (first @notes))) (.toBe "Theme: nord"))
                                          (-> (expect (.includes (.toLowerCase (:msg (first @notes))) "restart")) (.toBe false))
          ;; …and persisted, so the next launch agrees.
                                          (-> (expect (tc/active-theme nil default-dark)) (.toEqual nord)))))

                                  (it "runs the host hook with the new theme, which is where the pane is invalidated"
                                      (fn []
                                        (let [invalidated (atom 0)
                                              pane        #js {:invalidate (fn [] (swap! invalidated inc))}
                                              applied     (atom nil)]
                                          (tc/on-apply! (fn [t] (reset! applied t) (.invalidate pane)))
                                          (tc/apply-theme! "dracula" nil default-dark)
                                          (-> (expect @applied) (.toEqual (get tc/catalog "dracula")))
                                          (-> (expect @invalidated) (.toBe 1)))))

                                  (it "an unknown theme is refused and nothing changes"
                                      (fn []
                                        (let [agent (agent-with-builtins)
                                              notes (atom [])]
                                          ((get-in @(:commands agent) ["theme" :handler]) ["no-such-theme"] (ctx notes))
                                          (-> (expect (:level (first @notes))) (.toBe "error"))
                                          (-> (expect @tc/current) (.toBeNil)))))))

(describe "consumers read the theme per render" (fn []
                                                  (it "the status bar paints the model in the new primary colour after a switch"
                                                      (fn []
                                                        (let [theme (atom default-dark)
                                                              bar   (create-status-bar (fn [] @theme))
                                                              _     (.setState bar #js {:model "m"})
                                                              old   (aget (.render bar 80) 0)
                                                              _     (reset! theme nord)
                                                              new-l (aget (.render bar 80) 0)
                                                              hex   (get-in nord [:colors :primary])]
                                                          (when (seq (fg hex))
                                                            (-> (expect (.includes new-l (fg hex))) (.toBe true))
                                                            (-> (expect (.includes old (fg hex))) (.toBe false))))))

                                                  (it "the info and plan colours come from the theme, not a literal"
                                                      (fn []
                                                        (let [t     (assoc-in default-dark [:colors :info] "#123456")
                                                              t     (assoc-in t [:colors :plan] "#654321")
                                                              info  (first (render-message {:msg {:role "info" :content "x" :id "1"} :width 80 :theme t}))
                                                              plan  (first (render-message {:msg {:role "plan" :content "x" :id "2"} :width 80 :theme t}))]
                                                          (when (seq (fg "#123456"))
                                                            (-> (expect (.includes info (fg "#123456"))) (.toBe true))
                                                            (-> (expect (.includes plan (fg "#654321"))) (.toBe true))))))))
