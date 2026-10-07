(ns commit-command-test
  "/commit — draft with the commit role, commit on confirmation, never push."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [agent.extensions.model-roles.features.commit :as commit]))

(defn- git! [dir & args]
  (str/trim (str (cp/execFileSync "git" (clj->js args) #js {:cwd dir :encoding "utf8"}))))

(defn- repo []
  (let [d (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "nyma-commit-")))]
    (git! d "init" "-q")
    (git! d "config" "user.email" "t@t") (git! d "config" "user.name" "t")
    (fs/writeFileSync (path/join d "a.txt") "one\n")
    (git! d "add" ".") (git! d "commit" "-qm" "init")
    d))

(defn- api-with-model [prompts]
  #js {:settings (fn [& _] {:roles {}})
       :__state_atom (atom {:model "stub-model"})})

(defn- gen-returning [text seen]
  (fn [opts] (reset! seen opts) (js/Promise.resolve #js {:text text})))

(defn- ui [answers]
  (let [notes (atom [])]
    {:notes notes
     :ui #js {:notify  (fn [m _] (swap! notes conj m))
              :confirm (fn [_ & _] (js/Promise.resolve (:confirm answers)))
              :select  (fn [_ _] (js/Promise.resolve (:select answers)))
              :input   (fn [_ _] (js/Promise.resolve (:input answers)))}}))

(describe "pure helpers"
          (fn []
            (it "clean-draft strips fences, labels and quotes"
                (fn []
                  (-> (expect (commit/clean-draft "```\nfeat: x\n\nwhy\n```")) (.toBe "feat: x\n\nwhy"))
                  (-> (expect (commit/clean-draft "Commit message: fix: y")) (.toBe "fix: y"))))
            (it "with-subject replaces only the first line"
                (fn [] (-> (expect (commit/with-subject "feat: a\n\nbody" "fix: b")) (.toBe "fix: b\n\nbody"))))))

(defn ^:async test-commits-staged []
  (let [d    (repo)
        seen (atom nil)
        {:keys [ui notes]} (ui {:select "Commit"})]
    (fs/writeFileSync (path/join d "a.txt") "one\ntwo\n")
    (git! d "add" "a.txt")
    (js-await (commit/run! (api-with-model nil) #js {:ui ui}
                           {:cwd d :gen (gen-returning "```\nfeat(a): add two\n\nBecause two.\n```" seen)}))
    (-> (expect (git! d "log" "-1" "--format=%B")) (.toBe "feat(a): add two\n\nBecause two."))
    ;; the model saw the staged diff
    (-> (expect (.-prompt @seen)) (.toContain "+two"))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-stage-offer-and-cancel []
  (let [d (repo)
        {:keys [ui notes]} (ui {:confirm false})]
    (fs/writeFileSync (path/join d "a.txt") "changed\n")
    (js-await (commit/run! (api-with-model nil) #js {:ui ui} {:cwd d :gen (gen-returning "x" (atom nil))}))
    ;; declined: nothing staged, nothing committed
    (-> (expect (git! d "diff" "--cached" "--name-only")) (.toBe ""))
    (-> (expect (git! d "rev-list" "--count" "HEAD")) (.toBe "1"))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-edit-subject []
  (let [d (repo)
        {:keys [ui]} (ui {:confirm true :select "Edit subject" :input "fix: better subject"})]
    (fs/writeFileSync (path/join d "a.txt") "changed\n")
    (fs/writeFileSync (path/join d "new.txt") "untracked\n")
    (js-await (commit/run! (api-with-model nil) #js {:ui ui} {:cwd d :gen (gen-returning "feat: draft\n\nbody" (atom nil))}))
    (-> (expect (git! d "log" "-1" "--format=%B")) (.toBe "fix: better subject\n\nbody"))
    ;; tracked change staged on confirmation; untracked file never added
    (-> (expect (git! d "status" "--porcelain")) (.toBe "?? new.txt"))
    (fs/rmSync d #js {:recursive true})))

(describe "/commit"
          (fn []
            (it "commits the staged change with the drafted message" test-commits-staged)
            (it "offers to stage tracked changes; declining commits nothing" test-stage-offer-and-cancel)
            (it "edit subject keeps the body; untracked files are never staged" test-edit-subject)))
