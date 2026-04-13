(ns agent.ui.editor-bash
  "Editor bash mode — executes `!cmd` and `!!cmd` typed directly into
   the prompt editor.

   Routes through the bash_suite middleware chain by emitting a
   `before_tool_call` event (exactly what agent.loop does for LLM-
   initiated bash calls), so security analysis, env filtering, cwd
   tracking, and the stats bus all participate transparently. After
   the chain runs, we invoke `agent.tools/bash-execute` on the
   (possibly rewritten) command and return a parsed result.

   Prefix semantics, mirroring oh-my-pi:
     !cmd   → run, show, include in LLM context for future turns
     !!cmd  → run, show, HIDE from LLM context (:local-only message)

   Separated from app.cljs so the pure pieces (parse, format) are
   unit-testable without mounting the ink app."
  (:require [agent.tools :as tools]
            [agent.ui.editor-exec-util :as exec]
            [clojure.string :as str]))

;;; ─── Pure: parsing the editor text ──────────────────────

(defn parse-bash-input
  "Classify `text` into one of:
     {:kind :run         :command \"git status\"}
     {:kind :run-hidden  :command \"git status\"}
     {:kind :not-bash}

   Leading whitespace is stripped first so `  !ls` still fires (same
   contract as detect-mode). `!!` must be checked before `!` — order
   matters. An empty command after the sigil (e.g. `!` alone, `!! `)
   is :not-bash so the user isn't punished for an accidental
   keystroke."
  [text]
  (let [s (when text (.trimStart (str text)))]
    (cond
      (or (nil? s) (= s "")) {:kind :not-bash}

      (.startsWith s "!!")
      (let [cmd (str/trim (.slice s 2))]
        (if (seq cmd)
          {:kind :run-hidden :command cmd}
          {:kind :not-bash}))

      (.startsWith s "!")
      (let [cmd (str/trim (.slice s 1))]
        (if (seq cmd)
          {:kind :run :command cmd}
          {:kind :not-bash}))

      :else {:kind :not-bash})))

;;; ─── Impure: run through the middleware chain ───────────

(defn ^:async run-bash!
  "Execute a bash command through the bash_suite middleware chain,
   then invoke the underlying executor.

   Return shape:
     {:blocked?  false
      :command   final-command-after-env-filter-rewrite
      :stdout    \"...\"
      :stderr    \"...\"
      :exit-code 0}
   — or —
     {:blocked?  true
      :reason    \"BLOCKED [destructive]: ...\"
      :command   original-command
      :stdout    \"\" :stderr \"\" :exit-code -1}

   Never throws: any internal failure is surfaced in :stderr so the
   caller can render it like any other bash error."
  [agent command]
  (let [events (:events agent)]
    (if-not events
      ;; No event bus (e.g. unit tests with a minimal fake agent) —
      ;; skip the chain and execute directly. This keeps tests simple
      ;; without forcing every call site to wire up a full agent.
      (let [raw    (js-await (tools/bash-execute #js {:command command}))
            parsed (exec/parse-exec-json raw)]
        (merge {:blocked? false :command command} parsed))
      (let [payload #js {:name "bash" :args #js {:command command}}
            result  (js-await ((:emit-collect events) "before_tool_call" payload))]
        (cond
          ;; Security analysis / permissions vetoed the call.
          (or (get result "block") (get result "cancel"))
          {:blocked?  true
           :reason    (or (get result "reason")
                          "Command blocked by bash_suite")
           :command   command
           :stdout    ""
           :stderr    ""
           :exit-code -1}

          :else
          ;; Extensions may have rewritten the command (env_filter
          ;; prepends `unset`, cwd_manager prepends `cd`, etc). Pull
          ;; the final command from the merged result; fall back to
          ;; the original if nothing rewrote it.
          (let [rewritten (some-> (get result "args")
                                  (.-command))
                final-cmd (or rewritten command)
                raw       (js-await (tools/bash-execute #js {:command final-cmd}))
                parsed    (exec/parse-exec-json raw)]
            (merge {:blocked? false :command final-cmd} parsed)))))))

;;; ─── Pure: formatting the rendered message ──────────────

(defn format-bash-output
  "Build a human-readable text block for a run-bash! result. The
   styled message renderer in app.cljs will lay this out with a
   command header, dim body, and a colored exit-code footer — but
   the string content produced here is also useful on its own (for
   :role assistant fallback and for serialization to LLM context on
   `!cmd` turns)."
  [{:keys [command blocked? reason stdout stderr exit-code]}]
  (cond
    blocked?
    (str "$ " command "\n" (or reason "blocked"))

    :else
    (let [out (exec/truncate-output stdout)
          err (exec/truncate-output stderr)
          body (cond
                 (and (seq out) (seq err))
                 (str out "\nstderr:\n" err)

                 (seq out) out
                 (seq err) (str "stderr:\n" err)
                 :else     "")
          footer (when (and exit-code (not (zero? exit-code)))
                   (str "\nexit " exit-code))]
      (str "$ " command
           (when (seq body) (str "\n" body))
           footer))))
