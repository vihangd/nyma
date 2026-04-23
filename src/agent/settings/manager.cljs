(ns agent.settings.manager
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]
            [agent.debug :as d]
            [agent.utils.validation :as v]))

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
   :steering-mode  "one-at-a-time"
   :follow-up-mode "one-at-a-time"
   :transport              "auto"
   :tool-display           "collapsed"
   :tool-display-max-lines 500
   ;; Use the scrollback-based chat renderer: commit finalized messages
   ;; directly to terminal scrollback via Ink's writeToStdout and keep only
   ;; in-flight content in the dynamic region. Matches the Claude Code /
   ;; Codex / Aider architecture. Users can revert via
   ;;   "scrollbackMode": false
   ;; in ~/.nyma/settings.json during the transition period.
   ;; See src/agent/ui/scrollback.cljs.
   :scrollback-mode        true
   :status-line            {:preset "default"
                            :left-segments nil
                            :right-segments nil
                            :separator nil}
   :roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
           :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
           :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
           :plan    {:provider "anthropic" :model "claude-opus-4-20250514"
                     :allowed-tools ["read" "glob" "grep" "ls" "think" "web_search" "web_fetch"]
                     :permissions {"write" "deny" "edit" "deny" "bash" "deny"}}
           :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"
                     :allowed-tools ["read" "bash" "glob" "grep" "edit" "write"]}}})

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

(defn- load-json
  "Read, duplicate-key-scan, and parse a settings JSON file. Any
   duplicate-key warnings are sent through `d/warn` so they show up
   on stderr but don't block load. Returns nil when the file is
   missing or unparseable (the caller handles this case)."
  [file-path]
  (when (fs/existsSync file-path)
    (let [text   (fs/readFileSync file-path "utf8")
          issues (detect-duplicate-keys text)]
      (when (seq issues)
        (d/warn "settings" (str "Duplicate keys in " file-path)
                {:count (count issues)
                 :details (mapv v/format-issue issues)}))
      (try
        (js/JSON.parse text)
        (catch :default e
          (d/warn "settings" (str "Failed to parse " file-path ": " (.-message e)))
          nil)))))

(defn- save-json [file-path data]
  (let [dir (path/dirname file-path)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (fs/writeFileSync file-path (js/JSON.stringify (clj->js data) nil 2))))

(defn create-settings-manager
  "Two-scope settings: global + project. Project overrides global.
   Supports :reload to re-read files from disk without restarting.
   Accepts an optional opts map:
     :global-path  — override the global settings file path (default: ~/.nyma/settings.json)
     :project-path — override the project settings file path (default: .nyma/settings.json)"
  ([] (create-settings-manager {}))
  ([{:keys [global-path project-path]}]
   (let [global-path   (or global-path (path/join (os/homedir) ".nyma" "settings.json"))
         project-path  (or project-path ".nyma/settings.json")
         global-settings  (atom (load-json global-path))
         project-settings (atom (load-json project-path))
         overrides        (atom {})]

     {:get (fn []
             (merge defaults
                    (or @global-settings {})
                    (or @project-settings {})
                    @overrides))

      :set-override (fn [k v]
                      (swap! overrides assoc k v))

      :apply-overrides (fn [m]
                         (swap! overrides merge m))

      :reload (fn []
                (reset! global-settings (load-json global-path))
                (reset! project-settings (load-json project-path)))

      :save-global (fn [settings]
                     (save-json global-path
                                (merge @global-settings settings)))

      :save-project (fn [settings]
                      (save-json project-path
                                 (merge @project-settings settings)))

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
