(ns agent.providers.oauth
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private auth-dir
  (path/join (.-HOME (.-env js/process)) ".nyma" "auth"))

(defn- ensure-dir! []
  (when-not (fs/existsSync auth-dir)
    (fs/mkdirSync auth-dir #js {:recursive true})))

(defn- creds-path [provider-name]
  (path/join auth-dir (str provider-name ".json")))

(defn save-credentials
  "Write OAuth credentials to ~/.nyma/auth/{provider}.json."
  [provider-name creds]
  (ensure-dir!)
  (fs/writeFileSync
    (creds-path provider-name)
    (js/JSON.stringify
      #js {"access"     (:access creds)
           "refresh"    (:refresh creds)
           "expires-at" (:expires-at creds)}
      nil 2)
    "utf8"))

(defn load-credentials
  "Read OAuth credentials from disk. Returns nil if missing."
  [provider-name]
  (let [p (creds-path provider-name)]
    (when (fs/existsSync p)
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
