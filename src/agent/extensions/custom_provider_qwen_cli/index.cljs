(ns agent.extensions.custom-provider-qwen-cli.index
  (:require [agent.providers.oauth :as oauth]
            ["node:child_process" :as cp]))

;; ── OAuth Configuration ─────────────────────────────────────

(def ^:private device-code-url "https://chat.qwen.ai/api/v1/oauth2/device/code")
(def ^:private token-url "https://chat.qwen.ai/api/v1/oauth2/token")
(def ^:private client-id "f0304373b74a44d2b584a3fb70ca9e56")
(def ^:private scope "openid profile email model.completion")
(def ^:private provider-name "qwen-cli")
(def ^:private poll-interval-ms 2000)

;; ── Device Code Flow (RFC 8628) ──────────────────────────────

(defn ^:async start-device-flow
  "Initiate device code flow. Returns device code response."
  [pkce-challenge]
  (let [resp (js-await
               (js/fetch device-code-url
                 #js {:method "POST"
                      :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                      :body (str "client_id=" (js/encodeURIComponent client-id)
                                 "&code_challenge=" (js/encodeURIComponent pkce-challenge)
                                 "&code_challenge_method=S256"
                                 "&scope=" (js/encodeURIComponent scope))}))
        body (js-await (.json resp))]
    (when-not (.-device_code body)
      (throw (js/Error. (str "Device code request failed: "
                             (or (.-error body) "unknown")))))
    body))

(defn ^:async poll-for-token
  "Poll token endpoint until user authorizes or flow expires.
   Returns credentials or throws on error."
  [device-code verifier expires-in]
  (let [deadline (+ (js/Date.now) (* expires-in 1000))]
    (loop []
      (when (> (js/Date.now) deadline)
        (throw (js/Error. "Device code flow expired")))
      (let [resp (js-await
                   (js/fetch token-url
                     #js {:method "POST"
                          :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                          :body (str "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                                     "&device_code=" (js/encodeURIComponent device-code)
                                     "&client_id=" (js/encodeURIComponent client-id)
                                     "&code_verifier=" (js/encodeURIComponent verifier))}))
            body (js-await (.json resp))]
        (cond
          ;; Success
          (.-access_token body)
          {:access     (.-access_token body)
           :refresh    (.-refresh_token body)
           :expires-at (+ (js/Date.now) (* (or (.-expires_in body) 3600) 1000))}

          ;; Still pending — wait and retry
          (= (.-error body) "authorization_pending")
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval-ms))))
            (recur))

          ;; Slow down
          (= (.-error body) "slow_down")
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve (* poll-interval-ms 2)))))
            (recur))

          ;; Fatal errors
          (= (.-error body) "expired_token")
          (throw (js/Error. "Device code expired"))

          (= (.-error body) "access_denied")
          (throw (js/Error. "Access denied by user"))

          :else
          (throw (js/Error. (str "Token error: " (or (.-error body) "unknown")))))))))

(defn ^:async login-qwen
  "Run the full device code login flow for Qwen.
   Accepts a `ui` object with .notify(msg) and .input(prompt, placeholder) methods."
  [ui]
  (let [{:keys [verifier challenge]} (js-await (oauth/generate-pkce))
        device-resp (js-await (start-device-flow challenge))
        user-code   (.-user_code device-resp)
        verify-uri  (or (.-verification_uri_complete device-resp)
                        (.-verification_uri device-resp))
        expires-in  (or (.-expires_in device-resp) 600)]

    ;; Open browser and show user code
    (let [open-cmd (case (.-platform js/process)
                     "darwin" "open"
                     "win32"  "start"
                     "xdg-open")]
      (try (cp/execSync (str open-cmd " " (js/JSON.stringify verify-uri)))
           (catch :default _)))
    (.notify ui (str "Qwen Login — Enter code: " user-code
                     "\nBrowser opened: " verify-uri))

    ;; Poll for authorization
    (let [creds (js-await (poll-for-token
                            (.-device_code device-resp)
                            verifier
                            expires-in))]
      (oauth/save-credentials provider-name creds)
      (.notify ui "Qwen CLI login successful!")
      creds)))

(defn ^:async refresh-qwen
  "Refresh an expired Qwen token."
  [creds]
  (let [resp (js-await
               (js/fetch token-url
                 #js {:method "POST"
                      :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                      :body (str "grant_type=refresh_token"
                                 "&refresh_token=" (js/encodeURIComponent (:refresh creds))
                                 "&client_id=" (js/encodeURIComponent client-id))}))
        body (js-await (.json resp))]
    (if (.-access_token body)
      (let [new-creds {:access     (.-access_token body)
                       :refresh    (or (.-refresh_token body) (:refresh creds))
                       :expires-at (+ (js/Date.now)
                                      (* (or (.-expires_in body) 3600) 1000))}]
        (oauth/save-credentials provider-name new-creds)
        new-creds)
      (throw (js/Error. (str "Token refresh failed: " (or (.-error body) "unknown")))))))

;; ── Extension Entry Point ───────────────────────────────────

(defn ^:export default [api]
  ;; Register the Qwen CLI provider
  (.registerProvider api "qwen-cli"
    #js {:baseUrl   "https://dashscope.aliyuncs.com/compatible-mode/v1"
         :apiKeyEnv "QWEN_CLI_API_KEY"
         :api       "openai-compatible"
         :models    #js [#js {:id            "qwen3-coder-plus"
                              :name          "Qwen3 Coder Plus"
                              :contextWindow 1000000
                              :maxTokens     65536
                              :reasoning     false}
                         #js {:id            "qwen3-coder-flash"
                              :name          "Qwen3 Coder Flash"
                              :contextWindow 1000000
                              :maxTokens     65536
                              :reasoning     false}
                         #js {:id            "qwen3-vl-plus"
                              :name          "Qwen3 VL Plus"
                              :contextWindow 262144
                              :maxTokens     32768
                              :reasoning     true
                              :input         #js ["text" "image"]}]
         :oauth     #js {:name         "Qwen CLI"
                         :login        (fn [ui] (login-qwen ui))
                         :refreshToken (fn [cred] (refresh-qwen
                                                    {:refresh (aget cred "refresh")
                                                     :access  (aget cred "access")}))
                         :getApiKey    (fn [cred] (.-access cred))}})

  ;; Return cleanup function
  (fn []
    (.unregisterProvider api "qwen-cli")))
