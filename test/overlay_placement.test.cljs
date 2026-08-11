(ns overlay-placement.test
  "Where an overlay is drawn was never a decision anyone made: 23 of the 24
   overlay call sites in the repo pass no options, so they all inherited a
   `{width \"60%\" anchor \"center\"}` constant. The visible cost was the
   permission prompt landing dead centre, covering the transcript the user is
   reading in order to answer it.

   Nothing asserted an anchor anywhere before this file, and the fake UI in
   middleware.test.cljs ignores its options argument entirely — so a regression
   here was invisible."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.ui.overlay-host :as oh
             :refer [settings->overlay-options default-overlay-options
                     valid-anchors install!]]))

;;; ─── settings → pi-tui options ───────────────────────────────────────────

(describe "settings->overlay-options"
          (fn []
            (it "defaults to the bottom, out of the transcript's way"
                (fn []
                  (let [o (settings->overlay-options {})]
                    (-> (expect (.-anchor o)) (.toBe "bottom-center"))
                    (-> (expect (.-width o)) (.toBe "100%")))))

            (it "accepts every anchor pi-tui supports"
                (fn []
                  (doseq [a valid-anchors]
                    (let [o (settings->overlay-options {:ui {:overlay {:anchor a}}})]
                      (-> (expect (.-anchor o)) (.toBe a))))))

            (it "falls back rather than handing pi-tui an unknown anchor"
                (fn []
                  ;; Settings have no schema — only de-duping and case
                  ;; normalization — so an arbitrary string gets this far.
                  (doseq [bad ["middle" "bottom" "BOTTOM-CENTER" "" 42 nil]]
                    (let [o (settings->overlay-options {:ui {:overlay {:anchor bad}}})]
                      (-> (expect (.-anchor o)) (.toBe "bottom-center"))))))

            (it "merges a partial override instead of replacing the whole thing"
                (fn []
                  (let [o (settings->overlay-options {:ui {:overlay {:anchor "center"}}})]
                    (-> (expect (.-anchor o)) (.toBe "center"))
                    ;; The fields the user did not mention keep their defaults.
                    (-> (expect (.-width o)) (.toBe "100%"))
                    (-> (expect (.-minWidth o)) (.toBe 40))
                    (-> (expect (.-maxHeight o)) (.toBe "70%")))))

            (it "emits pi-tui's camelCase from kebab-case settings"
                (fn []
                  ;; settings/manager runs normalize-keys over loaded JSON, so a
                  ;; user writing "maxHeight" arrives here as :max-height. This
                  ;; function is the only place that mismatch is undone.
                  (let [o (settings->overlay-options
                           {:ui {:overlay {:max-height "40%" :min-width 25}}})]
                    (-> (expect (.-maxHeight o)) (.toBe "40%"))
                    (-> (expect (.-minWidth o)) (.toBe 25))
                    ;; And nothing kebab leaks through to pi-tui.
                    (-> (expect (js/Object.keys o)) (.toEqual ["width" "minWidth" "maxHeight" "anchor"])))))

            (it "takes counts as well as percentages"
                (fn []
                  (let [o (settings->overlay-options {:ui {:overlay {:width 50 :max-height 12}}})]
                    (-> (expect (.-width o)) (.toBe 50))
                    (-> (expect (.-maxHeight o)) (.toBe 12)))))

            (it "ignores sizes pi-tui could not parse"
                (fn []
                  ;; A SizeValue is a number or "N%". "wide" or "80" would make
                  ;; pi-tui compute NaN geometry.
                  (doseq [bad ["wide" "80" "%" true nil]]
                    (let [o (settings->overlay-options {:ui {:overlay {:width bad}}})]
                      (-> (expect (.-width o)) (.toBe "100%"))))))

            (it "survives settings with no :ui key at all"
                (fn []
                  (doseq [s [{} {:ui {}} {:ui {:overlay {}}} nil]]
                    (-> (expect (.-anchor (settings->overlay-options s)))
                        (.toBe "bottom-center")))))))

;;; ─── the wiring ──────────────────────────────────────────────────────────
;;; A fake TUI records exactly what `showOverlay` is handed. This is what pi-tui
;;; would position from, so it is the only thing worth asserting.

(defn- recording-tui []
  (let [seen (atom [])]
    {:seen seen
     :tui #js {:terminal #js {:columns 100 :rows 30}
               :showOverlay (fn [_component options]
                              (swap! seen conj options)
                              #js {:hide (fn [] nil)
                                   :focus (fn [] nil)
                                   :unfocus (fn [] nil)})
               :setFocus (fn [_] nil)
               :requestRender (fn [] nil)}}))

(defn- install-ui [tui & [options-fn]]
  (let [ui #js {}]
    (install! ui tui (cond-> {:restore-focus (fn [] nil)
                              :request-render (fn [] nil)}
                       options-fn (assoc :overlay-options-fn options-fn)))
    ui))

(describe "overlay placement through the real host"
          (fn []
            (it "puts the permission prompt at the bottom"
                (fn []
                  ;; Called exactly as middleware.cljs:312 calls it: two
                  ;; arguments, no options. That omission is why it was centred.
                  (let [{:keys [seen tui]} (recording-tui)
                        ui (install-ui tui)]
                    (.select ui "Allow 'edit'?"
                             #js ["Allow once" "Allow always (this project)" "Deny"])
                    (-> (expect (count @seen)) (.toBe 1))
                    (-> (expect (.-anchor (first @seen))) (.toBe "bottom-center")))))

            (it "places every primitive the same way"
                (fn []
                  ;; select/confirm/input/custom/showOverlay all resolve through
                  ;; the same fallback; if one drifts, this catches it.
                  (let [{:keys [seen tui]} (recording-tui)
                        ui (install-ui tui)]
                    (.select ui "s" #js ["a" "b"])
                    (.confirm ui "c" nil)
                    (.input ui "i" "" nil)
                    (.showOverlay ui "text" nil)
                    (.custom ui #js {:render (fn [_w _h] "x")} nil)
                    (-> (expect (count @seen)) (.toBe 5))
                    (doseq [o @seen]
                      (-> (expect (.-anchor o)) (.toBe "bottom-center"))))))

            (it "lets settings move it"
                (fn []
                  (let [{:keys [seen tui]} (recording-tui)
                        ui (install-ui tui #(settings->overlay-options
                                             {:ui {:overlay {:anchor "top-right"
                                                             :width "50%"}}}))]
                    (.select ui "q" #js ["a"])
                    (-> (expect (.-anchor (first @seen))) (.toBe "top-right"))
                    (-> (expect (.-width (first @seen))) (.toBe "50%")))))

            (it "re-reads settings on every overlay, not once at install"
                (fn []
                  ;; The thunk exists so a /settings change applies to the next
                  ;; overlay instead of needing a restart.
                  (let [{:keys [seen tui]} (recording-tui)
                        anchor (atom "bottom-center")
                        ui (install-ui tui #(settings->overlay-options
                                             {:ui {:overlay {:anchor @anchor}}}))]
                    (.select ui "first" #js ["a"])
                    (reset! anchor "center")
                    (.select ui "second" #js ["a"])
                    (-> (expect (.-anchor (first @seen))) (.toBe "bottom-center"))
                    (-> (expect (.-anchor (second @seen))) (.toBe "center")))))

            (it "an explicit per-call :overlay still wins"
                (fn []
                  ;; The escape hatch has to keep working, or a caller that
                  ;; genuinely needs a different placement has none.
                  (let [{:keys [seen tui]} (recording-tui)
                        ui (install-ui tui)]
                    (.select ui "q" #js ["a"]
                             #js {:overlay #js {:anchor "top-left" :width "30%"}})
                    (-> (expect (.-anchor (first @seen))) (.toBe "top-left")))))

            (it "falls back to the default when the settings thunk throws"
                (fn []
                  ;; A broken settings file must not cost the user their prompt.
                  (let [{:keys [seen tui]} (recording-tui)
                        ui (install-ui tui (fn [] (throw (js/Error. "bad settings"))))]
                    (.select ui "q" #js ["a"])
                    (-> (expect (.-anchor (first @seen))) (.toBe "bottom-center")))))

            (it "falls back when no settings thunk is supplied at all"
                (fn []
                  ;; sdk/gateway hosts install without one.
                  (let [{:keys [seen tui]} (recording-tui)
                        ui (install-ui tui)]
                    (.select ui "q" #js ["a"])
                    (-> (expect (.-anchor (first @seen)))
                        (.toBe (.-anchor default-overlay-options))))))))

;;; ─── through the real settings manager ───────────────────────────────────

(describe "a real settings file reaches pi-tui"
          (fn []
            (it "accepts camelCase JSON and keeps unmentioned fields defaulted"
                (fn []
                  ;; Two things at once. The JSON convention is camelCase and
                  ;; manager/normalize-keys rewrites it to kebab, so `maxHeight`
                  ;; arrives as :max-height and must come back out as maxHeight.
                  ;;
                  ;; And the merge is SHALLOW — a user's :ui map REPLACES the
                  ;; default one rather than deep-merging (the same trap :roles
                  ;; has). So a file setting only :anchor arrives with no
                  ;; :width at all, which is why every field in
                  ;; settings->overlay-options falls back independently.
                  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-overlay-"))
                        pp  (path/join dir "settings.json")]
                    (try
                      (fs/writeFileSync
                       pp (js/JSON.stringify
                           #js {:ui #js {:overlay #js {:anchor "top-center"
                                                       :maxHeight "33%"
                                                       :minWidth 22}}}))
                      (let [mgr    (create-settings-manager
                                    {:project-path pp
                                     :global-path (path/join dir "absent.json")})
                            opts   (settings->overlay-options ((:get mgr)))]
                        (-> (expect (.-anchor opts)) (.toBe "top-center"))
                        (-> (expect (.-maxHeight opts)) (.toBe "33%"))
                        (-> (expect (.-minWidth opts)) (.toBe 22))
                        ;; Never mentioned in the file, and wiped from defaults
                        ;; by the shallow merge — still correct.
                        (-> (expect (.-width opts)) (.toBe "100%")))
                      (finally
                        (try (fs/rmSync dir #js {:recursive true :force true})
                             (catch :default _ nil)))))))

            (it "ships the bottom default in settings, so /settings can show it"
                (fn []
                  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-overlay-"))]
                    (try
                      (let [mgr (create-settings-manager
                                 {:project-path (path/join dir "absent.json")
                                  :global-path (path/join dir "absent.json")})]
                        (-> (expect (get-in ((:get mgr)) [:ui :overlay :anchor]))
                            (.toBe "bottom-center")))
                      (finally
                        (try (fs/rmSync dir #js {:recursive true :force true})
                             (catch :default _ nil)))))))))

;;; ─── short terminals ─────────────────────────────────────────────────────

(describe "the bottom anchor at short terminal heights"
          (fn []
            (it "keeps the selected row visible"
                (fn []
                  ;; pi-tui slices overlay lines from the BOTTOM at maxHeight
                  ;; and render-frame uses a trailing window, so the selected
                  ;; row is the first thing lost if the box is sized wrong.
                  ;; /model dropped from an explicit 50% to the shared 70% in
                  ;; this change, and 70% of a 6-row terminal is the regime
                  ;; where that pinches.
                  (let [items (mapv (fn [i] #js {:value (str "m" i)
                                                 :label (str "model-" i)
                                                 :description "200.0k"})
                                    (range 60))]
                    (doseq [rows [6 8 10 12 16 24 40]]
                      (let [bw (oh/resolve-content-width default-overlay-options 100)
                            bh (oh/resolve-max-height default-overlay-options rows)
                            p  (oh/make-select-picker "Model" items (fn [_] nil) (fn [] bw))]
                        (dotimes [_ 30] ((.-onInput p) "" #js {:downArrow true}))
                        (let [lines (.split ((.-render p) 100 bh) "\n")]
                          (-> (expect (.-length lines)) (.toBeLessThanOrEqual bh))
                          (-> (expect (some (fn [l] (and (.includes l "▶")
                                                         (.includes l "model-30")))
                                            (vec lines)))
                              (.toBe true))))))))))

;;; ─── no bleed ────────────────────────────────────────────────────────────

(describe "the overlay covers the full width"
          (fn []
            (it "leaves no columns for the base UI to show through"
                (fn []
                  ;; pi-tui composites an overlay line over the base content at
                  ;; a column offset, so anything narrower than the terminal
                  ;; leaves the rows underneath visible on BOTH sides. At 90%
                  ;; on a 170-column terminal that was 8 columns each side of
                  ;; the editor's border and the status bar, framing every row
                  ;; in debris:
                  ;;   ────────  ▶ Allow once                        ─────────
                  ;;    nyma │ >  (1/3)
                  (doseq [cols [200 170 120 100 80 60 40]]
                    (-> (expect (oh/resolve-content-width default-overlay-options cols))
                        (.toBe cols)))))

            (it "still honours a narrower width when asked"
                (fn []
                  ;; Full-width is the default, not a rule — the setting stays
                  ;; live for anyone who prefers a floating panel.
                  (let [o (settings->overlay-options {:ui {:overlay {:width "60%"}}})]
                    (-> (expect (oh/resolve-content-width o 100)) (.toBe 60)))))))
