(ns agent.settings.manager
  (:require [agent.utils.home :as home]
             ["node:path" :as path]
            ["node:fs" :as fs]
            [agent.debug :as d]
            [agent.utils.validation :as v]
            [clojure.string :as str]))

(def defaults
  {:model          "claude-sonnet-4-20250514"
   :provider       "anthropic"
   :thinking       "off"
   :compaction     {:enabled true :threshold 0.85}
   ;; Retry on transient provider errors (429, 503, "high load").
   ;; :max-retries is the number of RETRIES (not total attempts). With 5
   ;; retries the AI SDK makes up to 6 attempts with exponential backoff
   ;; starting at 2s. The AI SDK also respects retry-after / retry-after-ms
   ;; headers from the provider if present.
   :retry          {:enabled true :max-retries 5}
   ;; Per-prompt cap on agentic tool-call → response cycles. Reaching
   ;; this stops the AI SDK loop mid-task; bump it for projects where
   ;; the agent legitimately needs more iterations.
   :max-steps      100
   ;; Session logs compress to about 13% of their size (0.82MB -> 0.11MB in 2ms,
   ;; measured), so an old sessions directory is mostly air. Off by default:
   ;; archive-after-days 0 means never, and any positive number compresses
   ;; sessions untouched for that long to `<id>.jsonl.zstd` at startup. Reading,
   ;; listing and resuming all handle either shape — a resumed archive is
   ;; restored to plain JSONL before anything appends to it.
   :sessions       {:archive-after-days 0}
   ;; Sampling temperature. nyma sent NONE until now, so every request ran at
   ;; whatever the provider defaulted to — typically 0.7-1.0. Measured cost of
   ;; that: re-running five tasks three times each gave pass^3 0% against
   ;; pass@3 60%, with one task recorded as timeout, error and pass at runtimes
   ;; from 183s to 1149s. Low, not zero: temperature 0 does not buy determinism
   ;; anyway (nondeterminism at 0 comes from batch-size-dependent reduction
   ;; kernels, not sampling), and coding still needs some latitude.
   ;; small-model model-profiles override this per model.
   :temperature    0.2
   ;; Cap on OUTPUT tokens per turn. nyma set none, so the provider decided.
   ;; Traced failure: on rust/decimal the model produced 70,565 characters of
   ;; prose hand-simulating the arithmetic digit by digit — "For i=2: product =
   ;; 0 * 10^18 + 0 = 0" — and was cut off mid-loop having never called a tool.
   ;; 7/7 runs of that task ended `never modified the stub`. A turn that cannot
   ;; ramble indefinitely has to act. Generous enough for real work: ~4k tokens
   ;; is a large file write.
   :max-output-tokens 8000
   :steering-mode  "one-at-a-time"
   :follow-up-mode "one-at-a-time"
   :transport              "auto"
   ;; "collapsed" (one line per tool call, ctrl+o expands the last one) or
   ;; "expanded" (every finished call shows its output). The line cap applies
   ;; to the expanded body; the rest is summarised as "… N more lines".
   :tool-display           "collapsed"
   :tool-display-max-lines 40
   ;; Layout mode for the chat view. Accepts:
   ;;   true    — default, natural-flow + writeToStdout commits
   ;;   false   — fixed-height + Ink <Static> emissions to scrollback
   ;;   "pager" — in-app scrollable pager (all turns stay inside Ink)
   ;; The setting key can be written as either kebab-case
   ;; ("scrollback-mode") or camelCase ("scrollbackMode") in the JSON
   ;; file; load-json normalizes both to kebab-case before merge.
   ;; See src/agent/ui/scrollback.cljs and src/agent/ui/chat_pager.cljs.
   :scrollback-mode        true
   :status-line            {:preset "default"
                            :left-segments nil
                            :right-segments nil
                            :separator nil}
   ;; Where pickers, prompts and info overlays are placed. `anchor` accepts any
   ;; of pi-tui's nine values — center, top-left, top-center, top-right,
   ;; left-center, right-center, bottom-left, bottom-center, bottom-right —
   ;; and anything else falls back to the default rather than reaching pi-tui.
   ;; `width` and `max-height` take a column/row count or an "N%" string.
   ;;
   ;; Bottom by default so a permission prompt does not cover the transcript
   ;; the user is reading to answer it. See agent.ui.overlay-host.
   :ui                     {:overlay {:anchor     "bottom-center"
                                      :width      "100%"
                                      :min-width  40
                                      :max-height "70%"}}
   ;; A role may carry a permission :policy mapping a tool CATEGORY
   ;; (exec|write|read|network) → allow|ask|deny. This is the permission-MODE
   ;; axis (modes-as-roles): /mode switches the active role; the gate asks/denies
   ;; per the policy. :default asks before writes/shell/network (the safe Claude
   ;; default); accept-edits auto-approves edits; full-auto allows everything.
   ;;
   ;; Every :allowed-tools list below carries `retrieve_result` because every
   ;; one of them allows `read`, and any capped tool result can hand the model a
   ;; truncation handle. A role that allowed the tool but not the recall would
   ;; leave the model holding an id it cannot spend. NOTE: a user :roles map
   ;; REPLACES this one rather than merging, so a custom role that narrows tools
   ;; has to carry `retrieve_result` itself.
   :roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"
                     :policy {"write" "ask" "exec" "ask" "network" "ask"}}
           :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
           :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
           ;; Permission-mode roles (model-less → preserve the active model).
           :accept-edits {:policy {"write" "allow" "exec" "ask" "network" "ask"}}
           :full-auto    {:policy {"read" "allow" "write" "allow" "exec" "allow" "network" "allow"}}
           ;; Stronger reviewer for the `advisor` tool / `/advisor` cmd.
           ;; Falls back to :deep, then current model, if unset by user.
           :advisor {:provider "anthropic" :model "claude-opus-4-20250514"}
           :plan    {:provider "anthropic" :model "claude-opus-4-20250514"
                     :allowed-tools ["read" "glob" "grep" "ls" "think" "web_search" "web_fetch" "retrieve_result"]
                     :permissions {"write" "deny" "edit" "deny" "bash" "deny"}}
           :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"
                     :allowed-tools ["read" "bash" "glob" "grep" "edit" "write" "retrieve_result"]}
           ;; --- Subagent roles (used by the `subagent` tool) ---
           ;; Read-only by default: the evidence boundary for coding agents
           ;; is single-threaded edits + isolated read-only exploration.
           ;; A subagent role is just a role with :description + :system-prompt.
           :scout      {:provider "anthropic" :model "claude-haiku-4-20250901"
                        :allowed-tools ["read" "glob" "grep" "ls" "retrieve_result"]
                        :description "Fast read-only codebase recon. Returns a compressed map/summary."
                        :system-prompt "You are a scout. Investigate the codebase read-only and return a compressed, structured summary (files, symbols, where things live). Do not propose edits."}
           :planner    {:provider "anthropic" :model "claude-opus-4-20250514"
                        :allowed-tools ["read" "glob" "grep" "ls" "web_search" "web_fetch" "retrieve_result"]
                        :description "Read-only implementation planner. Returns a numbered plan."
                        :system-prompt "You are a planner. Analyze read-only and return a detailed numbered plan under a 'Plan:' header. Do not modify files."}
           :reviewer   {:provider "anthropic" :model "claude-sonnet-4-20250514"
                        :allowed-tools ["read" "glob" "grep" "ls" "retrieve_result"]
                        :description "Read-only code reviewer. Returns findings, one per line."
                        :system-prompt "You are a reviewer. Examine the code read-only and return concise findings (path:line — problem — fix). Do not modify files."}
           :researcher {:provider "anthropic" :model "claude-sonnet-4-20250514"
                        :allowed-tools ["read" "web_search" "web_fetch" "retrieve_result"]
                        :description "Web/docs research. Returns sourced findings."
                        :system-prompt "You are a researcher. Gather information from docs/web and return a sourced summary with URLs. Do not modify files."}
           ;; Editing subagent — DISABLED by default. Editing subagents
           ;; fragment shared state; prefer keeping edits on the single
           ;; main thread. Enable per-call only when truly independent.
           :worker     {:provider "anthropic" :model "claude-sonnet-4-20250514"
                        :allowed-tools ["read" "glob" "grep" "ls" "edit" "write" "bash" "retrieve_result"]
                        :enabled false
                        :description "Implementation worker with edit tools (opt-in)."
                        :system-prompt "You are a worker. Implement the delegated task and return a concise summary of changes (files touched, what changed)."}}
   ;; Subagent extension knobs.
   :subagent       {:async-by-default false :max-depth 1}
   ;; Native plan-mode knobs.
   ;;  :auto-approve  — skip the approval gate (non-interactive / autonomous).
   ;;  :planner-role  — role whose model is used for PLANNING turns
   ;;                   ("opusplan": plan with the strong model, execute with the
   ;;                   selected one). Default :advisor (the strong-model knob,
   ;;                   shared with the advisor tool). Set to :plan to use the
   ;;                   :plan role's own model, or false to disable the switch.
   :plan-mode      {:auto-approve false :planner-role :advisor}
   ;; Extensions switched off by namespace: {"openwiki" false}. Builtin or
   ;; user, either scope; `/extensions disable <ns>` writes it. Merged PER KEY
   ;; across global and project (see `:get`), unlike the top-level replace.
   :extensions     {}
   ;; Project-context files read into the system prompt at session start,
   ;; in order of preference: at each directory level the FIRST name that
   ;; exists wins, so a repo carrying both injects AGENTS.md only.
   :context-files  ["AGENTS.md" "CLAUDE.md"]})

(defn detect-duplicate-keys
  "Scan a JSON source string for duplicate keys inside the same
   object. JSON.parse silently keeps the LAST value when a key is
   repeated, so a typo like
       { \"theme\": \"dark\", \"theme\": \"light\" }
   quietly discards the first entry. This scanner reports every
   duplicate as a :settings/duplicate-key validation warning before
   the caller hands the text to JSON.parse.

   Inspired by cc-kit's packages/ui/src/keybindings/validate.ts:239-285.

   Implementation notes:
     - Uses a small character-by-character scan instead of a full
       JSON parser so we can track the current object-depth scope.
     - Strings are skipped including their escape sequences so `\"{\"`
       inside a value isn't mistaken for an object boundary.
     - Returns a vector of validation issues (see agent.utils.validation).
       Empty vector when the text is clean."
  [source]
  (if-not (string? source)
    []
    (let [len            (count source)
          ;; Stack of per-object key sets. Each element is a transient set.
          stack          (volatile! (list))
          issues         (volatile! [])
          in-string?     (volatile! false)
          escape-next?   (volatile! false)
          current-key    (volatile! nil)
          key-start      (volatile! -1)]
      (loop [i 0]
        (if (>= i len)
          @issues
          (let [ch (.charAt source i)]
            (cond
              ;; Currently inside a string literal.
              @in-string?
              (cond
                @escape-next?
                (do (vreset! escape-next? false) (recur (inc i)))

                (= ch "\\")
                (do (vreset! escape-next? true) (recur (inc i)))

                (= ch "\"")
                (do
                  (vreset! in-string? false)
                  ;; If we were capturing a key, flush it.
                  (when (not (neg? @key-start))
                    (vreset! current-key (subs source (inc @key-start) i))
                    (vreset! key-start -1))
                  (recur (inc i)))

                :else
                (recur (inc i)))

              ;; String opening.
              (= ch "\"")
              (do
                (vreset! in-string? true)
                ;; A string that appears where a key is expected — we
                ;; track it as a potential key until we see ':'.
                (vreset! key-start i)
                (recur (inc i)))

              ;; Object open.
              (= ch "{")
              (do
                (vswap! stack conj (volatile! #{}))
                (recur (inc i)))

              ;; Object close.
              (= ch "}")
              (do
                (vswap! stack rest)
                (vreset! current-key nil)
                (recur (inc i)))

              ;; Colon — the preceding string was indeed a key.
              (= ch ":")
              (do
                (when (and @current-key (seq @stack))
                  (let [scope (first @stack)]
                    (if (contains? @scope @current-key)
                      (vswap! issues conj
                              (v/warning :settings/duplicate-key
                                         (str "Key \"" @current-key
                                              "\" appears more than once in the same object")
                                         {:path [@current-key]
                                          :suggestion "JSON.parse silently keeps the LAST value — remove the duplicate entry."}))
                      (vswap! scope conj @current-key))))
                (vreset! current-key nil)
                (recur (inc i)))

              ;; Comma resets the key so the next string isn't
              ;; mistaken for the previous key reused.
              (= ch ",")
              (do (vreset! current-key nil) (recur (inc i)))

              :else
              (recur (inc i))))))
      @issues)))

(defn camel->kebab
  "Pure: convert a camelCase string to kebab-case. Leaves already-
   kebab-case strings unchanged. Used to normalize JSON setting keys
   so users can write either style — JSON convention is camelCase,
   CLJS convention is kebab-case, and we accept both to match docs
   that mentioned \"scrollbackMode\" historically.

   Exported for testing."
  [s]
  (when (string? s)
    (.toLowerCase (.replace s (js/RegExp. "([a-z0-9])([A-Z])" "g") "$1-$2"))))

;; Maps whose KEYS are data (identifiers, ids) rather than config names.
;; Normalizing these corrupts them — see normalize-keys.
(def ^:private data-keyed-maps #{"model-profiles" "model-routing"})

(defn- normalize-values
  "Normalize the VALUES of a data-keyed map, leaving its keys untouched."
  [v]
  (if (object? v)
    (let [out #js {}]
      (doseq [k (js/Object.keys v)]
        (aset out k (normalize-keys (aget v k))))
      out)
    v))

(defn normalize-keys
  "Pure: walk a JS value and rewrite every string key from camelCase to
   kebab-case. Nested objects and array elements are normalized
   recursively. Primitives (number, boolean, string, null) and any
   non-plain-object (function, Date, Map, Set, class instance) pass
   through as-is. squint's `object?` is strict: it's true only for
   plain `{}` objects, so arrays fall through the array branch below.

   Some maps are keyed by DATA, not by config names, and rewriting those
   destroys them: a model id is not camelCase to be tidied. `model-profiles`
   is keyed by \"<provider>/<model-id>\", and normalization turned
   \"omlx/Qwen3.6-35B-A3B-OptiQ-4bit\" into
   \"omlx/qwen3.6-35-b-a3-b-opti-q-4bit\", which no lookup could ever match —
   so every per-model profile silently did nothing. Their VALUES are still
   normalized, so editStrategy → edit-strategy keeps working.

   Exported for testing."
  [v]
  (cond
    (nil? v)      v
    (array? v)    (.map v normalize-keys)
    (object? v)   (let [out #js {}]
                    (doseq [k (js/Object.keys v)]
                      (let [nk (camel->kebab k)
                            raw (aget v k)
                            nv  (if (contains? data-keyed-maps nk)
                                  (normalize-values raw)
                                  (normalize-keys raw))]
                        (aset out nk nv)))
                    out)
    :else         v))

(defn- load-json
  "Read, duplicate-key-scan, parse, and normalize a settings JSON file.
   Every camelCase key is rewritten to kebab-case so the CLJS code
   (which reads `(:scrollback-mode m)` → `get(m, \"scrollback-mode\")`)
   sees both styles. Any duplicate-key warnings are sent through
   `d/warn`. Returns nil when the file is missing or unparseable."
  [file-path]
  (when (fs/existsSync file-path)
    (let [text   (fs/readFileSync file-path "utf8")
          issues (detect-duplicate-keys text)]
      (when (seq issues)
        (d/warn "settings" (str "Duplicate keys in " file-path)
                {:count (count issues)
                 :details (mapv v/format-issue issues)}))
      (try
        (normalize-keys (js/JSON.parse text))
        (catch :default e
          (d/warn "settings" (str "Failed to parse " file-path ": " (.-message e)))
          nil)))))

(defn- save-json [file-path data]
  (let [dir (path/dirname file-path)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (fs/writeFileSync file-path (js/JSON.stringify (clj->js data) nil 2))))

(def inert-keys
  "Settings keys that are parsed and read by NOTHING, with the reason.

   Six shipped this way. Two were never built; four were live before `acee030`
   and were deleted with the Ink UI, leaving the key, the README entry and — for
   scrollback — a doc comment pointing at two files that no longer exist. A user
   who sets one gets exactly the silence they would get from a typo.
   `tool-display` and `tool-display-max-lines` have since been revived by the
   expandable tool view (interactive.cljs reads them), so four remain.

   This is the production copy, and `test/settings_reader_lint.test.cljs` READS
   it rather than keeping its own — the lints that mirror the thing they guard
   are the ones that go stale."
  {"steering-mode"          "never implemented; steers are injected all-at-once"
   "follow-up-mode"         "never implemented; the follow-up queue is already one-at-a-time"
   "scrollback-mode"        "removed with the Ink UI; there is no pager"
   "status-line"            "removed with the Ink UI; segments are auto-appended in id order"})

(defn inert-in
  "Keys of `user-settings` that are known to do nothing, sorted. Pure."
  [user-settings]
  (->> (keys (or user-settings {}))
       (map str)
       (filter inert-keys)
       sort
       vec))

(defn inert-warning
  "One-line-per-key warning for `user-settings`, or nil when there is nothing to
   say. Pure so it can be tested without a filesystem."
  [user-settings]
  (when-let [ks (seq (inert-in user-settings))]
    (str "settings: " (count ks) " key(s) in your settings file have no effect:\n"
         (->> ks
              (map (fn [k] (str "  " k " — " (get inert-keys k))))
              (str/join "\n")))))

(defn manifest-defaults
  "extension.json `settings` (a JS object: section → {key → default}) as
   {section {key default}} with kebab-case keys, the form `:get` merges.
   Pure. A section whose value is not an object is ignored."
  [js-settings]
  (if (object? js-settings)
    (reduce (fn [m section]
              (let [v (aget js-settings section)]
                (if (object? v)
                  (assoc m (camel->kebab section)
                         (reduce (fn [sm k] (assoc sm (camel->kebab k) (aget v k)))
                                 {} (js/Object.keys v)))
                  m)))
            {} (js/Object.keys js-settings))
    {}))

(defn create-settings-manager
  "Two-scope settings: global + project. Project overrides global.
   Supports :reload to re-read files from disk without restarting.
   Accepts an optional opts map:
     :global-path  — override the global settings file path (default: ~/.nyma/settings.json)
     :project-path — override the project settings file path (default: .nyma/settings.json)"
  ([] (create-settings-manager {}))
  ([{:keys [global-path project-path]}]
   (let [global-path   (or global-path (path/join (home/dir) ".nyma" "settings.json"))
         project-path  (or project-path ".nyma/settings.json")
         global-settings  (atom (load-json global-path))
         project-settings (atom (load-json project-path))
         overrides        (atom {})
         ;; Defaults declared by extension manifests, registered by the loader
         ;; before activation. Merged PER SECTION under the user's values, so
         ;; a user who sets one key of a section keeps the rest — unlike the
         ;; top-level shallow merge, which `:roles` relies on being a REPLACE.
         ext-defaults     (atom {})]

     {:get (fn []
             (let [merged (merge defaults
                                 (or @global-settings {})
                                 (or @project-settings {})
                                 @overrides
                                 ;; Per key: a project that disables one
                                 ;; extension must not silently re-enable
                                 ;; everything the global file switched off.
                                 {:extensions (merge {}
                                                     (:extensions @global-settings)
                                                     (:extensions @project-settings)
                                                     (:extensions @overrides))})]
               (reduce (fn [m k] (assoc m k (merge (get @ext-defaults k) (get m k))))
                       merged
                       (keys @ext-defaults))))

      ;; {section {key default}} from an extension manifest. Late registration
      ;; is fine: `:get` reads the atom on every call.
      :register-defaults! (fn [m] (swap! ext-defaults #(merge-with merge % m)))
      :ext-defaults       (fn [] @ext-defaults)

      ;; Only what the USER actually wrote. `:get` merges `defaults` in, so it
      ;; always contains every key and cannot answer "did they set this?".
      ;; With a scope (:global / :project), that file's contents alone — for
      ;; a read-modify-write of one nested section before `:save-*`.
      :user-settings (fn [& [scope]]
                       (case scope
                         :global  (or @global-settings {})
                         :project (or @project-settings {})
                         (merge (or @global-settings {}) (or @project-settings {}))))

      :set-override (fn [k v]
                      (swap! overrides assoc k v))

      :apply-overrides (fn [m]
                         (swap! overrides merge m))

      :reload (fn []
                (reset! global-settings (load-json global-path))
                (reset! project-settings (load-json project-path)))

      ;; The in-memory map follows the file, so `:get` answers with what was
      ;; just written instead of waiting for a restart or `:reload`.
      :save-global (fn [settings]
                     (let [m (merge @global-settings settings)]
                       (reset! global-settings m)
                       (save-json global-path m)))

      :save-project (fn [settings]
                      (let [m (merge @project-settings settings)]
                        (reset! project-settings m)
                        (save-json project-path m)))

      :tool-allowed? (fn [tool-name]
                      ;; Union of project + global allow-lists.
                      ;; Settings are plain JS objects from JSON.parse.
                       (let [js-allow (fn [s]
                                        (when s
                                          (let [perms (.-permissions s)]
                                            (when perms
                                              (.-allow perms)))))
                             global-allow  (or (js-allow @global-settings) #js [])
                             project-allow (or (js-allow @project-settings) #js [])
                             all-allowed   (into #{} (concat (js/Array.from global-allow)
                                                             (js/Array.from project-allow)))]
                         (contains? all-allowed tool-name)))

      :append-allow-tool! (fn [tool-name]
                           ;; Add tool-name to project permissions.allow (idempotent).
                           ;; Settings are plain JS objects; build/merge without js->clj.
                            (let [ps        @project-settings
                                  perms     (when ps (.-permissions ps))
                                  current   (if (and perms (.-allow perms))
                                              (js/Array.from (.-allow perms))
                                              [])
                                  as-set    (into #{} current)]
                              (when-not (contains? as-set tool-name)
                                (let [new-allow  (clj->js (conj current tool-name))
                                      new-perms  (doto (js/Object.assign #js {} (or perms #js {}))
                                                   (aset "allow" new-allow))
                                      updated    (doto (js/Object.assign #js {} (or ps #js {}))
                                                   (aset "permissions" new-perms))]
                                  (save-json project-path updated)
                                  (reset! project-settings (load-json project-path))))))})))
