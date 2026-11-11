(ns repo-poller.test
  "Git figures for the status line, kept off the render path.

   `status_bar/render` is synchronous and re-walks the registry at up to 10 Hz
   while streaming; `git status --porcelain` is an execSync. So the value has to
   arrive from a poller into an atom, which is what every extension segment
   already does.

   The two behaviours that matter are not 'does it poll' but: it must stay SILENT
   when nothing changed (a render at idle scrolls the terminal, which is why the
   status tick stops at idle), and it must STOP outside a repo rather than spawn
   two processes every interval forever."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.repo-poller :as rp]))

(defn- mk
  "A poller over a scripted sequence of fetch results."
  [results]
  (let [left   (atom (vec results))
        calls  (atom 0)
        seen   (atom [])
        p (rp/make-poller
           {:fetch (fn []
                     (swap! calls inc)
                     (let [[h & t] @left]
                       (reset! left (vec t))
                       h))
            :on-change (fn [v] (swap! seen conj v))})]
    (assoc p :calls calls :seen seen)))

(def ^:private clean {:branch "main" :staged 0 :unstaged 0 :untracked 0
                      :conflicted 0 :ahead 0 :behind 0})

;;; ─── the silence rule ──────────────────────────────────────────

(describe "repo-poller: notifies only on a change" (fn []

  (it "reports the first poll"
      (fn []
        (let [p (mk [clean])]
          ((:poll! p))
          (-> (expect (count @(:seen p))) (.toBe 1)))))

  (it "says nothing when nothing moved"
      (fn []
        ;; the load-bearing case: an unchanged poll must not reach the bar,
        ;; because a render at idle moves the terminal scroll position
        (let [p (mk [clean clean clean])]
          ((:poll! p)) ((:poll! p)) ((:poll! p))
          (-> (expect (count @(:seen p))) (.toBe 1)))))

  (it "reports a branch switch"
      (fn []
        (let [p (mk [clean (assoc clean :branch "feature")])]
          ((:poll! p)) ((:poll! p))
          (-> (expect (count @(:seen p))) (.toBe 2))
          (-> (expect (:git-branch (last @(:seen p)))) (.toBe "feature")))))

  (it "reports a file becoming dirty"
      (fn []
        (let [p (mk [clean (assoc clean :unstaged 2)])]
          ((:poll! p)) ((:poll! p))
          (-> (expect (:unstaged (:git-status (last @(:seen p))))) (.toBe 2)))))

  (it "ignores a field no segment draws"
      (fn []
        ;; compared on the RENDERED fields, so a change nothing shows cannot
        ;; trigger a render
        (let [p (mk [clean (assoc clean :detached? false :some-future-field 42)])]
          ((:poll! p)) ((:poll! p))
          (-> (expect (count @(:seen p))) (.toBe 1)))))))

;;; ─── stopping ──────────────────────────────────────────────────

(describe "repo-poller: stops instead of spinning" (fn []

  (it "a non-repo stops after one poll"
      (fn []
        ;; otherwise every interval is two wasted spawns for the whole session
        (let [p (mk [nil nil nil])]
          ((:poll! p)) ((:poll! p)) ((:poll! p))
          (-> (expect @(:calls p)) (.toBe 1)))))

  (it "a repo that disappears mid-session clears the segment once"
      (fn []
        (let [p (mk [clean nil])]
          ((:poll! p)) ((:poll! p))
          (-> (expect (count @(:seen p))) (.toBe 2))
          (-> (expect (last @(:seen p))) (.toBeNil)))))

  (it "a non-repo FIRST poll still reports, so a caller can clear"
      (fn []
        ;; it used to report nothing on the first poll, and the only caller sets
        ;; the `path` segment inside on-change — so `path`, which has nothing to
        ;; do with git, was permanently hidden outside a repo
        (let [p (mk [nil])]
          ((:poll! p))
          (-> (expect (count @(:seen p))) (.toBe 1))
          (-> (expect (last @(:seen p))) (.toBeNil)))))

  (it "stop! halts polling"
      (fn []
        (let [p (mk [clean clean])]
          ((:poll! p))
          ((:stop! p))
          ((:poll! p))
          (-> (expect @(:calls p)) (.toBe 1)))))

  (it "a throwing fetch is treated as no repo, not a crash"
      (fn []
        (let [calls (atom 0)
              p (rp/make-poller {:fetch (fn [] (swap! calls inc) (throw (js/Error. "git gone")))
                                 :on-change (fn [_])})]
          ((:poll! p))
          ((:poll! p))
          (-> (expect @calls) (.toBe 1)))))))

;;; ─── the shape handed to the segment ───────────────────────────

(describe "repo-poller: state->bar" (fn []

  (it "splits into the keys git-seg actually reads"
      (fn []
        (let [b (rp/state->bar {:branch "main" :staged 1 :unstaged 2 :untracked 3})]
          (-> (expect (:git-branch b)) (.toBe "main"))
          (-> (expect (:staged (:git-status b))) (.toBe 1))
          (-> (expect (:unstaged (:git-status b))) (.toBe 2))
          (-> (expect (:untracked (:git-status b))) (.toBe 3)))))

  (it "a detached HEAD gets a label rather than a nil branch"
      (fn []
        ;; git-seg hides on a blank branch, so a detached checkout would vanish
        (-> (expect (:git-branch (rp/state->bar {:branch nil :detached? true})))
            (.toBe "detached"))))

  (it "missing counts become zero, so the segment reads clean not broken"
      (fn []
        (-> (expect (:git-status (rp/state->bar {:branch "main"})))
            (.toEqual (clj->js {:staged 0 :unstaged 0 :untracked 0
                                :conflicted 0 :ahead 0 :behind 0})))))

  (it "nil in, nil out"
      (fn []
        (-> (expect (rp/state->bar nil)) (.toBeNil))))))

;;; ─── the setting ───────────────────────────────────────────────

(describe "repo-poller: start!" (fn []

  (it "disabled does nothing at all — no timer, no first poll"
      (fn []
        ;; the point of the setting is a repo where porcelain is expensive, so
        ;; "skip inside the poll" would not be enough
        (let [calls (atom 0)
              stop (rp/start! {:fetch (fn [] (swap! calls inc) clean)
                               :on-change (fn [_]) :enabled? false})]
          (-> (expect @calls) (.toBe 0))
          (stop))))

  (it "enabled polls immediately rather than waiting out the interval"
      (fn []
        (let [calls (atom 0)
              stop (rp/start! {:fetch (fn [] (swap! calls inc) clean)
                               :on-change (fn [_]) :interval-ms 999999})]
          (-> (expect @calls) (.toBe 1))
          (stop))))

  (it "the returned stop fn is safe to call"
      (fn []
        (let [stop (rp/start! {:fetch (fn [] clean) :on-change (fn [_]) :interval-ms 999999})]
          (-> (expect (stop)) (.toBeNil))))))) 

(describe "repo-poller: a fetch that answers with a promise" (fn []
  (it "settles when the promise does, and stops on a nil answer"
      ^:async (fn []
                (let [seen (atom [])
                      p    (rp/make-poller {:fetch (fn [] (js/Promise.resolve clean))
                                         :on-change #(swap! seen conj %)})]
                  (js-await ((:poll! p)))
                  (js-await ((:poll! p)))
                  (-> (expect (count @seen)) (.toBe 1))
                  (-> (expect (:git-branch (first @seen))) (.toBe "main")))
                (let [seen (atom [])
                      p    (rp/make-poller {:fetch (fn [] (js/Promise.reject (js/Error. "x")))
                                         :on-change #(swap! seen conj %)})]
                  (js-await ((:poll! p)))
                  (-> (expect @seen) (.toEqual #js [nil]))
                  (-> (expect ((:poll! p))) (.toBeNil)))))))
