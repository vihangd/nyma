(ns agent.extensions.bash-suite.shared
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]))

;; ── Stats tracking ───────────────────────────────────────────
(def suite-stats
  (atom {:security-analysis {:commands-analyzed 0 :blocked 0
                             :classified {:safe 0 :read-only 0 :write 0
                                          :network 0 :destructive 0}}
         :permissions       {:auto-approved 0 :user-approved 0 :denied 0
                             :remembered 0}
         :output-handling   {:truncations 0 :bytes-saved 0
                             :temp-files-created 0 :retrievals 0}
         :env-filter        {:vars-stripped 0 :commands-filtered 0}
         :cwd-manager       {:cd-tracked 0 :cwd-prepended 0
                             :invalid-cwd-caught 0}
         :background-jobs   {:jobs-started 0 :jobs-completed 0 :jobs-killed 0}}))

;; ── Default configuration ────────────────────────────────────
(def default-config
  {:security-analysis {:enabled true
                       :block-destructive true
                       :block-privilege-escalation true
                       :block-obfuscated true
                       :block-network-exec true
                       :block-redirects false
                       :max-subshell-depth 4}
   :permissions {:enabled true
                 :auto-approve-safe true
                 :allowlist ["ls *" "cat *" "head *" "tail *" "wc *" "file *"
                             "git status" "git log *" "git diff *" "git show *"
                             "git branch *" "git stash *"
                             "npm test *" "npm run test *" "npm run build *"
                             "npm run lint *" "npm install" "npm ci"
                             "bun test *" "bun run *" "bun install"
                             "cargo test *" "cargo build *" "cargo check *"
                             "python -m pytest *" "go test *"
                             "echo *" "pwd" "date" "which *" "type *"
                             "find *" "grep *" "rg *" "tree *"]
                 :denylist ["rm -rf /" "rm -rf /*" "rm -rf ~" ":(){ *"
                            "dd if=/dev/*" "mkfs.*" "> /dev/sd*"
                            "chmod -R 777 /" "chmod -R 777 /*"]}
   :output-handling {:enabled true
                     :max-bytes 30720
                     :absolute-max-bytes 153600
                     :head-lines 30
                     :tail-lines 50
                     :preserve-stderr true
                     :temp-dir nil}
   :env-filter {:enabled true
                :strip-vars ["LD_PRELOAD" "LD_LIBRARY_PATH"
                             "DYLD_INSERT_LIBRARIES" "DYLD_LIBRARY_PATH"
                             "BASH_ENV" "ENV" "CDPATH" "GLOBIGNORE"
                             "IFS" "PROMPT_COMMAND" "PS4"
                             "BASH_FUNC_" "SHELLOPTS"]}
   :cwd-manager {:enabled true
                 :track-cd true
                 :validate-cwd true}
   :background-jobs {:enabled true
                     :max-concurrent 5
                     :output-buffer-lines 500
                     :auto-detect-patterns
                     ["npm run dev" "npm run start" "npm start"
                      "yarn dev" "yarn start"
                      "bun run dev" "bun run start"
                      "python -m http.server" "python manage.py runserver"
                      "cargo watch" "cargo run"
                      "go run" "nodemon"]}
   :timeout-classifier {:enabled true
                        :default-timeout-ms 30000
                        :long-running-timeout-ms 300000
                        :patterns
                        ["(^|\\s)(npm|pnpm|yarn|bun)\\s+(install|ci|run\\s+build|run\\s+test|test|build)"
                         "(^|\\s)(pytest|jest|vitest)\\b"
                         "(^|\\s)cargo\\s+(build|test|check|run)\\b"
                         "(^|\\s)go\\s+(build|test|run|mod)\\b"
                         "(^|\\s)(mvn|gradle|gradlew)\\b"
                         "(^|\\s)make\\b"
                         "(^|\\s)cmake\\b"
                         "(^|\\s)docker\\s+(build|compose\\s+build)\\b"
                         "(^|\\s)(torchrun|python.*train)\\b"
                         "(^|\\s)ffmpeg\\b"
                         "(^|\\s)tsc(\\s|$)"]}})

;; ── Utility functions ────────────────────────────────────────

(defn load-config
  "Load config from .nyma/settings.json bash-suite key, merge with defaults."
  []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")]
    (if (fs/existsSync settings-path)
      (try
        (let [raw (fs/readFileSync settings-path "utf8")
              parsed (js/JSON.parse raw)
              suite  (.-bash-suite parsed)]
          (if suite
            ;; js->clj doesn't exist in Squint; parse via JSON round-trip
            (let [suite-clj (js/JSON.parse (js/JSON.stringify suite))]
              (merge-with merge default-config suite-clj))
            default-config))
        (catch :default _e default-config))
      default-config)))

(defn is-bash-tool?
  "Check if a tool name refers to the bash tool."
  [tool-name]
  (= (str tool-name) "bash"))

(defn glob-match?
  "Simple glob matching. Supports * as wildcard for any characters.
   'git log *' matches 'git log --oneline'.
   'ls *' matches 'ls -la /tmp'."
  [pattern text]
  (let [pattern (str pattern)
        text    (str text)]
    (if (not (.includes pattern "*"))
      (= pattern text)
      (let [parts (.split pattern "*")
            first-part (aget parts 0)
            last-part  (aget parts (dec (.-length parts)))]
        (and (.startsWith text first-part)
             (or (= last-part "") (.endsWith text last-part))
             ;; Check all middle parts appear in order
             (loop [remaining (.slice text (count first-part))
                    idx       1]
               (if (>= idx (dec (.-length parts)))
                 true
                 (let [part (aget parts idx)
                       pos  (.indexOf remaining part)]
                   (if (< pos 0)
                     false
                     (recur (.slice remaining (+ pos (count part)))
                            (inc idx)))))))))))

(defn generate-id
  "Generate a short unique hex ID."
  []
  (.toString (js/Bun.hash (str (js/Date.now) "-" (js/Math.random))) 16))

(defn temp-output-dir
  "Return configured temp directory or OS temp."
  [config]
  (or (:temp-dir (:output-handling config)) (os/tmpdir)))

(defn truncate-middle
  "Keep first head-lines and last tail-lines, with truncation notice in the middle."
  [text head-lines tail-lines notice]
  (let [lines (.split (str text) "\n")
        total (.-length lines)]
    (if (<= total (+ head-lines tail-lines))
      text
      (let [head-part (.slice lines 0 head-lines)
            tail-part (.slice lines (- total tail-lines))
            omitted   (- total head-lines tail-lines)]
        (str (.join head-part "\n")
             "\n" (or notice (str "... (" omitted " lines truncated) ...")) "\n"
             (.join tail-part "\n"))))))
