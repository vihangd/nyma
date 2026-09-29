(ns git-repo-state.test
  "Porcelain parsing, kept separate from the shell-out so it is testable without
   a repo — and because the column semantics are easy to get wrong.

   X is the index, Y the worktree, and they are INDEPENDENT: one file can be both
   staged and unstaged. A conflict is U in either column (or AA/DD), and counting
   those as ordinary staged changes would report a clean-looking repo mid-merge."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.utils.git-files :as g]))

(defn- p [s] (g/parse-porcelain s))

(describe "parse-porcelain" (fn []

  (it "counts staged and unstaged independently for one file"
      (fn []
        ;; "MM" = staged modification AND a further worktree modification
        (let [r (p "MM src/a.cljs")]
          (-> (expect (:staged r)) (.toBe 1))
          (-> (expect (:unstaged r)) (.toBe 1)))))

  (it "a staged-only change is not counted as unstaged"
      (fn []
        (let [r (p "M  src/a.cljs")]
          (-> (expect (:staged r)) (.toBe 1))
          (-> (expect (:unstaged r)) (.toBe 0)))))

  (it "a worktree-only change is not counted as staged"
      (fn []
        (let [r (p " M src/a.cljs")]
          (-> (expect (:staged r)) (.toBe 0))
          (-> (expect (:unstaged r)) (.toBe 1)))))

  (it "untracked files are their own bucket"
      (fn []
        (let [r (p "?? new.cljs\n?? other.cljs")]
          (-> (expect (:untracked r)) (.toBe 2))
          (-> (expect (:staged r)) (.toBe 0))
          (-> (expect (:unstaged r)) (.toBe 0)))))

  (it "a conflict is a conflict, not a staged change"
      (fn []
        ;; mid-merge this is the number that matters, and UU has U in both
        ;; columns so a naive tally would report it as staged AND unstaged
        (let [r (p "UU src/a.cljs\nAA src/b.cljs\nDD src/c.cljs")]
          (-> (expect (:conflicted r)) (.toBe 3))
          (-> (expect (:staged r)) (.toBe 0))
          (-> (expect (:unstaged r)) (.toBe 0)))))

  (it "a one-sided conflict marker still counts as conflicted"
      (fn []
        (-> (expect (:conflicted (p "AU src/a.cljs"))) (.toBe 1))
        (-> (expect (:conflicted (p "UD src/b.cljs"))) (.toBe 1))))

  (it "renames and copies count on the side they appear"
      (fn []
        (let [r (p "R  old.cljs -> new.cljs")]
          (-> (expect (:staged r)) (.toBe 1))
          (-> (expect (:unstaged r)) (.toBe 0)))))

  (it "a clean repo is all zeros, not nil"
      (fn []
        (-> (expect (p "")) (.toEqual (clj->js {:staged 0 :unstaged 0 :untracked 0 :conflicted 0})))))

  (it "blank and truncated lines are skipped rather than miscounted"
      (fn []
        (let [r (p "\n\nM\n M src/a.cljs\n")]
          (-> (expect (:unstaged r)) (.toBe 1))
          (-> (expect (:staged r)) (.toBe 0)))))))

(describe "parse-ahead-behind" (fn []

  (it "reads behind then ahead, which is the order rev-list --left-right gives"
      (fn []
        ;; @{upstream}...HEAD: left is upstream-only (behind), right is HEAD-only
        (-> (expect (g/parse-ahead-behind "2\t5")) (.toEqual (clj->js {:ahead 5 :behind 2})))))

  (it "in sync is 0/0, which is NOT the same as no upstream"
      (fn []
        (-> (expect (g/parse-ahead-behind "0\t0")) (.toEqual (clj->js {:ahead 0 :behind 0})))))

  (it "no upstream is nil, because run-git returns empty on failure"
      (fn []
        ;; `git rev-list @{upstream}` exits non-zero without a tracking branch,
        ;; and run-git turns any failure into "" — so nil must mean "unknown",
        ;; not "in sync"
        (-> (expect (g/parse-ahead-behind "")) (.toBeNil))
        (-> (expect (g/parse-ahead-behind "   ")) (.toBeNil))))

  (it "garbage is nil rather than NaN"
      (fn []
        (-> (expect (g/parse-ahead-behind "fatal: no upstream")) (.toBeNil))
        (-> (expect (g/parse-ahead-behind "1")) (.toBeNil))))

  (it "tolerates spaces instead of a tab"
      (fn []
        (-> (expect (g/parse-ahead-behind "3 4")) (.toEqual (clj->js {:ahead 4 :behind 3})))))))

;;; ─── through the real shell, in a real repo ────────────────────
;;
;; The parser tests above all feed a string directly, which is exactly why they
;; missed the bug that mattered: `run-git` trims BOTH ends, so the leading space
;; of a first-line ` M` — the worktree column — was gone before the parser saw
;; it, and a worktree change was reported as staged. Only a test that goes
;; through the shell can catch that class.

(describe "repo-state through git itself" (fn []

  (it "a worktree-only change reads as unstaged, not staged"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-git-"))]
          (g/run-git "init -q" d)
          (g/run-git "config user.email t@t" d)
          (g/run-git "config user.name t" d)
          (fs/writeFileSync (path/join d "a.txt") "one\n")
          (g/run-git "add a.txt" d)
          (g/run-git "commit -qm first" d)
          ;; modify without staging — porcelain gives " M a.txt", and this is
          ;; the FIRST line, where the trim bug lived
          (fs/writeFileSync (path/join d "a.txt") "two\n")
          (let [r (g/repo-state d)]
            (-> (expect (:unstaged r)) (.toBe 1))
            (-> (expect (:staged r)) (.toBe 0)))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "staging that same change moves it to staged"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-git-"))]
          (g/run-git "init -q" d)
          (g/run-git "config user.email t@t" d)
          (g/run-git "config user.name t" d)
          (fs/writeFileSync (path/join d "a.txt") "one\n")
          (g/run-git "add a.txt" d)
          (g/run-git "commit -qm first" d)
          (fs/writeFileSync (path/join d "a.txt") "two\n")
          (g/run-git "add a.txt" d)
          (let [r (g/repo-state d)]
            (-> (expect (:staged r)) (.toBe 1))
            (-> (expect (:unstaged r)) (.toBe 0)))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "no upstream gives nil ahead/behind, not 0/0"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-git-"))]
          (g/run-git "init -q" d)
          (g/run-git "config user.email t@t" d)
          (g/run-git "config user.name t" d)
          (fs/writeFileSync (path/join d "a.txt") "one\n")
          (g/run-git "add a.txt" d)
          (g/run-git "commit -qm first" d)
          (let [r (g/repo-state d)]
            (-> (expect (:ahead r)) (.toBeUndefined))
            (-> (expect (:behind r)) (.toBeUndefined)))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "outside a repo it is nil, not an empty tally"
      (fn []
        ;; a tally of zeros would render as a clean repo; nil renders as nothing
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-nogit-"))]
          (-> (expect (g/repo-state d)) (.toBeNil))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "untracked files are counted without being confused for changes"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-git-"))]
          (g/run-git "init -q" d)
          (fs/writeFileSync (path/join d "new.txt") "x\n")
          (let [r (g/repo-state d)]
            (-> (expect (:untracked r)) (.toBe 1))
            (-> (expect (:staged r)) (.toBe 0))
            (-> (expect (:unstaged r)) (.toBe 0)))
          (fs/rmSync d #js {:recursive true :force true}))))))
