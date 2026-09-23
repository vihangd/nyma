(ns agent.commands.builtins
  "Built-in slash command implementations (/help, /model, /clear, /skill, /skills, /resume, /export, etc.)."
  (:require [agent.utils.data :as data]
            [agent.model-info :as model-info]
            [agent.sessions.compaction :refer [compact compaction-receipt settings->opts resolve-settings]]
            [agent.sessions.manager :refer [session->seed-messages]]
            [agent.ui.theme-catalog :as theme-catalog]
            [agent.ui.themes :refer [default-dark]]
            [agent.sessions.listing :refer [list-sessions scope-to-project format-row]]
            [agent.commands.share :as share :refer [messages->html messages->markdown]]
            [agent.commands.parser :as cmd-parser]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.keybinding-registry :as kbr]
            [agent.extension-loader :refer [deactivate-all discover-and-load last-load-failures last-disabled reload-one! eval-expr!]]
            [agent.extension-scope :refer [handler-errors]]
            [agent.resources.loader :refer [discover]]
            [agent.providers.oauth :as oauth]
            [agent.ui.tree-viewer :refer [create-tree-viewer]]
            [agent.ui.editor-bash :as editor-bash]
            [agent.ui.editor-eval :as editor-eval]
            [agent.resources.skills :as skills]
            [agent.utils.template-args :as template-args]
            [agent.loop :refer [follow-up]]
            [agent.debug :as d]
            [agent.ui.skill-picker :as skill-picker]
            [agent.providers.catalog :as catalog]
            [agent.thinking :as thinking]
            [agent.utils.credentials :as creds-util]
            [clojure.string :as str]
            ["node:fs" :as fs]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- notify
  "Show a notification via UI if available, else log to console."
  [ctx msg & [level]]
  (if (and ctx (.-ui ctx) (.-notify (.-ui ctx)))
    (.notify (.-ui ctx) msg (or level "info"))
    (js/console.log (str "\n" msg "\n"))))

(defn- show-info
  "Show info text via overlay if available, else log to console."
  [ctx text]
  (if (and ctx (.-ui ctx) (.-showOverlay (.-ui ctx)))
    (.showOverlay (.-ui ctx) text)
    (js/console.log (str "\n" text "\n"))))

(defn positional-args
  "The flag-free tokens interactive mode's shared splitter put on `ctx`, or
   nil when the caller is not interactive mode (a keybinding, an alias
   forwarding to another command, a test). Nil means \"fall back to the
   `args` vector you were handed\" — never an empty vector, which would be
   indistinguishable from a command genuinely called with no arguments."
  [ctx]
  (when ctx
    (when-let [p (try (aget ctx "positional") (catch :default _ nil))]
      (vec p))))

(defn command-flags
  "`--flag` / `--flag=value` / `--no-flag` parsed by the shared splitter,
   as a map of raw hyphenated key → true | false | string. Empty when the
   caller did not come through interactive mode."
  [ctx]
  (or (when ctx
        (when-let [f (try (aget ctx "flags") (catch :default _ nil))]
          (into {} (map (fn [k] [k (aget f k)]) (js/Object.keys f)))))
      {}))

;;; ─── Model selection ────────────────────────────────────────
;;;
;;; MRU is per-session and in-memory. ponytail: a cross-session MRU needs a
;;; storage decision (settings file vs the optional SQLite store); within one
;;; session this already covers the common "flip between two models" case.

(defonce ^:private recent-models (atom []))

(defn remember-model!
  "Push `spec` to the front of the MRU list (deduped, capped)."
  [spec]
  (when (seq (str spec))
    (swap! recent-models
           (fn [xs]
             (vec (take 10 (cons (str spec) (remove #(= % (str spec)) xs))))))))

(defn current-model-id
  "Display id of the active model."
  [agent]
  (let [m (:model (:config agent))]
    (cond
      (nil? m)    "unknown"
      (string? m) m
      :else       (or (.-modelId m) "unknown"))))

(defn current-models
  "Catalogue for the picker, most-recently-used first."
  [agent]
  (let [providers ((:list (:provider-registry agent)))
        ctx-fn    (:context-window (:model-registry agent))]
    (catalog/rank-by-recent (catalog/list-all-models providers ctx-fn)
                            @recent-models)))

(defn informative-name
  "The catalogue's display name for `m`, but only when it says something the id
   does not. Nil when the name is just the id prettified.

   This is not decoration. A gateway can serve a model under a vendor's id that
   is NOT that vendor's model, and the name is the only warning: FreeLLMAPI
   lists `claude-opus-4-5` as \"Opus slot (auto-routed to a free model)\". The
   name used to be a fallback shown only when a model had no context window or
   price — so on any gateway reporting windows, which is most, it never
   appeared and the user picked `claude-opus-4-5` believing it was Opus.

   Comparison is on lowercase alphanumerics, so \"Qwen3 32B\" is recognised as a
   restatement of `qwen3-32b` and suppressed."
  [m]
  (let [nm  (str (or (:name m) ""))
        id  (last (.split (str (or (:spec m) (:id m) "")) "/"))
        ;; Explicit RegExp with "g": a #"…" literal has no flags, so .replace
        ;; would strip only the FIRST separator — "GPT-OSS 120B" stayed
        ;; "gptoss 120b" and read as different from `gpt-oss-120b`.
        key (fn [s] (.replace (.toLowerCase (str s)) (js/RegExp. "[^a-z0-9]+" "g") ""))]
    (when (and (seq nm) (not= (key nm) (key id)))
      nm)))

(defn model->item
  "Catalogue entry → the {value,label,description} shape ui.select expects."
  [m]
  (let [ctx   (catalog/format-context (:context-window m))
        price (catalog/format-price (:cost m))
        meta  (->> [ctx price (informative-name m)] (filter seq) (str/join " · "))]
    {:value       (:spec m)
     :label       (:spec m)
     :description (if (seq meta) meta (:name m))}))

(defn model-catalogue-text
  "Plain-text catalogue for `/model list`."
  [agent]
  (let [models (current-models agent)]
    (if (empty? models)
      "No models registered."
      ;; Pad the spec column: with literal two-space separators the context and
      ;; price columns never line up once specs vary in length, which they do by
      ;; ~30 characters across providers.
      (let [spec-w (reduce (fn [w m] (max w (count (:spec m)))) 0 models)
            ctx-w  (reduce (fn [w m] (max w (count (catalog/format-context
                                                    (:context-window m))))) 0 models)
            pad    (fn [s n] (str s (.repeat " " (max 0 (- n (count s))))))]
        (str "Models (" (count models) ") — current: " (current-model-id agent) "\n\n"
             (str/join "\n"
                       (map (fn [m]
                              (str/trimr
                               (str "  " (pad (:spec m) spec-w)
                                    "  " (pad (catalog/format-context (:context-window m)) ctx-w)
                                    "  " (catalog/format-price (:cost m)))))
                            models)))))))

(defn- ui-selectable? [ctx]
  (boolean (and ctx (.-ui ctx) (.-select (.-ui ctx)))))

(defn- switch-model! [agent ctx model-spec]
  (when-let [api (.-extension-api agent)]
    (.setModel api model-spec))
  (remember-model! model-spec)
  (notify ctx (str "Model changed to: " model-spec)))

(defn scaffold-extension
  "Generate a skeleton ClojureScript extension file."
  [ext-name]
  (str "(ns " ext-name ")\n\n"
       ";; Extension: " ext-name "\n"
       ";; Registered tools, events, and commands go here.\n\n"
       "(defn ^:export default [api]\n"
       "  (js/console.log \"[" ext-name "] loaded\")\n"
       "  ;; Return deactivate function for cleanup\n"
       "  (fn [] (js/console.log \"[" ext-name "] deactivated\")))\n"))

(def ^:private help-group-order
  "Section order and titles for `/help`. `:extensions` is a placeholder —
   one subsection per extension namespace is spliced in at that point."
  [[:session    "Session"]
   [:model      "Model"]
   [:modes      "Modes & roles"]
   [:extensions "Extensions"]
   [:agent      "Agent commands (forwarded via //)"]
   [:prompts    "Prompt templates"]
   [:other      "Other"]])

(def ^:private modes-and-roles
  "Commands that belong under 'Modes & roles' whatever registered them.
   These five come from the model-roles extension, so grouping purely by
   namespace would file them under Extensions — where nobody looks for
   `/mode`."
  #{"mode" "planmode" "role" "roles" "escalate"})

(defn- short-of [name]
  (let [i (.indexOf (str name) "__")]
    (if (neg? i) (str name) (.slice (str name) (+ i 2)))))

(defn- agent-forwarded? [cmd]
  (= "agent-shell"
     (or (:forward-to cmd)
         (when (some? cmd)
           (try (aget cmd "forward-to") (catch :default _ nil))))))

(defn- extension-ns
  "`model-roles__role` → \"model-roles\", or nil for an unnamespaced name."
  [name]
  (let [i (.indexOf (str name) "__")]
    (when-not (neg? i) (.slice (str name) 0 i))))

(defn help-group
  "Which `/help` section a command belongs in.

   Builtins declare `:group` on their registration map. Extension commands
   are grouped by their namespace prefix — except the five that are really
   modes, which an explicit table pulls out. Anything unclaimed is :other,
   so a new command is listed rather than lost."
  [name cmd]
  (cond
    (agent-forwarded? cmd)                 :agent
    (contains? modes-and-roles (short-of name)) :modes
    (some? (:group cmd))                   (keyword (str (:group cmd)))
    (some? (extension-ns name))            :extensions
    :else                                  :other))

(defn- help-line [prefix shown cmd]
  (let [aliases (:aliases cmd)]
    (str "  " prefix (.trimEnd (str shown))
         (when (seq aliases) (str " (" (str/join ", " aliases) ")"))
         " — " (or (:description cmd) ""))))

(defn help-sections
  "The grouped `/help` text for a commands map.

   The flat alphabetical dump this replaces put `/cls` between `/clear` and
   `/compact` and buried every extension's commands among the builtins, so
   the one question help is asked — 'what can I do with sessions?' — took a
   full read of 40 lines. Sections answer it; within a section the order is
   alphabetical by the name shown.

   Hidden and disabled commands are filtered by `visible-commands`, and
   names come from `compute-display-names`, so this and the slash picker
   name commands identically. Pure."
  [commands]
  (let [visible   (cmd-parser/visible-commands commands)
        displays  (cmd-parser/compute-display-names (vec (keys visible)))
        entries   (map (fn [[name cmd]]
                         {:name    name
                          :shown   (.trim (str (get displays name name)))
                          :cmd     cmd
                          :group   (help-group name cmd)})
                       visible)
        by-group  (group-by :group entries)
        fmt       (fn [e] (help-line (if (= :agent (:group e)) "//" "/")
                                     (:shown e) (:cmd e)))
        sort-ent  (fn [es] (sort-by :shown es))
        section   (fn [title es]
                    (when (seq es)
                      (str title "\n" (str/join "\n" (map fmt (sort-ent es))))))
        ext-block (let [es (get by-group :extensions [])
                        by-ns (group-by (fn [e] (or (extension-ns (:name e)) "")) es)]
                    (when (seq es)
                      (str "Extensions\n"
                           (str/join "\n"
                                     (map (fn [ns]
                                            (str "  [" ns "]\n"
                                                 (str/join "\n"
                                                           (map (fn [e] (str "  " (fmt e)))
                                                                (sort-ent (get by-ns ns))))))
                                          (sort (keys by-ns)))))))
        blocks    (keep (fn [[g title]]
                          (if (= g :extensions)
                            ext-block
                            (section title (get by-group g []))))
                        help-group-order)]
    (str (str/join "\n\n" blocks)
         "\n\nRun /help <command> for one command's details.")))

(defn help-for-command
  "`/help <cmd>` — one command's name, aliases and description, or a
   not-found line. Resolution goes through the same namespace-suffix
   fallback the input dispatcher uses, so `/help role` works."
  [commands name]
  (let [n (str name)]
    (if-let [cmd (resolve-command commands n)]
      (str "/" n
           (when (seq (:aliases cmd))
             (str " (aliases: " (str/join ", " (:aliases cmd)) ")"))
           "\n  " (or (:description cmd) "(no description)"))
      (str "No such command: /" n "\nRun /help to list commands."))))

;;; ─── Async command handlers ──

(defonce ^:private prompt-command-names
  ;; What register-prompt-commands! last registered, so a reload can drop a
  ;; prompt whose file went away instead of leaving a stale `/name`.
  (atom #{}))

(defn register-prompt-commands!
  "Each discovered prompt template becomes `/<name>`: arguments fill
   `$ARGUMENTS`/`$N` and the text is submitted as a user message. A name
   already taken by a builtin or an extension is skipped with a warning —
   a template must never shadow a command."
  [agent prompts]
  (swap! (:commands agent) (fn [cs] (apply dissoc cs @prompt-command-names)))
  (reset! prompt-command-names #{})
  (doseq [[pname {:keys [template description argument-hint]}] prompts]
    (if-let [taken (resolve-command @(:commands agent) pname)]
      (d/warn "prompts" (str "prompt template /" pname " skipped: the name belongs to "
                             (or (:description taken) "an existing command")))
      (do
        (swap! prompt-command-names conj pname)
        (swap! (:commands agent) assoc pname
               {:group       :prompts
                :description (str (or description (str "Prompt template " pname))
                                  (when (seq argument-hint) (str " — " argument-hint)))
                :handler
                (fn [args _ctx]
                  (let [text   (template-args/substitute template (vec args))
                        events (:events agent)]
                    ;; turn_request STARTS a turn (interactive mode owns the
                    ;; submit path); where nothing listens, queue it for the
                    ;; next turn like any extension-sent message.
                    (if (pos? ((:handler-count events) "turn_request"))
                      ((:emit events) "turn_request" #js {:text text :echo true})
                      (follow-up agent {:role "user" :content text}))))})))))

(defn format-eval-value
  "A value from /eval as text, capped."
  [v]
  (let [s (cond
            (nil? v)    "nil"
            (string? v) v
            :else       (try (js/JSON.stringify v nil 1) (catch :default _ (str v))))
        s (or s (str v))]
    (if (> (count s) 4000) (str (subs s 0 4000) " …") s)))

(defn ^:async handle-reload-one
  "Reload ONE extension by namespace: deactivate it, load it again from disk,
   swap the new entry into the loaded list. The rest of the session — other
   extensions, settings, the transcript — is untouched."
  [agent extensions-atom ns ctx]
  (if-let [old (some #(when (= (:namespace %) ns) %) @extensions-atom)]
    (let [new (js-await (reload-one! (.-extension-api agent) old))
          ;; A failed reload keeps its ENTRY (deactivated, scope swept) so the
          ;; next /reload <ns> or watcher save can try again; dropping it made
          ;; one syntax error detach the extension until a full /reload.
          kept (or new (assoc old :deactivate nil :failed? true))]
      (swap! extensions-atom (fn [es] (mapv #(if (= (:namespace %) ns) kept %) es)))
      (if new
        (notify ctx (str "Reloaded extension " ns) "info")
        (notify ctx (str "Extension " ns " failed to reload and is now OFF; see /extensions") "error")))
    (notify ctx (str "No loaded extension named " ns ". /extensions lists them") "warning")))

(defn ^:async handle-reload
  "Orchestrate full reload: deactivate → settings → resources → extensions → flags."
  [agent resources extensions-atom resolve-flags-fn ctx]
  ;; 1. Deactivate loaded extensions
  (when extensions-atom
    (js-await (deactivate-all @extensions-atom)))
  ;; 2. Reload settings from disk
  (when-let [settings (:settings resources)]
    (when-let [reload-fn (:reload settings)]
      (reload-fn)))
  ;; 3. Rediscover resources
  (let [new-resources (js-await (discover {:events (:events agent)
                                           :reason "reload"
                                           :context-files (when-let [s (:settings resources)]
                                                            (when (fn? (:get s))
                                                              (:context-files ((:get s)))))
                                           :max-skill-description
                                           (when-let [s (:settings resources)]
                                             (when (fn? (:get s))
                                               (:max-description-length (:skills ((:get s))))))}))]
    ;; 4. Rebuild system prompt
    (when-let [build-fn (:build-system-prompt new-resources)]
      (aset (:config agent) "system-prompt" (build-fn)))
    ;; Theme too: a user theme file edited or added under .nyma/themes is
    ;; part of what was just rediscovered.
    (theme-catalog/activate!
     (theme-catalog/active-theme (:themes new-resources) default-dark))
    ;; 5. Reload extensions
    (when (and extensions-atom (.-extension-api agent))
      (let [loaded (js-await (discover-and-load
                              (:extension-dirs new-resources)
                              (.-extension-api agent)
                              (:builtin-extensions new-resources)))]
        (reset! extensions-atom loaded)))
    ;; 6. Re-resolve CLI flags
    (when resolve-flags-fn
      (resolve-flags-fn agent))
    ;; The `skill` tool closes over the skill map it was built with; a skill
    ;; added since launch was `/skill`-able but unknown to the model.
    (skills/register-skill-tool! agent (:skills new-resources))
    (skills/register-skill-activation! agent (:skills new-resources))
    ;; 7. session_ready again. Extensions set up their world on it — status
    ;;    segments, ACP auto-connect, MCP servers — and it used to fire once at
    ;;    startup only, so /reload deactivated all of that and brought none of
    ;;    it back. `reload` had zero listeners.
    (js-await ((:emit-async (:events agent)) "session_ready"
                                             #js {:cwd        (js/process.cwd)
                                                  :model      (current-model-id agent)
                                                  :extensions (count (or (when extensions-atom @extensions-atom) []))
                                                  :reason     "reload"}))
    ;; Prompt templates LAST: an extension that registers its command on
    ;; session_ready used to land after this and overwrite a prompt of the
    ;; same name. Now the prompt sees the full command set and, on a
    ;; collision, is the one that steps aside (skipped with a warning).
    (register-prompt-commands! agent (:prompts new-resources)))
  ((:emit (:events agent)) "reload" {})
  (notify ctx "Extensions reloaded"))

(def global-settings-path
  "Where `/settings` writes. Mirrors create-settings-manager's default."
  "~/.nyma/settings.json")

(defn nested-setting?
  "True for a value the one-line prompt cannot round-trip: a map or a vector.

   `:roles`, `:permissions`, `:scoped-models` are all of this shape, and a row
   reading `roles = {\"build\" {...}}` invited an edit that could only ever make
   things worse."
  [v]
  ;; typeof, not map?/vector?: settings arrive from JSON.parse as plain JS
  ;; objects and arrays, and in squint a map IS a plain object — one check
  ;; covers all three, and `some?` keeps null (typeof "object") out.
  (and (some? v) (= "object" (js/typeof v))))

(defn settings-row
  "One `/settings` row. Nested values collapse to `key = {…}` rather than
   spilling a whole subtree into a picker line."
  [k v]
  (str k " = " (if (nested-setting? v) "{…}" (pr-str v))))

(defn coerce-setting-value
  "Parse a typed setting value as JSON, falling back to the raw string.

   Everything the picker wrote used to land as a string: `max-steps` became
   \"200\" and `(or (:max-steps merged) 100)` happily took it, so the agent ran
   with a step limit that was a string; `true` became \"true\", which is truthy
   whatever the user typed. JSON is the right parser because the settings file
   IS JSON — whatever round-trips through it is exactly what is legal there."
  [s]
  (let [t (str/trim (str s))]
    (data/parse-json t t)))

(defn ^:async handle-settings
  "Interactive settings viewer/editor.

   An edit is PERSISTED (`:save-global` → ~/.nyma/settings.json). It used to go
   to `:set-override` only, which lives in memory for the life of the process:
   the user saw the confirmation, restarted, and the setting was gone."
  [resources ctx]
  (let [settings (:settings resources)
        current  (when settings ((:get settings)))
        entries  (when current
                   (mapv (fn [[k v]] (settings-row k v))
                         (sort-by first current)))]
    (if-not (and ctx (.-ui ctx) (.-select (.-ui ctx)))
      (show-info ctx (str "Settings:\n" (str/join "\n" (map #(str "  " %) entries))))
      (let [choice (js-await (.select (.-ui ctx) "Settings (select to edit):" (clj->js entries)))]
        (when choice
          (let [key-name (first (.split choice " = "))]
            (if (nested-setting? (get current key-name))
              (notify ctx (str key-name " is a nested value — edit it in "
                               global-settings-path)
                      "error")
              (let [new-val (js-await (.input (.-ui ctx)
                                              (str "New value for " key-name ":") ""))]
                (when (and new-val (seq (.trim new-val)))
                  (let [v (coerce-setting-value new-val)]
                    ((:save-global settings) (assoc {} key-name v))
                    ;; Also for THIS process: :save-global writes the file but
                    ;; does not touch the in-memory global map, so `:get` would
                    ;; keep returning the old value until a restart.
                    ((:set-override settings) key-name v)
                    (notify ctx (str key-name " = " (pr-str v)
                                     " (saved to " global-settings-path ")"))))))))))))

(defn- ^:async handle-oauth-login
  "Run OAuth flow for a provider. Returns true on success."
  [provider oauth-cfg ctx agent]
  (if-not (and ctx (.-ui ctx) (.-input (.-ui ctx)))
    (notify ctx "OAuth login requires interactive mode" "error")
    (do
      (notify ctx (str "Starting OAuth login for " (or (:name oauth-cfg) provider) "..."))
      (try
        (let [ui-callbacks #js {:notify (fn [msg] (notify ctx msg))
                                :input  (fn [prompt placeholder]
                                          (.input (.-ui ctx) prompt (or placeholder "")))}
              creds (js-await ((:login oauth-cfg) ui-callbacks))]
          (when creds
            (notify ctx (str "Logged in to " provider " via OAuth"))
            ;; Re-resolve the model now that credentials are available
            (when-not (.-model (:config agent))
              (try
                (let [;; Use first model from provider's registered models
                      p-info (when-let [reg (:provider-registry agent)]
                               ((:get reg) provider))
                      model-id (or (when-let [models (:models p-info)]
                                     (:id (first models)))
                                   (str provider "/default"))
                      model ((:resolve (:provider-registry agent)) provider model-id)]
                  (set! (.-model (:config agent)) model)
                  ;; Mirror cli.cljs / extensions.cljs setModel: persist
                  ;; the user-friendly provider label so the status line
                  ;; reflects the post-OAuth model immediately.
                  (aset (:config agent) "active-provider-name" (or provider ""))
                  (notify ctx (str "Model resolved: " model-id)))
                (catch :default e
                  (notify ctx (str "Model resolution failed: " (.-message e)) "error"))))))
        (catch :default e
          (notify ctx (str "OAuth login failed: " (.-message e)) "error"))))))

(defn registered-providers
  "Sorted names of every provider in the agent's registry."
  [agent]
  (vec (sort (keys (or (when-let [reg (:provider-registry agent)] ((:list reg))) {})))))

(defn unknown-provider-message
  "Refusal text for a provider nobody registered, or nil when it is known.

   `/login gogle` used to prompt for a key and save it under \"gogle\": a
   credential for a provider that does not exist, with no error at any point.
   The next run looked up the REAL provider's (still missing) key and said
   \"No credentials found\", so the typo was invisible from both ends. Naming
   the registered set is what tells a typo apart from an extension that failed
   to load — they need opposite fixes."
  [provider known]
  (when-not (contains? (set known) provider)
    (str "Unknown provider '" provider "'. Registered: " (str/join ", " known))))

(defn- ^:async handle-key-login
  "Prompt for an API key and save it to ~/.nyma/credentials.json, 0600."
  [provider ctx]
  (let [cred-path (creds-util/credentials-path)]
    (if-not (and ctx (.-ui ctx) (.-input (.-ui ctx)))
      (notify ctx "Login requires interactive mode" "error")
      (let [key (js-await (.input (.-ui ctx) (str "API key for " provider ":") "sk-..."))]
        (when (and key (seq (.trim key)))
          (let [{:keys [ok error]} (creds-util/read-for-write)]
            (if error
              ;; Never rewrite a file we could not read: "unparseable" and
              ;; "empty" look the same to a reader, and treating them the same
              ;; here would drop every other provider's key.
              (notify ctx error "error")
              (let [dir (str (.. js/process -env -HOME) "/.nyma")]
                (aset ok provider (.trim key))
                (when-not (fs/existsSync dir)
                  (fs/mkdirSync dir #js {:recursive true :mode 0700}))
                (fs/writeFileSync cred-path (js/JSON.stringify ok nil 2)
                                  #js {:encoding "utf8" :mode creds-util/private-mode})
                ;; `:mode` is create-only, so an existing 0644 file keeps its
                ;; mode through the write — this is the half that fixes the
                ;; ones already on disk.
                (creds-util/harden! cred-path)
                (notify ctx (str "Saved " provider " API key"))))))))))

(defn ^:async handle-login
  "Handle /login command.
   /login [provider]         — prompt for API key
   /login oauth [provider]   — trigger OAuth flow"
  [args ctx agent]
  (let [is-oauth  (= (first args) "oauth")
        provider  (if is-oauth
                    (or (second args) "anthropic")
                    (or (first args) "anthropic"))
        p-config  (when-let [reg (:provider-registry agent)]
                    ((:get reg) provider))
        oauth-cfg (when p-config (:oauth p-config))
        ;; p-config was already computed here and consulted only on the OAuth
        ;; branch; the key branch never looked at it.
        unknown   (unknown-provider-message provider (registered-providers agent))]
    (cond
      unknown  (notify ctx unknown "error")
      is-oauth (if-not oauth-cfg
                 (notify ctx (str "Provider '" provider "' does not support OAuth") "error")
                 (handle-oauth-login provider oauth-cfg ctx agent))
      :else    (handle-key-login provider ctx))))

;;; ─── Registration ───────────────────────────────────────────

(defn extensions-report
  "The `/extensions` listing: loaded, disabled in settings, failed."
  [resources extensions-atom]
  (let [loaded   (or (when extensions-atom @extensions-atom) [])
        errs     @handler-errors
        failed   @last-load-failures
        disabled @last-disabled
        merged   (when-let [sm (:settings resources)]
                   (when (fn? (:get sm)) ((:get sm))))
        ;; An extension that loaded but whose feature is switched off: its
        ;; manifest declares `<section>.enabled` and the merged settings say
        ;; false. Five shipped off-by-default with no way to learn they
        ;; existed or how to turn them on.
        off-hint (fn [{:keys [namespace manifest]}]
                   (when-let [decl (and manifest (.-settings manifest))]
                     (some (fn [section]
                             (let [keys (aget decl section)]
                               (when (and keys (some? (aget keys "enabled")))
                                 (let [v (get-in merged [section "enabled"])]
                                   (when-not v
                                     (str " — off: set " section ".enabled: true"
                                          " in settings, or --ext-" namespace))))))
                           (js/Object.keys decl))))
        row      (fn [{:keys [namespace type] :as ext}]
                   (str "  " namespace " (" (str type) ")"
                        (off-hint ext)
                        (when-let [n (get errs namespace)]
                          (str " — " n " handler error" (when (> n 1) "s")))))]
    (str "Extensions (" (count loaded) " loaded"
         (when (seq disabled) (str ", " (count disabled) " disabled"))
         (when (seq failed) (str ", " (count failed) " failed")) ")\n"
         (str/join "\n" (map row (sort-by :namespace loaded)))
         (when (seq disabled)
           (str "\n\nDisabled:\n"
                (str/join "\n" (map (fn [ns] (str "  " ns " — disabled in settings (/extensions enable " ns ")"))
                                    (sort disabled)))))
         (when (seq failed)
           (str "\n\nFailed to load:\n"
                (str/join "\n" (map (fn [[ns r]] (str "  " ns " — " r))
                                    (sort-by first failed)))))
         "\n\nErrors are also in ~/.nyma/debug.log (NYMA_DEBUG=1 or --debug for more).")))

(defn toggle-extension!
  "`/extensions disable|enable <ns> [--project]`: read-modify-write the
   `extensions` section of the chosen settings file. The saves are shallow
   at the top level, so the section is rebuilt from that file alone — the
   other scope's entries stay where they are. Takes effect on /reload."
  [resources extensions-atom sub ns project? ctx]
  (let [sm     (:settings resources)
        known  (set (concat (map :namespace (or (when extensions-atom @extensions-atom) []))
                            (keys @last-load-failures)
                            @last-disabled))]
    (cond
      (not (and sm (fn? (:save-global sm))))
      (notify ctx "Settings are not available here" "error")

      (or (nil? ns) (not (contains? known ns)))
      (notify ctx (str (if ns (str "Unknown extension " ns ". ") "Which extension? ")
                       "Known: " (str/join ", " (sort known)))
              "error")

      :else
      (let [scope   (if project? :project :global)
            section (or (get ((:user-settings sm) scope) "extensions") {})
            ;; `enable` removes the key rather than writing true, so the
            ;; other scope's verdict (and the default) applies again.
            section (if (= sub "disable")
                      (assoc section ns false)
                      (dissoc section ns))
            save    (if project? (:save-project sm) (:save-global sm))]
        (save {"extensions" section})
        ;; A global enable removes the global key, but the merge is per key
        ;; and the project file wins — so if .nyma/settings.json also says
        ;; false, nothing changed and the receipt must not claim it did.
        (if (and (= sub "enable") (not project?)
                 (false? (get-in ((:get sm)) [:extensions ns])))
          (notify ctx (str "Enabled " ns " (global), but .nyma/settings.json still disables it"
                           " — run /extensions enable " ns " --project")
                  "warning")
          (notify ctx (str (if (= sub "disable") "Disabled " "Enabled ") ns
                           " (" (name scope) "). /reload to apply.")))))))

(defn- replay-session!
  "Switch the session manager to `file-path` and reseed the store from it.

   Replay is not a new turn, so JSONL re-append is suppressed for its
   duration; try/finally so a throw mid-replay cannot leave the flag stuck
   true (which would silently disable all future persistence). Seeds via
   the shared transform so tool_call/tool_result/compaction are handled
   identically to the startup resume path."
  [agent file-path]
  (let [sm @(:session agent)]
    ((:switch-file sm) file-path)
    (swap! (:state agent) assoc :replaying-session? true)
    (try
      ((:dispatch! (:store agent)) :messages-cleared {})
      (doseq [msg (session->seed-messages ((:build-context sm)))]
        ((:dispatch! (:store agent)) :message-added {:message msg}))
      (finally
        (swap! (:state agent) assoc :replaying-session? false)))
    ((:emit (:events agent)) "session_start"
                             {:reason "resume" :previousSessionFile file-path})))

(defn register-builtins
  "Register every built-in slash command on the agent; `/help` lists them."
  [agent session resources & [extensions-atom resolve-flags-fn]]
  (swap! (:commands agent) merge
         {"help"
          {:description "Show available commands, grouped. /help <command> for one"
           :aliases     ["?"]
           :handler (fn [args ctx]
                      (let [cmds @(:commands agent)
                            argv (let [p (positional-args ctx)]
                                   (if (some? p) p (vec args)))]
                        (if (seq argv)
                          (show-info ctx (help-for-command cmds (first argv)))
                          (show-info ctx (help-sections cmds)))))}

          "model"
          {:group       :model
           :description "Pick a model (no args), switch to one, or `list` them all"
           :handler (fn [args ctx]
                      (cond
                        ;; `/model list` — full catalogue as scrollable text
                        (= (first args) "list")
                        (show-info ctx (model-catalogue-text agent))

                        ;; `/model <provider/id>` — direct switch (unchanged)
                        (seq args)
                        (let [model-spec (str/join " " args)]
                          (switch-model! agent ctx model-spec))

                        ;; Bare `/model` — fuzzy picker over the catalogue.
                        ;; This used to pass :overlay explicitly to sit at the
                        ;; bottom; the shared default is bottom-anchored now, so
                        ;; the override would only restate it.
                        :else
                        (let [models (current-models agent)]
                          (if (and (ui-selectable? ctx) (seq models))
                            (-> (.select (.-ui ctx)
                                         (str "Model — current: " (current-model-id agent))
                                         (clj->js (mapv model->item models)))
                                (.then (fn [chosen]
                                         (when chosen
                                           (switch-model! agent ctx (.-value chosen))))))
                            (notify ctx (str "Model: " (current-model-id agent)))))))}

          "clear"
          {:group       :session
           :description "Reset the context in THIS session file — the transcript and the model's context, nothing on disk"
           :handler (fn [_args ctx]
                      ((:dispatch! (:store agent)) :messages-cleared {})
                      ((:emit (:events agent)) "session_clear" {})
                      (notify ctx "Messages cleared"))}

          "cls"
     ;; Borrowed from cc-kit's clearCommand at packages/ui/src/commands/
     ;; builtins.ts:28-36 — /clear is nyma's 'reset the session', and
     ;; users sometimes want to ALSO wipe the terminal scrollback. cls
     ;; issues the same ANSI escape sequence (CSI 2J + CSI 3J + CSI H)
     ;; as cc-kit, which clears both the visible screen AND the
     ;; scrollback on terminals that support the CSI 3J extension.
          {:group       :session
           :description "Clear the terminal screen and scrollback only — the conversation is untouched"
           :handler (fn [_args _ctx]
                      (.write (.-stdout js/process) "\u001b[2J\u001b[3J\u001b[H"))}

          "exit"
          {:description "Exit the agent"
           :aliases     ["quit" "q"]
           :handler (fn [_args _ctx]
                      ;; `/exit` used to call process.exit itself. That runs the
                      ;; synchronous `exit` handler and nothing else, so MCP
                      ;; sockets and LSP clients were killed rather than closed
                      ;; and any extension awaiting cleanup never finished. The
                      ;; bus event routes it into cli's async shutdown — the
                      ;; same one SIGINT takes — so session_shutdown fires once.
                      (let [events (:events agent)]
                        (if (pos? ((:handler-count events) "exit"))
                          ((:emit events) "exit" {:reason "exit"})
                          ;; No cli handler (sdk mode, a test harness): the old
                          ;; behaviour, rather than a /exit that does nothing.
                          (do ((:emit events) "session_shutdown" {:reason "exit"})
                              (js/process.exit 0)))))}

          "theme"
          {:description "Switch the color theme, effective immediately. Usage: /theme [name]"
           :handler
           (fn [args ctx]
             (let [themes (:themes resources)
                   names  (theme-catalog/all-theme-names themes)
                   say    (fn [m l] (when-let [ui (.-ui ctx)] (when (.-notify ui) (.notify ui m l))))
                   apply! (fn [name]
                            (theme-catalog/apply-theme! name themes default-dark)
                            (say (str "Theme: " name) "info"))]
               (cond
                 (seq args)
                 (let [name (str (first args))]
                   (if (some #(= % name) names)
                     (apply! name)
                     (say (str "Unknown theme '" name "'. Available: " (clojure.string/join ", " names)) "error")))

                 (and (.-ui ctx) (.-select (.-ui ctx)))
                 (-> (.select (.-ui ctx) "Theme:" (clj->js (vec names)))
                     (.then (fn [choice] (when choice (apply! (str choice))))))

                 :else
                 (say (str "Themes: " (clojure.string/join ", " names) "\nUsage: /theme <name>") "info"))))}

          "bash"
          ;; Discovery wrapper over editor bash mode — the `/bash ls`
          ;; picker entry is identical to typing `!ls`. Runs the full
          ;; bash_suite middleware chain via editor-bash/run-bash! and
          ;; appends a :kind :bash message so the chat view renders it
          ;; through the same styled component.
          {:description "Run a shell command through bash_suite (alias: !cmd in the editor)"
           :handler (fn [args ctx]
                      (let [command (str/join " " args)
                            append  (aget ctx "append-message")]
                        (cond
                          (empty? command)
                          (notify ctx "Usage: /bash <command> — or type !cmd directly in the editor" "info")

                          (nil? append)
                          ;; No message sink available (headless ctx) —
                          ;; still run the command, but fall back to a
                          ;; notification for the first line of output.
                          (-> (editor-bash/run-bash! agent command)
                              (.then (fn [result]
                                       (notify ctx
                                               (editor-bash/format-bash-output result)
                                               (if (:blocked? result) "error" "info")))))

                          :else
                          (-> (editor-bash/run-bash! agent command)
                              (.then
                               (fn [result]
                                 (let [content (editor-bash/format-bash-output result)]
                                   (append {:role      "assistant"
                                            :kind      :bash
                                            :content   content
                                            :command   command
                                            :stdout    (:stdout result)
                                            :stderr    (:stderr result)
                                            :exit-code (:exit-code result)
                                            :blocked?  (:blocked? result)
                                            :reason    (:reason result)}))))))))}

          "bb"
          ;; Discovery wrapper over editor eval mode — `/bb (+ 1 2)`
          ;; is identical to typing `$(+ 1 2)`. Uses the exact same
          ;; code path: run-eval! + format-eval-output + :kind :eval
          ;; message append. See agent.ui.editor-eval for the
          ;; documented security gap.
          {:description "Evaluate a Babashka expression (alias: $expr in the editor)"
           :handler (fn [args ctx]
                      (let [expr   (str/join " " args)
                            append (aget ctx "append-message")]
                        (cond
                          (empty? expr)
                          (notify ctx "Usage: /bb <expr> — or type $expr directly in the editor" "info")

                          (nil? append)
                          ;; Headless ctx — run anyway and surface
                          ;; the first line via notify.
                          (-> (editor-eval/run-eval! expr)
                              (.then (fn [result]
                                       (notify ctx
                                               (editor-eval/format-eval-output result)
                                               (if (:unavailable? result) "error" "info")))))

                          :else
                          (-> (editor-eval/run-eval! expr)
                              (.then
                               (fn [result]
                                 (let [content (editor-eval/format-eval-output result)]
                                   (append {:role         "assistant"
                                            :kind         :eval
                                            :content      content
                                            :expr         expr
                                            :stdout       (:stdout result)
                                            :stderr       (:stderr result)
                                            :exit-code    (:exit-code result)
                                            :unavailable? (:unavailable? result)
                                            :install-hint (:install-hint result)}))))))))}

          "new"
          {:group       :session
           :description "Start a FRESH session file — the old one stays on disk and is resumable"
           :handler (fn [_args ctx]
                      ((:dispatch! (:store agent)) :messages-cleared {})
                      ((:emit (:events agent)) "session_start" {:reason "new"})
                      (notify ctx "New session started"))}

          "fork"
          {:group       :session
           :description "Fork conversation at current point"
           :handler (fn [_args ctx]
                      (when-let [s @(:session agent)]
                        (let [leaf ((:leaf-id s))]
                          ((:emit (:events agent)) "session_before_fork" {:leaf-id leaf})
                          ((:branch s) leaf)
                          ((:emit (:events agent)) "session_start" {:reason "fork"})
                          (notify ctx "Session forked"))))}

          "tree"
          {:group       :session
           :description "Show session tree"
           :handler (fn [_args ctx]
                      (when-let [s @(:session agent)]
                        (if (and ctx (.-ui ctx) (.-custom (.-ui ctx)))
                     ;; Interactive tree viewer via custom overlay
                          (.custom (.-ui ctx) (create-tree-viewer s))
                     ;; Fallback: text dump
                          (let [tree ((:get-tree s))
                                lines (->> (take 20 tree)
                                           (map (fn [e]
                                                  (str "  " (:id e) " [" (:role e) "] "
                                                       (subs (or (:content e) "") 0
                                                             (min 60 (count (or (:content e) ""))))))))]
                            (show-info ctx
                                       (str "Session tree (" (count tree) " entries):\n"
                                            (str/join "\n" lines)
                                            (when (> (count tree) 20) "\n  ... more entries")))))))}

          "compact"
          {:group       :session
           :description "Compact conversation context now, and say by how much"
           :handler (fn [_args ctx]
                      ;; Returned, so the dispatcher's busy state and .catch
                      ;; cover it: this used to fire "Compaction complete"
                      ;; before compaction ran, and without :force? a manual
                      ;; /compact below the auto threshold did nothing at all.
                      ;; The receipt comes from what `compact` returned, not
                      ;; from re-estimating the live messages: a summary longer
                      ;; than the two turns it replaced read as "nothing".
                      (when-let [s @(:session agent)]
                        (-> (compact s (:model (:config agent)) (:events agent)
                                     ;; settings->opts first: compaction.strategy (and the
                                     ;; thresholds) apply to a manual /compact the same as
                                     ;; to the automatic one, or one session compacts two
                                     ;; ways depending on who triggered it.
                                     (merge (settings->opts (resolve-settings (:settings agent)))
                                            {:model-registry (:model-registry agent)
                                             :state-atom     (:state agent)
                                             :force?         true
                                             :model-key      (model-info/config-model-key (:config agent))}))
                            (.then (fn [result] (notify ctx (compaction-receipt result)))))))}

          "debug"
          {:description "Show debug information"
           :handler (fn [_args ctx]
                      (let [s @(:state agent)]
                        (show-info ctx
                                   (str "--- Debug Info ---\n"
                                        "Messages: " (count (:messages s)) "\n"
                                        "Input tokens: " (or (:total-input-tokens s) 0) "\n"
                                        "Output tokens: " (or (:total-output-tokens s) 0) "\n"
                                        "Cost: $" (.toFixed (or (:total-cost s) 0) 4) "\n"
                                        "Turns: " (or (:turn-count s) 0) "\n"
                                        "Active tools: " (count (:active-tools s)) "\n"
                                        "Active executions: " (count (:active-executions s)) "\n"
                                        "Thinking level: " @(:thinking-level agent) "\n"
                                        "------------------"))))}

          "reload"
          {:description "Reload extensions and configuration; /reload <ns> reloads one extension only"
           :handler (fn [args ctx]
                      (let [argv (let [p (positional-args ctx)] (if (some? p) p (vec args)))
                            ns   (first argv)]
                        (if (and (seq (str ns)) extensions-atom)
                          (handle-reload-one agent extensions-atom (str ns) ctx)
                          (handle-reload agent resources extensions-atom resolve-flags-fn ctx))))}

          "eval"
          {:description "Dev: evaluate a ClojureScript form against the live agent (js/globalThis.__nyma). Needs NYMA_DEV=1 or settings dev.eval"
           :handler (fn [args ctx]
                      (let [settings (when-let [s (:settings resources)] (when (fn? (:get s)) ((:get s))))
                            allowed  (or (= "1" (str (or (aget js/process.env "NYMA_DEV") "")))
                                         (boolean (get-in settings [:dev :eval])))
                            form     (str/join " " (let [p (positional-args ctx)] (if (some? p) p (vec args))))]
                        (cond
                          (not allowed)
                          (notify ctx "/eval is a dev tool: set NYMA_DEV=1 or {\"dev\": {\"eval\": true}} to enable" "warning")

                          (str/blank? form)
                          (notify ctx "Usage: /eval <form> — e.g. /eval (count (:messages @(:state js/globalThis.__nyma)))" "info")

                          :else
                          (do (aset js/globalThis "__nyma" agent)
                              (-> (eval-expr! form)
                                  (.then (fn [v] (notify ctx (str "=> " (format-eval-value v)) "info")))
                                  (.catch (fn [e] (notify ctx (str "eval error: " (.-message e)) "error"))))))))}

          "replay"
          {:description "Dev: show the event-sourced store's log tail; /replay <n> for the last n events (default 20)"
           :handler (fn [args ctx]
                      (let [argv  (let [p (positional-args ctx)] (if (some? p) p (vec args)))
                            n     (let [x (js/parseInt (str (first argv)))] (if (js/isNaN x) 20 (max 1 x)))
                            store (:store agent)
                            hist  (if (and store (:history store)) ((:history store)) [])]
                        (if (empty? hist)
                          (notify ctx "No events recorded in this session" "info")
                          (let [tail   (take-last n hist)
                                counts (frequencies (map #(str (:type %)) hist))
                                t0     (or (:timestamp (first hist)) 0)]
                            (notify ctx
                                    (str "Event log: " (count hist) " events (ring-capped)\n"
                                         (str/join "  " (map (fn [[t c]] (str t "×" c)) (sort-by second > counts)))
                                         "\n\nLast " (count tail) ":\n"
                                         (str/join "\n"
                                                   (map (fn [e]
                                                          (str "  +" (js/Math.round (/ (- (or (:timestamp e) t0) t0) 1000)) "s  "
                                                               (:type e)
                                                               (when-let [d (:data e)]
                                                                 (let [s (try (js/JSON.stringify (clj->js d)) (catch :default _ (str d)))]
                                                                   (str "  " (subs (str s) 0 (min 80 (count (str s)))))))))
                                                        tail)))
                                    "info")))))}

          "extensions"
          {:description "List extensions; /extensions disable|enable <ns> [--project] switches one off or on in settings"
           :handler (fn [args ctx]
                      (let [argv     (let [p (positional-args ctx)] (if (some? p) p (vec args)))
                            ;; A keybinding or alias hands the raw vector with
                            ;; the flag still in it.
                            project? (boolean (or (get (command-flags ctx) "project")
                                                  (some #{"--project"} argv)))
                            argv     (vec (remove #{"--project"} argv))
                            sub      (first argv)]
                        (if (contains? #{"disable" "enable"} sub)
                          (toggle-extension! resources extensions-atom sub (second argv) project? ctx)
                          (show-info ctx (extensions-report resources extensions-atom)))))}

          "name"
          {:group       :session
           :description "Set or show the session display name. Quote it: /name \"Q3 planning\""
           :handler (fn [args ctx]
                      ;; Migrated to the shared splitter: `/name "Q3 planning"`
                      ;; arrives as ONE token, and a stray `--flag` no longer
                      ;; lands inside the session's display name.
                      (let [argv (let [p (positional-args ctx)] (if (some? p) p (vec args)))]
                        (if (seq argv)
                          (let [nm (str/join " " argv)]
                            (when-let [s @(:session agent)]
                              ((:set-session-name s) nm))
                            (notify ctx (str "Session named: " nm)))
                          (let [current (when-let [s @(:session agent)]
                                          ((:get-session-name s)))]
                            (notify ctx (str "Session: " (or current "(unnamed)")))))))}

          "session"
          {:group       :session
           :description "Show session info and stats"
           :handler (fn [_args ctx]
                      (let [s    @(:state agent)
                            sess @(:session agent)
                            fp   (when sess ((:get-file-path sess)))
                            tree (when sess ((:get-tree sess)))
                            name (when sess ((:get-session-name sess)))]
                        (show-info ctx
                                   (str "--- Session Info ---\n"
                                        "Name: " (or name "(unnamed)") "\n"
                                        "Path: " (or fp "(ephemeral)") "\n"
                                        "Entries: " (count (or tree [])) "\n"
                                        "Messages: " (count (:messages s)) "\n"
                                        "Input tokens: " (or (:total-input-tokens s) 0) "\n"
                                        "Output tokens: " (or (:total-output-tokens s) 0) "\n"
                                        "Cost: $" (.toFixed (or (:total-cost s) 0) 4) "\n"
                                        "Turns: " (or (:turn-count s) 0) "\n"
                                        "--------------------"))))}

          "copy"
          {:description "Copy last assistant message to clipboard"
           :handler (fn [_args ctx]
                      (let [msgs     (:messages @(:state agent))
                            last-asst (last (filter #(= (:role %) "assistant") msgs))]
                        (if-not last-asst
                          (notify ctx "No assistant message to copy" "error")
                          (let [text     (share/content->text (:content last-asst))
                                platform (.-platform js/process)
                                cmd      (case platform
                                           "darwin" "pbcopy"
                                           "win32"  "clip"
                                           "xclip -selection clipboard")
                                proc     (js/Bun.spawn #js ["sh" "-c" cmd]
                                                       #js {:stdin "pipe" :stdout "pipe" :stderr "pipe"})]
                            (.write (.-stdin proc) text)
                            (.end (.-stdin proc))
                            (.then (.-exited proc)
                                   (fn [code]
                                     (if (zero? code)
                                       (notify ctx "Copied to clipboard")
                                       (notify ctx (str "Clipboard command failed (" cmd
                                                        " exited " code ")") "error"))))))))}

          "hotkeys"
          {:description "Show the keyboard shortcuts that are actually bound"
           :handler (fn [_args ctx]
                      ;; Generated, not hardcoded. The list this replaced
                      ;; advertised Ctrl+L as "Show current model" and Ctrl+P as
                      ;; "Reserved" — neither was bound to anything — and said
                      ;; nothing about Ctrl-C, Enter or extension shortcuts.
                      (show-info ctx (kbr/hotkeys-text
                                      @(:keybinding-registry agent)
                                      @(:shortcuts agent))))}

          "export"
          {:group       :session
           :description "Export session to file (html, md, jsonl)"
           :handler (fn [args ctx]
                      (let [format  (or (first args) "html")
                            sess    @(:session agent)
                            msgs    (or (when sess ((:build-context sess)))
                                        (:messages @(:state agent)))
                            name    (or (when sess ((:get-session-name sess))) "session")
                            ts      (js/Date.now)
                            ext     (case format "md" "md" "jsonl" "jsonl" "html")
                            out-dir (str (js/process.cwd) "/.nyma/exports")
                            out-path (str out-dir "/" name "-" ts "." ext)
                            content (case format
                                      "md"    (messages->markdown msgs name)
                                      "jsonl" (str/join "\n"
                                                        (map #(js/JSON.stringify (clj->js %)) msgs))
                                      (messages->html msgs name))]
                        (when-not (fs/existsSync out-dir)
                          (fs/mkdirSync out-dir #js {:recursive true}))
                        (.then (js/Bun.write out-path content)
                               (fn [_] (notify ctx (str "Exported to " out-path))))))}

          "resume"
          {:group       :session
           :description "Resume a previous session"
           :handler (fn [_args ctx]
                      (let [dir      (str (.. js/process -env -HOME) "/.nyma/sessions")
                            all      (list-sessions dir)
                            ;; Same scoping as `nyma -r`: this project first,
                            ;; falling back to everything when none match so the
                            ;; command never shows an empty list.
                            scoped   (scope-to-project all (js/process.cwd))
                            mine     (first scoped)
                            other    (second scoped)
                            sessions (if (seq mine) mine all)
                            hidden   (if (seq mine) (count other) 0)]
                        (if (empty? sessions)
                          (notify ctx "No sessions found" "error")
                          (if-not (and ctx (.-ui ctx) (.-select (.-ui ctx)))
                       ;; Non-interactive: just list sessions
                            (show-info ctx
                                       (str "Available sessions:\n"
                                            (str/join "\n"
                                                      (map-indexed (fn [i s]
                                                                     (str "  " (inc i) ". "
                                                                          (format-row s 76)))
                                                                   sessions))))
                       ;; Interactive: show selector
                            (let [options (mapv (fn [s] (format-row s 76)) sessions)]
                              (.then (.select (.-ui ctx) "Resume session:" (clj->js options))
                                     (fn [choice]
                                       (when choice
                                         (let [idx  (.indexOf options choice)
                                               sess (nth sessions idx)]
                                           (replay-session! agent (:path sess))
                                           (notify ctx (str "Resumed: " (:name sess))))))))))))}

          "import"
          {:group       :session
           :description "Import and resume a session from a JSONL file"
           :handler (fn [args ctx]
                      (let [file-path (first args)]
                        (cond
                          (or (nil? file-path) (empty? (str file-path)))
                          (notify ctx "Usage: /import <path.jsonl>" "error")

                          (not (fs/existsSync file-path))
                          (notify ctx (str "File not found: " file-path) "error")

                          :else
                          (do (replay-session! agent file-path)
                              (notify ctx (str "Imported session from " file-path))))))}

          "settings"
          {:description "View or change settings"
           :handler (fn [_args ctx]
                      (handle-settings resources ctx))}

          "changelog"
          {:description "Show changelog"
           :handler (fn [_args ctx]
                      (let [path "CHANGELOG.md"]
                        (if (fs/existsSync path)
                          (show-info ctx (fs/readFileSync path "utf8"))
                          (notify ctx "No CHANGELOG.md found" "error"))))}

          "thinking"
          {:group       :model
           :description (str "Set extended thinking level: /thinking <"
                             (str/join "|" thinking/levels) ">")
           :handler (fn [args ctx]
                      (let [level (some-> (first args) str/trim str/lower-case)
                            cur   @(:thinking-level agent)]
                        (cond
                          (nil? level)
                          (notify ctx (str "Thinking level: " cur
                                           "  (set with /thinking <"
                                           (str/join "|" thinking/levels) ">)"))

                          (not (thinking/valid-level? level))
                          (notify ctx (str "Invalid thinking level: " level
                                           ". Valid: " (str/join ", " thinking/levels))
                                  "error")

                          :else
                          (do (reset! (:thinking-level agent) level)
                              (notify ctx
                                      (if (= "off" level)
                                        "Thinking off — no reasoning parameter will be sent"
                                        (str "Thinking level: " level)))))))}

          "login"
          {:group       :model
           :description "Login: /login [provider] for API key, /login oauth [provider] for OAuth"
           :handler (fn [args ctx]
                      (handle-login args ctx agent))}

          "logout"
          {:group       :model
           :description "Remove credentials for a provider. Usage: /logout <provider>"
           :handler (fn [args ctx]
                      ;; Bare `/logout` used to default to anthropic and delete
                      ;; a credential the user never named — a destructive
                      ;; default for a command whose only job is destruction.
                      (let [provider (first args)]
                        (cond
                          (not (seq (str (or provider ""))))
                          (notify ctx (str "Usage: /logout <provider>\nRegistered: "
                                           (str/join ", " (registered-providers agent)))
                                  "error")

                          :else
                          (let [oauth-creds (oauth/load-credentials provider)
                                cred-path   (creds-util/credentials-path)]
                            (if oauth-creds
                              (do (oauth/clear-credentials provider)
                                  (notify ctx (str "Removed OAuth credentials for " provider)))
                              (if-not (and cred-path (fs/existsSync cred-path))
                                (notify ctx "No credentials found" "error")
                                (let [{:keys [ok error]} (creds-util/read-for-write)]
                                  (if error
                                    ;; Rewriting a file we could not parse would
                                    ;; remove every key, not the one asked for.
                                    (notify ctx error "error")
                                    (do
                                      (js-delete ok provider)
                                      (fs/writeFileSync cred-path (js/JSON.stringify ok nil 2)
                                                        #js {:encoding "utf8"
                                                             :mode creds-util/private-mode})
                                      (creds-util/harden! cred-path)
                                      (notify ctx (str "Removed " provider " API key")))))))))))}

          "scoped-models"
          {:group       :model
           :description "Show or set per-extension model overrides"
           :handler (fn [args ctx]
                      (let [settings (:settings resources)
                            current  (when settings ((:get settings)))
                            scoped   (or (:scoped-models current) {})]
                        (if (>= (count args) 2)
                     ;; Set: /scoped-models ext-name model-id
                          (let [ext-name (first args)
                                model-id (str/join " " (rest args))
                                updated  (assoc scoped ext-name model-id)]
                            ((:set-override settings) :scoped-models updated)
                            (notify ctx (str ext-name " → " model-id)))
                     ;; Show all
                          (if (empty? scoped)
                            (notify ctx "No scoped model overrides set")
                            (show-info ctx
                                       (str "Scoped models:\n"
                                            (str/join "\n"
                                                      (map (fn [[k v]] (str "  " k " → " v)) scoped))))))))}

          "new-extension"
          {:description "Create a new extension from template. Usage: /new-extension <name>"
           :handler (fn [args ctx]
                      ;; Bare `/new-extension` used to scaffold a file literally
                      ;; called my-extension.cljs in ~/.nyma/extensions, which
                      ;; then loaded on every launch. A missing argument is a
                      ;; usage error, not a name.
                      (if-not (seq (str (or (first args) "")))
                        (notify ctx "Usage: /new-extension <name>" "error")
                        (let [ext-name (first args)
                              ext-dir  (str (.. js/process -env -HOME) "/.nyma/extensions")
                              file     (str ext-dir "/" ext-name ".cljs")]
                          (if (fs/existsSync file)
                            (notify ctx (str "Extension already exists: " file) "error")
                            (do
                              (when-not (fs/existsSync ext-dir)
                                (fs/mkdirSync ext-dir #js {:recursive true}))
                              (fs/writeFileSync file (scaffold-extension ext-name))
                              (notify ctx (str "Created extension: " file)))))))}

          "skill"
          {:description "Activate a skill by name. Usage: /skill <name> [args] (also /skill:<name>)"
           :handler
           (fn [args ctx]
             (let [skill-name (first args)
                   skill-args (vec (rest args))
                   all-skills (:skills resources)]
               (cond
                 (empty? skill-name)
                 (notify ctx "Usage: /skill <name>  or  /skills to browse" "error")

                 (not (get all-skills skill-name))
                 (notify ctx (str "Unknown skill: \"" skill-name "\"."
                                  (or (skills/alias-hint all-skills skill-name) "")
                                  " Use /skills to see available.") "error")

                 (contains? (:active-skills @(:state agent)) skill-name)
                 (notify ctx (str "Skill \"" skill-name "\" is already active") "info")

                 :else
                 (-> (skills/activate-skill all-skills skill-name agent skill-args)
                     (.then (fn [_]
                              (notify ctx (str "Skill \"" skill-name "\" activated"))))
                     (.catch (fn [e]
                               (notify ctx (str "Failed to activate skill: " (.-message e)) "error")))))))}

          "skills"
          {:description "Browse and activate available skills (`/skills doctor` for what they cost)"
           :handler
           (fn [args ctx]
             (let [all-skills (:skills resources)
                   active     (:active-skills @(:state agent))
                   skill-list (mapv (fn [[sname skill]]
                                      {:name   sname
                                       :active (contains? active sname)
                                       :desc   (or (:description skill)
                                                   (skills/first-skill-line
                                                    (or (:body skill) (:markdown skill))))})
                                    all-skills)]
               (cond
                 ;; `/skills doctor` — what each skill costs on every request,
                 ;; what it is allowed to do, where it came from, how often it
                 ;; has actually fired, and any spec violation. The instrument
                 ;; for deciding which skills earn their tokens.
                 (= "doctor" (first args))
                 ;; Same budget the loader's "Available Skills" block applies,
                 ;; or the numbers this command exists to report are wrong for
                 ;; anyone who changed the setting.
                 (let [budget (when-let [st (:settings resources)]
                                (when (fn? (:get st))
                                  (:max-description-length (:skills ((:get st))))))
                       rows   (skills/doctor-rows all-skills budget)]
                   (if (empty? rows)
                     (notify ctx "No skills found.")
                     (notify ctx
                             (str "Skills — cost and trust\n"
                                  (str/join "\n"
                                            (map (fn [r]
                                                   (str (if (contains? active (:name r)) "● " "○ ")
                                                        (:name r)
                                                        "  [" (:scope r) "]"
                                                        "  ~" (:desc-tokens r) " tok/req"
                                                        (when (:truncated? r) " (truncated)")
                                                        (when (:hidden? r) "  hidden-from-model")
                                                        (when (pos? (:references r))
                                                          (str "  " (:references r) " refs"))
                                                        (when (:has-tools r)
                                                          (if (:tools-gated r) "  tools:BLOCKED" "  tools"))
                                                        (when (seq (:grants r))
                                                          (if (:grants-gated r)
                                                            "  grants:BLOCKED"
                                                            (str "  grants:" (str/join "," (:grants r)))))
                                                        "  used:" (:explicit r) "/" (:model r) "/" (:auto r)
                                                        (when (seq (:findings r))
                                                          (str "\n    ! " (str/join "\n    ! " (:findings r))))))
                                                 rows))
                                  "\n\nused: you/model/auto. [project] skills are instructions-only:"
                                  " their tools and tool grants are refused."))))

                 (empty? skill-list)
                 (notify ctx "No skills found. Place skill directories in ~/.nyma/skills/ or .nyma/skills/")

                 ;; Interactive picker via custom overlay (TUI mode)
                 (and ctx (.-ui ctx) (.-custom (.-ui ctx)))
                 (let [picker (skill-picker/create-picker
                               skill-list
                               (fn [chosen]
                                 (when chosen
                                   (-> (skills/activate-skill all-skills chosen agent)
                                       (.then (fn [_]
                                                (notify ctx (str "Skill \"" chosen "\" activated"))))
                                       (.catch (fn [e]
                                                 (notify ctx (str "Failed: " (.-message e)) "error")))))))]
                   (.custom (.-ui ctx) picker))

                 ;; Text fallback for gateway / non-TUI modes that don't
                 ;; expose a custom-overlay channel. Same data, different
                 ;; surface — user can /skill <name> from the listing.
                 :else
                 (let [body (->> skill-list
                                 (map (fn [{:keys [name active desc]}]
                                        (str (if active "● " "○ ")
                                             name
                                             (when (seq desc) (str " — " desc)))))
                                 (str/join "\n"))]
                   (notify ctx (str "Skills:\n" body
                                    "\n\nUse /skill <name> to activate."))))))}})
  ;; Templates last: extensions are already loaded when cli.cljs calls this,
  ;; so every name a template could collide with is in the map.
  (register-prompt-commands! agent (:prompts resources)))
