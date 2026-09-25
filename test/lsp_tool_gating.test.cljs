(ns lsp-tool-gating.test
  "Nine LSP tools ride on every request — roughly 1,650 tokens of near-duplicate
   path/line/character schemas — whether or not a language server could ever
   answer. On a machine with no server binaries installed, and in every headless
   benchmark run, they are pure weight.

   The obvious predicate does not work. `get-any-running-client` and
   `lsp-client/running?` are only ever true BECAUSE a tool was called: servers
   spawn lazily and the LSP tools are what spawn them. Gate on those and they
   can never become true. So the gate is config-level — a catalog server that is
   not disabled and whose binary is on PATH — which is answerable before any
   call and cheap enough to compute once."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.extensions.lsp-suite.lsp-config :as cfg]))

(def ^:private servers
  {"ts"   {:id "ts"   :command ["typescript-language-server" "--stdio"]
           :extensions [".ts" ".js"] :disabled? false}
   "ruby" {:id "ruby" :command ["ruby-lsp"]
           :extensions [".rb"] :disabled? false}
   "rust" {:id "rust" :command ["rust-analyzer"]
           :extensions [".rs"] :disabled? false}})

(defn- on-path [& names] (fn [c] (contains? (set names) c)))

;;; ─── the predicate ─────────────────────────────────────────────

(describe "lsp gating: is any server actually usable" (fn []

  (it "yes when an installed server handles a file type that is here"
      (fn []
        (-> (expect (cfg/any-server-available? servers (on-path "ruby-lsp") [".rb" ".md"]))
            (.toBe true))))

  (it "no when nothing is installed"
      (fn []
        (-> (expect (cfg/any-server-available? servers (on-path) [".rb"])) (.toBe false))))

  (it "an installed server for a language that is NOT here does not count"
      (fn []
        ;; the case measured on this machine: rust-analyzer and clangd installed,
        ;; the JavaScript server not, and a pure-JavaScript workspace. A plain
        ;; "is anything on PATH" test says yes and the nine schemas ride along
        ;; for nothing.
        (-> (expect (cfg/any-server-available? servers (on-path "rust-analyzer") [".js" ".json"]))
            (.toBe false))))

  (it "matches when both the binary and the file type line up"
      (fn []
        (-> (expect (cfg/any-server-available?
                     servers (on-path "typescript-language-server") [".js" ".json"]))
            (.toBe true))))

  (it "a disabled server does not count even if installed and relevant"
      (fn []
        (let [s (assoc-in servers ["ruby" :disabled?] true)]
          (-> (expect (cfg/any-server-available? s (on-path "ruby-lsp") [".rb"])) (.toBe false)))))

  (it "an empty catalog is not usable"
      (fn []
        (-> (expect (cfg/any-server-available? {} (on-path "ruby-lsp") [".rb"])) (.toBe false))))

  (it "a server with no command is skipped rather than crashing"
      (fn []
        (-> (expect (cfg/any-server-available?
                     {"x" {:id "x" :command [] :extensions [".x"]}} (on-path "ruby-lsp") [".x"]))
            (.toBe false))))

  (it "an empty workspace matches nothing"
      (fn []
        (-> (expect (cfg/any-server-available? servers (on-path "ruby-lsp") []))
            (.toBe false))))))

;;; ─── the workspace scan ────────────────────────────────────────

(describe "lsp gating: what is actually in the workspace" (fn []

  (it "finds the extensions present, dotted to match the catalog"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-lsp-"))]
          (fs/writeFileSync (path/join d "a.js") "x")
          (fs/mkdirSync (path/join d "src"))
          (fs/writeFileSync (path/join d "src" "b.rb") "x")
          (let [exts (cfg/workspace-extensions d)]
            (-> (expect (contains? exts ".js")) (.toBe true))
            (-> (expect (contains? exts ".rb")) (.toBe true))
            (-> (expect (contains? exts ".go")) (.toBe false)))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "skips node_modules and friends, which are not the workspace's languages"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-lsp-"))]
          (fs/mkdirSync (path/join d "node_modules"))
          (fs/writeFileSync (path/join d "node_modules" "dep.rs") "x")
          (fs/writeFileSync (path/join d "main.js") "x")
          (let [exts (cfg/workspace-extensions d)]
            (-> (expect (contains? exts ".js")) (.toBe true))
            (-> (expect (contains? exts ".rs")) (.toBe false)))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "a dotfile is not an extension"
      (fn []
        (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-lsp-"))]
          (fs/writeFileSync (path/join d ".npmrc") "x")
          (-> (expect (contains? (cfg/workspace-extensions d) ".npmrc")) (.toBe false))
          (fs/rmSync d #js {:recursive true :force true}))))

  (it "an unreadable directory is not fatal"
      (fn []
        (-> (expect (cfg/workspace-extensions "/definitely/not/here")) (.toEqual #{}))))))

;;; ─── the subtraction ───────────────────────────────────────────

(def ^:private offered
  ["read" "write" "bash" "lsp-suite__hover" "lsp-suite__find_references"
   "lsp-suite__code_action" "mcp_search" "mcp_call" "retrieve_result"])

(describe "lsp gating: what the tool_access_check handler answers" (fn []

  (it "subtracts the LSP tools when no server could serve them"
      (fn []
        (let [out (set (cfg/without-lsp-tools offered ["hover" "find_references" "code_action"]))]
          (-> (expect (contains? out "lsp-suite__hover")) (.toBe false))
          (-> (expect (contains? out "lsp-suite__code_action")) (.toBe false))
          (-> (expect (contains? out "read")) (.toBe true))
          (-> (expect (contains? out "bash")) (.toBe true)))))

  (it "subtraction preserves the gateway tools without naming them"
      (fn []
        ;; this event merges by intersection, so a SUBTRACTIVE answer never has
        ;; to reason about mcp_search/mcp_call/retrieve_result — it keeps
        ;; anything it does not name. That is why token_suite's budget handler
        ;; is shaped this way too.
        (let [out (set (cfg/without-lsp-tools offered ["hover" "find_references" "code_action"]))]
          (-> (expect (contains? out "mcp_search")) (.toBe true))
          (-> (expect (contains? out "mcp_call")) (.toBe true))
          (-> (expect (contains? out "retrieve_result")) (.toBe true)))))

  (it "matches an unprefixed registration too"
      (fn []
        (let [out (set (cfg/without-lsp-tools ["hover" "read"] ["hover"]))]
          (-> (expect (contains? out "hover")) (.toBe false))
          (-> (expect (contains? out "read")) (.toBe true)))))

  (it "does not eat a tool that merely ends in a similar word"
      (fn []
        ;; a real LSP tool is in the list so the handler DOES have an opinion —
        ;; otherwise it correctly returns nil and the assertion would pass
        ;; against a set that is empty for the wrong reason
        (let [out (set (cfg/without-lsp-tools
                        ["my_hover" "hoverboard" "read" "lsp-suite__hover"] ["hover"]))]
          (-> (expect (contains? out "lsp-suite__hover")) (.toBe false))
          (-> (expect (contains? out "my_hover")) (.toBe true))
          (-> (expect (contains? out "hoverboard")) (.toBe true))
          (-> (expect (contains? out "read")) (.toBe true)))))

  (it "returns nil when it would remove nothing, which is how you say no opinion"
      (fn []
        ;; nil means "no opinion" to the intersection merge; returning the full
        ;; list instead is harmless but noisy, and returning [] would hide
        ;; every tool in the session
        (-> (expect (cfg/without-lsp-tools ["read" "bash"] ["hover"])) (.toBeNil))))))
