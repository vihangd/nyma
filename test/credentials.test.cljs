(ns credentials.test
  "Covers the shared `/login` credentials reader and the registry's
   key-resolution order.

   The reader is exercised against a real temp HOME rather than a mock, since
   the bug it fixes was precisely that nothing read the file — a mock of the
   reader would have passed against the broken code."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.utils.credentials :as credentials]
            [agent.providers.registry :refer [build-provider-entry resolve-api-key]]))

(def ^:private real-home (.. js/process -env -HOME))

(defn- set-home! [dir]
  (aset js/process.env "HOME" dir))

(defn- write-creds! [obj]
  (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-creds-"))
        nyma (path/join dir ".nyma")]
    (fs/mkdirSync nyma #js {:recursive true})
    (fs/writeFileSync (path/join nyma "credentials.json")
                      (js/JSON.stringify (clj->js obj)))
    (set-home! dir)
    dir))

(defn- empty-home! []
  (set-home! (fs/mkdtempSync (path/join (os/tmpdir) "nyma-nocreds-"))))

(describe "credentials/read-credential" (fn []
                                          (afterEach (fn [] (set-home! real-home)))

                                          (it "reads a key saved under the provider name"
                                              (fn []
                                                (write-creds! {"yunwu" "sk-yunwu"})
                                                (-> (expect (credentials/read-credential "yunwu")) (.toBe "sk-yunwu"))))

                                          (it "returns nil when the provider has no entry"
                                              (fn []
                                                (write-creds! {"other" "sk-other"})
                                                (-> (expect (credentials/read-credential "yunwu")) (.toBeNil))))

                                          (it "returns nil when the file does not exist"
                                              (fn []
                                                (empty-home!)
                                                (-> (expect (credentials/read-credential "yunwu")) (.toBeNil))))

                                          (it "returns nil rather than throwing on malformed JSON"
                                              (fn []
                                                (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bad-"))
                                                      nyma (path/join dir ".nyma")]
                                                  (fs/mkdirSync nyma #js {:recursive true})
                                                  (fs/writeFileSync (path/join nyma "credentials.json") "{not json")
                                                  (set-home! dir)
                                                  (-> (expect (credentials/read-credential "yunwu")) (.toBeNil)))))

                                          (it "tries fallback names in order — claude-native reads the anthropic entry"
                                              (fn []
                                                (write-creds! {"anthropic" "sk-ant"})
                                                (-> (expect (credentials/read-credential "claude-native" "anthropic"))
                                                    (.toBe "sk-ant"))))

                                          (it "prefers the first name over its fallbacks"
                                              (fn []
                                                (write-creds! {"anthropic" "sk-ant" "claude-native" "sk-cn"})
                                                (-> (expect (credentials/read-credential "claude-native" "anthropic"))
                                                    (.toBe "sk-cn"))))

                                          (it "ignores empty-string entries"
                                              (fn []
                                                (write-creds! {"yunwu" ""})
                                                (-> (expect (credentials/read-credential "yunwu")) (.toBeNil))))))

;; ── Registry resolution order ────────────────────────────────
;; The declarative path (no :create-model) is what settings-declared providers
;; use. Before this change it never consulted credentials.json, so `/login`
;; wrote a key that nothing read.

(describe "registry key resolution" (fn []
                                      ;; js-delete must not sit in tail position —
                                      ;; squint emits `return return delete`.
                                      (beforeEach (fn [] (js-delete js/process.env "TEST_RELAY_KEY") nil))
                                      (afterEach  (fn []
                                                    (set-home! real-home)
                                                    (js-delete js/process.env "TEST_RELAY_KEY")
                                                    nil))

                                      (it "uses a key saved by /login when no env var is set"
                                          (fn []
                                            (write-creds! {"testrelay" "sk-saved"})
                                            (let [entry (build-provider-entry "testrelay"
                                                                              {:api         "openai-compatible"
                                                                               :base-url    "https://example.test/v1"
                                                                               :api-key-env "TEST_RELAY_KEY"})]
        ;; Resolution happens inside create-model; reaching a model object at
        ;; all proves a key was found (the no-credentials path throws).
                                              (-> (expect (fn [] ((:create-model entry) "some-model")))
                                                  (.not.toThrow)))))

                                      (it "prefers the env var over the saved key"
                                          (fn []
                                            (write-creds! {"testrelay" "sk-saved"})
                                            (aset js/process.env "TEST_RELAY_KEY" "sk-env")
                                            (-> (expect (:key (resolve-api-key
                                                               "testrelay"
                                                               {:api-key-env "TEST_RELAY_KEY"})))
                                                (.toBe "sk-env"))))

                                      (it "falls through to the saved key when the env var is unset"
                                          (fn []
                                            (write-creds! {"testrelay" "sk-saved"})
                                            (-> (expect (:key (resolve-api-key
                                                               "testrelay"
                                                               {:api-key-env "TEST_RELAY_KEY"})))
                                                (.toBe "sk-saved"))))

                                      (it "resolves nothing when neither source has a key"
                                          (fn []
                                            (empty-home!)
                                            (-> (expect (resolve-api-key
                                                         "testrelay"
                                                         {:api-key-env "TEST_RELAY_KEY"}))
                                                (.toBeNil))))

                                      (it "throws a message naming /login when nothing is available"
                                          (fn []
                                            (empty-home!)
                                            (let [entry (build-provider-entry "testrelay"
                                                                              {:api         "openai-compatible"
                                                                               :base-url    "https://example.test/v1"
                                                                               :api-key-env "TEST_RELAY_KEY"})]
                                              (-> (expect (fn [] ((:create-model entry) "some-model")))
                                                  (.toThrow #"/login testrelay")))))))

;;; ─── file permissions ───────────────────────────────────────
;;; API keys were written with the default mode, so umask decided: on the usual
;;; 022 they landed 0644 — world-readable, in the one file whose whole job is
;;; holding secrets.

(defn- mode-of [p]
  (bit-and (.-mode (fs/statSync p)) 0777))

(describe "saved API keys are not world-readable"
          (fn []
            (it "tightens an existing 0644 credentials file on read"
                (fn []
                  (let [dir (write-creds! {"anthropic" "sk-secret"})
                        p   (path/join dir ".nyma" "credentials.json")]
                    (fs/chmodSync p 0644)
                    (-> (expect (mode-of p)) (.toBe 420))   ;; 0644, as written today
                    (credentials/read-all)
                    (-> (expect (mode-of p)) (.toBe 384))   ;; 0600
                    (fs/rmSync dir #js {:recursive true}))))

            (it "leaves an already-private file alone"
                (fn []
                  (let [dir (write-creds! {"anthropic" "sk-secret"})
                        p   (path/join dir ".nyma" "credentials.json")]
                    (fs/chmodSync p 0600)
                    (-> (expect (credentials/harden! p)) (.toBe false))
                    (-> (expect (mode-of p)) (.toBe 384))
                    (fs/rmSync dir #js {:recursive true}))))

            (it "still returns the keys after tightening"
                (fn []
                  (let [dir (write-creds! {"anthropic" "sk-secret"})]
                    (fs/chmodSync (path/join dir ".nyma" "credentials.json") 0644)
                    (-> (expect (credentials/read-credential "anthropic")) (.toBe "sk-secret"))
                    (fs/rmSync dir #js {:recursive true}))))

            (it "0600 is 384, not whatever the literal happened to compile to"
                (fn []
          ;; Clojure spells octal `0600`; `0o600` is not a valid Clojure number
          ;; at all and fails the squint compile. This pins the value.
                  (-> (expect credentials/private-mode) (.toBe 384))))))
