(ns agent.extensions.claude-hook-bridge.config
  "Hook configuration loader.

   Reads the `hooks` key from a stack of settings files, in CC order.
   Always loads (in precedence order, later overrides earlier):
     - ~/.nyma/settings.json
     - .nyma/settings.json
     - .nyma/settings.local.json

   Optionally loads (when the `hooks-compat` settings flag opts in):
     - ~/.claude/settings.json
     - .claude/settings.json
     - .claude/settings.local.json
     - ~/.agents/hooks.json (future)
     - .agents/hooks.json (future)

   Honors `disableAllHooks: true` from any source — when seen, every
   source below it is dropped (matches CC).

   Critical: the bridge must NOT use nyma's shallow settings merge for
   the hooks key, because PreToolUse: [A] in one source and [B] in
   another should produce [A, B] not [B]. We re-merge the hooks key
   ourselves with deep-concat semantics."
  (:require [agent.utils.home :as home]
             ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]))

(defn malformed-config-message
  "The error shown for a hooks source that will not parse. Names the exact
   path — a present-but-broken settings file yielding zero hooks is
   indistinguishable from having configured none."
  [file-path err-msg]
  (str "Malformed hooks config: " file-path " — " err-msg
       ". Its hooks will NOT load."))

(defn- read-json-file
  "Read a JSON file. Returns nil when absent, {:parsed obj} when it parses,
   {:error msg} when it does not. Distinguishing the last two is the whole
   point: the old `safe-read-json` collapsed both into nil and logged
   nothing anywhere — not even debug.log."
  [p]
  (when (and p (fs/existsSync p))
    (try
      {:parsed (js/JSON.parse (fs/readFileSync p "utf8"))}
      (catch :default e {:error (or (.-message e) (str e))}))))

(defn- safe-read-json
  "Read a JSON file, return parsed JS object or nil on any failure."
  [p]
  (:parsed (read-json-file p)))

(defn- normalize-event-entries
  "An event-key value may be:
     - an array of CC-shape entries  : [{matcher, hooks: [...]}]
     - a single object                : {matcher?, hooks: [...]} or [hooks]
     - a string                       : convenience for {hooks:[{type:command, command:s}]}
   Normalize to an array of CC-shape entries."
  [v]
  (cond
    (js/Array.isArray v)
    (vec (.map v identity))

    (string? v)
    [#js {:matcher "*"
          :hooks   #js [#js {:type "command" :command v}]}]

    (object? v)
    [v]

    :else
    []))

(defn- normalize-hooks-block
  "Given a `hooks` JS object {EventName: ...}, normalize each value to
   an array of CC-shape entries. Returns a CLJS map keyed by event name."
  [hooks-obj]
  (when hooks-obj
    (->> (js-keys hooks-obj)
         (map (fn [k]
                [k (normalize-event-entries (aget hooks-obj k))]))
         (into {}))))

(defn- merge-event-arrays
  "Concatenate two normalized hook-event arrays. Later entries are
   appended (so project hooks fire after global hooks at the same
   priority)."
  [a b]
  (vec (concat (or a []) (or b []))))

(defn- merge-hooks-maps
  "Deep-merge two normalized hooks maps by concatenating per-event arrays."
  [a b]
  (reduce-kv
   (fn [acc k v]
     (assoc acc k (merge-event-arrays (get acc k) v)))
   (or a {})
   (or b {})))

(defn- read-hooks-from-settings
  "Pull the `hooks` JS object out of a settings JSON file at `p`.
   Returns {:hooks {...} :disable-all? bool :source p}, or
   {:error msg :source p} when the file is present but unparseable,
   or nil when it is absent."
  [p]
  (when-let [{:keys [parsed error]} (read-json-file p)]
    (if error
      {:error error :source p}
      (let [hooks   (aget parsed "hooks")
            disable (aget parsed "disableAllHooks")]
        {:hooks (normalize-hooks-block hooks)
         :disable-all? (boolean disable)
         :source p}))))

(defn- read-bare-hooks
  "Read a standalone hooks.json file (used for ~/.agents/hooks.json
   convention). The file root IS the hooks block — no `hooks` key
   wrapper."
  [p]
  (when-let [{:keys [parsed error]} (read-json-file p)]
    (if error
      {:error error :source p}
      {:hooks (normalize-hooks-block parsed)
       :disable-all? false
       :source p})))

(defn default-source-paths
  "Return the ordered list of source path objects to try.

   `compat` is a CLJS map: {:claude bool :agents bool}.
   `home` overrides home/dir — used in tests to isolate."
  ([cwd compat] (default-source-paths cwd compat (home/dir)))
  ([cwd compat home]
   (let [nyma-paths
         [{:read read-hooks-from-settings :path (path/join home ".nyma" "settings.json")}
          {:read read-hooks-from-settings :path (path/join cwd  ".nyma" "settings.json")}
          {:read read-hooks-from-settings :path (path/join cwd  ".nyma" "settings.local.json")}]
         claude-paths
         (when (:claude compat)
           [{:read read-hooks-from-settings :path (path/join home ".claude" "settings.json")}
            {:read read-hooks-from-settings :path (path/join cwd  ".claude" "settings.json")}
            {:read read-hooks-from-settings :path (path/join cwd  ".claude" "settings.local.json")}])
         agents-paths
         (when (:agents compat)
           [{:read read-bare-hooks :path (path/join home  ".agents" "hooks.json")}
            {:read read-bare-hooks :path (path/join cwd   ".agents" "hooks.json")}])]
     ;; Each FILE once. `cwd` is the literal project directory, so running nyma
     ;; from your home directory makes three of these pairs name the same file —
     ;; and because per-event arrays are concatenated, every matcher in them was
     ;; registered twice and every hook command ran twice. Keep the first
     ;; (home/global) occurrence; the content is identical either way.
     (let [seen (atom #{})]
       (vec (keep (fn [src]
                    (let [k (try (fs/realpathSync (:path src))
                                 (catch :default _e (path/resolve (:path src))))]
                      (when-not (contains? @seen k)
                        (swap! seen conj k)
                        src)))
                  (concat nyma-paths claude-paths agents-paths)))))))

(defn handler-label
  "One-line label for a handler spec, as `/hooks` prints it:
   `command: rtk hook claude`, `http: http://…`, `prompt`,
   `mcp_tool: server/tool`. Pure."
  [h]
  (let [t (or (and h (aget h "type")) "command")]
    (cond
      (= t "http")     (str "http: " (or (aget h "url") "?"))
      (= t "prompt")   (str "prompt: " (or (aget h "model") "<active model>"))
      (= t "mcp_tool") (str "mcp_tool: " (or (aget h "server") "?") "/" (or (aget h "tool") "?"))
      :else            (str "command: " (or (aget h "command") "?")))))

(defn source-index
  "Flatten one source's normalized hooks map into display rows
   {:event :matcher :handler :source}. Kept OUT of `:hooks` on purpose —
   stamping provenance onto the user's own entry objects would leak into
   `getHookConfig` and into the matcher/diagnostics walks. Pure."
  [hooks-map source]
  (let [rows (atom [])]
    (doseq [[event entries] hooks-map]
      (doseq [entry entries]
        (let [hs (or (aget entry "hooks") #js [])]
          (doseq [h (vec hs)]
            (swap! rows conj {:event   event
                              :matcher (or (aget entry "matcher") "*")
                              :handler (handler-label h)
                              :source  source})))))
    @rows))

(defn load-merged-hooks
  "Walk the source list in precedence order. Stop accumulating below
   any source that sets disableAllHooks: true. Return:
     {:hooks {EventName -> [normalized-entries...]}
      :sources-loaded [paths]
      :candidates [{:path p :exists? bool}]
      :index [{:event :matcher :handler :source}]
      :errors [{:path p :message m}]
      :disable-all-source path-or-nil}

   `home` is the optional global home dir override (defaults to
   home/dir) — used in tests to isolate from the real ~/.claude."
  ([cwd compat] (load-merged-hooks cwd compat (home/dir)))
  ([cwd compat home]
   (let [sources (default-source-paths cwd compat home)
         ;; Reduce with disable-all? short-circuit.
         result (reduce
                 (fn [acc {:keys [read path]}]
                   (if (:disable-all-source acc)
                     acc
                     (if-let [block (read path)]
                       (if (:error block)
                         ;; A file that is present but will not parse is
                         ;; reported, not silently skipped. The sources below
                         ;; it still load — one broken file should not take
                         ;; the rest of the stack down with it.
                         (update acc :errors conj {:path path :message (:error block)})
                         (cond-> (-> acc
                                     (update :sources-loaded conj path)
                                     (update :index into (source-index (:hooks block) path))
                                     (update :hooks merge-hooks-maps (:hooks block)))
                           (:disable-all? block) (assoc :disable-all-source path)))
                       acc)))
                 {:hooks {} :sources-loaded [] :index [] :errors []
                  :disable-all-source nil}
                 sources)]
     ;; Report against ALL EIGHT candidates, not just the ones this compat
     ;; setting consults: "I put my hooks in .claude/settings.json and nyma
     ;; ignored them" is the whole reason the file list is printed, and a
     ;; list that omits the file the user edited answers nothing.
     (assoc result :candidates
            (let [consulted (set (map :path sources))]
              (mapv (fn [{:keys [path]}]
                      {:path       path
                       :exists?    (boolean (fs/existsSync path))
                       :consulted? (contains? consulted path)
                       :loaded?    (boolean (some #{path} (:sources-loaded result)))})
                    (default-source-paths cwd {:claude true :agents true} home)))))))

(defn format-report
  "Render a `load-merged-hooks` result as the `/hooks` output. Pure, so the
   test needs no filesystem."
  [{:keys [candidates index errors disable-all-source]}]
  (str/join
   "\n"
   (concat
    ["Hooks"
     ""
     (if (seq index)
       (str "Resolved hooks (" (count index) "):")
       "Resolved hooks: none.")]
    (map (fn [{:keys [event matcher handler source]}]
           (str "  " event " [" matcher "] → " handler "\n      from " source))
         index)
    [""
     "Source files (lowest → highest precedence):"]
    (map (fn [{:keys [path exists? consulted? loaded?]}]
           (str "  "
                (cond loaded?          "✓"
                      (not consulted?) "·"
                      exists?          "!"
                      :else            "·")
                " " path
                (cond
                  loaded?                     " (read)"
                  (and exists? (not consulted?)) " (exists — not consulted; enable via hooks-compat)"
                  (not consulted?)            " (not consulted; enable via hooks-compat)"
                  exists?                     " (failed to parse)"
                  :else                       " (not found)")))
         candidates)
    (when disable-all-source
      ["" (str "disableAllHooks: true in " disable-all-source
               " — every source below it was dropped.")])
    (when (seq errors)
      (concat ["" (str "Parse failures (" (count errors) "):")]
              (map (fn [{:keys [path message]}]
                     (str "  ✗ " path "\n      " message))
                   errors))))))

(defn load-compat-flags
  "Read `hooks-compat` from settings if present. Defaults to {:claude
   false :agents false}. We deliberately do NOT participate in nyma's
   normal settings merge here — those merges are shallow and would
   collapse our hook arrays. We do a tiny separate read for the flags
   themselves.

   `home` overrides home/dir — used in tests to isolate from the
   user's real ~/.nyma/settings.json."
  ([cwd] (load-compat-flags cwd (home/dir)))
  ([cwd home]
   (let [candidates
         [(path/join cwd  ".nyma" "settings.local.json")
          (path/join cwd  ".nyma" "settings.json")
          (path/join home ".nyma" "settings.json")]
         compat (some (fn [p]
                        (when-let [parsed (safe-read-json p)]
                          (aget parsed "hooks-compat")))
                      candidates)]
     {:claude (boolean (and compat (.-claude compat)))
      :agents (boolean (and compat (.-agents compat)))})))
