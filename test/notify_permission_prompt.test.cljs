(ns notify-permission-prompt-test
  "A waiting permission prompt is announced, and the notification uses the
   sequence THIS terminal understands.

   Only OSC 777 was ever sent, which iTerm2 (OSC 9) and Kitty (OSC 99)
   ignore, and nothing fired while a prompt sat waiting — a run blocked on an
   unseen approval was the classic 'came back to nothing'."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [resolve-ask]]
            [agent.extensions.desktop-notify.index :as dn]))

(def ^:private ESC (js/String.fromCharCode 27))
(def ^:private BEL (js/String.fromCharCode 7))

(describe "notification-sequence"
          (fn []
            (it "iTerm2 gets OSC 9, Kitty OSC 99, everything else OSC 777"
                (fn []
                  (-> (expect (dn/notification-sequence #js {:TERM_PROGRAM "iTerm.app"} "nyma" "hi" false))
                      (.toBe (str ESC "]9;nyma: hi" BEL)))
                  (-> (expect (dn/notification-sequence #js {:KITTY_WINDOW_ID "3"} "nyma" "hi" false))
                      (.toBe (str ESC "]99;;nyma: hi" ESC "\\")))
                  (-> (expect (dn/notification-sequence #js {:TERM_PROGRAM "ghostty"} "nyma" "hi" false))
                      (.toBe (str ESC "]777;notify;nyma;hi" BEL)))))
            (it "urgent adds a bell"
                (fn []
                  (-> (expect (.endsWith (dn/notification-sequence #js {} "nyma" "x" true) (str BEL BEL)))
                      (.toBe true))))))

(defn ^:async test-prompt-announced []
  (let [events (create-event-bus)
        seen   (atom nil)
        _      ((:on events) "permission_prompt" (fn [d] (reset! seen (:tool d))))
        ui     #js {:available true :select (fn [_ _] (js/Promise.resolve "Allow once"))}]
    (js-await (resolve-ask nil {:tool-name "bash" :args {:command "ls"} :events events} ui "bash" nil))
    (-> (expect @seen) (.toBe "bash"))))

(describe "permission gate"
          (fn [] (it "announces a waiting prompt" test-prompt-announced)))
