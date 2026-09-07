(ns keybindings-dispatch.test
  "A registered shortcut must fire on a keypress.

   `registerShortcut` put a handler in `(:shortcuts agent)` and
   `apply-keybindings` put every keybindings.json binding in the same atom —
   and NOTHING read that atom at keypress time. `interactive.cljs` had exactly
   two input listeners, for Esc and Ctrl+C. So prompt_history's ctrl+r opened
   nothing, model_roles' role-cycle key cycled nothing, and a user's custom
   binding did nothing at all.

   The suite was green because `extensions.test.cljs:184` asserts the atom
   received an entry and stops there — registry written, nobody reading it
   (roadmap 3a). This drives the real `matchesKey` from pi-tui with real
   terminal bytes, which is the assertion that was missing.

   Interactive's listener is one line over `dispatch-shortcut!`; the byte→combo
   matching, the two stored shapes, and the error isolation all live here."
  (:require ["bun:test" :refer [describe it expect]]
            ["@mariozechner/pi-tui" :refer [matchesKey]]
            [agent.keybindings :as kb]))

;; Real terminal bytes, not key names — the layer where this broke. Built from
;; char codes rather than pasted control characters, which an editor or a
;; formatter can silently eat.
(def ctrl-r (js/String.fromCharCode 18))
(def ctrl-p (js/String.fromCharCode 16))
(def alt-p  (str (js/String.fromCharCode 27) "p"))

(describe "shortcut dispatch" (fn []

                                (it "parses the bytes it claims to" (fn []
    ;; Guard the guard: if matchesKey stopped recognising these, every
    ;; assertion below would pass by never matching anything.
                                                                      (-> (expect (matchesKey ctrl-r "ctrl+r")) (.toBe true))
                                                                      (-> (expect (matchesKey alt-p "alt+p")) (.toBe true))
                                                                      (-> (expect (matchesKey ctrl-p "ctrl+r")) (.toBe false))))

                                (it "runs the handler registerShortcut stored — a bare fn" (fn []
    ;; prompt_history's shape.
                                                                                             (let [ran (atom false)
                                                                                                   shortcuts {"ctrl+r" (fn [] (reset! ran true))}]
                                                                                               (-> (expect (kb/dispatch-shortcut! shortcuts ctrl-r matchesKey)) (.toBe true))
                                                                                               (-> (expect @ran) (.toBe true)))))

                                (it "runs the handler apply-keybindings stored — a map" (fn []
    ;; keybindings.json's shape. Two shapes in one atom: a dispatcher that
    ;; knows only one of them leaves half the bindings dead, which is the
    ;; formatArgs bug in a different costume.
                                                                                          (let [ran (atom false)
                                                                                                shortcuts {"ctrl+r" {:action "command:clear" :source "keybindings.json"
                                                                                                                     :handler (fn [] (reset! ran true))}}]
                                                                                            (-> (expect (kb/dispatch-shortcut! shortcuts ctrl-r matchesKey)) (.toBe true))
                                                                                            (-> (expect @ran) (.toBe true)))))

                                (it "leaves an unbound key alone" (fn []
    ;; The return value is what interactive turns into `{consume: true}`, so a
    ;; false here is the difference between typing normally and a dead editor.
                                                                    (let [ran (atom false)
                                                                          shortcuts {"ctrl+r" (fn [] (reset! ran true))}]
                                                                      (-> (expect (kb/dispatch-shortcut! shortcuts ctrl-p matchesKey)) (.toBe false))
                                                                      (-> (expect @ran) (.toBe false)))))

                                (it "matches the real combos the two shipped extensions register" (fn []
    ;; prompt_history binds ctrl+r; model_roles' default cycle key is alt+p
    ;; (its comment says it deliberately avoids ctrl+r).
                                                                                                    (let [log (atom [])
                                                                                                          shortcuts {"ctrl+r" (fn [] (swap! log conj :history))
                                                                                                                     "alt+p"  (fn [] (swap! log conj :cycle))}]
                                                                                                      (kb/dispatch-shortcut! shortcuts ctrl-r matchesKey)
                                                                                                      (kb/dispatch-shortcut! shortcuts alt-p matchesKey)
                                                                                                      (-> (expect @log) (.toEqual #js [:history :cycle])))))

                                (it "reports handled — and survives — when the handler throws" (fn []
    ;; A shortcut that throws must not take the input pipeline down, and the
    ;; key was still consumed: it matched.
                                                                                                 (let [shortcuts {"ctrl+r" (fn [] (throw (js/Error. "boom")))}]
                                                                                                   (-> (expect (kb/dispatch-shortcut! shortcuts ctrl-r matchesKey)) (.toBe true)))))

                                (it "a user binding replaces an extension's on the same combo" (fn []
    ;; Load order: extensions register first (cli.cljs:556), apply-keybindings
    ;; runs after (:620), so the user wins — one map key, last writer. Correct
    ;; precedence, and now a warning rather than a silent swap.
                                                                                 (let [log        (atom [])
                                                                                       shortcuts  (atom {"ctrl+r" (fn [] (swap! log conj "extension"))})
                                                                                       commands   (atom {"clear" {:handler (fn [_a _c] (swap! log conj "user"))}})]
                                                                                   (kb/apply-keybindings shortcuts commands {"ctrl+r" "command:clear"})
                                                                                   (kb/dispatch-shortcut! @shortcuts ctrl-r matchesKey)
                                                                                   (-> (expect @log) (.toEqual #js ["user"])))))

                                (it "does nothing with an empty registry" (fn []
                                                                            (-> (expect (kb/dispatch-shortcut! {} ctrl-r matchesKey)) (.toBe false))))

                                (it "survives a combo matchesKey cannot parse" (fn []
    ;; A typo'd binding in keybindings.json must not break every other key.
                                                                                 (let [ran (atom false)
                                                                                       shortcuts {"ctrl+" (fn [] (reset! ran "bad"))
                                                                                                  "ctrl+r" (fn [] (reset! ran "good"))}]
                                                                                   (-> (expect (kb/dispatch-shortcut! shortcuts ctrl-r matchesKey)) (.toBe true))
                                                                                   (-> (expect @ran) (.toBe "good")))))))
