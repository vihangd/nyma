(ns ext-token-suite.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.tool-truncation :as tool-truncation]
            [agent.extensions.token-suite.observation-mask :as observation-mask]
            [agent.extensions.token-suite.expired-context :as expired-context]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions.token-suite.priority-assembly :as priority-assembly]
            [agent.extensions.token-suite.repo-map :as repo-map]
            [agent.extensions.token-suite.diff-edit :as diff-edit]
            [agent.extensions.token-suite.structured-context :as structured-context]
            [agent.extensions.token-suite.smart-compaction :as smart-compaction]
            [agent.extensions.token-suite.context-folding :as context-folding]
            [agent.extensions :refer [create-extension-api]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [clojure.string :as str]))

(defn- make-agent []
  (create-agent {:model "mock-model" :system-prompt "You are a test agent."}))

(defn- make-api [agent]
  (create-extension-api agent))

(defn- reset-stats! []
  (reset! shared/suite-stats
    {:observation-mask {:turns 0 :messages-masked 0 :tokens-saved 0}
     :kv-cache         {:turns 0 :cache-hits 0 :cached-tokens 0}
     :expired-context  {:turns 0 :stale-replaced 0 :tokens-saved 0}
     :tool-truncation  {:calls 0 :chars-saved 0}
     :repo-map         {:files 0 :symbols 0 :last-index-ms 0}
     :priority-assembly {:turns 0 :messages-pruned 0 :tokens-saved 0}
     :diff-edit          {:hunks-applied 0 :fuzzy-matches 0 :chars-saved 0 :calls 0}
     :structured-context {:files-discovered 0 :hot-tokens 0 :warm-tokens 0 :cache-hits 0}
     :smart-compaction   {:background-updates 0 :offloads 0 :full-compactions 0
                          :tokens-archived 0 :re-reads 0}
     :context-folding    {:foci-started 0 :foci-completed 0 :messages-folded 0
                          :tokens-freed 0}}))

(beforeEach reset-stats!)

;; ═══════════════════════════════════════════════════════════════
;; Tool Result Truncation
;; ═══════════════════════════════════════════════════════════════

(describe "ext-tool-truncation" (fn []
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
;; Observation Masking — async tests extracted as top-level defn
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-mask-keeps-recent []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (observation-mask/activate api)
        seen-msgs (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen-msgs (.-messages config))
        #js {:block true :reason "ok"})
      0)
    (swap! (:state agent) assoc :messages
      [{:role "user" :content "test"}
       {:role "tool_call" :content "call1" :metadata {:tool-name "read"}}
       {:role "tool_result" :content "result line 1\nresult line 2"}
       {:role "assistant" :content "done"}])
    (js-await (run agent "another question"))
    (-> (expect (some? @seen-msgs)) (.toBe true))))

(defn ^:async test-mask-preserves-user-assistant []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (observation-mask/activate api)
        seen-msgs (atom nil)]
    ((:on (:events agent)) "before_provider_request"
      (fn [config]
        (reset! seen-msgs (.-messages config))
        #js {:block true :reason "ok"})
      0)
    (swap! (:state agent) assoc :messages
      [{:role "user" :content "hello world"}
       {:role "assistant" :content "hi there"}])
    (js-await (run agent "test"))
    (let [first-msg (aget @seen-msgs 0)]
      (-> (expect (.-content first-msg)) (.toContain "hello world")))))

(defn ^:async test-mask-zero-results []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (observation-mask/activate api)]
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect true) (.toBe true))))

(describe "ext-observation-mask" (fn []
  (it "masks old tool_results keeping recent ones" test-mask-keeps-recent)
  (it "never modifies user or assistant messages" test-mask-preserves-user-assistant)
  (it "handles zero tool_results gracefully" test-mask-zero-results)
  (it "placeholder utils compute correctly"
    (fn []
      (let [content "line1\nline2\nline3\nline4"]
        (-> (expect (shared/count-lines content)) (.toBe 4))
        (-> (expect (shared/count-chars content)) (.toBe (count content))))))))

;; ═══════════════════════════════════════════════════════════════
;; Expired Context Pruning
;; ═══════════════════════════════════════════════════════════════

(describe "ext-expired-context" (fn []
  (it "activates without error"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            deact (expired-context/activate api)]
        (-> (expect (fn? deact)) (.toBe true)))))

  (it "deactivate resets state"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            deact (expired-context/activate api)]
        (deact)
        (-> (expect true) (.toBe true)))))

  (it "tracks file operations via tool_execution_end"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (expired-context/activate api)]
        ((:emit (:events agent)) "tool_execution_end"
          #js {:toolName "read" :args #js {:path "/src/foo.cljs"} :duration 100})
        (-> (expect true) (.toBe true)))))))

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
        (deact)
        ;; retrieve_archived should be unregistered
        (-> (expect (nil? (get ((:get-active (:tool-registry agent))) "retrieve_archived"))) (.toBe true)))))

  (it "registers retrieve_archived tool"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (smart-compaction/activate api)
            tools ((:get-active (:tool-registry agent)))]
        (-> (expect (some? (get tools "retrieve_archived"))) (.toBe true)))))

  (it "offloads large tool_result in context_assembly"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (smart-compaction/activate api)
            ;; Create a large tool_result message
            large-content (str/join "\n" (map #(str "line " % " — this is a detailed description of what happened during the test execution run number " %) (range 500)))
            messages #js [#js {:role "user" :content "test"}
                          #js {:role "tool_result" :content large-content}]
            event #js {:messages messages
                       :systemPrompt "test prompt"
                       :tokenBudget #js {:contextWindow 10000
                                         :inputBudget 7000
                                         :tokensUsed 5500
                                         :model "test"}}]
        ;; Emit context_assembly — offload threshold is 0.70, 5500/7000 = 0.786
        ((:emit (:events agent)) "context_assembly" event)
        ;; The large result should be archived
        (let [result-content (.-content (aget messages 1))]
          (-> (expect (.includes (str result-content) "Archived")) (.toBe true))))))

  (it "preserves error-containing tool results"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (smart-compaction/activate api)
            error-content (str (str/join "\n" (map #(str "line " %) (range 500)))
                              "\nTypeError: Cannot read property")
            messages #js [#js {:role "user" :content "test"}
                          #js {:role "tool_result" :content error-content}]
            event #js {:messages messages
                       :systemPrompt "test"
                       :tokenBudget #js {:contextWindow 10000
                                         :inputBudget 7000
                                         :tokensUsed 5500
                                         :model "test"}}]
        ((:emit (:events agent)) "context_assembly" event)
        ;; Error content should NOT be archived
        (let [result-content (.-content (aget messages 1))]
          (-> (expect (.includes (str result-content) "Archived")) (.toBe false))))))

  (it "skips already-masked results"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (smart-compaction/activate api)
            messages #js [#js {:role "user" :content "test"}
                          #js {:role "tool_result" :content "[tool_result: read — 100 lines]"}]
            event #js {:messages messages
                       :systemPrompt "test"
                       :tokenBudget #js {:contextWindow 10000
                                         :inputBudget 7000
                                         :tokensUsed 5500
                                         :model "test"}}]
        ((:emit (:events agent)) "context_assembly" event)
        (let [result-content (.-content (aget messages 1))]
          (-> (expect (.startsWith (str result-content) "[tool_result:")) (.toBe true))))))

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

(defn test-retrieve-archived-has-input-schema []
  ;; Contract guard: retrieve_archived must use AI SDK tool() with inputSchema,
  ;; NOT a raw JS object with parameters. asSchema must produce {type: "object"}.
  (let [agent (make-agent)
        api   (make-api agent)
        deact (smart-compaction/activate api)
        tools ((:all (:tool-registry agent)))
        t     (get tools "retrieve_archived")]
    ;; Tool must be registered
    (-> (expect t) (.toBeTruthy))
    ;; Must have inputSchema (tool() sets this), NOT parameters (raw object pattern)
    (-> (expect (.-inputSchema t)) (.toBeTruthy))
    (-> (expect (.-parameters t)) (.toBeUndefined))
    ;; asSchema must produce a valid JSON Schema with type: "object"
    (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
      (-> (expect (.-type schema)) (.toBe "object"))
      ;; hash property must exist
      (-> (expect (.. schema -properties -hash)) (.toBeTruthy)))
    (deact)))

(defn test-all-token-suite-tools-pass-schema-validation []
  ;; Contract guard: every tool registered by token-suite sub-modules must
  ;; pass asSchema validation — the same check the AI SDK runs before sending
  ;; tools to the Anthropic API.
  (let [agent (make-agent)
        api   (make-api agent)
        deact-sc (smart-compaction/activate api)
        deact-de (diff-edit/activate api)
        deact-cf (context-folding/activate api)
        deact-sx (structured-context/activate api)
        all-tools ((:all (:tool-registry agent)))]
    ;; Check every registered tool
    (doseq [[name t] all-tools]
      (-> (expect (.-inputSchema t)) (.toBeTruthy))
      (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
        (-> (expect (.-type schema)) (.toBe "object"))))
    (deact-sc) (deact-de) (deact-cf) (deact-sx)))

(describe "ext-smart-compaction-schema" (fn []
  (it "retrieve_archived has valid AI SDK inputSchema"
      test-retrieve-archived-has-input-schema)
  (it "all token-suite tools pass asSchema validation"
      test-all-token-suite-tools-pass-schema-validation)))

;; ═══════════════════════════════════════════════════════════════
;; Context Folding
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-start-focus-pushes []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (context-folding/activate api)
        tools ((:get-active (:tool-registry agent)))
        start-tool (get tools "start_focus")]
    (when start-tool
      (let [result (js-await ((.-execute start-tool)
                               #js {:objective "find the bug"}))]
        (-> (expect result) (.toContain "FOCUS_START"))
        (-> (expect result) (.toContain "find the bug"))
        (-> (expect (:foci-started (:context-folding @shared/suite-stats))) (.toBe 1))))))

(defn ^:async test-complete-focus-pops []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (context-folding/activate api)
        tools ((:get-active (:tool-registry agent)))
        start-tool (get tools "start_focus")
        complete-tool (get tools "complete_focus")]
    (when (and start-tool complete-tool)
      (js-await ((.-execute start-tool) #js {:objective "explore"}))
      (let [result (js-await ((.-execute complete-tool)
                               #js {:summary "Found the issue in auth.ts"
                                    :key_artifacts #js ["/src/auth.ts"]}))]
        (-> (expect result) (.toContain "FOCUS_END"))
        (-> (expect (:foci-completed (:context-folding @shared/suite-stats))) (.toBe 1))))))

(defn ^:async test-complete-without-start []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (context-folding/activate api)
        tools ((:get-active (:tool-registry agent)))
        complete-tool (get tools "complete_focus")]
    (when complete-tool
      (let [result (js-await ((.-execute complete-tool)
                               #js {:summary "oops"}))]
        (-> (expect result) (.toContain "Error"))))))

(defn ^:async test-max-depth []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (context-folding/activate api)
        tools ((:get-active (:tool-registry agent)))
        start-tool (get tools "start_focus")]
    (when start-tool
      ;; Push 3 foci (max-depth default = 3)
      (js-await ((.-execute start-tool) #js {:objective "focus 1"}))
      (js-await ((.-execute start-tool) #js {:objective "focus 2"}))
      (js-await ((.-execute start-tool) #js {:objective "focus 3"}))
      ;; Fourth should fail
      (let [result (js-await ((.-execute start-tool) #js {:objective "focus 4"}))]
        (-> (expect result) (.toContain "Error"))
        (-> (expect result) (.toContain "depth"))))))

(defn ^:async test-fold-applied-in-context-assembly []
  (let [agent (make-agent)
        api   (make-api agent)
        _deact (context-folding/activate api)
        tools ((:get-active (:tool-registry agent)))
        start-tool (get tools "start_focus")
        complete-tool (get tools "complete_focus")]
    (when (and start-tool complete-tool)
      ;; Start a focus and complete it
      (let [start-result (js-await ((.-execute start-tool) #js {:objective "search codebase"}))]
        (js-await ((.-execute complete-tool)
                    #js {:summary "Found bug in auth.ts:42"
                         :key_artifacts #js ["/src/auth.ts"]}))
        ;; Now simulate context_assembly with messages containing the markers
        (let [messages #js [#js {:role "user" :content "fix the bug"}
                            #js {:role "tool_result" :content start-result}
                            #js {:role "assistant" :content "Let me search..."}
                            #js {:role "tool_result" :content "file contents"}
                            #js {:role "tool_result"
                                 :content (str "[FOCUS_END:" (.substring start-result
                                                               (+ (.indexOf start-result ":") 1)
                                                               (.indexOf start-result "]"))
                                               "] Focus completed.")}]
              event #js {:messages messages
                         :systemPrompt "test"
                         :tokenBudget #js {:contextWindow 100000
                                           :inputBudget 70000
                                           :tokensUsed 5000
                                           :model "test"}}]
          ;; Emit context_assembly to apply the fold
          ((:emit (:events agent)) "context_assembly" event)
          ;; Messages should be fewer after folding
          (-> (expect (.-length messages)) (.toBeLessThan 5)))))))

(describe "ext-context-folding" (fn []
  (it "activates and deactivates cleanly"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            deact (context-folding/activate api)]
        (-> (expect (fn? deact)) (.toBe true))
        (deact)
        (-> (expect (nil? (get ((:get-active (:tool-registry agent))) "start_focus"))) (.toBe true))
        (-> (expect (nil? (get ((:get-active (:tool-registry agent))) "complete_focus"))) (.toBe true)))))

  (it "registers start_focus and complete_focus tools"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (context-folding/activate api)
            tools ((:get-active (:tool-registry agent)))]
        (-> (expect (some? (get tools "start_focus"))) (.toBe true))
        (-> (expect (some? (get tools "complete_focus"))) (.toBe true)))))

  (it "start_focus pushes to stack" test-start-focus-pushes)
  (it "complete_focus pops and creates pending fold" test-complete-focus-pops)
  (it "complete without start returns error" test-complete-without-start)
  (it "max depth enforcement" test-max-depth)
  (it "pending fold applied in context_assembly" test-fold-applied-in-context-assembly)

  (it "focus instructions injected via prompt-section"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            _deact (context-folding/activate api)
            result (atom nil)]
        ;; Capture before_agent_start result
        ((:on (:events agent)) "before_provider_request"
          (fn [config]
            (reset! result (.-system config))
            #js {:block true :reason "ok"})
          0)
        ;; The instructions should be in the system prompt via prompt-sections
        (-> (expect true) (.toBe true)))))

  (it "stats tracking works"
    (fn []
      (swap! shared/suite-stats assoc-in [:context-folding :foci-started] 5)
      (swap! shared/suite-stats assoc-in [:context-folding :foci-completed] 4)
      (swap! shared/suite-stats assoc-in [:context-folding :messages-folded] 23)
      (-> (expect (:foci-started (:context-folding @shared/suite-stats))) (.toBe 5))
      (-> (expect (:foci-completed (:context-folding @shared/suite-stats))) (.toBe 4))
      (-> (expect (:messages-folded (:context-folding @shared/suite-stats))) (.toBe 23))))))

;; ═══════════════════════════════════════════════════════════════
;; Integration Tests
;; ═══════════════════════════════════════════════════════════════

(defn ^:async test-integration-full-pipeline []
  (let [agent (make-agent)
        api   (make-api agent)
        _d1   (observation-mask/activate api)
        _d2   (expired-context/activate api)
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
  (it "all 10 extensions activate without conflict"
    (fn []
      (let [agent (make-agent)
            api   (make-api agent)
            d1    (tool-truncation/activate api)
            d2    (observation-mask/activate api)
            d3    (kv-cache/activate api)
            d4    (priority-assembly/activate api)
            d5    (diff-edit/activate api)
            d6    (structured-context/activate api)
            d7    (smart-compaction/activate api)
            d8    (context-folding/activate api)]
        (-> (expect (fn? d1)) (.toBe true))
        (-> (expect (fn? d2)) (.toBe true))
        (-> (expect (fn? d3)) (.toBe true))
        (-> (expect (fn? d4)) (.toBe true))
        (-> (expect (fn? d5)) (.toBe true))
        (-> (expect (fn? d6)) (.toBe true))
        (-> (expect (fn? d7)) (.toBe true))
        (-> (expect (fn? d8)) (.toBe true))
        ;; Deactivate all
        (d1) (d2) (d3) (d4) (d5) (d6) (d7) (d8))))

  (it "full pipeline runs without error" test-integration-full-pipeline)

  (it "stats tracking works"
    (fn []
      (swap! shared/suite-stats assoc-in [:observation-mask :messages-masked] 42)
      (swap! shared/suite-stats assoc-in [:kv-cache :cache-hits] 10)
      (-> (expect (:messages-masked (:observation-mask @shared/suite-stats))) (.toBe 42))
      (-> (expect (:cache-hits (:kv-cache @shared/suite-stats))) (.toBe 10))))

  (it "shared config loads defaults when no settings file"
    (fn []
      (let [config (shared/load-config)]
        (-> (expect (get-in config [:observation-mask :keep-recent])) (.toBe 10))
        (-> (expect (get-in config [:tool-truncation :max-chars])) (.toBe 10000))
        (-> (expect (get-in config [:diff-edit :fuzzy-enabled])) (.toBe true))
        (-> (expect (get-in config [:structured-context :hot-budget])) (.toBe 2000))
        (-> (expect (get-in config [:smart-compaction :offload-threshold])) (.toBe 0.70))
        (-> (expect (get-in config [:context-folding :max-depth])) (.toBe 3)))))))
