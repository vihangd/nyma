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

(defn- harness [confirm]
  (let [notes (atom []) overlay (atom nil) sent (atom nil)]
    {:notes notes :overlay overlay :sent sent
     :api #js {:settings (fn [& _] {:roles {}})
               :__state_atom (atom {:model "stub-model"})
               :sendUserMessage (fn [m] (reset! sent m))}
     :ctx #js {:ui #js {:notify      (fn [m _] (swap! notes conj m))
                        :showOverlay (fn [t] (reset! overlay t))
                        :confirm     (fn [_] (js/Promise.resolve confirm))}}}))

(defn- gen-returning [text seen]
  (fn [opts] (reset! seen opts) (js/Promise.resolve #js {:text text})))

(defn ^:async test-uncommitted []
  (let [d (repo) seen (atom nil)
        {:keys [api ctx overlay sent]} (harness true)]
    (fs/writeFileSync (path/join d "a.txt") "one\ntwo\n")
    (fs/writeFileSync (path/join d "new.txt") "n\n")
    (js-await (review/run! api [] ctx {:cwd d :gen (gen-returning "a.txt:2 — high — off by one — fix it" seen)}))
    (-> (expect (.-prompt @seen)) (.toContain "+two"))
    (-> (expect (.-prompt @seen)) (.toContain "new.txt"))
    (-> (expect @overlay) (.toContain "off by one"))
    ;; confirmed → the findings go to the agent
    (-> (expect @sent) (.toContain "a.txt:2 — high"))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-base-and-guards []
  (let [d (repo) seen (atom nil)
        {:keys [api ctx notes sent]} (harness false)]
    (fs/writeFileSync (path/join d "b.txt") "b\n")
    (git! d "add" ".") (git! d "commit" "-qm" "b")
    ;; clean tree: nothing uncommitted, but HEAD~1 has a change
    (js-await (review/run! api [] ctx {:cwd d :gen (gen-returning "x" seen)}))
    (-> (expect (last @notes)) (.toContain "try /review <base>"))
    (js-await (review/run! api ["HEAD~1"] ctx {:cwd d :gen (gen-returning "No findings." seen)}))
    (-> (expect (.-prompt @seen)) (.toContain "+b"))
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
            (it "reviews since a base; guards bad revisions" test-base-and-guards)))
