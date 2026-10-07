(ns direct-spawn-lint.test
  "A compiled-in extension can reach `Bun.spawn` or `node:child_process` directly,
   with nothing to declare and nothing to gate it. Spawning is the sharpest thing
   an extension does, so this allowlists who may, and a new spawner has to appear
   in a diff.

   This is NOT the `:spawn` capability. That one means \"may call api.exec /
   api.spawn\", and declaring it on an extension that only uses `Bun.spawn`
   directly fails the capability-coverage lint's `declares no capability it never
   uses`. Two different questions.

   Note the roadmap entry names `Bun.spawn` ONLY. `lsp_suite` spawns through
   `node:child_process`, so a lint written to the letter of that entry would
   have reported green while a language server was being launched behind its
   back. Both forms are covered here, and the test below plants one of each."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private ext-root "src/agent/extensions")

(defn- cljs-files [dir]
  (mapcat (fn [e]
            (let [p (path/join dir (.-name e))]
              (cond (.isDirectory e) (cljs-files p)
                    (.endsWith (.-name e) ".cljs") [p]
                    :else [])))
          (fs/readdirSync dir #js {:withFileTypes true})))

(defn spawns?
  "Does this source spawn a process? Pure, and exposed so the planted-violation
   test below exercises the same predicate the lint uses.

   Covers both forms: `Bun.spawn`/`Bun.spawnSync` and anything reached through
   `node:child_process` (`spawn`, `spawnSync`, `exec`, `execSync`, `execFile`,
   `fork`). A lint that knew only the first would lie green."
  [src]
  (boolean
   (or (re-find #"Bun\.spawn|js/Bun\.spawn|\.spawnSync|\.spawn\b" (str src))
       (and (re-find #"node:child_process" (str src))
            (re-find #"exec|spawn|fork" (str src))))))

(defn- ext-dirs []
  (->> (fs/readdirSync ext-root #js {:withFileTypes true})
       (filter (fn [e] (.isDirectory e)))
       (map (fn [e] (.-name e)))
       vec))

(defn- declares-spawn? [dir]
  (let [m (path/join ext-root dir "extension.json")]
    (and (fs/existsSync m)
         (try
           (let [caps (aget (js/JSON.parse (fs/readFileSync m "utf8")) "capabilities")]
             (boolean (and caps (.includes caps "spawn"))))
           (catch :default _e false)))))

(defn- spawning-dirs []
  (->> (ext-dirs)
       (filter (fn [d]
                 (some (fn [f] (spawns? (fs/readFileSync f "utf8")))
                       (cljs-files (path/join ext-root d)))))
       vec))

;;; ─── the lint's own predicate must be able to fail ─────────────

(describe "spawn detection covers both forms" (fn []

  (it "catches Bun.spawn"
      (fn []
        (-> (expect (spawns? "(js/Bun.spawn #js [\"sh\"])")) (.toBe true))))

  (it "catches node:child_process, which the roadmap entry omits"
      (fn []
        ;; lsp_suite uses this form; a Bun-only lint reported green while a
        ;; language server was launched behind it
        (-> (expect (spawns? "(:require [\"node:child_process\" :as cp])\n(cp/spawnSync \"which\" x)"))
            (.toBe true))
        (-> (expect (spawns? "(:require [\"node:child_process\" :as cp])\n(cp/execSync \"git status\")"))
            (.toBe true))))

  (it "does not fire on source that merely mentions the words"
      (fn []
        (-> (expect (spawns? ";; we could exec something here one day")) (.toBe false))
        (-> (expect (spawns? "(defn forked-path [x] x)")) (.toBe false))))))

;;; ─── the lint ──────────────────────────────────────────────────

(def permitted
  "Extensions allowed to launch a process directly. An ALLOWLIST, deliberately:
   spawning is the sharpest thing an extension can do, and the point of this test
   is that adding a new spawner requires editing this list, in a diff a reviewer
   sees.

   Why not the `:spawn` capability — which is what the roadmap entry suggested?
   Because `:spawn` means \"may call `api.exec` / `api.spawn`\", not \"launches
   processes\". The capability-coverage lint enforces that reading in the other
   direction: declaring `spawn` on an extension that never calls those APIs fails
   `declares no capability it never uses`. Trying it that way is what surfaced the
   conflict. Two different questions, so two different mechanisms."
  #{"agent_shell"              ; ACP agent subprocesses
    "agent_state"              ; state inspection helpers
    "bash_suite"               ; the shell tool itself
    "claude_hook_bridge"       ; runs user-configured hook commands
    "custom_provider_qwen_cli" ; drives the qwen CLI
    "lsp_suite"                ; lazily spawns language servers
    "subagent"                 ; isolation:worktree runs the child as its own nyma
    "verify_gate"})            ; runs the configured verify command

(describe "direct process spawning is allowlisted" (fn []

  (it "no extension outside the allowlist launches a process"
      (fn []
        (let [unexpected (vec (remove permitted (spawning-dirs)))]
          (-> (expect unexpected) (.toEqual #js [])))))

  (it "the allowlist has no stale entries"
      (fn []
        ;; an entry for an extension that no longer spawns is a permission
        ;; nobody revoked
        (let [actual (set (spawning-dirs))
              stale  (vec (remove actual permitted))]
          (-> (expect stale) (.toEqual #js [])))))

  (it "the lint is actually looking at something"
      (fn []
        ;; a lint over an empty set passes forever
        (-> (expect (> (count (spawning-dirs)) 2)) (.toBe true))))))
