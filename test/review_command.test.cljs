(ns review-command-test
  "/review — the deep role reviews the change itself, not the conversation."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [agent.extensions.model-roles.features.review :as review]))

(defn- git! [dir & args]
  (str/trim (str (cp/execFileSync "git" (clj->js args) #js {:cwd dir :encoding "utf8"}))))

(defn- repo []
  (let [d (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "nyma-review-")))]
    (git! d "init" "-q")
    (git! d "config" "user.email" "t@t") (git! d "config" "user.name" "t")
    (fs/writeFileSync (path/join d "a.txt") "one\n")
    (git! d "add" ".") (git! d "commit" "-qm" "init")
    d))

(defn- harness [confirm seen]
  (let [notes (atom []) overlay (atom nil) sent (atom nil) reply (atom nil) queued (atom nil)]
    {:notes notes :overlay overlay :sent sent :queued queued
     :reply reply
     :api #js {:settings (fn [& _] {:roles {}})
               :getTool (fn [n] (when (= n "subagent__subagent")
                                  #js {:execute (fn [args _]
                                                  (reset! seen args)
                                                  (js/Promise.resolve @reply))}))
               :canStartTurn (fn [] true)
               :emitGlobal (fn [evt d] (when (= evt "turn_request") (reset! sent (.-text d))))
               :sendUserMessage (fn [m] (reset! queued m))}
     :ctx #js {:ui #js {:notify      (fn [m _] (swap! notes conj m))
                        :showOverlay (fn [t] (reset! overlay t))
                        :confirm     (fn [_] (js/Promise.resolve confirm))}}}))

(defn- found [text] {:ok true :status "done" :text text})

(defn ^:async test-uncommitted []
  (let [d (repo) seen (atom nil)
        {:keys [api ctx overlay sent reply]} (harness true seen)]
    (fs/writeFileSync (path/join d "a.txt") "one\ntwo\n")
    (fs/writeFileSync (path/join d "new.txt") "n\n")
    (reset! reply (found "a.txt:2 — high — off by one — fix it"))
    (js-await (review/run! api [] ctx {:cwd d}))
    (-> (expect (.-task @seen)) (.toContain "+two"))
    (-> (expect (.-task @seen)) (.toContain "new.txt"))
    (-> (expect (.-agent @seen)) (.toBe "reviewer"))
    (-> (expect @overlay) (.toContain "off by one"))
    ;; confirmed → the findings start a turn
    (-> (expect @sent) (.toContain "a.txt:2 — high"))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-child-failed []
  (let [d (repo) seen (atom nil)
        {:keys [api ctx notes sent reply]} (harness true seen)]
    (fs/writeFileSync (path/join d "a.txt") "two\n")
    (reset! reply {:ok false :text "Could not resolve model"})
    (js-await (review/run! api [] ctx {:cwd d}))
    (-> (expect (last @notes)) (.toContain "Could not resolve model"))
    (-> (expect @sent) (.toBeNull))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-no-submit-path []
  (let [d (repo) seen (atom nil)
        {:keys [api ctx sent queued reply]} (harness true seen)]
    (aset api "canStartTurn" (fn [] false))
    (fs/writeFileSync (path/join d "a.txt") "two\n")
    (reset! reply (found "a.txt:1 — high — x — y"))
    (js-await (review/run! api [] ctx {:cwd d}))
    (-> (expect @sent) (.toBeNull))
    (-> (expect @queued) (.toContain "a.txt:1"))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-base-and-guards []
  (let [d (repo) seen (atom nil)
        {:keys [api ctx notes sent reply]} (harness false seen)]
    (fs/writeFileSync (path/join d "b.txt") "b\n")
    (git! d "add" ".") (git! d "commit" "-qm" "b")
    ;; clean tree: nothing uncommitted, but HEAD~1 has a change
    (js-await (review/run! api [] ctx {:cwd d}))
    (-> (expect (last @notes)) (.toContain "try /review <base>"))
    (reset! reply (found "No findings."))
    (js-await (review/run! api ["HEAD~1"] ctx {:cwd d}))
    (-> (expect (.-task @seen)) (.toContain "+b"))
    (-> (expect (last @notes)) (.toContain "no findings"))
    (-> (expect @sent) (.toBeNull))
    ;; anything a shell would read is refused before it reaches git
    (js-await (review/run! api ["main;touch x"] ctx {:cwd d}))
    (-> (expect (last @notes)) (.toContain "not a revision"))
    (js-await (review/run! api ["nope"] ctx {:cwd d}))
    (-> (expect (last @notes)) (.toContain "unknown revision"))
    (fs/rmSync d #js {:recursive true})))

(describe "/review"
          (fn []
            (it "reviews uncommitted changes and hands findings to the agent" test-uncommitted)
            (it "keeps the fix request where no mode can start a turn" test-no-submit-path)
(it "reports a failed child instead of calling it clean" test-child-failed)
(it "reviews since a base; guards bad revisions" test-base-and-guards)))
