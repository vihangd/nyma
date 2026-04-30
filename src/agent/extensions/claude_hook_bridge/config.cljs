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
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

(defn- safe-read-json
  "Read a JSON file, return parsed JS object or nil on any failure."
  [p]
  (when (and p (fs/existsSync p))
    (try
      (js/JSON.parse (fs/readFileSync p "utf8"))
      (catch :default _e nil))))

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
   Returns {:hooks {...} :disable-all? bool} or nil."
  [p]
  (when-let [parsed (safe-read-json p)]
    (let [hooks   (aget parsed "hooks")
          disable (aget parsed "disableAllHooks")]
      {:hooks (normalize-hooks-block hooks)
       :disable-all? (boolean disable)
       :source p})))

(defn- read-bare-hooks
  "Read a standalone hooks.json file (used for ~/.agents/hooks.json
   convention). The file root IS the hooks block — no `hooks` key
   wrapper."
  [p]
  (when-let [parsed (safe-read-json p)]
    {:hooks (normalize-hooks-block parsed)
     :disable-all? false
     :source p}))

(defn default-source-paths
  "Return the ordered list of source path objects to try.

   `compat` is a CLJS map: {:claude bool :agents bool}.
   `home` overrides os/homedir() — used in tests to isolate."
  ([cwd compat] (default-source-paths cwd compat (os/homedir)))
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
     (vec (concat nyma-paths claude-paths agents-paths)))))

(defn load-merged-hooks
  "Walk the source list in precedence order. Stop accumulating below
   any source that sets disableAllHooks: true. Return:
     {:hooks {EventName -> [normalized-entries...]}
      :sources-loaded [paths]
      :disable-all-source path-or-nil}

   `home` is the optional global home dir override (defaults to
   os/homedir()) — used in tests to isolate from the real ~/.claude."
  ([cwd compat] (load-merged-hooks cwd compat (os/homedir)))
  ([cwd compat home]
   (let [sources (default-source-paths cwd compat home)
         ;; Reduce with disable-all? short-circuit.
         result (reduce
                 (fn [acc {:keys [read path]}]
                   (if (:disable-all-source acc)
                     acc
                     (if-let [block (read path)]
                       (cond-> (-> acc
                                   (update :sources-loaded conj path)
                                   (update :hooks merge-hooks-maps (:hooks block)))
                         (:disable-all? block) (assoc :disable-all-source path))
                       acc)))
                 {:hooks {} :sources-loaded [] :disable-all-source nil}
                 sources)]
     result)))

(defn load-compat-flags
  "Read `hooks-compat` from settings if present. Defaults to {:claude
   false :agents false}. We deliberately do NOT participate in nyma's
   normal settings merge here — those merges are shallow and would
   collapse our hook arrays. We do a tiny separate read for the flags
   themselves."
  [cwd]
  (let [home (os/homedir)
        candidates
        [(path/join cwd  ".nyma" "settings.local.json")
         (path/join cwd  ".nyma" "settings.json")
         (path/join home ".nyma" "settings.json")]
        compat (some (fn [p]
                       (when-let [parsed (safe-read-json p)]
                         (aget parsed "hooks-compat")))
                     candidates)]
    {:claude (boolean (and compat (.-claude compat)))
     :agents (boolean (and compat (.-agents compat)))}))
