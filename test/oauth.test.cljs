(ns oauth.test
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.providers.oauth :as oauth]))

(def ^:private test-provider "test-oauth-provider")
(defn- test-path []
  (path/join (.-HOME (.-env js/process)) ".nyma" "auth" (str test-provider ".json")))

(afterEach (fn []
             (when (fs/existsSync (test-path))
               (fs/unlinkSync (test-path)))))

(describe "oauth - save-credentials" (fn []
                                       (it "writes credentials to disk"
                                           (fn []
                                             (oauth/save-credentials test-provider
                                                                     {:access "tok-123" :refresh "ref-456" :expires-at 9999999999999})
                                             (-> (expect (fs/existsSync (test-path))) (.toBe true))))

                                       (it "writes valid JSON"
                                           (fn []
                                             (oauth/save-credentials test-provider
                                                                     {:access "tok" :refresh "ref" :expires-at 1000})
                                             (let [raw (fs/readFileSync (test-path) "utf8")
                                                   parsed (js/JSON.parse raw)]
                                               (-> (expect (.-access parsed)) (.toBe "tok"))
                                               (-> (expect (.-refresh parsed)) (.toBe "ref")))))))

(describe "oauth - load-credentials" (fn []
                                       (it "returns nil when no credentials exist"
                                           (fn []
                                             (-> (expect (oauth/load-credentials "nonexistent-provider-xyz")) (.toBeUndefined))))

                                       (it "returns saved credentials"
                                           (fn []
                                             (oauth/save-credentials test-provider
                                                                     {:access "abc" :refresh "def" :expires-at 5000})
                                             (let [creds (oauth/load-credentials test-provider)]
                                               (-> (expect (:access creds)) (.toBe "abc"))
                                               (-> (expect (:refresh creds)) (.toBe "def"))
                                               (-> (expect (:expires-at creds)) (.toBe 5000)))))))

(describe "oauth - clear-credentials" (fn []
                                        (it "deletes stored credentials"
                                            (fn []
                                              (oauth/save-credentials test-provider {:access "x" :refresh "y" :expires-at 1})
                                              (-> (expect (fs/existsSync (test-path))) (.toBe true))
                                              (oauth/clear-credentials test-provider)
                                              (-> (expect (fs/existsSync (test-path))) (.toBe false))))

                                        (it "does nothing if no credentials exist"
                                            (fn []
      ;; Should not throw
                                              (oauth/clear-credentials "nonexistent-provider-abc")
                                              (-> (expect true) (.toBe true))))))

(describe "oauth - needs-refresh?" (fn []
                                     (it "returns true when expired"
                                         (fn []
                                           (-> (expect (oauth/needs-refresh? {:expires-at 1000})) (.toBe true))))

                                     (it "returns true within 5-minute buffer"
                                         (fn []
                                           (let [almost-expired (+ (js/Date.now) 60000)] ;; 1 min from now < 5 min buffer
                                             (-> (expect (oauth/needs-refresh? {:expires-at almost-expired})) (.toBe true)))))

                                     (it "returns false when not expired"
                                         (fn []
                                           (let [far-future (+ (js/Date.now) 3600000)] ;; 1 hour from now
                                             (-> (expect (oauth/needs-refresh? {:expires-at far-future})) (.toBe false)))))

                                     (it "returns false when no expires-at"
                                         (fn []
                                           (-> (expect (oauth/needs-refresh? {:access "tok"})) (.toBe false))))))

(defn ^:async test-pkce-generates []
  (let [{:keys [verifier challenge]} (js-await (oauth/generate-pkce))]
    (-> (expect (string? verifier)) (.toBe true))
    (-> (expect (string? challenge)) (.toBe true))
    (-> (expect (> (count verifier) 10)) (.toBe true))
    (-> (expect (> (count challenge) 10)) (.toBe true))))

(defn ^:async test-pkce-unique []
  (let [a (js-await (oauth/generate-pkce))
        b (js-await (oauth/generate-pkce))]
    (-> (expect (not= (:verifier a) (:verifier b))) (.toBe true))))

(describe "oauth - generate-pkce" (fn []
                                    (it "generates verifier and challenge" test-pkce-generates)
                                    (it "generates unique values each call" test-pkce-unique)))

;;; ─── file permissions ───────────────────────────────────────
;;; An OAuth refresh token is a long-lived credential. It was written with the
;;; default mode, so umask decided — typically 0644.

(defn- mode-of [p]
  (bit-and (.-mode (fs/statSync p)) 0777))

(describe "stored OAuth tokens are not world-readable"
          (fn []
            (it "creates the credentials file 0600"
                (fn []
                  (when (fs/existsSync (test-path)) (fs/unlinkSync (test-path)))
                  (oauth/save-credentials test-provider
                                          {:access "a" :refresh "r" :expires-at 1})
                  (-> (expect (mode-of (test-path))) (.toBe 384))
                  (fs/unlinkSync (test-path))))

            (it "tightens a file that is already on disk 0644"
                (fn []
          ;; `:mode` applies on CREATE only, so rewriting a loose file leaves it
          ;; loose — which is every token written before this landed.
                  (oauth/save-credentials test-provider {:access "a" :refresh "r"})
                  (fs/chmodSync (test-path) 0644)
                  (oauth/load-credentials test-provider)
                  (-> (expect (mode-of (test-path))) (.toBe 384))
                  (fs/unlinkSync (test-path))))

            (it "still round-trips the token after tightening"
                (fn []
          ;; The encoding has to move into the options map; passing
          ;; `#js {:mode …}` in place of "utf8" silently drops it.
                  (oauth/save-credentials test-provider
                                          {:access "tok" :refresh "ref" :expires-at 99})
                  (let [c (oauth/load-credentials test-provider)]
                    (-> (expect (:access c)) (.toBe "tok"))
                    (-> (expect (:refresh c)) (.toBe "ref"))
                    (-> (expect (:expires-at c)) (.toBe 99)))
                  (fs/unlinkSync (test-path))))))
