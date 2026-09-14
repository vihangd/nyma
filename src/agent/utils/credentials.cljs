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

(def private-mode
  "Owner read/write only — `0600`, which squint compiles to 384.

   API keys were written with the default mode, so umask decided: on the common
   022 they landed 0644, world-readable, in a file whose whole job is holding
   secrets. Every other agent, script and backup on the box could read them."
  0600)

(defn harden!
  "chmod `p` to 0600 when it is readable by group or other. Returns true if it
   changed something.

   The write-side `{:mode 0600}` only applies when the file is CREATED, so it
   does nothing for the credentials files already sitting at 0644 on every
   machine nyma has ever run on. Fixing those needs a read-side pass, which is
   why both halves exist."
  [p]
  (boolean
   (try
     (when (and p (fs/existsSync p))
       (let [mode (bit-and (.-mode (fs/statSync p)) 0777)]
         (when (pos? (bit-and mode 0077))
           (fs/chmodSync p private-mode)
           true)))
     (catch :default _ false))))

(defn read-all
  "Parsed credentials object, or nil when missing/unreadable/malformed.

   Tightens the file's permissions on the way past — see `harden!`."
  []
  (when-let [p (credentials-path)]
    (when (fs/existsSync p)
      (harden! p)
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
