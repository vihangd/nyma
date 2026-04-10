(ns external-editor-spike.test
  "Validation for Q2: spawnSync + file-round-trip works at the Node
   level. This test does NOT render Ink — it proves the primitive
   sequence (write temp file → spawnSync a child that touches it →
   read the result) works cleanly under bun, which is the foundation
   HookEditor's external-editor flow depends on.

   The full Ink-level validation requires a TTY, so it lives in a
   separate manual spike at /tmp/nyma-spike-external-editor.mjs that
   the user runs interactively."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:child_process" :refer [spawnSync]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- tmp-file [tag]
  (path/join (os/tmpdir)
             (str "nyma-spike-" tag "-" (js/Date.now) ".txt")))

(describe "external-editor primitives" (fn []
  (it "spawnSync runs a non-interactive command and returns status 0"
    (fn []
      (let [result (spawnSync "true" #js [] #js {:stdio "ignore"})]
        (-> (expect (.-status result)) (.toBe 0)))))

  (it "spawnSync can write to a temp file via sh -c"
    (fn []
      (let [f      (tmp-file "write")
            _      (fs/writeFileSync f "initial")
            result (spawnSync "sh"
                     #js ["-c" "echo updated > \"$1\"" "sh" f]
                     #js {:stdio "ignore"})]
        (-> (expect (.-status result)) (.toBe 0))
        (let [content (.trim (fs/readFileSync f "utf8"))]
          (-> (expect content) (.toBe "updated"))
          (fs/unlinkSync f)))))

  (it "write → spawn → read round-trip preserves content"
    (fn []
      (let [f      (tmp-file "roundtrip")
            _      (fs/writeFileSync f "before edit\n")
            ;; Simulate a 'editor' by appending to the file
            result (spawnSync "sh"
                     #js ["-c" "printf 'after edit\\n' >> \"$1\"" "sh" f]
                     #js {:stdio "ignore"})
            content (fs/readFileSync f "utf8")]
        (-> (expect (.-status result)) (.toBe 0))
        (-> (expect (.includes content "before edit")) (.toBe true))
        (-> (expect (.includes content "after edit")) (.toBe true))
        (fs/unlinkSync f))))

  (it "spawnSync of a missing command returns non-zero status"
    (fn []
      (let [result (spawnSync "nyma-nonexistent-command-xyz" #js []
                              #js {:stdio "ignore"})]
        ;; Either an error is set or status is non-zero
        (-> (expect (or (.-error result) (not= 0 (.-status result))))
            (.toBeTruthy)))))))
