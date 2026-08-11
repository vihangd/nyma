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
            ["@mariozechner/pi-tui" :refer [TUI]]
            [agent.ui.overlay-host :as oh :refer [printable-char install!]]
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

(defn- fake-terminal []
  #js {:columns 100 :rows 30
       :start (fn [_ _] nil) :stop (fn [] nil) :write (fn [_] nil)
       :hideCursor (fn [] nil) :showCursor (fn [] nil)})

(defn- host []
  (let [tui (new TUI (fake-terminal))
        ui  #js {}]
    (install! ui tui {:restore-focus (fn [] nil) :request-render (fn [] nil)})
    {:tui tui :ui ui}))

(defn- type-str! [tui s]
  ;; Send each character the way a Kitty-protocol terminal would.
  (doseq [ch (vec (.split s ""))]
    (.handleInput tui (kitty (.charCodeAt ch 0)))))

(describe "typing reaches an overlay through the real TUI"
          (fn []
            (it "filters the picker by what was typed"
                (^:async
                 fn []
                 (let [{:keys [tui ui]} (host)
                       p (.select ui "Pick" #js ["alpha" "beta" "gamma"])]
                   ;; Narrow to the only entry containing "bet", then accept.
                   ;; With typing dropped the filter stays empty and Enter
                   ;; takes index 0 — "alpha" — which is exactly how the bug
                   ;; presented: an apparently arbitrary answer.
                   (type-str! tui "bet")
                   (.handleInput tui (str ESC "[13u"))
                   (-> (expect (js-await p)) (.toBe "beta")))))

            (it "arrows and Enter still work alongside typing"
                (^:async
                 fn []
                 ;; These went through matchesKey and were never broken; the
                 ;; test exists so a fix to typing cannot regress them.
                 (let [{:keys [tui ui]} (host)
                       p (.select ui "Pick" #js ["alpha" "beta" "gamma"])]
                   (.handleInput tui (str ESC "[B"))
                   (.handleInput tui (str ESC "[13u"))
                   (-> (expect (js-await p)) (.toBe "beta")))))

            (it "types into an input overlay, as /login does"
                (^:async
                 fn []
                 (let [{:keys [tui ui]} (host)
                       p (.input ui "API key" "" nil)]
                   (type-str! tui "sk-abc123")
                   (.handleInput tui (str ESC "[13u"))
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
