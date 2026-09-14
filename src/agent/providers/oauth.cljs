(ns agent.providers.oauth
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.utils.credentials :as creds-util]))

(def ^:private auth-dir
  (path/join (.-HOME (.-env js/process)) ".nyma" "auth"))

(defn- ensure-dir! []
  (when-not (fs/existsSync auth-dir)
    (fs/mkdirSync auth-dir #js {:recursive true :mode 0700})))

(defn- creds-path [provider-name]
  (path/join auth-dir (str provider-name ".json")))

(defn save-credentials
  "Write OAuth credentials to ~/.nyma/auth/{provider}.json, owner-readable only."
  [provider-name creds]
  (ensure-dir!)
  (let [p (creds-path provider-name)]
    (fs/writeFileSync
     p
     (js/JSON.stringify
      #js {"access"     (:access creds)
           "refresh"    (:refresh creds)
           "expires-at" (:expires-at creds)}
      nil 2)
     ;; The encoding has to move INTO this map: passing `#js {:mode …}` as the
     ;; third argument in place of "utf8" silently drops it. And `:mode` only
     ;; applies on CREATE, so an already-loose file still needs the chmod.
     #js {:encoding "utf8" :mode creds-util/private-mode})
    (creds-util/harden! p)))

(defn load-credentials
  "Read OAuth credentials from disk. Returns nil if missing.

   Tightens the file's permissions on the way past — every token written
   before this landed on disk at whatever umask allowed, typically 0644."
  [provider-name]
  (let [p (creds-path provider-name)]
    (when (fs/existsSync p)
      (creds-util/harden! p)
      (try
        (let [raw (fs/readFileSync p "utf8")
              parsed (js/JSON.parse raw)]
          {:access     (aget parsed "access")
           :refresh    (aget parsed "refresh")
           :expires-at (aget parsed "expires-at")})
        (catch :default _e nil)))))

(defn clear-credentials
  "Delete stored credentials for a provider."
  [provider-name]
  (let [p (creds-path provider-name)]
    (when (fs/existsSync p)
      (fs/unlinkSync p))))

(defn needs-refresh?
  "Check if credentials need refreshing (expired or within 5-minute buffer)."
  [creds]
  (if-let [expires-at (:expires-at creds)]
    (< expires-at (+ (js/Date.now) 300000))
    false))

(defn ^:async generate-pkce
  "Generate PKCE code verifier and challenge for OAuth flows."
  []
  (let [array (js/Uint8Array. 32)
        _     (js/crypto.getRandomValues array)
        verifier (.replace
                  (.toString (js/Buffer.from array) "base64url")
                  #"[^a-zA-Z0-9_-]" "")
        encoder (js/TextEncoder.)
        data (.encode encoder verifier)
        hash (js-await (js/crypto.subtle.digest "SHA-256" data))
        challenge (.replace
                   (.toString (js/Buffer.from (js/Uint8Array. hash)) "base64url")
                   #"[^a-zA-Z0-9_-]" "")]
    {:verifier verifier :challenge challenge}))
