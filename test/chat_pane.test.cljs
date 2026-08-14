(ns chat-pane.test
  "Tests for create-chat-pane — covers appendChunk, setMessages, pushMessage,
   replaceMessage, getMessages, and the widget role used by thinking-renderer."
  (:require ["bun:test" :refer [describe it expect]]
            ["@mariozechner/pi-tui" :refer [visibleWidth]]
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

;;; ─── per-message render cache ────────────────────────────────────────────
;;; pi-tui calls every child's render from scratch on every frame and requests
;;; a frame on every keystroke, so the pane used to re-render the whole
;;; transcript per keypress — 66ms at 900 messages against a 16ms budget, which
;;; is why typing slowed down as a session grew. Finished messages are
;;; immutable (add-chunk! rebuilds only the last one), so their lines are
;;; computed once.

(defn- md-msg [i]
  {:role "assistant" :id (str "a" i)
   :content (str "## Finding " i "\n\nIn `chat_pane.cljs`:\n\n```clojure\n(defn render [w] ...)\n```\n\n- one\n- two **bold**\n")})

(defn- mixed-transcript [turns]
  (vec (mapcat (fn [i]
                 [{:role "user" :id (str "u" i) :content (str "Investigate issue " i)}
                  (md-msg i)
                  {:role "tool-result" :id (str "t" i) :content "  out 1\n  out 2\n  out 3"}])
               (range turns))))

(describe "render cache"
          (fn []
            (it "makes a warm render dramatically cheaper than a cold one"
                (fn []
                  ;; A perf fix needs a perf assertion: a correctness-only test
                  ;; passes just as happily on the slow version. Threshold is
                  ;; deliberately loose (10x) against a measured ~290x, so this
                  ;; fails on regression without flaking on a loaded machine.
                  (let [pane (create-chat-pane theme)
                        _    (.setMessages pane (clj->js (mixed-transcript 300)))
                        t0   (js/performance.now)
                        _    (.render pane 100)
                        cold (- (js/performance.now) t0)
                        t1   (js/performance.now)
                        _    (dotimes [_ 5] (.render pane 100))
                        warm (/ (- (js/performance.now) t1) 5)]
                    (-> (expect (> (/ cold warm) 10)) (.toBe true)))))

            (it "returns identical output on a repeat render"
                (fn []
                  ;; The cache must not change a single byte.
                  (let [pane (create-chat-pane theme)]
                    (.setMessages pane (clj->js (mixed-transcript 12)))
                    (let [a (vec (.render pane 100))
                          b (vec (.render pane 100))]
                      (-> (expect (count a)) (.toBe (count b)))
                      (-> (expect (.join (to-array a) "\n"))
                          (.toBe (.join (to-array b) "\n")))))))

            (it "re-wraps when the width changes instead of serving stale lines"
                (fn []
                  ;; Width is part of the cache key. Serving lines measured for
                  ;; a wider terminal is how pi-tui gets a line it throws on.
                  ;;
                  ;; The content MUST be long enough to wrap differently at the
                  ;; two widths — an earlier version of this test used short
                  ;; messages, so no line exceeded 40 columns even when rendered
                  ;; at 100 and it passed with width dropped from the key.
                  (let [pane (create-chat-pane theme)
                        long-line (apply str (repeat 12 "wide content that must rewrap "))]
                    (.setMessages pane
                                  (clj->js [{:role "user" :id "u" :content long-line}
                                            {:role "assistant" :id "a" :content long-line}]))
                    (let [wide (vec (.render pane 100))]
                      ;; Precondition: at 100 columns some line really is >40,
                      ;; otherwise the assertion below proves nothing.
                      (-> (expect (boolean (some #(> (visibleWidth %) 40) wide))) (.toBe true)))
                    (let [narrow (vec (.render pane 40))]
                      (doseq [l narrow]
                        (-> (expect (<= (visibleWidth l) 40)) (.toBe true)))))))

            (it "re-renders the streaming tail while earlier messages stay cached"
                (fn []
                  (let [pane (create-chat-pane theme)]
                    (.setMessages pane (clj->js (mixed-transcript 4)))
                    (let [before (vec (.render pane 100))]
                      (.appendChunk pane " and more text")
                      (let [after (vec (.render pane 100))]
                        ;; The tail changed …
                        (-> (expect (= (.join (to-array before) "\n")
                                       (.join (to-array after) "\n")))
                            (.toBe false))
                        ;; … and the head did not.
                        (-> (expect (first after)) (.toBe (first before))))))))

            (it "drops cached lines on invalidate"
                (fn []
                  ;; invalidate clears the markdown caches, so the line cache
                  ;; must go too — otherwise the next frame serves lines built
                  ;; from caches just thrown away.
                  ;;
                  ;; Asserted by COST, not by output: the rebuilt lines are
                  ;; byte-identical, so an equality check passes whether or not
                  ;; the cache was cleared and proves nothing.
                  (let [pane (create-chat-pane theme)]
                    (.setMessages pane (clj->js (mixed-transcript 200)))
                    (.render pane 100)
                    (let [t0   (js/performance.now)
                          _    (.render pane 100)
                          warm (- (js/performance.now) t0)]
                      (.invalidate pane)
                      (let [t1    (js/performance.now)
                            _     (.render pane 100)
                            after (- (js/performance.now) t1)]
                        ;; A render after invalidate must pay real work again.
                        (-> (expect (> (/ after (max warm 0.001)) 5)) (.toBe true)))))))))
