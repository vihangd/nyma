(ns render-width-safety.test
  "pi-tui THROWS when a rendered line is wider than the terminal, from inside
   its own render timer, after calling stop(). The exception escapes with the
   terminal torn down and kills the session — there is no error hook and no
   strict-width opt-out. So an over-width line is not a cosmetic bug, it is
   data loss, and nyma has to guarantee it never emits one.

   A real session died this way: tab-indented Go source under an `Allow 'edit'?`
   permission overlay produced a line 3 columns per tab too wide. A tab is
   measured differently by every layer — Bun.stringWidth 0, pi-tui visibleWidth
   1, pi-tui's overlay compositor 3, a terminal 4-8 — so a line containing one
   has no single true width.

   These tests measure with pi-tui's own `visibleWidth`, because that is the
   function whose verdict crashes us."
  (:require ["bun:test" :refer [describe it expect]]
            ["@mariozechner/pi-tui" :refer [visibleWidth]]
            [clojure.string :as str]
            [agent.utils.ansi :as ansi]
            [agent.ui.chat-pane :refer [create-chat-pane]]
            [agent.ui.chat-renderer :as cr :refer [clamp-line]]))

(def ^:private ESC (js/String.fromCharCode 27))

;; Verbatim from the crash log — the Go snippet that was on screen.
(def ^:private crashing-go
  (str "```go\n"
       "\tln, err := net.Listen(\"tcp\", addr)\n"
       "\tif err != nil {\n"
       "\t\tlog.Fatalf(\"Failed to listen: %v\", err)\n"
       "\t}\n"
       "```"))

(defn- render [content width]
  (vec (cr/render-message {:msg {:role "assistant" :content content}
                           :width width :theme {} :md-cache nil})))

;; ── The reported crash ───────────────────────────────────

(describe "the reported crash"
          (fn []
            (it "renders tab-indented Go with no tabs left and nothing over width"
                (fn []
                  (doseq [w [167 120 80 40]]
                    (let [lines (render crashing-go w)]
                      (doseq [l lines]
                        ;; A surviving tab means the width is unknowable again.
                        (-> (expect (.includes l "\t")) (.toBe false))
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))

            (it "agrees with pi-tui about the width after expansion"
                (fn []
                  ;; The broken property: our measure said a tab was free while
                  ;; pi-tui's said otherwise. Post-expansion they must match.
                  (doseq [l (render crashing-go 80)]
                    (-> (expect (ansi/string-width l)) (.toBe (visibleWidth l))))))

            (it "keeps indentation levels distinct"
                (fn []
                  (let [text (str/join "\n" (render crashing-go 80))]
                    ;; log.Fatalf was two tabs deep, net.Listen one.
                    (-> (expect (.includes text "        log.Fatalf")) (.toBe true))
                    (-> (expect (.includes text "    ln, err")) (.toBe true)))))))

;; ── The class, not just tabs ─────────────────────────────

(def ^:private adversarial
  {"tabs"           "\tone\n\t\ttwo\n\t\t\tthree"
   "cjk"            "漢字テスト 中文字符 한국어 텍스트"
   "emoji"          "🎉 done 👨‍👩‍👧‍👦 family 🇯🇵 flag"
   "combining"      "é é ñ ü  áb̀ç"
   "ansi"           (str ESC "[38;2;255;0;0mred" ESC "[0m " ESC "[1mbold" ESC "[0m")
   "osc8-link"      (str ESC "]8;;https://example.com/very/long/path" ESC "\\link text" ESC "]8;;" ESC "\\")
   "crlf"           "line one\r\nline two\r\nline three"
   "long-word"      (apply str (repeat 300 "x"))
   "tabs-plus-cjk"  "\t漢字\t\tmore 漢字"
   "mixed"          (str "\tcode 漢 🎉 " ESC "[1mbold" ESC "[0m\r\n\t\tdeeper")})

(describe "adversarial content never exceeds the width it was given"
          (fn []
            (doseq [[label content] adversarial]
              (it (str label " stays within width")
                  (fn []
                    (doseq [w [167 100 80 40 20]]
                      (doseq [l (render content w)]
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))))

;; ── The clamp ────────────────────────────────────────────

(describe "chat-pane clamp"
          (fn []
            (it "truncates a line that would crash the renderer"
                (fn []
                  (-> (expect (visibleWidth (clamp-line (apply str (repeat 200 "x")) 50)))
                      (.toBeLessThanOrEqual 50))))

            (it "handles wide glyphs without overshooting"
                (fn []
                  ;; A CJK cell is 2 columns; a naive character clamp would
                  ;; leave this at 2x the width.
                  (-> (expect (visibleWidth (clamp-line (apply str (repeat 60 "漢")) 40)))
                      (.toBeLessThanOrEqual 40))))

            (it "leaves a conforming line untouched"
                (fn []
                  (-> (expect (clamp-line "short" 50)) (.toBe "short"))))

            (it "is a no-op for a nonsense width rather than throwing"
                (fn []
                  (-> (expect (clamp-line "abc" 0)) (.toBe "abc"))
                  (-> (expect (clamp-line "abc" nil)) (.toBe "abc"))))))

;; ── expand-tabs ──────────────────────────────────────────

(describe "ansi/expand-tabs"
          (fn []
            (it "advances to the next tab stop rather than substituting 4 spaces"
                (fn []
                  ;; At column 2, a tab advances 2 columns — not 4.
                  (-> (expect (ansi/expand-tabs "ab\tcd")) (.toBe "ab  cd"))
                  (-> (expect (ansi/expand-tabs "\tx")) (.toBe "    x"))))

            (it "expands every line of a multi-line string"
                (fn []
                  (-> (expect (.includes (ansi/expand-tabs "\ta\n\tb") "\t")) (.toBe false))))

            (it "passes tab-free input through unchanged"
                (fn []
                  (-> (expect (ansi/expand-tabs "plain text")) (.toBe "plain text"))))

            (it "tolerates nil"
                (fn []
                  (-> (expect (ansi/expand-tabs nil)) (.toBeNil))))))

;; ── The component boundary ───────────────────────────────
;; clamp-line is tested above in isolation; this drives the real component to
;; prove the clamp is actually wired into the funnel that feeds pi-tui.

(describe "chat-pane render is width-safe end to end"
          (fn []
            (it "emits nothing over width for adversarial content at any size"
                (fn []
                  (let [pane (create-chat-pane {})]
                    (doseq [[_ content] adversarial]
                      (.pushMessage pane #js {:role "assistant" :content content}))
                    (doseq [w [167 80 40 20 10]]
                      (doseq [l (vec (.render pane w))]
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))

            ;; NOTE: no test here proves the clamp is *wired*, and that is not
            ;; an oversight. Through the pane, chat-renderer already emits
            ;; conforming lines for everything in the corpus — removing the
            ;; clamp changes no result. It is unfired defence-in-depth against
            ;; the width bugs in the pi-tui release we are pinned to, and a test
            ;; asserting otherwise would be theatre. The clamp's behaviour is
            ;; covered directly above.

            (it "survives a width of 1 without throwing"
                (fn []
                  ;; A resize to a sliver must degrade, not crash.
                  (let [pane (create-chat-pane {})]
                    (.pushMessage pane #js {:role "assistant" :content crashing-go})
                    (doseq [l (vec (.render pane 1))]
                      (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual 1))))))))

;; ── render-message honours its own width contract ────────
;; An earlier note here blamed the markdown cache. That was wrong: measured with
;; and without a cache atom the results are identical. The variable is WIDTH.
;;
;; Before the exit clamp, every role overflowed and each in a different way —
;; assistant from width 3, user from 5, thinking from 6, and the tool branches
;; from 10, the last because they floor their wrap budget at `(max 10 …)`, a
;; floor above the width they were handed. `wrap-ansi` cannot break inside a
;; grapheme cluster, so a run of flags at width 2 came out 240 columns over.

(def ^:private wide "🇯🇵漢字 hello world")

(def ^:private every-role
  {"user"        {:role "user" :content wide}
   "assistant"   {:role "assistant" :content wide}
   "thinking"    {:role "thinking" :content wide}
   "asst+think"  {:role "assistant" :content "<think>deep 漢字 thought</think>answer 🇯🇵"}
   "tool-start"  {:role "tool-start" :tool-name "read" :args {:path "/very/long/path/漢字.go"}}
   "tool-end"    {:role "tool-end" :tool-name "read" :args {:path "/x"}
                  :result (apply str (repeat 20 "🇯🇵"))}
   "error"       {:role "error" :content wide}
   "shell"       {:role "shell" :content wide}
   "info"        {:role "info" :content wide}
   "plan"        {:role "plan" :content wide}
   "unknown"     {:role "surprise" :content wide}})

(describe "render-message never exceeds the width it was given"
          (fn []
            (doseq [[label msg] every-role]
              (it (str label " fits at every width from 1 to 12")
                  (fn []
                    ;; The full matrix on purpose: a single-role test would have
                    ;; missed the tool branches, which failed from width 10.
                    (doseq [w (range 1 13)]
                      (doseq [l (cr/render-message {:msg msg :width w
                                                    :theme {} :md-cache nil})]
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))

            (it "contains the catastrophic case"
                (fn []
                  ;; 60 flags at width 2 previously produced one line 240 columns over.
                  (doseq [l (cr/render-message {:msg {:role "assistant"
                                                      :content (apply str (repeat 60 "🇯🇵"))}
                                                :width 2 :theme {} :md-cache nil})]
                    (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual 2)))))

            (it "is unaffected by the markdown cache"
                (fn []
                  ;; The claim this corrects. Same input, cache vs no cache.
                  (let [m {:role "assistant" :content "🇯🇵"}
                        no-cache (cr/render-message {:msg m :width 3 :theme {} :md-cache nil})
                        cached   (cr/render-message {:msg m :width 3 :theme {}
                                                     :md-cache (atom nil)})]
                    (-> (expect (vec no-cache)) (.toEqual (vec cached))))))))

(describe "the clamp does not alter normal rendering"
          (fn []
            (doseq [[label msg] every-role]
              (it (str label " is untouched at readable widths")
                  (fn []
                    ;; The clamp must remove crashes without silently truncating
                    ;; real content, so at sane widths it has to be a no-op:
                    ;; every line already fits, therefore clamp-line returns it
                    ;; identically.
                    (doseq [w [40 80 120]]
                      (doseq [l (cr/render-message {:msg msg :width w
                                                    :theme {} :md-cache nil})]
                        (-> (expect (clamp-line l w)) (.toBe l)))))))))
