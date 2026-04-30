(ns claude-hook-bridge-load-smoke.test
  "Smoke test: import every compiled bridge module to confirm it
   parses as valid JS and its top-level evaluation doesn't throw.

   Catches the class of bug squint can let through silently — for
   example, a `(fn ...)` body that uses `js-await` without `^:async`
   compiles to JS containing `await` outside an async function, which
   is a SyntaxError the runtime only surfaces at module-load time.
   None of the unit tests previously imported the per-event handler
   modules (they went through `dispatch/dispatch` directly), so the
   broken .mjs files sat on disk with green tests until extension
   auto-discovery actually tried to load them in a real session."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private dist-bridge-dir
  "/Users/vihangd/projects/pers/nyma/dist/agent/extensions/claude_hook_bridge")

(defn- collect-mjs
  "Recursively list every .mjs file under `dir`."
  [dir]
  (let [out (atom [])]
    (doseq [entry (fs/readdirSync dir #js {:withFileTypes true})]
      (let [p (path/join dir (.-name entry))]
        (cond
          (.isDirectory entry) (swap! out into (collect-mjs p))
          (.endsWith (.-name entry) ".mjs") (swap! out conj p))))
    @out))

(defn ^:async test-every-bridge-module-loads []
  ;; Builds the import URL once and walks the dist tree.
  (let [files (collect-mjs dist-bridge-dir)
        results (atom [])]
    (doseq [p files]
      (let [r (js-await
               (-> (js/import p)
                   (.then (fn [_] {:path p :ok true}))
                   (.catch (fn [e]
                             {:path p
                              :ok false
                              :error (or (.-message e) (str e))}))))]
        (swap! results conj r)))
    (let [failures (filterv (complement :ok) @results)]
      (doseq [f failures]
        (js/console.error (str "[smoke] " (:path f) " — " (:error f))))
      (-> (expect (count failures)) (.toBe 0))
      (-> (expect (pos? (count @results))) (.toBe true)))))

(describe "bridge/load-smoke"
          (fn []
            (it "every compiled .mjs in claude_hook_bridge/ imports cleanly"
                test-every-bridge-module-loads)))
