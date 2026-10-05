(ns test-tmp-root-test
  "Tests write temp files under the preload's per-process run root, never the
   real $TMPDIR. Without it the suite left ~1000 dirs behind per run — 73k had
   piled up. See scripts/test-preload.mjs, point 5."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(describe "test temp root"
          (fn []
            (it "os.tmpdir() is the run root, named for this process"
                (fn []
                  (-> (expect (path/basename (os/tmpdir)))
                      (.toMatch (js/RegExp. (str "^nyma-testrun-" (.-pid js/process) "(-|$)"))))))

            (it "HOME lives inside it"
                (fn []
                  (-> (expect (.startsWith (.. js/process -env -HOME) (os/tmpdir))) (.toBe true))))

            (it "a mkdtemp lands inside it"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-probe-"))]
                    (-> (expect (= (path/dirname d) (os/tmpdir))) (.toBe true))
                    (fs/rmSync d #js {:recursive true}))))))
