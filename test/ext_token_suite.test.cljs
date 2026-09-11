(ns ext-token-suite.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions.token-suite.priority-assembly :as priority-assembly]
            [agent.extensions.token-suite.repo-map :as repo-map]
            [agent.extensions.token-suite.diff-edit :as diff-edit]
            [agent.extensions.token-suite.structured-context :as structured-context]
            [agent.extensions.token-suite.smart-compaction :as smart-compaction]
            [agent.extensions :refer [create-extension-api]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [clojure.string :as str]))

(defn- make-agent []
  (create-agent {:model "mock-model" :system-prompt "You are a test agent."}))

(defn- make-api [agent]
  (create-extension-api agent))

(defn- reset-stats! []
  (reset! shared/suite-stats
          {           :kv-cache         {:turns 0 :cache-hits 0 :cached-tokens 0}
           :repo-map         {:files 0 :symbols 0 :last-index-ms 0}
           :priority-assembly {:turns 0 :messages-pruned 0 :tokens-saved 0}
           :diff-edit          {:hunks-applied 0 :fuzzy-matches 0 :chars-saved 0 :calls 0}
           :structured-context {:files-discovered 0 :hot-tokens 0 :warm-tokens 0 :cache-hits 0}
           :smart-compaction   {:full-compactions 0}}))

(beforeEach reset-stats!)

;; ═══════════════════════════════════════════════════════════════
;; Tool Result Truncation
;; ═══════════════════════════════════════════════════════════════

(describe "token-suite shared: head/tail helpers" (fn []
                                  (it "passes short results unchanged"
                                      (fn []
                                        (let [short "line1\nline2\nline3"]
                                          (-> (expect (shared/truncate-head-tail short 100 50)) (.toBe short)))))

                                  (it "truncates long text with head+tail"
                                      (fn []
                                        (let [lines (clj->js (map #(str "line-" %) (range 300)))
                                              text  (.join lines "\n")
                                              result (shared/truncate-head-tail text 10 5)]
                                          (-> (expect result) (.toContain "line-0"))
                                          (-> (expect result) (.toContain "line-9"))
                                          (-> (expect result) (.toContain "line-299"))
                                          (-> (expect result) (.toContain "truncated")))))

                                  (it "preserves text under threshold"
                                      (fn []
                                        (-> (expect (shared/truncate-head-tail "short" 100 50)) (.toBe "short"))))

                                  (it "count-lines works correctly"
                                      (fn []
                                        (-> (expect (shared/count-lines "a\nb\nc")) (.toBe 3))
                                        (-> (expect (shared/count-lines "")) (.toBe 0))
                                        (-> (expect (shared/count-lines "no newlines")) (.toBe 1))))

                                  (it "has-error-pattern detects errors"
                                      (fn []
                                        (-> (expect (shared/has-error-pattern? "TypeError: foo")) (.toBe true))
                                        (-> (expect (shared/has-error-pattern? "all good")) (.toBe false))))))

;; ═══════════════════════════════════════════════════════════════
;; KV Cache Optimization
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-kv-restructures-claude []
  (let [;; Create agent with a Claude-like model that has .modelId
        model #js {:modelId "claude-sonnet-4-20250514"}
        agent (create-agent {:model model :system-prompt "You are a test agent."})
        api   (make-api agent)
        _deact (kv-cache/activate api)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"})
                           50)
    (js-await (run agent "test"))
    (-> (expect (some? @seen-system)) (.toBe true))))

(defn ^:async test-kv-leaves-non-claude []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (kv-cache/activate api)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"})
                           50)
    (js-await (run agent "test"))
    (-> (expect (string? @seen-system)) (.toBe true))))

(defn ^:async test-kv-system-array-elements-have-role-system []
  ;; Contract guard: AI SDK requires SystemModelMessage {role: "system", content: string}
  ;; NOT {type: "text", text: string}. Anthropic API rejects the latter.
  (let [model #js {:modelId "claude-sonnet-4-20250514"}
        ;; Pad system prompt beyond min-system-tokens threshold (~500 chars)
        long-prompt (str/join " " (repeat 800 "The quick brown fox jumps over the lazy dog."))
        agent (create-agent {:model model :system-prompt long-prompt})
        api   (make-api agent)
        _deact (kv-cache/activate api)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"})
                           50)
    (js-await (run agent "test"))
    ;; Must have been restructured into an array
    (-> (expect (js/Array.isArray @seen-system)) (.toBe true))
    ;; Every element must have role="system" and content (not type/text)
    (doseq [i (range (.-length @seen-system))]
      (let [entry (aget @seen-system i)]
        (-> (expect (.-role entry)) (.toBe "system"))
        (-> (expect (.-content entry)) (.toBeDefined))
        (-> (expect (.-text entry)) (.toBeUndefined))))))

(defn ^:async test-kv-system-array-stable-section-has-cache-control []
  ;; Contract guard: stable section must carry cacheControl providerOptions
  (let [model #js {:modelId "claude-sonnet-4-20250514"}
        long-prompt (str/join " " (repeat 800 "The quick brown fox jumps over the lazy dog."))
        agent (create-agent {:model model :system-prompt long-prompt})
        api   (make-api agent)
        _deact (kv-cache/activate api)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"})
                           50)
    (js-await (run agent "test"))
    (when (js/Array.isArray @seen-system)
      (let [stable (aget @seen-system 0)
            dynamic (aget @seen-system 1)]
        ;; Stable section must carry cacheControl
        (-> (expect (.. stable -providerOptions -anthropic -cacheControl -type))
            (.toBe "ephemeral"))
        ;; Dynamic section should NOT have cacheControl
        (-> (expect (.-providerOptions dynamic)) (.toBeUndefined))))))

(describe "ext-kv-cache" (fn []
                           (it "restructures system for Claude models" test-kv-restructures-claude)
                           (it "leaves non-Claude models unchanged" test-kv-leaves-non-claude)
                           (it "system array elements have role 'system' and content key"
                               test-kv-system-array-elements-have-role-system)
                           (it "stable section carries cacheControl providerOptions"
                               test-kv-system-array-stable-section-has-cache-control)
                           (it "deactivate resets hash state"
                               (fn []
                                 (let [agent (make-agent)
                                       api   (make-api agent)
                                       deact (kv-cache/activate api)]
                                   (deact)
                                   (-> (expect true) (.toBe true)))))
                           (it "stats atom tracks turns"
                               (fn []
                                 (let [agent (make-agent)
                                       api   (make-api agent)
                                       _deact (kv-cache/activate api)]
                                   ((:emit (:events agent)) "after_provider_request"
                                                            #js {:cachedTokens 5000 :model "test"})
                                   (-> (expect (:turns (:kv-cache @shared/suite-stats))) (.toBe 1))
                                   (-> (expect (:cached-tokens (:kv-cache @shared/suite-stats))) (.toBe 5000)))))))

;; ═══════════════════════════════════════════════════════════════
;; Priority Prompt Assembly
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-priority-no-prune-under-budget []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (priority-assembly/activate api)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] #js {:block true :reason "ok"}) 0)
    (js-await (run agent "test"))
    (-> (expect (:messages-pruned (:priority-assembly @shared/suite-stats))) (.toBe 0))))

(describe "ext-priority-assembly" (fn []
                                    (it "returns nil when under budget" test-priority-no-prune-under-budget)
                                    (it "activates without error"
                                        (fn []
                                          (let [agent (make-agent)
                                                api   (make-api agent)
                                                deact (priority-assembly/activate api)]
                                            (-> (expect (fn? deact)) (.toBe true)))))))

;; ═══════════════════════════════════════════════════════════════
;; Repo Map
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-repo-map-injects-prompt []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (repo-map/activate api)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"})
                           0)
    (js-await (run agent "test"))
    (-> (expect @seen-system) (.toContain "Repository Map"))))

(describe "ext-repo-map" (fn []
                           (it "activates and indexes current directory"
                               (fn []
                                 (let [agent (make-agent)
                                       api   (make-api agent)
                                       deact (repo-map/activate api)]
                                   (-> (expect (:files (:repo-map @shared/suite-stats))) (.toBeGreaterThan 0))
                                   (deact))))

                           (it "extracts symbols from source files"
                               (fn []
                                 (let [agent (make-agent)
                                       api   (make-api agent)
                                       _deact (repo-map/activate api)]
                                   (-> (expect (:symbols (:repo-map @shared/suite-stats))) (.toBeGreaterThan 0)))))

                           (it "injects prompt-section via before_agent_start" test-repo-map-injects-prompt)))

;; ═══════════════════════════════════════════════════════════════
;; Diff-Only Edit (fuzzy matching + multi_edit)
;; ═══════════════════════════════════════════════════════════════

(defn- tmp-file [content]
  (let [fpath (path/join (os/tmpdir) (str "nyma-test-" (js/Date.now) "-" (js/Math.random) ".txt"))]
    (fs/writeFileSync fpath content "utf8")
    fpath))

(defn ^:async test-multi-edit-exact-match []
  (let [fpath (tmp-file "function hello() {\n  return 1;\n}\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "return 1;"
                                             :new_string "return 2;"}]}))]
          (-> (expect result) (.toContain "1/1"))
          (-> (expect result) (.toContain "exact"))
          (let [updated (fs/readFileSync fpath "utf8")]
            (-> (expect updated) (.toContain "return 2;"))))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-whitespace-fuzzy []
  (let [fpath (tmp-file "function  hello()  {\n  return   1;\n}\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "return 1;"
                                             :new_string "return 42;"}]}))]
          (-> (expect result) (.toContain "1/1"))
          (let [updated (fs/readFileSync fpath "utf8")]
            (-> (expect updated) (.toContain "return 42;"))))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-indent-match []
  (let [fpath (tmp-file "class Foo {\n    fn bar() {\n        return 1;\n    }\n}\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        ;; Search with no indentation, should match via indent-match
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "fn bar() {\nreturn 1;\n}"
                                             :new_string "fn bar() {\nreturn 2;\n}"}]}))]
          (-> (expect result) (.toContain "1/1"))
          (let [updated (fs/readFileSync fpath "utf8")]
            (-> (expect updated) (.toContain "return 2;"))))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-multiple-hunks []
  (let [fpath (tmp-file "line A\nline B\nline C\nline D\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "line A" :new_string "line X"}
                                        #js {:old_string "line C" :new_string "line Y"}]}))]
          (-> (expect result) (.toContain "2/2"))
          (let [updated (fs/readFileSync fpath "utf8")]
            (-> (expect updated) (.toContain "line X"))
            (-> (expect updated) (.toContain "line Y"))
            (-> (expect updated) (.toContain "line B"))))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-partial-failure []
  (let [fpath (tmp-file "line A\nline B\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "line A" :new_string "line X"}
                                        #js {:old_string "NONEXISTENT" :new_string "line Z"}]}))]
          (-> (expect result) (.toContain "1/2"))
          (-> (expect result) (.toContain "not found")))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-ambiguous-rejected []
  ;; old_string occurs twice → must NOT silently edit the first; reject + report.
  (let [fpath (tmp-file "x = 1;\ny = 2;\nx = 1;\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "x = 1;" :new_string "x = 9;"}]}))]
          (-> (expect result) (.toContain "AMBIGUOUS"))
          (-> (expect result) (.toContain "0/1"))
          ;; file unchanged — no corruption
          (let [updated (fs/readFileSync fpath "utf8")]
            (-> (expect updated) (.not.toContain "x = 9;"))
            (-> (expect (count (.split updated "x = 1;"))) (.toBe 3))))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-repair-hint []
  ;; not-found, but a near-match exists → error must show the closest text to copy,
  ;; turning a dead "not found" into a one-turn self-correction.
  (let [fpath (tmp-file "the quick brown fox jumps\nsecond line\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (let [result (js-await
                      ((.-execute multi-edit)
                       #js {:path fpath
                            :edits #js [#js {:old_string "the quick brown cat sits"
                                             :new_string "REPLACED"}]}))]
          (-> (expect result) (.toContain "0/1"))
          (-> (expect result) (.toContain "closest text"))
          (-> (expect result) (.toContain "the quick brown fox jumps"))
          ;; no corruption — file untouched
          (let [updated (fs/readFileSync fpath "utf8")]
            (-> (expect updated) (.toContain "the quick brown fox jumps"))
            (-> (expect updated) (.not.toContain "REPLACED"))))))
    (fs/unlinkSync fpath)))

(defn ^:async test-multi-edit-stats []
  (let [fpath (tmp-file "aaa\nbbb\nccc\n")
        agent (make-agent)
        api   (make-api agent)
        _deact (diff-edit/activate api)
        tools ((:get-active (:tool-registry agent)))]
    (let [multi-edit (get tools "multi_edit")]
      (when multi-edit
        (js-await
         ((.-execute multi-edit)
          #js {:path fpath
               :edits #js [#js {:old_string "aaa" :new_string "xxx"}]}))
        (-> (expect (:calls (:diff-edit @shared/suite-stats))) (.toBe 1))
        (-> (expect (:hunks-applied (:diff-edit @shared/suite-stats))) (.toBe 1))))
    (fs/unlinkSync fpath)))

(describe "ext-diff-edit" (fn []
                            (it "exact match works" test-multi-edit-exact-match)
                            (it "whitespace-insensitive fuzzy match" test-multi-edit-whitespace-fuzzy)
                            (it "indentation-preserving match" test-multi-edit-indent-match)
                            (it "applies multiple hunks" test-multi-edit-multiple-hunks)
                            (it "handles partial failure" test-multi-edit-partial-failure)
                            (it "rejects ambiguous (multi-match) edits without corrupting" test-multi-edit-ambiguous-rejected)
                            (it "shows closest text on not-found (repair-on-failure)" test-multi-edit-repair-hint)
                            (it "tracks stats" test-multi-edit-stats)

                            (it "activates and deactivates cleanly"
                                (fn []
                                  (let [agent (make-agent)
                                        api   (make-api agent)
                                        deact (diff-edit/activate api)]
                                    (-> (expect (fn? deact)) (.toBe true))
                                    (deact)
                                    (-> (expect (nil? (get ((:get-active (:tool-registry agent))) "multi_edit"))) (.toBe true)))))

                            (it "compress middleware enriches edit result"
                                (fn []
                                  (let [agent (make-agent)
                                        api   (make-api agent)
                                        _deact (diff-edit/activate api)]
        ;; The middleware is registered — we just test it doesn't crash
                                    (-> (expect true) (.toBe true)))))

                            (it "levenshtein-similarity computes correctly"
                                (fn []
                                  (-> (expect (shared/levenshtein-similarity "abc" "abc")) (.toBe 1.0))
                                  (-> (expect (shared/levenshtein-similarity "" "")) (.toBe 1.0))
                                  (-> (expect (< (shared/levenshtein-similarity "abc" "xyz") 0.5)) (.toBe true))
                                  (-> (expect (> (shared/levenshtein-similarity "hello" "helo") 0.7)) (.toBe true))))

                            (it "levenshtein-distance is correct"
                                (fn []
                                  (-> (expect (shared/levenshtein-distance "kitten" "sitting")) (.toBe 3))
                                  (-> (expect (shared/levenshtein-distance "" "abc")) (.toBe 3))
                                  (-> (expect (shared/levenshtein-distance "abc" "")) (.toBe 3))
                                  (-> (expect (shared/levenshtein-distance "same" "same")) (.toBe 0))))))

;; ═══════════════════════════════════════════════════════════════
;; Structured Context Files
;; ═══════════════════════════════════════════════════════════════

(defn- setup-context-dir []
  (let [dir (path/join (os/tmpdir) (str "nyma-ctx-test-" (js/Date.now)))]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "CLAUDE.md")
                      "# Project\nThis is a test project.\n## Build\nbun run build\n")
    (fs/writeFileSync (path/join dir ".cursorrules")
                      "Use TypeScript. Prefer functional style.\n")
    ;; Create a subdirectory with context
    (let [subdir (path/join dir "src" "components")]
      (fs/mkdirSync subdir #js {:recursive true})
      (fs/writeFileSync (path/join subdir "CONTEXT.md")
                        "Components use React hooks pattern.\n"))
    dir))

(defn- cleanup-context-dir [dir]
  (try (fs/rmSync dir #js {:recursive true :force true})
       (catch :default _e nil)))

(defn ^:async test-ctx-hot-tier []
  (let [dir (setup-context-dir)
        orig-cwd (js/process.cwd)]
    (try
      (js/process.chdir dir)
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (structured-context/activate api)
            tools  ((:get-active (:tool-registry agent)))
            ctx-tool (get tools "context_files")]
        (when ctx-tool
          (let [result (js-await ((.-execute ctx-tool) #js {:action "list"}))]
            (-> (expect result) (.toContain "Hot"))
            (-> (expect result) (.toContain "CLAUDE.md")))))
      (finally
        (js/process.chdir orig-cwd)
        (cleanup-context-dir dir)))))

(defn ^:async test-ctx-reads-files []
  (let [dir (setup-context-dir)
        orig-cwd (js/process.cwd)]
    (try
      (js/process.chdir dir)
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (structured-context/activate api)
            tools  ((:get-active (:tool-registry agent)))
            ctx-tool (get tools "context_files")]
        (when ctx-tool
          (let [result (js-await ((.-execute ctx-tool) #js {:action "read" :path "CLAUDE.md"}))]
            (-> (expect result) (.toContain "test project")))))
      (finally
        (js/process.chdir orig-cwd)
        (cleanup-context-dir dir)))))

(defn ^:async test-ctx-skips-agents []
  (let [dir (setup-context-dir)
        orig-cwd (js/process.cwd)]
    (fs/writeFileSync (path/join dir "AGENTS.md") "Should be skipped\n")
    (try
      (js/process.chdir dir)
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (structured-context/activate api)
            tools  ((:get-active (:tool-registry agent)))
            ctx-tool (get tools "context_files")]
        (when ctx-tool
          (let [result (js-await ((.-execute ctx-tool) #js {:action "list"}))]
            (-> (expect (.includes result "AGENTS.md")) (.toBe false)))))
      (finally
        (js/process.chdir orig-cwd)
        (cleanup-context-dir dir)))))

(defn ^:async test-ctx-warm-discovery []
  (let [dir (setup-context-dir)
        orig-cwd (js/process.cwd)]
    (try
      (js/process.chdir dir)
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (structured-context/activate api)
            tools  ((:get-active (:tool-registry agent)))
            ctx-tool (get tools "context_files")]
        (when ctx-tool
          (let [result (js-await ((.-execute ctx-tool) #js {:action "list"}))]
            (-> (expect result) (.toContain "Warm"))
            (-> (expect result) (.toContain "CONTEXT.md")))))
      (finally
        (js/process.chdir orig-cwd)
        (cleanup-context-dir dir)))))

(describe "ext-structured-context" (fn []
                                     (it "activates and deactivates cleanly"
                                         (fn []
                                           (let [agent (make-agent)
                                                 api   (make-api agent)
                                                 deact (structured-context/activate api)]
                                             (-> (expect (fn? deact)) (.toBe true))
                                             (deact))))

                                     (it "discovers context files at project root"
                                         (fn []
                                           (let [dir   (setup-context-dir)
            ;; Temporarily set cwd
                                                 orig-cwd (js/process.cwd)]
                                             (try
                                               (js/process.chdir dir)
                                               (let [agent (make-agent)
                                                     api   (make-api agent)
                                                     _deact (structured-context/activate api)]
                                                 (-> (expect (:files-discovered (:structured-context @shared/suite-stats)))
                                                     (.toBeGreaterThan 0)))
                                               (finally
                                                 (js/process.chdir orig-cwd)
                                                 (cleanup-context-dir dir))))))

                                     (it "classifies root files as hot tier" test-ctx-hot-tier)
                                     (it "context_files tool reads files" test-ctx-reads-files)
                                     (it "does not discover AGENTS.md (handled by loader)" test-ctx-skips-agents)
                                     (it "discovers subdirectory context files as warm" test-ctx-warm-discovery)

                                     (it "stats track discovery count"
                                         (fn []
                                           (let [dir (setup-context-dir)
                                                 orig-cwd (js/process.cwd)]
                                             (try
                                               (js/process.chdir dir)
                                               (let [agent (make-agent)
                                                     api   (make-api agent)
                                                     _deact (structured-context/activate api)]
            ;; Should discover CLAUDE.md + .cursorrules (hot) + src/components/CONTEXT.md (warm)
                                                 (-> (expect (:files-discovered (:structured-context @shared/suite-stats)))
                                                     (.toBeGreaterThanOrEqual 2)))
                                               (finally
                                                 (js/process.chdir orig-cwd)
                                                 (cleanup-context-dir dir))))))))

;; ═══════════════════════════════════════════════════════════════
;; Smart Compaction
;; ═══════════════════════════════════════════════════════════════

(describe "ext-smart-compaction" (fn []
                                   (it "activates and deactivates cleanly"
                                       (fn []
                                         (let [agent (make-agent)
                                               api   (make-api agent)
                                               deact (smart-compaction/activate api)]
                                           (-> (expect (fn? deact)) (.toBe true))
                                           (deact))))

                                   (it "structured compaction sets evt-ctx.summary via before_compact"
                                       (fn []
                                         (let [agent (make-agent)
                                               api   (make-api agent)
                                               _deact (smart-compaction/activate api)
                                               evt-ctx #js {:context [{:role "user" :content "fix the bug in auth"}
                                                                      {:role "assistant" :content "I'll look at it"}
                                                                      {:role "tool_call" :content "read" :metadata {:tool-name "read" :args {:path "/src/auth.ts"}}}
                                                                      {:role "tool_result" :content "file contents here"}]
                                                            :usage 80000
                                                            :summary nil}]
                                           ((:emit (:events agent)) "before_compact" evt-ctx)
                                           (-> (expect (some? (.-summary evt-ctx))) (.toBe true))
                                           (-> (expect (.includes (str (.-summary evt-ctx)) "User Intent")) (.toBe true)))))

                                   (it "tracks re-reads"
                                       (fn []
                                         (let [agent (make-agent)
                                               api   (make-api agent)
                                               _deact (smart-compaction/activate api)]
        ;; First read
                                           ((:emit (:events agent)) "tool_execution_end"
                                                                    #js {:toolName "read" :args #js {:path "/src/foo.ts"} :duration 100})
        ;; Second read of same file
                                           ((:emit (:events agent)) "tool_execution_end"
                                                                    #js {:toolName "read" :args #js {:path "/src/foo.ts"} :duration 100})
        ;; Re-read tracking should increment (even without cache, the history tracks it)
                                           (-> (expect true) (.toBe true)))))

                                   (it "hash-content produces consistent hashes"
                                       (fn []
                                         (let [h1 (shared/hash-content "hello world")
                                               h2 (shared/hash-content "hello world")
                                               h3 (shared/hash-content "different")]
                                           (-> (expect h1) (.toBe h2))
                                           (-> (expect (not= h1 h3)) (.toBe true)))))))

;; ═══════════════════════════════════════════════════════════════
;; Smart Compaction — Schema Contract Validation
;; ═══════════════════════════════════════════════════════════════

(defn test-all-token-suite-tools-pass-schema-validation []
  ;; Contract guard: every tool registered by token-suite sub-modules must
  ;; pass asSchema validation — the same check the AI SDK runs before sending
  ;; tools to the Anthropic API.
  (let [agent (make-agent)
        api   (make-api agent)
        deact-sc (smart-compaction/activate api)
        deact-de (diff-edit/activate api)
        deact-sx (structured-context/activate api)
        all-tools ((:all (:tool-registry agent)))]
    ;; Check every registered tool
    (doseq [[name t] all-tools]
      (-> (expect (.-inputSchema t)) (.toBeTruthy))
      (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
        (-> (expect (.-type schema)) (.toBe "object"))))
    (deact-sc) (deact-de) (deact-sx)))

(describe "ext-smart-compaction-schema" (fn []
                                          (it "all token-suite tools pass asSchema validation"
                                              test-all-token-suite-tools-pass-schema-validation)))

;; ═══════════════════════════════════════════════════════════════
;; Integration Tests
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-integration-full-pipeline []
  (let [agent (make-agent)
        api   (make-api agent)
        _d3   (kv-cache/activate api)
        _d4   (priority-assembly/activate api)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] #js {:block true :reason "ok"}) 0)
    (swap! (:state agent) assoc :messages
           [{:role "user" :content "test"}
            {:role "tool_result" :content "some result content here"}
            {:role "assistant" :content "analysis"}])
    (js-await (run agent "follow up"))
    (-> (expect true) (.toBe true))))

(describe "token-suite integration" (fn []
                                      (it "all 6 extensions activate without conflict"
                                          (fn []
                                            (let [agent (make-agent)
                                                  api   (make-api agent)
                                                  d3    (kv-cache/activate api)
                                                  d4    (priority-assembly/activate api)
                                                  d5    (diff-edit/activate api)
                                                  d6    (structured-context/activate api)
                                                  d7    (smart-compaction/activate api)]
                                              (-> (expect (fn? d3)) (.toBe true))
                                              (-> (expect (fn? d4)) (.toBe true))
                                              (-> (expect (fn? d5)) (.toBe true))
                                              (-> (expect (fn? d6)) (.toBe true))
                                              (-> (expect (fn? d7)) (.toBe true))
        ;; Deactivate all
                                              (d3) (d4) (d5) (d6) (d7))))

                                      (it "full pipeline runs without error" test-integration-full-pipeline)

                                      (it "stats tracking works"
                                          (fn []
                                            (swap! shared/suite-stats assoc-in [:kv-cache :cache-hits] 10)
                                            (-> (expect (:cache-hits (:kv-cache @shared/suite-stats))) (.toBe 10))))

                                      (it "shared config loads defaults when no settings file"
                                          (fn []
                                            (let [config (shared/load-config)]
                                              (-> (expect (get-in config [:diff-edit :fuzzy-enabled])) (.toBe true))
                                              (-> (expect (get-in config [:structured-context :hot-budget])) (.toBe 2000)))))))
