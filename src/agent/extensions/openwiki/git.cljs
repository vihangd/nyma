(ns agent.extensions.openwiki.git
  "Git plumbing for OpenWiki. Synchronous shell-outs (node:child_process),
   matching the upstream design — git evidence is collected in the host
   process before the agent turn starts. Every call is guarded; a failure
   yields \"\" rather than throwing so the command handler stays robust."
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(defn- git
  "Run a git command, return trimmed stdout, or \"\" on any failure."
  [cmd]
  (try
    ;; maxBuffer: execSync defaults to 1 MB, and `ls-files` on a large repo
    ;; exceeds it — which threw ENOBUFS, hit the catch, and returned "". The
    ;; caller cannot tell that from "clean repo", so the orientation block went
    ;; silently empty. Exactly the failure the truncation fix was meant to end.
    (str/trim (cp/execSync (str "git " cmd)
                           #js {:encoding "utf-8" :maxBuffer (* 64 1024 1024)}))
    (catch :default _ "")))

(defn in-git-repo? []
  (try
    (cp/execSync "git rev-parse --is-inside-work-tree" #js {:stdio "ignore"})
    true
    (catch :default _ false)))

(defn head []
  (git "rev-parse HEAD"))

(defn collect-context
  "Status + recent log + working-tree changes — the baseline evidence for init."
  []
  {:status (git "status --short")
   :log    (git "log --oneline --max-count=20")
   ;; name-status, NOT a patch — the prompts must label it as the file list it
   ;; is, or the model believes it has seen the changes themselves.
   :diff   (git "diff --name-status HEAD")})

(defn changes-since
  "Committed changes since `sha` (name-status + oneline). Empty when `sha` is
   blank or invalid."
  [sha]
  (if (str/blank? (str sha))
    ""
    (git (str "log " sha "..HEAD --name-status --oneline"))))

(def ^:private tree-limit 120)

(defn repo-tree
  "A bounded orientation listing: file counts per directory (three levels
   deep), busiest first, plus the root-level files.

   Not a truncated `ls-files`. That is what this was, and on a 700-file repo
   `head -200` handed the model an alphabetical prefix — `.github/`, `bench/`,
   `docs/` and one sliver of `src/`, never `src/agent/loop.cljs` or
   `src/gateway/` — with nothing saying it had been cut. A silent prefix is
   worse than a summary: it reads as the whole repo."
  []
  (let [files (->> (str/split-lines (git "ls-files"))
                   (map str/trim)
                   (remove str/blank?))
        total (count files)]
    (if (zero? total)
      ""
      (let [group  (fn [f]
                     (let [parts (.split f "/")]
                       (if (<= (count parts) 1)
                         "(repo root)"
                         (str/join "/" (take 3 (butlast parts))))))
            counts (->> files
                        (reduce (fn [acc f] (let [g (group f)] (assoc acc g (inc (or (get acc g) 0))))) {})
                        (sort-by (fn [[_ n]] (- n))))
            shown  (take tree-limit counts)]
        (str total " tracked files across " (count counts) " directories"
             (when (> (count counts) tree-limit)
               (str " — the " tree-limit " largest are listed; "
                    (- (count counts) tree-limit) " smaller directories are not"))
             ":\n"
             (str/join "\n" (map (fn [[k n]] (str "  " n "\t" k)) shown)))))))
