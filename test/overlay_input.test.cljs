(ns overlay-input.test
  "Typing was dropped in every overlay — the picker filter, /login's API-key
   field, the model switcher.

   pi-tui never leaves the terminal sending bare characters. It queries for the
   Kitty keyboard protocol and falls back to xterm's modifyOtherKeys
   (terminal.js:128-138), so a plain `a` arrives as `ESC[97u` or `ESC[27;1;97~`.
   `printable-char` tested `(= (count data) 1)` and returned nil for both.
   Arrows and Enter kept working, because those go through `matchesKey` which
   understands every encoding — that asymmetry is why it presented as a dead
   overlay rather than as dead typing.

   The existing overlay_host tests drive `handleInput` with hand-written LEGACY
   byte constants, so they were green throughout. These use the encodings a
   real terminal actually sends."
  (:require ["bun:test" :refer [describe it expect]]
            ["@earendil-works/pi-tui" :refer [TuiMainScreen Editor]]
            [agent.ui.overlay-host :as oh :refer [printable-char install! enter-key?]]
            [agent.modes.interactive :refer [abort-on-escape?]]))

(def ^:private ESC (js/String.fromCharCode 27))

;; Encodings a real terminal emits, by mode.
(defn- kitty [code] (str ESC "[" code "u"))
(defn- kitty-shift [code shifted] (str ESC "[" code ":" shifted ";2u"))
(defn- mok [code] (str ESC "[27;1;" code "~"))
(defn- mok-shift [code] (str ESC "[27;2;" code "~"))

(describe "printable-char decodes what the terminal actually sends"
          (fn []
            (it "handles the Kitty keyboard protocol"
                (fn []
                  (-> (expect (printable-char (kitty 97))) (.toBe "a"))
                  (-> (expect (printable-char (kitty 49))) (.toBe "1"))
                  (-> (expect (printable-char (kitty 45))) (.toBe "-"))
                  (-> (expect (printable-char (kitty 47))) (.toBe "/"))
                  (-> (expect (printable-char (kitty 32))) (.toBe " "))))

            (it "handles shifted keys under Kitty"
                (fn []
                  ;; The shifted codepoint is the second field; without it
                  ;; every capital would arrive lowercase.
                  (-> (expect (printable-char (kitty-shift 97 65))) (.toBe "A"))
                  (-> (expect (printable-char (kitty-shift 49 33))) (.toBe "!"))))

            (it "handles xterm modifyOtherKeys, the fallback encoding"
                (fn []
                  ;; Used when the terminal does not answer the Kitty query
                  ;; (terminal.js:132-137). Missing this left typing dead on
                  ;; those terminals even after the Kitty half was fixed.
                  (-> (expect (printable-char (mok 98))) (.toBe "b"))
                  (-> (expect (printable-char (mok-shift 66))) (.toBe "B"))))

            (it "still handles a bare character"
                (fn []
                  ;; A terminal in neither mode. The decoder returns undefined
                  ;; here, so the original single-character test must remain.
                  (-> (expect (printable-char "a")) (.toBe "a"))
                  (-> (expect (printable-char "Z")) (.toBe "Z"))))

            (it "refuses keys that are not text, in every encoding"
                (fn []
                  ;; Backspace decodes to a one-character string holding DEL,
                  ;; which prints as nothing and is TRUTHY — unguarded it took
                  ;; the printable branch and swallowed the key instead of
                  ;; letting the backspace branch handle it.
                  (doseq [d [(kitty 127) (kitty 13) (kitty 27)
                             (js/String.fromCharCode 127)
                             (js/String.fromCharCode 13)
                             ESC]]
                    (-> (expect (printable-char d)) (.toBeNil)))))

            (it "leaves ctrl+p / ctrl+n as their bare letter"
                (fn []
                  ;; dispatch-input tests (and (.-ctrl key) (= input "p")).
                  (-> (expect (printable-char (str ESC "[112;5u"))) (.toBe "p"))
                  (-> (expect (printable-char (str ESC "[110;5u"))) (.toBe "n"))))

            (it "still unwraps a bracketed paste"
                (fn []
                  (let [pasted (str ESC "[200~" "sk-secret-key" ESC "[201~")]
                    (-> (expect (printable-char pasted)) (.toBe "sk-secret-key")))))))

;;; ─── end to end ──────────────────────────────────────────────────────────
;;; Through a real TUI: showOverlay → setFocus → handleInput. The existing
;;; suite calls handleInput on the adapted component directly, which skips the
;;; routing AND lets it use legacy bytes a real terminal would not send — which
;;; is why it stayed green while every overlay was unusable.

;; pi-tui 0.85 removed the public `handleInput` on the TUI — input now arrives
;; only through the callback the TUI hands to `terminal.start`. So the fake
;; terminal has to capture that callback, and the TUI has to actually be
;; started, which is closer to how a real terminal drives it anyway.
(defn- fake-terminal
  "Returns [terminal on-input-atom]; the atom holds the TUI's input callback
   once `start` has been called."
  []
  (let [on-input (atom nil)]
    [#js {:columns 100 :rows 30
          :start (fn [oi _] (reset! on-input oi) nil)
          :stop (fn [] nil) :write (fn [_] nil)
          :drainInput (fn [& _] (js/Promise.resolve))
          :hideCursor (fn [] nil) :showCursor (fn [] nil)
          :clearLine (fn [] nil) :clearFromCursor (fn [] nil)
          :clearScreen (fn [] nil) :moveBy (fn [_] nil)
          :setTitle (fn [_] nil) :setProgress (fn [_] nil)
          :kittyProtocolActive true}
     on-input]))

(defn- feed!
  "Deliver `data` the way the terminal would."
  [on-input data]
  (when-let [f @on-input] (f data)))

(defn- host []
  (let [[term on-input] (fake-terminal)
        tui (new TuiMainScreen term)
        ui  #js {}]
    (.start tui)
    (install! ui tui {:restore-focus (fn [] nil) :request-render (fn [] nil)})
    {:tui tui :ui ui :on-input on-input}))

(defn- type-str! [on-input s]
  ;; Send each character the way a Kitty-protocol terminal would.
  (doseq [ch (vec (.split s ""))]
    (feed! on-input (kitty (.charCodeAt ch 0)))))

(describe "typing reaches an overlay through the real TUI"
          (fn []
            (it "filters the picker by what was typed"
                (^:async
                 fn []
                 (let [{:keys [tui ui on-input]} (host)
                       p (.select ui "Pick" #js ["alpha" "beta" "gamma"])]
                   ;; Narrow to the only entry containing "bet", then accept.
                   ;; With typing dropped the filter stays empty and Enter
                   ;; takes index 0 — "alpha" — which is exactly how the bug
                   ;; presented: an apparently arbitrary answer.
                   (type-str! on-input "bet")
                   (feed! on-input (str ESC "[13u"))
                   (-> (expect (js-await p)) (.toBe "beta")))))

            (it "arrows and Enter still work alongside typing"
                (^:async
                 fn []
                 ;; These went through matchesKey and were never broken; the
                 ;; test exists so a fix to typing cannot regress them.
                 (let [{:keys [tui ui on-input]} (host)
                       p (.select ui "Pick" #js ["alpha" "beta" "gamma"])]
                   (feed! on-input (str ESC "[B"))
                   (feed! on-input (str ESC "[13u"))
                   (-> (expect (js-await p)) (.toBe "beta")))))

            (it "types into an input overlay, as /login does"
                (^:async
                 fn []
                 (let [{:keys [tui ui on-input]} (host)
                       p (.input ui "API key" "" nil)]
                   (type-str! on-input "sk-abc123")
                   (feed! on-input (str ESC "[13u"))
                   (-> (expect (js-await p)) (.toBe "sk-abc123")))))))

;;; ─── Escape while an overlay is open ─────────────────────────────────────

(describe "global Escape does not reach past an open overlay"
          (fn []
            (it "aborts the run only when no overlay is up"
                (fn []
                  ;; Input listeners run BEFORE focus dispatch, so one Esc used
                  ;; to kill the turn AND dismiss the picker. A permission
                  ;; prompt is shown precisely while a submit is in flight, so
                  ;; cancelling the prompt aborted the run it was asking about.
                  (-> (expect (abort-on-escape? ESC true 0)) (.toBe true))
                  (-> (expect (abort-on-escape? ESC true 1)) (.toBe false))))

            (it "leaves Esc alone when nothing is running"
                (fn []
                  ;; Idle, Esc belongs to the editor.
                  (-> (expect (abort-on-escape? ESC false 0)) (.toBe false))))

            (it "ignores keys that are not Escape"
                (fn []
                  (doseq [d ["a" (str ESC "[97u") (str ESC "[B")]]
                    (-> (expect (abort-on-escape? d true 0)) (.toBe false)))))))

;;; ─── Enter, in every encoding ────────────────────────────────────────────
;;; pi-tui's own matchesKey misses one. Its plain-Enter branch
;;; (keys.js:719-725) checks \r, \n, SS3 and the two Kitty forms and RETURNS —
;;; never reaching the matchesModifyOtherKeys call below it, which is wired
;;; only for MODIFIED Enter. pi-tui puts the terminal into modifyOtherKeys
;;; itself when the Kitty query goes unanswered, so on those terminals plain
;;; Enter was unrecognised and no picker could ever accept a selection.

(describe "Enter is recognised whatever the terminal sends"
          (fn []
            (it "accepts every encoding"
                (fn []
                  (doseq [d [(js/String.fromCharCode 13)      ;; legacy CR
                             (js/String.fromCharCode 10)      ;; legacy LF
                             (str ESC "[13u")                 ;; kitty
                             (str ESC "[13;1u")               ;; kitty, explicit modifier
                             (str ESC "[13;1:1u")             ;; kitty, press event
                             (str ESC "[57414u")              ;; kitty KP_ENTER
                             (str ESC "[27;1;13~")            ;; modifyOtherKeys CR
                             (str ESC "[27;1;10~")]]          ;; modifyOtherKeys LF
                    (-> (expect (enter-key? d)) (.toBe true)))))

            (it "does not fire on anything else"
                (fn []
                  ;; A loose match here would make every keystroke submit.
                  (doseq [d ["a" (str ESC "[97u") (str ESC "[B") (str ESC "[A")
                             ESC (str ESC "[27;1;97~") (str ESC "[27;1;27~")
                             (js/String.fromCharCode 127)]]
                    (-> (expect (enter-key? d)) (.toBe false)))))

            (it "resolves a real picker under every encoding"
                (^:async
                 fn []
                 ;; The reported symptom: the prompt is on screen, the arrow
                 ;; moves, and Enter does nothing at all — the promise never
                 ;; settles, so the tool call hangs waiting for an approval
                 ;; that can't be given.
                 (doseq [enter [(js/String.fromCharCode 13)
                                (str ESC "[13u")
                                (str ESC "[27;1;13~")
                                (str ESC "[57414u")]]
                   (let [{:keys [tui ui on-input]} (host)
                         p (.select ui "Allow 'web_search'?"
                                    #js ["Allow once" "Allow always (this project)" "Deny"])]
                     (feed! on-input (str ESC "[B"))
                     (feed! on-input enter)
                     (-> (expect (js-await p)) (.toBe "Allow always (this project)"))))))))

;;; ─── stacked overlays ────────────────────────────────────────────────────
;;; "The overlay doesn't accept input SOMETIMES" — the sometimes is a second
;;; overlay. pi-tui's hide() restores focus to the topmost REMAINING overlay
;;; (tui.js:191-193), but the host then called restore-focus unconditionally
;;; and yanked it to the editor. The surviving overlay stayed on screen and
;;; silently stopped accepting input: arrows and Enter went to the editor.
;;;
;;; Two tool calls in one step both hitting the permission gate is enough, as
;;; is a prompt arriving while a picker is already open.

(defn- host-with-editor []
  (let [[term on-input] (fake-terminal)
        tui    (new TuiMainScreen term)
        theme  (new js/Proxy #js {} #js {:get (fn [& _] (fn [s] s))})
        editor (new Editor tui theme #js {:paddingX 1})
        ui     #js {}]
    (.addChild tui editor)
    (.setFocus tui editor)
    (install! ui tui {:restore-focus (fn [] (.setFocus tui editor))
                      :request-render (fn [] nil)})
    (.start tui)
    {:tui tui :ui ui :editor editor :on-input on-input}))

(describe "two overlays at once"
          (fn []
            (it "keeps the remaining overlay usable after the top one closes"
                (^:async
                 fn []
                 (let [{:keys [tui ui on-input]} (host-with-editor)
                       first-p  (.select ui "Allow 'web_search'?" #js ["Allow once" "Deny"])
                       second-p (.select ui "Allow 'web_fetch'?"  #js ["Allow once" "Deny"])]
                   (-> (expect (.-length (.-overlayStack tui))) (.toBe 2))
                   ;; Answer the focused (second) prompt.
                   (feed! on-input (str ESC "[13u"))
                   (-> (expect (js-await second-p)) (.toBe "Allow once"))
                   ;; The first is still up — and must still take input.
                   (-> (expect (.-length (.-overlayStack tui))) (.toBe 1))
                   (feed! on-input (str ESC "[13u"))
                   (-> (expect (js-await first-p)) (.toBe "Allow once")))))

            (it "returns focus to the editor once the stack is empty"
                (^:async
                 fn []
                 ;; The behaviour the unconditional refocus existed for; it must
                 ;; survive the fix.
                 (let [{:keys [tui ui editor on-input]} (host-with-editor)
                       p (.select ui "Pick" #js ["a" "b"])]
                   (feed! on-input (str ESC "[13u"))
                   (js-await p)
                   (-> (expect (.-length (.-overlayStack tui))) (.toBe 0))
                   (-> (expect (identical? (.-focusedComponent tui) editor)) (.toBe true)))))

            (it "leaves focus on the survivor, not the editor"
                (^:async
                 fn []
                 (let [{:keys [tui ui editor on-input]} (host-with-editor)
                       _ (.select ui "outer" #js ["a"])
                       inner (.select ui "inner" #js ["a"])]
                   (feed! on-input (str ESC "[13u"))
                   (js-await inner)
                   (-> (expect (identical? (.-focusedComponent tui) editor)) (.toBe false))
                   (-> (expect (identical? (.-focusedComponent tui)
                                           (.-component (aget (.-overlayStack tui) 0))))
                       (.toBe true)))))))
