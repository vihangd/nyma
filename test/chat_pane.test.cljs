(ns chat-pane.test
  "Tests for create-chat-pane — covers appendChunk, setMessages, pushMessage,
   replaceMessage, getMessages, and the widget role used by thinking-renderer."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.chat-pane :refer [create-chat-pane]]))

(def ^:private theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"
            :error     "#f7768e"
            :warning   "#e0af68"}})

(defn- make-pane [] (create-chat-pane theme))

;;; ─── creation ─────────────────────────────────────────────────────────────

(describe "chat-pane/create" (fn []
                               (it "has render and invalidate"
                                   (fn []
                                     (let [p (make-pane)]
                                       (-> (expect (fn? (.-render p))) (.toBe true))
                                       (-> (expect (fn? (.-invalidate p))) (.toBe true)))))

                               (it "render returns array"
                                   (fn []
                                     (let [p     (make-pane)
                                           lines (.render p 80)]
                                       (-> (expect (js/Array.isArray lines)) (.toBe true)))))

                               (it "starts with empty render"
                                   (fn []
                                     (let [p     (make-pane)
                                           lines (.render p 80)]
                                       (-> (expect (.-length lines)) (.toBe 0)))))))

;;; ─── setMessages ──────────────────────────────────────────────────────────

(describe "chat-pane/setMessages" (fn []
                                    (it "replaces full message list"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.setMessages p [{:role "user" :content "hello" :id "1"}])
                                            (let [msgs (.getMessages p)]
                                              (-> (expect (count msgs)) (.toBe 1))
                                              (-> (expect (:content (first msgs))) (.toBe "hello"))))))

                                    (it "clearing with empty vector produces empty render"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.setMessages p [{:role "user" :content "hi" :id "1"}])
                                            (.setMessages p [])
                                            (-> (expect (.-length (.render p 80))) (.toBe 0)))))

                                    (it "accepts CLJS vectors directly"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.setMessages p [{:role "user" :content "hi" :id "1"}])
                                            (-> (expect (count (.getMessages p))) (.toBe 1)))))))

;;; ─── pushMessage ──────────────────────────────────────────────────────────

(describe "chat-pane/pushMessage" (fn []
                                    (it "appends a message"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.pushMessage p {:role "user" :content "a" :id "1"})
                                            (.pushMessage p {:role "user" :content "b" :id "2"})
                                            (-> (expect (count (.getMessages p))) (.toBe 2)))))

                                    (it "assigns id when missing"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.pushMessage p {:role "user" :content "x"})
                                            (let [msg (first (.getMessages p))]
                                              (-> (expect (seq (:id msg))) (.toBeTruthy))))))))

;;; ─── appendChunk ──────────────────────────────────────────────────────────

(describe "chat-pane/appendChunk" (fn []
                                    (it "creates assistant message on first chunk"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.appendChunk p "hello")
                                            (let [msgs (.getMessages p)]
                                              (-> (expect (count msgs)) (.toBe 1))
                                              (-> (expect (:role (first msgs))) (.toBe "assistant"))
                                              (-> (expect (:content (first msgs))) (.toBe "hello"))))))

                                    (it "appends to existing assistant message"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.appendChunk p "foo")
                                            (.appendChunk p "bar")
                                            (let [msgs (.getMessages p)]
                                              (-> (expect (count msgs)) (.toBe 1))
                                              (-> (expect (:content (first msgs))) (.toBe "foobar"))))))

                                    (it "starts new assistant message after non-assistant"
                                        (fn []
                                          (let [p (make-pane)]
                                            (.pushMessage p {:role "user" :content "q" :id "u1"})
                                            (.appendChunk p "answer")
                                            (let [msgs (.getMessages p)]
                                              (-> (expect (count msgs)) (.toBe 2))
                                              (-> (expect (:role (second msgs))) (.toBe "assistant"))))))))

;;; ─── replaceMessage ───────────────────────────────────────────────────────

(describe "chat-pane/replaceMessage" (fn []
                                       (it "replaces first matching message"
                                           (fn []
                                             (let [p (make-pane)]
                                               (.setMessages p [{:role "tool-start" :exec-id "e1" :id "m1"}
                                                                {:role "user" :content "hi" :id "m2"}])
                                               (.replaceMessage p
                                                                (fn [m] (= (:id m) "m1"))
                                                                {:role "tool-end" :exec-id "e1" :id "m1"})
                                               (let [msgs (.getMessages p)]
                                                 (-> (expect (:role (first msgs))) (.toBe "tool-end"))))))

                                       (it "appends when no match"
                                           (fn []
                                             (let [p (make-pane)]
                                               (.setMessages p [{:role "user" :content "hi" :id "m1"}])
                                               (.replaceMessage p
                                                                (fn [_] false)
                                                                {:role "assistant" :content "new" :id "m2"})
                                               (-> (expect (count (.getMessages p))) (.toBe 2)))))))

;;; ─── render produces lines ────────────────────────────────────────────────

(describe "chat-pane/render" (fn []
                               (it "renders user message"
                                   (fn []
                                     (let [p (make-pane)]
                                       (.setMessages p [{:role "user" :content "test" :id "1"}])
                                       (-> (expect (pos? (.-length (.render p 80)))) (.toBe true)))))

                               (it "renders error message"
                                   (fn []
                                     (let [p (make-pane)]
                                       (.setMessages p [{:role "error" :content "boom" :id "1"}])
                                       (-> (expect (pos? (.-length (.render p 80)))) (.toBe true)))))

                               (it "renders info message without throwing"
                                   (fn []
                                     (let [p (make-pane)]
                                       (.setMessages p [{:role "info" :content "Role: plan" :id "1"}])
                                       (-> (expect (pos? (.-length (.render p 80)))) (.toBe true)))))

                               (it "separates messages with blank line"
                                   (fn []
                                     (let [p (make-pane)]
                                       (.setMessages p [{:role "user" :content "a" :id "1"}
                                                        {:role "user" :content "b" :id "2"}])
                                       (let [lines (vec (.render p 80))]
                                         (-> (expect (boolean (some #(= % "") lines))) (.toBe true))))))))

;;; ─── widget messages (thinking-renderer contract) ─────────────────────────

(describe "chat-pane/widget-messages" (fn []
                                        (it "renders widget role verbatim"
                                            (fn []
                                              (let [p (make-pane)]
                                                (.setMessages p [{:role "widget"
                                                                  :content "💭 Thinking (1k tokens)\n│ step 1"
                                                                  :id "w1"}])
                                                (let [lines (vec (.render p 80))]
                                                  (-> (expect (boolean (some #(.includes % "💭") lines))) (.toBe true))))))

                                        (it "widget line count matches content lines"
                                            (fn []
                                              (let [p (make-pane)]
                                                (.setMessages p [{:role "widget"
                                                                  :content "line1\nline2\nline3"
                                                                  :id "w1"}])
                                                (let [lines (vec (.render p 80))]
                                                  (-> (expect (count (filter #(not= % "") lines))) (.toBe 3))))))))

;;; ─── invalidate resets caches ─────────────────────────────────────────────

(describe "chat-pane/invalidate" (fn []
                                   (it "does not throw and render still works after"
                                       (fn []
                                         (let [p (make-pane)]
                                           (.setMessages p [{:role "assistant" :content "hello" :id "1"}])
                                           (.render p 80)
                                           (.invalidate p)
                                           (-> (expect (pos? (.-length (.render p 80)))) (.toBe true)))))))
