(ns queue-followup-key-test
  "alt+enter queues a follow-up instead of steering.

   Both queues have always existed — `steer-queue` drains between tool steps,
   `follow-queue` after the turn finishes — but only steering had a key. Enter
   during a turn steers, which is the right default and the wrong thing when
   you mean \"when you're done, also do this\": a steer can derail the turn it
   interrupts.

   The encodings are pinned here because this is a repo where a binding with no
   working wire is the recurring bug: `app.tools.expand` sat documented with no
   handler, and every keybindings.json binding was inert because nothing read
   the shortcut map at keypress time. A binding nothing can trigger is worse
   than no binding, so the assertion is that pi-tui's `matchesKey` really
   answers for the bytes a terminal sends."
  (:require ["bun:test" :refer [test expect describe]]
            ["@earendil-works/pi-tui" :refer [matchesKey]]
            [agent.keybinding-registry :as kbr]))

(def ^:private esc (js/String.fromCharCode 27))

(describe "alt+enter is matchable"
          (fn []
            (test "matches all three encodings a terminal can send"
                  (fn []
        ;; Classic (ESC prefix), Kitty (CSI u with modifier 3 = alt), and
        ;; xterm modifyOtherKeys — which pi-tui itself switches the terminal
        ;; into when the Kitty query goes unanswered.
                    (doseq [data [(str esc "\r") (str esc "[13;3u") (str esc "[27;3;13~")]]
                      (-> (expect (matchesKey data "alt+enter")) (.toBe true)))))

            (test "does not match plain Enter — submit must keep working"
                  (fn []
                    (-> (expect (matchesKey "\r" "alt+enter")) (.toBe false))))

            (test "does not match shift+enter, which pi-tui's editor owns for newline"
                  (fn []
        ;; This is why alt+enter was free to take: the editor's `newLine`
        ;; default is shift+enter, not alt+enter.
                    (-> (expect (matchesKey (str esc "[13;2u") "alt+enter")) (.toBe false))))))

(describe "the action"
          (fn []
            (test "is registered with alt+enter as its default"
                  (fn []
                    (-> (expect (get-in kbr/default-actions ["app.queue" :default-keys]))
                        (.toEqual (clj->js ["alt+enter"])))))

            (test "says which queue it uses, so it is distinguishable from app.steer"
                  (fn []
                    (let [q (get-in kbr/default-actions ["app.queue" :description])
                          s (get-in kbr/default-actions ["app.steer" :description])]
                      (-> (expect q) (.toContain "AFTER"))
                      (-> (expect (= q s)) (.toBe false)))))

            (test "is reboundable — not :fixed? like app.steer and app.submit"
                  (fn []
        ;; `return` is fixed because the editor owns it. alt+enter is ours.
                    (-> (expect (get-in kbr/default-actions ["app.queue" :fixed?])) (.toBeFalsy))))

            (test "does not collide with another default binding"
                  (fn []
                    (let [reg (kbr/create-registry {})
                          conflicting (->> (:conflicts reg)
                                           (filter (fn [c] (some #{"app.queue"} (:action-ids c)))))]
                      (-> (expect (count conflicting)) (.toBe 0)))))))
