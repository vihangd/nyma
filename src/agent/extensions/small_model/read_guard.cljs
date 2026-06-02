(ns agent.extensions.small-model.read-guard
  "Read-guard — trim oversized file reads at the source.

   Small models have limited context windows.  Rather than waiting for
   token_suite to truncate a tool result post-hoc (which costs tokens
   that are already in-flight), read-guard overrides the built-in `read`
   tool: when the file has more lines than the limit, return only the
   head plus a directive to use grep/glob to find the relevant section.

   This steers the model toward targeted reads rather than dumping the
   full file, which is more token-efficient and avoids the common
   small-model failure mode of losing the task context in a sea of code.

   Override pattern from: src/agent/extensions/mcp_client/tool_override.cljs
   (stub first → capture __original → real wrapper via second overrideTool)
  ")

;; ── Helpers ──────────────────────────────────────────────────────

(defn- count-lines [s]
  (.-length (.split (str s) "\n")))

(defn- truncate-with-hint [text max-lines]
  (let [lines     (.split (str text) "\n")
        total     (.-length lines)
        head-text (.join (.slice lines 0 max-lines) "\n")]
    (str head-text
         "\n\n[read-guard: file has " total " lines; showing first "
         max-lines ". Use grep or glob to locate the relevant section, "
         "then re-read with a specific line range, e.g. {range: [N, M]}.]")))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Override the `read` tool with a size-guarded version.
   Returns a cleanup fn, or a no-op if overrideTool is unavailable."
  [api config]
  (let [rg-cfg    (:read-guard config)
        max-lines (or (:max-lines rg-cfg) 60)]

    (when-not (.-overrideTool api)
      (js/console.warn "[small-model/read-guard] overrideTool not available — ensure 'tools-override' capability is declared."))

    (if-not (.-overrideTool api)
      (fn [])  ; no-op cleanup
      ;; Step 1: register a stub to capture __original
      (let [stub #js {:description ""
                      :inputSchema #js {:type "object" :properties #js {}}
                      :execute     (fn [_] nil)}
            _ (.overrideTool api "read" stub)
            original (.-__original stub)]

        (if-not original
          ;; No native `read` found — restore and bail
          (do (try (.unoverrideTool api "read") (catch :default _ nil))
              (fn []))
          ;; Step 2: real override that chains to original
          (let [override #js
                          {:description
                           (str (or (.-description original) "Read a file.")
                                " (Files over " max-lines " lines are trimmed to "
                                "the first " max-lines " lines with a search hint.)")
                           :inputSchema (or (.-inputSchema original)
                                            (.-parameters original)
                                            #js {:type "object" :properties #js {}})
                           :execute
                           (^:async fn [args]
                             (let [result (js-await ((.-execute original) args))
                                   text   (str result)
                         ;; Only guard full-file reads (no explicit range/offset)
                                   no-range (and (not (.-range args))
                                                 (not (.-offset args))
                                                 (not (.-limit args)))]
                               (if (and no-range (> (count-lines text) max-lines))
                                 (truncate-with-hint text max-lines)
                                 result)))}]
            (.overrideTool api "read" override)

            ;; Cleanup
            (fn []
              (when (.-unoverrideTool api)
                (.unoverrideTool api "read")))))))))
