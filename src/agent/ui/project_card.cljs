(ns agent.ui.project-card
  "The block a session opens with: where you are, and what the agent loaded.

   The status line carries SESSION facts — model, mode, cost, context fill, a
   clock. None of them answer the questions you actually have on opening a
   project: which branch, how dirty, what did the agent pick up, and why is my
   prompt this big.

   Split the way `resumed-banner` is: `render` is pure over a values map so it
   can be tested without a TUI, and `collect` does the I/O. Everything expensive
   lives in `collect`, which runs ONCE at startup — the status line re-renders at
   10 Hz while streaming and nothing here may go near it.

   Every line is optional. A non-git directory, a project with no skills, a
   machine with no repo map: each line is simply absent rather than showing a
   zero, because `0 skills` reads as a broken install and a missing line reads as
   nothing to say."
  (:require [clojure.string :as str]
            ["node:path" :as path]
            [agent.utils.home :as home]
            [agent.token-estimation :as te]
            [agent.utils.git-files :as git]
            [agent.extensions.token-suite.shared :as suite]))

;; ── formatting ───────────────────────────────────────────────────

(defn abbrev-path
  "`/Users/me/projects/x` → `~/projects/x`. The home prefix is noise in a path
   you are looking at to confirm which checkout you are in."
  [p home-dir]
  (let [p (str (or p ""))
        h (str (or home-dir ""))]
    (if (and (seq h) (.startsWith p h))
      (str "~" (.slice p (count h)))
      p)))

(defn compact-count
  "1240 → `1.2k`. Kept short because these share one line."
  [n]
  (let [n (or n 0)]
    (cond
      (< n 1000)     (str n)
      (< n 1000000)  (let [v (/ n 1000)]
                       (str (if (< v 10) (.toFixed v 1) (js/Math.round v)) "k"))
      :else          (str (.toFixed (/ n 1000000) 1) "M"))))

(defn git-line
  "`main +2 ~3 ?8 ↑1 ↓4`, or nil outside a repo.

   Compact on purpose: this is the one line a git user reads at a glance, and the
   sigils are the ones `git status -sb` already taught them. A clean repo in sync
   shows just the branch — the absence of markers IS the information."
  [{:keys [branch detached? staged unstaged untracked conflicted ahead behind]}]
  (when (or branch detached?)
    (str/join
     " "
     (cond-> [(if detached? "detached" branch)]
       (pos? (or conflicted 0)) (conj (str "!" conflicted))
       (pos? (or staged 0))     (conj (str "+" staged))
       (pos? (or unstaged 0))   (conj (str "~" unstaged))
       (pos? (or untracked 0))  (conj (str "?" untracked))
       ;; nil ahead/behind means no upstream, which is not the same as in sync,
       ;; so neither marker appears rather than showing ↑0
       (pos? (or ahead 0))      (conj (str "↑" ahead))
       (pos? (or behind 0))     (conj (str "↓" behind))))))

(defn loaded-line
  "What the agent picked up. Omits any count that is zero."
  [{:keys [extensions skills mcp-servers mcp-tools]}]
  (let [parts (cond-> []
                (pos? (or extensions 0))  (conj (str extensions " extensions"))
                (pos? (or skills 0))      (conj (str skills " skills"))
                (pos? (or mcp-servers 0))
                (conj (str mcp-servers " MCP"
                           (when (pos? (or mcp-tools 0))
                             (str " (" (compact-count mcp-tools) " tools)")))))]
    (when (seq parts) (str/join " · " parts))))

(defn prompt-line
  "What the standing prompt costs before you type anything.

   Measured at ~9,750 tokens of AGENTS.md in this repo — 92% of the system
   prompt — and nothing surfaced it. It is the most actionable number here for
   anyone wondering why every request is expensive."
  [{:keys [agents-md-tokens]}]
  (when (and agents-md-tokens (pos? agents-md-tokens))
    (str "AGENTS.md ~" (compact-count agents-md-tokens) " tok")))

(defn index-line
  "What the repo map indexed. Deliberately says `indexed`, not `files`: the
   count is extension-filtered and depth-capped, so it is not a repo file count
   and must not be labelled one."
  [{:keys [indexed-files indexed-symbols]}]
  (when (pos? (or indexed-files 0))
    (str (compact-count indexed-files) " files indexed"
         (when (pos? (or indexed-symbols 0))
           (str " · " (compact-count indexed-symbols) " symbols")))))

(defn render
  "The card, or nil when there is nothing worth printing.

   Pure. `facts` is whatever `collect` gathered; every field is optional."
  [{:keys [version cwd home-dir] :as facts}]
  (let [head  (str "nyma " (or version "?")
                   (when (seq (str (or cwd ""))) (str "  ·  " (abbrev-path cwd home-dir))))
        body  (->> [(git-line facts) (loaded-line facts)
                    (prompt-line facts) (index-line facts)]
                   (remove nil?)
                   (remove str/blank?))]
    (when (seq body)
      (str/join "\n" (cons head (map (fn [l] (str "  " l)) body))))))

;; ── collection (I/O — startup only) ──────────────────────────────

(defn collect
  "Gather the card's facts. Three git shell-outs plus in-memory reads.

   Called once, after the first frame has painted. Never from a render: the
   status line re-renders up to 10 Hz while streaming and a git spawn there
   would block the UI.

   `resources` is the JS object `loader/discover` returned — note `agents-md` is
   a hyphenated key, so it needs `aget`, not `.-agents-md`."
  [{:keys [version cwd resources extension-count]}]
  (let [cwd    (or cwd (js/process.cwd))
        agents (when resources (or (aget resources "agents-md") ""))
        skills (when resources (aget resources "skills"))
        stats  @suite/suite-stats]
    (merge
     {:version         version
      :cwd             cwd
      :home-dir        (try (home/dir) (catch :default _e nil))
      :extensions      extension-count
      :skills          (try (count (js/Object.keys (or skills #js {})))
                            (catch :default _e 0))
      :agents-md-tokens (when (seq (str agents))
                          (try (te/estimate-tokens (str agents))
                               (catch :default _e nil)))
      :indexed-files   (get-in stats [:repo-map :files])
      :indexed-symbols (get-in stats [:repo-map :symbols])}
     ;; nil outside a repo, and `merge` of nil is a no-op, so the git line
     ;; simply does not appear
     (or (try (git/repo-state cwd) (catch :default _e nil)) {}))))
