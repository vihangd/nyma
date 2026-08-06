(ns agent.utils.credentials
  "Read API keys saved by `/login <provider>`.

   `handle-key-login` (agent.commands.builtins) writes `~/.nyma/credentials.json`
   as a flat `{\"<provider>\": \"<key>\"}` map, for ANY provider name. Every
   provider extension used to re-implement the reader privately, and the
   declarative path in `agent.providers.registry` didn't read the file at all —
   so `/login` silently did nothing for settings-declared providers."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn credentials-path
  "Absolute path to the credentials file, or nil when HOME is unset."
  []
  (when-let [home (.. js/process -env -HOME)]
    (path/join home ".nyma" "credentials.json")))

(defn read-all
  "Parsed credentials object, or nil when missing/unreadable/malformed."
  []
  (when-let [p (credentials-path)]
    (when (fs/existsSync p)
      (try
        (js/JSON.parse (fs/readFileSync p "utf8"))
        (catch :default _ nil)))))

(defn read-credential
  "Saved key for `provider-name`, or nil.

   `fallback-names` are additional keys to try in order — e.g. claude-native
   reads the `anthropic` entry, since that's what `/login anthropic` writes."
  [provider-name & fallback-names]
  (when-let [creds (read-all)]
    (some (fn [k]
            (let [v (when (seq (str k)) (aget creds (str k)))]
              (when (and (string? v) (seq v)) v)))
          (cons provider-name fallback-names))))
