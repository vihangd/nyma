(ns small-model-read-guard-recorder.test
  "read-guard knows which files have been read, and refuses a `write` or `edit`
   onto an existing file that is not in that set. It learned the set by wrapping
   the `read` tool — which meant it only worked while nothing else owned `read`.

   mcp_client overrides `read` onto lean-ctx's `ctx_read`, and when the MCP server
   is healthy the delegator routes there and never calls the wrapper beneath it.
   So the recorder never ran, `read-paths` stayed empty all session, and every
   write onto an existing file was refused while the model was told it had
   succeeded. Fixing the override stack was not enough on its own: a chained
   wrapper that nobody calls still records nothing.

   The recorder now rides `tool_complete`, which fires once per tool call after
   the top-most wrapper, whoever owns the tool."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.extensions.small-model.read-guard :as rg]))

(def ^:private dirs (atom []))

(afterEach (fn []
             (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
             (reset! dirs [])
             nil))

(defn- tmp-file [content]
  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-rg-"))
        f (path/join d "target.js")]
    (swap! dirs conj d)
    (fs/writeFileSync f content)
    f))

(defn- harness
  "Activate read-guard against a stand-in api, capturing its hooks. `read` is
   already owned by someone else, the way mcp_client owns it in production."
  [cfg]
  (let [hooks   (atom {})
        tools   (atom {"read"  #js {:description "native read"
                                    :execute (fn [_] "contents")}
                       "write" #js {:description "native write"
                                    :execute (fn [_] "Wrote 4 bytes to x")}
                       "edit"  #js {:description "native edit"
                                    :execute (fn [_] "Edit applied (1 replacements)")}})
        api #js {:on              (fn [ev f] (swap! hooks assoc ev f) nil)
                 :off             (fn [& _] nil)
                 :addMiddleware   (fn [_] nil)
                 :removeMiddleware (fn [& _] nil)
                 :getTool         (fn [n] (get @tools n))
                 :overrideTool    (fn [n td]
                                    (set! (.-__original td) (get @tools n))
                                    (swap! tools assoc n td) nil)
                 :unoverrideTool  (fn [n] (swap! tools assoc n (.-__original (get @tools n))) nil)}
        cleanup (rg/activate api (merge {:read-guard {:enabled true :max-lines 500}} cfg))]
    {:hooks hooks :tools tools :cleanup cleanup}))

(defn- complete!
  "Fire tool_complete the way middleware.cljs:237-245 does."
  [h tool args result & [{:keys [cancelled is-error]}]]
  (when-let [f (get @(:hooks h) "tool_complete")]
    (f #js {:toolName tool :args (clj->js args) :result result
            :cancelled (boolean cancelled) :isError (boolean is-error)}
       nil)))

;; The guard wrappers are ^:async, so these return promises — (str promise) is
;; "[object Promise]", which contains neither "Refusing" nor anything else useful.
(defn ^:async write! [h f content]
  (str (js-await ((.-execute (get @(:tools h) "write"))
                  (clj->js {:path f :content content})))))

(defn ^:async edit! [h f]
  (str (js-await ((.-execute (get @(:tools h) "edit"))
                  (clj->js {:path f :old_string "original" :new_string "new"})))))

;;; ─── the recorder ──────────────────────────────────────────────

(describe "read-guard: the recorder does not depend on owning `read`" (fn []

  (it "a read seen only through tool_complete still unlocks the write"
      (^:async fn []
        ;; the production case: mcp_client's delegator served the read, so
        ;; read-guard's own wrapper was never invoked
        (let [h (harness {}) f (tmp-file "original\n")]
          (complete! h "read" {:path f} "original\n")
          (-> (expect (js-await (write! h f "new content\n"))) (.not.toContain "Refusing")))))

  (it "an unread existing file is still refused"
      (^:async fn []
        (let [h (harness {}) f (tmp-file "original\n")]
          (-> (expect (js-await (write! h f "new\n"))) (.toContain "Refusing")))))

  (it "a brand-new file needs no read"
      (^:async fn []
        (let [h (harness {}) f (tmp-file "x")
              fresh (path/join (path/dirname f) "does-not-exist.js")]
          (-> (expect (js-await (write! h fresh "hello\n"))) (.not.toContain "Refusing")))))

  (it "a cancelled read unlocks nothing"
      (^:async fn []
        (let [h (harness {}) f (tmp-file "original\n")]
          (complete! h "read" {:path f} nil {:cancelled true})
          (-> (expect (js-await (write! h f "new\n"))) (.toContain "Refusing")))))

  (it "a failed read unlocks nothing, even when it failed as a STRING"
      (^:async fn []
        ;; mcp_client swallows a failure into "[ERROR] …" and reports isError
        ;; false, so trusting isError alone would unlock a write on a read that
        ;; never returned the file
        (let [h (harness {}) f (tmp-file "original\n")]
          (complete! h "read" {:path f} "[ERROR] server unavailable")
          (-> (expect (js-await (write! h f "new\n"))) (.toContain "Refusing")))))

  (it "a read of some OTHER file does not unlock this one"
      (^:async fn []
        (let [h (harness {}) f (tmp-file "original\n")
              other (path/join (path/dirname f) "elsewhere.js")]
          (fs/writeFileSync other "x")
          (complete! h "read" {:path other} "x")
          (-> (expect (js-await (write! h f "new\n"))) (.toContain "Refusing")))))))

;;; ─── the edit guard, which was silently dead ───────────────────

(describe "read-guard: the edit guard exists again" (fn []

  (it "an unread edit is refused"
      (^:async fn []
        ;; mcp_client overrides `edit` onto ctx_edit, so read-guard's edit
        ;; wrapper was dropped entirely and edits were unguarded
        (let [h (harness {}) f (tmp-file "original\n")]
          (-> (expect (js-await (edit! h f))) (.toContain "Refusing")))))

  (it "a read unlocks the edit"
      (^:async fn []
        (let [h (harness {}) f (tmp-file "original\n")]
          (complete! h "read" {:path f} "original\n")
          (-> (expect (js-await (edit! h f))) (.not.toContain "Refusing")))))))

;;; ─── the symlink trap ──────────────────────────────────────────

(describe "read-guard: one file is one key" (fn []

  (it "the realpath and the symlinked spelling are the same key"
      (^:async fn []
        ;; on macOS os.tmpdir() is /var/folders/... whose realpath is
        ;; /private/var/folders/...; path/resolve does not resolve symlinks, so
        ;; reading one spelling and writing the other looked like two files
        (let [h (harness {}) f (tmp-file "original\n")
              real (fs/realpathSync f)]
          (when (not= real f)
            (complete! h "read" {:path f} "original\n")
            (-> (expect (js-await (write! h real "new\n"))) (.not.toContain "Refusing"))))))

  (it "normalize-path agrees on both spellings"
      (fn []
        (let [f (tmp-file "x") real (fs/realpathSync f)]
          (-> (expect (rg/normalize-path f)) (.toBe (rg/normalize-path real))))))))
