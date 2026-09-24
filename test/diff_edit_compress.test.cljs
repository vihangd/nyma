(ns diff-edit-compress.test
  "`compress-leave` shortens a successful edit/write result so the transcript
   does not carry the whole file back. It used to do that unconditionally, from
   the ARGUMENTS, without ever looking at what the tool returned:

     (aset ctx \"result\" (str \"Wrote \" (count content-str) \"B ...\"))

   So a `write` that did not write still reported a byte count. Measured on the
   benchmark: read-guard refuses a write onto a file it has not seen read, the
   refusal is a string rather than a throw, and this replaced it with
   \"Wrote 915B (35 lines) to transpose.js\". The model believed it, the tests
   kept failing against the untouched skeleton, and the task burned 600s
   re-writing a file that was never being written. Same signature on two
   different models.

   The rule now: compress what the native tool reports as success, pass
   everything else through untouched. `result` was already bound here and
   never read — the check was meant to exist."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.token-suite.diff-edit :as de]))

(defn- ctx [tool result args]
  #js {"tool-name" tool "result" result "args" (clj->js args)})

(defn- result-of [c] (aget c "result"))

;;; ─── the bug ───────────────────────────────────────────────────

(defn- test-refusal-survives []
  ;; the exact string read_guard returns, and the exact way it was lost
  (let [refusal (str "[read-guard] Refusing to write over the existing file transpose.js "
                     "because it has not been read in this session. Read it first, "
                     "then use edit for a targeted change.")
        out     (de/compress-leave (ctx "write" refusal {:path "transpose.js"
                                                         :content "console.log(1);\n"}))]
    (-> (expect (result-of out)) (.toBe refusal))))

(defn- test-error-survives []
  (let [err "Error: EACCES: permission denied, open 'transpose.js'"
        out (de/compress-leave (ctx "write" err {:path "transpose.js" :content "x"}))]
    (-> (expect (result-of out)) (.toBe err))))

(defn- test-edit-refusal-survives []
  (let [refusal "[read-guard] Refusing to edit transpose.js because it has not been read"
        out     (de/compress-leave (ctx "edit" refusal {:path "transpose.js"}))]
    (-> (expect (result-of out)) (.toBe refusal))))

(defn- test-empty-result-not-invented []
  ;; a tool that returned nothing has not told us it succeeded
  (-> (expect (result-of (de/compress-leave (ctx "write" "" {:path "a.js" :content "xy"}))))
      (.toBe "")))

;;; ─── still compresses a real success ──────────────────────────

(defn- test-write-success-compressed []
  ;; tools.cljs write-execute returns "Wrote N bytes to PATH"
  (let [out (de/compress-leave
             (ctx "write" "Wrote 16 bytes to transpose.js"
                  {:path "transpose.js" :content "console.log(1);\n"}))]
    (-> (expect (result-of out)) (.toBe "Wrote 16B (2 lines) to transpose.js"))))

(defn- test-edit-success-compressed []
  ;; tools.cljs edit returns "Edit applied (N replacements)" or
  ;; "Edit applied at line N of PATH. It now reads:\n<the whole region>"
  (let [long-form (str "Edit applied at line 7 of transpose.js. It now reads:\n"
                       (.repeat "some quoted source line\n" 40))
        out       (de/compress-leave (ctx "edit" long-form {:path "transpose.js"}))]
    (-> (expect (result-of out)) (.toBe "Edit applied to transpose.js"))
    (-> (expect (< (count (result-of out)) (count long-form))) (.toBe true))))

(defn- test-other-tools-untouched []
  (let [out (de/compress-leave (ctx "bash" "PASS 13 tests" {:command "jest"}))]
    (-> (expect (result-of out)) (.toBe "PASS 13 tests"))))

(defn- test-overridden-edit-passes-through []
  ;; mcp_client overrides `edit` onto lean-ctx ctx_edit, whose success string is
  ;; not the native one. Passing it through loses a little compression;
  ;; fabricating a success for a tool we cannot read is the thing that cost 600s.
  (let [out (de/compress-leave (ctx "edit" "F1=transpose.js patched 1 hunk" {:path "transpose.js"}))]
    (-> (expect (result-of out)) (.toBe "F1=transpose.js patched 1 hunk"))))

(describe "diff-edit compress: never invent a success"
          (fn []
            (it "a write refusal reaches the model instead of a byte count" test-refusal-survives)
            (it "a write error reaches the model" test-error-survives)
            (it "an edit refusal reaches the model" test-edit-refusal-survives)
            (it "an empty result is not turned into a success" test-empty-result-not-invented)
            (it "a tool we do not recognise passes through" test-overridden-edit-passes-through)))

(describe "diff-edit compress: still compresses"
          (fn []
            (it "a real write is shortened" test-write-success-compressed)
            (it "a real edit drops the quoted region" test-edit-success-compressed)
            (it "an unrelated tool is untouched" test-other-tools-untouched)))
