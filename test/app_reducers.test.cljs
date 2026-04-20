(ns app-reducers.test
  "Tests for the pure helpers extracted from agent.ui.app.

   Covers two bug classes that previously had no regression tests:

   Bug-1: Bracketed paste in the main editor showed '[201~' instead of
          '[paste #N +X lines]'. Root cause: ink's event bus splits the
          paste sequence into three events (ESC[200~, body, ESC[201~);
          ink-text-input appended each as a direct setState call; the last
          one overwrote our functional marker update.

   Bug-2: Grouped tool display showed '?' instead of labels (glob pattern,
          read path, etc.). Root cause: on-end never copied :args from the
          matched tool-start, so group-messages had nil args for every item."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.app-reducers :as r]
            ["./agent/ui/tool_status.jsx" :refer [group_messages tool_group_label]]
            ["./agent/ui/bracketed_paste.mjs" :refer [create_paste_handler]]))

;;; ─── find-tool-start-idx ─────────────────────────────────

(describe "app-reducers:find-tool-start-idx" (fn []
                                               (it "returns index of matching tool-start"
                                                   (fn []
                                                     (let [prev [{:role "tool-start" :exec-id "e1" :tool-name "read"}
                                                                 {:role "tool-start" :exec-id "e2" :tool-name "glob"}]]
                                                       (-> (expect (r/find-tool-start-idx prev "e1")) (.toBe 0))
                                                       (-> (expect (r/find-tool-start-idx prev "e2")) (.toBe 1)))))

                                               (it "returns nil when no match"
                                                   (fn []
                                                     (let [prev [{:role "tool-start" :exec-id "e1"}]]
                                                       (-> (expect (r/find-tool-start-idx prev "missing")) (.toBeNil)))))

                                               (it "returns nil on empty vector"
                                                   (fn []
                                                     (-> (expect (r/find-tool-start-idx [] "e1")) (.toBeNil))))))

;;; ─── apply-tool-start ────────────────────────────────────

(describe "app-reducers:apply-tool-start" (fn []
                                            (it "appends a tool-start message with args"
                                                (fn []
                                                  (let [result (r/apply-tool-start [] {:tool-name "glob"
                                                                                       :exec-id   "e1"
                                                                                       :args      {:pattern "**/*.cljs"}}
                                                                                   "collapsed" 500)]
                                                    (-> (expect (count result)) (.toBe 1))
                                                    (let [msg (first result)]
                                                      (-> (expect (:role msg)) (.toBe "tool-start"))
                                                      (-> (expect (:tool-name msg)) (.toBe "glob"))
                                                      (-> (expect (get-in msg [:args :pattern])) (.toBe "**/*.cljs"))
                                                      (-> (expect (:exec-id msg)) (.toBe "e1"))
                                                      (-> (expect (:verbosity msg)) (.toBe "collapsed"))
                                                      (-> (expect (:max-lines msg)) (.toBe 500))))))

                                            (it "copies custom-one-line-args when present"
                                                (fn []
                                                  (let [result (r/apply-tool-start [] {:tool-name "bash"
                                                                                       :exec-id   "e1"
                                                                                       :args      {:command "ls"}
                                                                                       :custom-one-line-args "ls"}
                                                                                   "collapsed" 500)]
                                                    (-> (expect (:custom-one-line-args (first result))) (.toBe "ls")))))

                                            (it "omits custom-one-line-args key when absent"
                                                (fn []
                                                  (let [msg (first (r/apply-tool-start [] {:tool-name "read" :exec-id "e1"}
                                                                                       "collapsed" 500))]
                                                    (-> (expect (contains? msg :custom-one-line-args)) (.toBe false)))))

                                            (it "uses custom-verbosity from event data when provided"
                                                (fn []
                                                  (let [msg (first (r/apply-tool-start [] {:tool-name "t" :exec-id "e1"
                                                                                           :custom-verbosity "expanded"}
                                                                                       "collapsed" 500))]
                                                    (-> (expect (:verbosity msg)) (.toBe "expanded")))))))

;;; ─── apply-tool-end — Bug-2 regression ──────────────────

(describe "app-reducers:apply-tool-end" (fn []

  ;; THE BUG-2 REGRESSION TEST:
  ;; Middleware never puts :args on the end event; on-end must copy them
  ;; from the matching start. Before the fix, :args was nil on every
  ;; tool-end message and tool-group-label fell through to "?".
                                          (it "copies :args from the matched start message (Bug-2 regression)"
                                              (fn []
                                                (let [prev   [{:role "tool-start" :tool-name "glob"
                                                               :exec-id "e1" :args {:pattern "**/*.cljs"}}]
            ;; End event as emitted by middleware — no :args field
                                                      data   {:tool-name "glob" :exec-id "e1"
                                                              :result "a.cljs\nb.cljs" :duration 5}
                                                      result (r/apply-tool-end prev data "collapsed" 500)]
                                                  (-> (expect (count result)) (.toBe 1))
                                                  (let [msg (first result)]
                                                    (-> (expect (:role msg)) (.toBe "tool-end"))
                                                    (-> (expect (get-in msg [:args :pattern])) (.toBe "**/*.cljs"))))))

                                          (it "produces a tool-end with correct fields"
                                              (fn []
                                                (let [prev   [{:role "tool-start" :tool-name "read" :exec-id "e1"
                                                               :args {:path "src/foo.cljs"}}]
                                                      data   {:tool-name "read" :exec-id "e1"
                                                              :result "file contents" :duration 12}
                                                      msg    (first (r/apply-tool-end prev data "collapsed" 500))]
                                                  (-> (expect (:role msg)) (.toBe "tool-end"))
                                                  (-> (expect (:tool-name msg)) (.toBe "read"))
                                                  (-> (expect (:result msg)) (.toBe "file contents"))
                                                  (-> (expect (:duration msg)) (.toBe 12))
                                                  (-> (expect (:exec-id msg)) (.toBe "e1")))))

                                          (it "appends an orphan end-message when no matching start exists"
                                              (fn []
                                                (let [result (r/apply-tool-end [] {:tool-name "glob" :exec-id "orphan"
                                                                                   :result "" :duration 1}
                                                                               "collapsed" 500)]
                                                  (-> (expect (count result)) (.toBe 1))
                                                  (-> (expect (:role (first result))) (.toBe "tool-end"))
                                                  ;; No start → :args key absent entirely
                                                  (-> (expect (contains? (first result) :args)) (.toBe false)))))

                                          (it "copies custom-one-line-result when present"
                                              (fn []
                                                (let [prev [{:role "tool-start" :exec-id "e1" :tool-name "bash"}]
                                                      msg  (first (r/apply-tool-end prev {:tool-name "bash" :exec-id "e1"
                                                                                          :result "ok" :duration 1
                                                                                          :custom-one-line-result "ls output"}
                                                                                    "collapsed" 500))]
                                                  (-> (expect (:custom-one-line-result msg)) (.toBe "ls output")))))

                                          (it "does not set :args key when start message has no :args"
                                              (fn []
      ;; A start message with no :args should not add :args nil to the end msg
                                                (let [prev [{:role "tool-start" :exec-id "e1" :tool-name "bash"}]
                                                      msg  (first (r/apply-tool-end prev {:tool-name "bash" :exec-id "e1"
                                                                                          :result "ok" :duration 1}
                                                                                    "collapsed" 500))]
                                                  (-> (expect (contains? msg :args)) (.toBe false)))))))

;;; ─── apply-tool-update ───────────────────────────────────

(describe "app-reducers:apply-tool-update" (fn []
                                             (it "updates custom-status-text on matching start"
                                                 (fn []
                                                   (let [prev   [{:role "tool-start" :exec-id "e1" :tool-name "bash"
                                                                  :custom-status-text "starting..."}]
                                                         result (r/apply-tool-update prev {:exec-id "e1" :data "running..."})]
                                                     (-> (expect (:custom-status-text (first result))) (.toBe "running...")))))

                                             (it "returns prev unchanged when no matching start"
                                                 (fn []
                                                   (let [prev [{:role "tool-start" :exec-id "e1" :tool-name "bash"}]
                                                         result (r/apply-tool-update prev {:exec-id "nope" :data "x"})]
                                                     (-> (expect (= result (vec prev))) (.toBe true)))))))

;;; ─── Bug-2 end-to-end: reducer → group-messages → labels ─

(describe "app-reducers:start→end→group roundtrip (Bug-2 e2e)" (fn []

  ;; This test simulates the exact failure path the user saw:
  ;;   1. tool_execution_start fires for three glob calls
  ;;   2. tool_execution_end fires (without :args) for each
  ;;   3. group-messages collapses them
  ;;   4. tool-group-label should return the pattern, not "?"
                                                                 (it "grouped glob items show pattern not '?' after start→end pipeline"
                                                                     (fn []
                                                                       (let [verbosity "collapsed"
                                                                             max-lines 500
            ;; Simulate three on-start calls
                                                                             patterns  ["**/*.cljs" "src/**/*.ts" "test/**/*.cljs"]
                                                                             after-starts
                                                                             (reduce
                                                                              (fn [msgs [i pattern]]
                                                                                (r/apply-tool-start
                                                                                 msgs
                                                                                 {:tool-name "glob" :exec-id (str "e" i)
                                                                                  :args {:pattern pattern}}
                                                                                 verbosity max-lines))
                                                                              []
                                                                              (map-indexed vector patterns))
            ;; Simulate three on-end calls — no :args in event (realistic)
                                                                             after-ends
                                                                             (reduce
                                                                              (fn [msgs i]
                                                                                (r/apply-tool-end
                                                                                 msgs
                                                                                 {:tool-name "glob" :exec-id (str "e" i)
                                                                                  :result (str "file" i ".cljs") :duration (inc i)}
                                                                                 verbosity max-lines))
                                                                              after-starts
                                                                              (range 3))
                                                                             grouped (group_messages after-ends)
                                                                             group   (first grouped)]
                                                                         (-> (expect (count grouped)) (.toBe 1))
                                                                         (-> (expect (:role group)) (.toBe "tool-group"))
                                                                         (-> (expect (count (:items group))) (.toBe 3))
        ;; Each item's args label must be the pattern, not "?"
                                                                         (doseq [[item pattern] (map vector (:items group) patterns)]
                                                                           (-> (expect (tool_group_label "glob" (:args item)))
                                                                               (.toBe pattern))))))

                                                                 (it "grouped read items show paths not '?' after start→end pipeline"
                                                                     (fn []
                                                                       (let [paths ["src/a.cljs" "src/b.cljs"]
                                                                             after-starts
                                                                             (reduce (fn [msgs [i p]]
                                                                                       (r/apply-tool-start
                                                                                        msgs {:tool-name "read" :exec-id (str "r" i)
                                                                                              :args {:path p}} "collapsed" 500))
                                                                                     [] (map-indexed vector paths))
                                                                             after-ends
                                                                             (reduce (fn [msgs i]
                                                                                       (r/apply-tool-end
                                                                                        msgs {:tool-name "read" :exec-id (str "r" i)
                                                                                              :result "contents" :duration 5} "collapsed" 500))
                                                                                     after-starts (range 2))
                                                                             items (:items (first (group_messages after-ends)))]
                                                                         (-> (expect (tool_group_label "read" (:args (first items))))
                                                                             (.toBe "src/a.cljs"))
                                                                         (-> (expect (tool_group_label "read" (:args (second items))))
                                                                             (.toBe "src/b.cljs")))))))

;;; ─── make-guarded-setter — Bug-1 regression ─────────────

(describe "app-reducers:make-guarded-setter (Bug-1 regression)" (fn []

                                                                  (it "passes functional updates through always, even when blocked"
    ;; This is the paste marker path — it MUST always fire.
                                                                      (fn []
                                                                        (let [ref     #js {:current true}   ;; block is ON
                                                                              calls   (atom [])
                                                                              setter  (r/make-guarded-setter ref #(swap! calls conj %))]
                                                                          (setter (fn [prev] (str prev "marker")))
                                                                          (-> (expect (count @calls)) (.toBe 1))
                                                                          (-> (expect (fn? (first @calls))) (.toBe true)))))

                                                                  (it "drops direct string updates while block-ref is true (Bug-1)"
    ;; ink-text-input calls onChange('[201~') — must be suppressed.
                                                                      (fn []
                                                                        (let [ref    #js {:current true}
                                                                              calls  (atom [])
                                                                              setter (r/make-guarded-setter ref #(swap! calls conj %))]
                                                                          (setter "[201~")
                                                                          (-> (expect (count @calls)) (.toBe 0)))))

                                                                  (it "allows direct string updates when block-ref is false"
    ;; Normal typing (not during a paste) must still work.
                                                                      (fn []
                                                                        (let [ref    #js {:current false}
                                                                              calls  (atom [])
                                                                              setter (r/make-guarded-setter ref #(swap! calls conj %))]
                                                                          (setter "hello")
                                                                          (-> (expect (count @calls)) (.toBe 1))
                                                                          (-> (expect (first @calls)) (.toBe "hello")))))

                                                                  (it "suppresses all three paste-fragment direct updates in sequence"
    ;; The exact sequence ink-text-input fires for ESC[200~body ESC[201~.
                                                                      (fn []
                                                                        (let [ref    #js {:current true}
                                                                              calls  (atom [])
                                                                              setter (r/make-guarded-setter ref #(swap! calls conj %))]
                                                                          (setter "[200~")   ;; paste start fragment — must drop
                                                                          (setter "body")    ;; paste body — must drop
                                                                          (setter "[201~")   ;; paste end fragment — must drop
                                                                          (-> (expect (count @calls)) (.toBe 0)))))))

;;; ─── make-ink-paste-fn — Bug-1 regression ───────────────

(describe "app-reducers:make-ink-paste-fn (Bug-1 regression)" (fn []

                                                                (it "sets block-ref true synchronously on ESC[200~"
                                                                    (fn []
                                                                      (let [ref      #js {:current false}
                                                                            queued   (atom [])
                                                                            paste-fn (r/make-ink-paste-fn ref #(swap! queued conj %))]
                                                                        (paste-fn "\u001b[200~")
                                                                        (-> (expect (.-current ref)) (.toBe true)))))

                                                                (it "does not clear block-ref synchronously on ESC[201~"
    ;; The clear must be deferred so TextInput's synchronous handler for
    ;; the same '[201~' event still sees the ref as true.
                                                                    (fn []
                                                                      (let [ref      #js {:current true}
                                                                            queued   (atom [])
                                                                            paste-fn (r/make-ink-paste-fn ref #(swap! queued conj %))]
                                                                        (paste-fn "\u001b[201~")
        ;; Ref still true right now — the callback is only queued
                                                                        (-> (expect (.-current ref)) (.toBe true))
        ;; But a callback was scheduled
                                                                        (-> (expect (count @queued)) (.toBe 1)))))

                                                                (it "queued callback clears block-ref when run"
                                                                    (fn []
                                                                      (let [ref      #js {:current true}
                                                                            queued   (atom [])
                                                                            paste-fn (r/make-ink-paste-fn ref #(swap! queued conj %))]
                                                                        (paste-fn "\u001b[201~")
        ;; Run the deferred clear
                                                                        ((first @queued))
                                                                        (-> (expect (.-current ref)) (.toBe false)))))

                                                                (it "ignores unrelated input events"
                                                                    (fn []
                                                                      (let [ref      #js {:current false}
                                                                            queued   (atom [])
                                                                            paste-fn (r/make-ink-paste-fn ref #(swap! queued conj %))]
                                                                        (paste-fn "a")
                                                                        (paste-fn "hello")
                                                                        (paste-fn "[200~")   ;; without ESC prefix — should not match
                                                                        (-> (expect (.-current ref)) (.toBe false))
                                                                        (-> (expect (count @queued)) (.toBe 0)))))

                                                                (it "full paste lifecycle: start→end clears after deferred tick"
    ;; Simulate the three-event sequence ink emits for a bracketed paste.
                                                                    (fn []
                                                                      (let [ref      #js {:current false}
                                                                            queued   (atom [])
                                                                            paste-fn (r/make-ink-paste-fn ref #(swap! queued conj %))]
        ;; Event 1: paste start — blocks immediately
                                                                        (paste-fn "\u001b[200~")
                                                                        (-> (expect (.-current ref)) (.toBe true))
        ;; Event 2: body — no change to ref
                                                                        (paste-fn "my secret key")
                                                                        (-> (expect (.-current ref)) (.toBe true))
        ;; Event 3: paste end — queues clear, ref still true right now
                                                                        (paste-fn "\u001b[201~")
                                                                        (-> (expect (.-current ref)) (.toBe true))
                                                                        (-> (expect (count @queued)) (.toBe 1))
        ;; Deferred tick fires — ref goes false
                                                                        ((first @queued))
                                                                        (-> (expect (.-current ref)) (.toBe false)))))))

;;; ─── make-stdin-paste-handler ────────────────────────────

(describe "app-reducers:make-stdin-paste-handler" (fn []

                                                    (it "short-circuits when overlay is active — Bug-1 /login path"
    ;; When /login dialog is open, pasted bytes must not be consumed here
    ;; or the dialog's TextInput gets only the ESC[201~ end marker.
                                                        (fn []
                                                          (let [process-calls (atom 0)
                                                                process-fn    (fn [_] (swap! process-calls inc) {:handled false})
                                                                overlay-ref   #js {:current true}   ;; overlay is open
                                                                set-value     (fn [_])
                                                                handler       (r/make-stdin-paste-handler process-fn overlay-ref set-value)]
                                                            (handler "\u001b[200~some key\u001b[201~")
                                                            (-> (expect @process-calls) (.toBe 0)))))

                                                    (it "processes paste data when no overlay is active"
                                                        (fn []
                                                          (let [paste-handler (create_paste_handler)
                                                                process-fn    (:process paste-handler)
                                                                overlay-ref   #js {:current false}
                                                                set-calls     (atom [])
                                                                set-value     (fn [v] (swap! set-calls conj v))
                                                                handler       (r/make-stdin-paste-handler process-fn overlay-ref set-value)]
                                                            (handler "\u001b[200~hello paste\u001b[201~")
        ;; A functional update should have been queued to append the marker
                                                            (-> (expect (count @set-calls)) (.toBe 1))
                                                            (-> (expect (fn? (first @set-calls))) (.toBe true))
        ;; Apply the functional update to verify it appends the marker
                                                            (let [marker-result ((first @set-calls) "")]
                                                              (-> (expect (.startsWith marker-result "[paste #1")) (.toBe true))))))

                                                    (it "does not call set-value for non-paste data"
                                                        (fn []
                                                          (let [process-fn  (create_paste_handler)
                                                                overlay-ref #js {:current false}
                                                                calls       (atom [])
                                                                handler     (r/make-stdin-paste-handler
                                                                             (:process process-fn) overlay-ref
                                                                             (fn [v] (swap! calls conj v)))]
        ;; Plain keystroke with no paste framing
                                                            (handler "hello")
                                                            (-> (expect (count @calls)) (.toBe 0)))))))

;;; ─── Message-id stability (RED until Step 3 assigns ids) ────
;;; These tests encode the contract that must hold once nanoid ids are
;;; assigned in apply-tool-start / apply-tool-end. They will fail until
;;; Step 3 of the render refactor adds :id to every message.

(describe "app-reducers:message ids [RED until Step 3]"
          (fn []

            (it "[RED] apply-tool-start assigns a stable :id to the created message"
                (fn []
                  (let [result (r/apply-tool-start [] {:tool-name "read" :exec-id "e1"
                                                       :args {:path "f.cljs"}}
                                                   "collapsed" 500)
                        msg    (first result)]
                    ;; Must have an :id once Step 3 is implemented
                    (-> (expect (some? (:id msg))) (.toBe true))
                    (-> (expect (string? (:id msg))) (.toBe true)))))

            (it "[RED] apply-tool-end preserves the start message's :id"
                (fn []
                  (let [prev   [(r/apply-tool-start [] {:tool-name "read" :exec-id "e1"
                                                        :args {:path "f.cljs"}}
                                                    "collapsed" 500)
                                 ;; unwrap: apply-tool-start returns a vector
                                ]
                        ;; Build prev as a single tool-start message
                        prev-v [(first (r/apply-tool-start [] {:tool-name "read" :exec-id "e1"
                                                               :args {:path "f.cljs"}}
                                                           "collapsed" 500))]
                        start-id (:id (first prev-v))
                        result (r/apply-tool-end prev-v {:tool-name "read" :exec-id "e1"
                                                         :result "contents" :duration 10}
                                                 "collapsed" 500)
                        end-msg (first result)]
                    ;; End message must carry the same id as start
                    (-> (expect (:id end-msg)) (.toBe start-id)))))

            (it "[RED] apply-tool-end does not shift earlier messages (index-stable)"
                ;; When apply-tool-end replaces index i, all messages at j < i
                ;; must be reference-equal to what they were before.
                (fn []
                  (let [user-msg  {:role "user" :content "q" :id "stable-user-id"}
                        start-msg (first (r/apply-tool-start [] {:tool-name "read" :exec-id "e1"
                                                                 :args {:path "f.cljs"}}
                                                             "collapsed" 500))
                        prev      [user-msg start-msg]
                        result    (r/apply-tool-end prev {:tool-name "read" :exec-id "e1"
                                                          :result "ok" :duration 5}
                                                    "collapsed" 500)]
                    ;; Index 0 (user-msg) must be the same reference
                    (-> (expect (identical? (first result) user-msg)) (.toBe true))
                    ;; Index 1 is now a tool-end (different object is expected)
                    (-> (expect (:role (second result))) (.toBe "tool-end")))))

            (it "[RED] apply-tool-update preserves :id on the updated message"
                (fn []
                  (let [prev-v [(first (r/apply-tool-start [] {:tool-name "bash" :exec-id "e1"
                                                               :args {:command "ls"}}
                                                           "collapsed" 500))]
                        orig-id (:id (first prev-v))
                        result  (r/apply-tool-update prev-v {:exec-id "e1" :data "running..."})]
                    (-> (expect (:id (first result))) (.toBe orig-id)))))))

;;; ─── Orphaned tool-start cleanup (RED until Step 6) ─────────

(describe "app-reducers: orphaned tool-start handling [RED until Step 6]"
          (fn []

            (it "[RED] cancel-orphaned-tool-starts converts lingering start to cancelled tool-end"
                ;; Placeholder: cancel-orphaned-tool-starts does not exist yet.
                ;; Fill in the assertion body in Step 6 when the function is added
                ;; to app_reducers.cljs. Until then the when-guard keeps this a no-op.
                (fn []
                  (when (fn? r/cancel-orphaned-tool-starts)
                    (let [user-msg  {:role "user" :content "q" :id "u1"}
                          start-msg {:role "tool-start" :tool-name "read"
                                     :exec-id "e1" :args {:path "f.cljs"} :id "start-id"}
                          prev      [user-msg start-msg]
                          result    (r/cancel-orphaned-tool-starts prev)
                          end-msg   (second result)]
                      (-> (expect (:role end-msg)) (.toBe "tool-end"))
                      (-> (expect (:id end-msg)) (.toBe "start-id"))
                      (-> (expect (:result end-msg)) (.toContain "cancelled"))))))))

;;; ─── make-submit-guard ───────────────────────────────────────────────────
;;;
;;; Regression tests for the key-repeat / double-Enter duplication bug:
;;; multiple onSubmit calls fired before React cleared the TextInput value.

(defn ^:async test-submit-guard-single []
  (let [calls (atom [])
        lock  #js {:current false}
        guard (r/make-submit-guard lock (fn [t] (.resolve js/Promise (swap! calls conj t))))]
    (js-await (guard "hello"))
    (-> (expect (count @calls)) (.toBe 1))
    (-> (expect (first @calls)) (.toBe "hello"))))

(defn ^:async test-submit-guard-blocks-concurrent []
  (let [calls     (atom [])
        lock      #js {:current false}
        resolvers (atom [])
        slow-fn   (fn [t]
                    (swap! calls conj t)
                    (js/Promise. (fn [res _] (swap! resolvers conj res))))
        guard     (r/make-submit-guard lock slow-fn)]
    (guard "first")
    (guard "second")     ; dropped — lock held
    ((first @resolvers) nil)
    (js-await (.resolve js/Promise nil))
    (-> (expect (count @calls)) (.toBe 1))
    (-> (expect (first @calls)) (.toBe "first"))))

(defn ^:async test-submit-guard-sequential []
  (let [calls (atom [])
        lock  #js {:current false}
        guard (r/make-submit-guard lock (fn [t] (.resolve js/Promise (swap! calls conj t))))]
    (js-await (guard "a"))
    (js-await (guard "b"))
    (-> (expect (count @calls)) (.toBe 2))
    (-> (expect @calls) (.toEqual #js ["a" "b"]))))

(defn test-submit-guard-empty []
  (let [calls (atom [])
        lock  #js {:current false}
        guard (r/make-submit-guard lock (fn [t] (.resolve js/Promise (swap! calls conj t))))]
    (guard "")
    (-> (expect (count @calls)) (.toBe 0))
    (-> (expect (.-current lock)) (.toBe false))))

(defn ^:async test-submit-guard-releases-on-rejection []
  (let [calls (atom 0)
        lock  #js {:current false}
        guard (r/make-submit-guard lock
                                   (fn [_]
                                     (swap! calls inc)
                                     (.reject js/Promise (js/Error. "boom"))))]
    (try (js-await (guard "x")) (catch :default _ nil))
    (-> (expect (.-current lock)) (.toBe false))
    (try (js-await (guard "y")) (catch :default _ nil))
    (-> (expect @calls) (.toBe 2))))

(describe "app-reducers:make-submit-guard"
          (fn []
            (it "calls submit-fn once for a single call"   test-submit-guard-single)
            (it "blocks concurrent call while first in flight" test-submit-guard-blocks-concurrent)
            (it "accepts sequential calls after lock releases"  test-submit-guard-sequential)
            (it "rejects empty string without calling submit-fn" test-submit-guard-empty)
            (it "releases lock on rejection so next call goes through" test-submit-guard-releases-on-rejection)))
