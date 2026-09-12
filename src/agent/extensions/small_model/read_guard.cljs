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

   Also enforces **read-before-edit**.  A small model will happily `edit` a
   file it has never read, guessing at `old_string` — the edit then fails on a
   no-match, or worse matches somewhere unintended.  little-coder carries two
   extensions for this (write-guard, read-guard-edit) for the same reason.
   Since this module already wraps `read`, it is the one place that knows what
   has been read, so the check lives here rather than in a new extension."
  (:require [agent.debug :as d]
            ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── Helpers ──────────────────────────────────────────────────────

;; Paths this session has read, absolute so "./x" and "x" are the same file.
(defn normalize-path [p]
  (when (and p (string? p) (seq p))
    (path/resolve (str p))))

(defn edit-refusal
  "The message an unread edit gets back, or nil when the edit may proceed.

   Pure, so the rule is testable without a tool registry."
  [read-paths p]
  (when-let [abs (normalize-path p)]
    (when-not (contains? read-paths abs)
      (str "[read-guard] Refusing to edit " p " because it has not been read "
           "in this session. Call read on it first — old_string has to match "
           "the file exactly, and guessing it is how edits silently hit the "
           "wrong line."))))

(defn write-refusal
  "Refuse `write` onto an existing file that has not been read: that is a
   silent whole-file overwrite of content the model never looked at. Creating
   a new file is always fine."
  [read-paths p exists?]
  (when-let [abs (normalize-path p)]
    (when (and exists? (not (contains? read-paths abs)))
      (str "[read-guard] Refusing to write over the existing file " p
           " because it has not been read in this session. Read it first, "
           "then use edit for a targeted change."))))

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
  (let [rg-cfg      (:read-guard config)
        max-lines   (or (:max-lines rg-cfg) 60)
        ;; Session-scoped; a fresh activation starts with nothing read.
        read-paths  (atom #{})
        ;; On by default: the failure it prevents is silent.
        guard-edit? (not (false? (:require-read-before-edit rg-cfg)))]

    (when-not (.-overrideTool api)
      (d/warn "[small-model/read-guard] overrideTool not available — ensure 'tools-override' capability is declared."))

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
                                   ;; Recorded whatever the size, and only
                                   ;; after the read actually returned — a
                                   ;; failed read must not unlock an edit.
                                   _      (when-let [abs (normalize-path (.-path args))]
                                            (swap! read-paths conj abs))
                         ;; Only guard full-file reads (no explicit range/offset)
                                   no-range (and (not (.-range args))
                                                 (not (.-offset args))
                                                 (not (.-limit args)))]
                               (if (and no-range (> (count-lines text) max-lines))
                                 (truncate-with-hint text max-lines)
                                 result)))}]
            (.overrideTool api "read" override)

            ;; ── read-before-edit ────────────────────────────────
            (when guard-edit?
              (doseq [[tool-name refuse] [["edit"  (fn [args]
                                                     (edit-refusal @read-paths (.-path args)))]
                                          ["write" (fn [args]
                                                     (write-refusal
                                                      @read-paths (.-path args)
                                                      (try (fs/existsSync (str (.-path args)))
                                                           (catch :default _ false))))]]]
                (let [stub2 #js {:description ""
                                 :inputSchema #js {:type "object" :properties #js {}}
                                 :execute     (fn [_] nil)}
                      _     (.overrideTool api tool-name stub2)
                      orig2 (.-__original stub2)]
                  (if-not orig2
                    (try (.unoverrideTool api tool-name) (catch :default _ nil))
                    (.overrideTool api tool-name
                                   #js {:description (.-description orig2)
                                        :inputSchema (or (.-inputSchema orig2)
                                                         (.-parameters orig2)
                                                         #js {:type "object" :properties #js {}})
                                        :execute
                                        (^:async fn [args]
                                          ;; Returned as a string, not thrown:
                                          ;; the model has to read it and act,
                                          ;; and a throw becomes a tool error
                                          ;; it cannot recover from as cleanly.
                                          (or (refuse args)
                                              (js-await ((.-execute orig2) args))))})))))

            ;; Cleanup
            (fn []
              (when (.-unoverrideTool api)
                (.unoverrideTool api "read")
                (when guard-edit?
                  (doseq [t ["edit" "write"]]
                    (try (.unoverrideTool api t) (catch :default _ nil))))))))))))
