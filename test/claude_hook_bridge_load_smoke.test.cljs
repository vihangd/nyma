(ns claude-hook-bridge-load-smoke.test
  "Smoke tests for the compiled bridge — two layers of protection
   for the class of bug nyma's unit tests miss:

   Layer 1 (every .mjs imports cleanly):
   Catches squint output that compiles to syntactically-valid JS
   but throws at module parse / top-level evaluation. Example:
   `js-await` inside a non-async `(fn ...)` compiles to bare
   `await`, which the JS engine rejects only when `import()` runs.

   Layer 2 (default(api) actually runs):
   The unit tests poke individual modules directly. Layer 1 only
   imports — top-level code runs but the extension's `default` is
   not invoked. The activate path inside `default` (file globs,
   env-var reads, fs.watch setup) only fails when the bridge is
   bound to a real api, the same way agent.extension-loader's
   discover-and-load does it. This layer mocks a minimal api and
   calls default(api) so any crash on activation also fails CI.

   Together these catch every issue surfaced in real sessions so
   far. Add new probes here if a class of bug ever slips through."
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

;;; ─── Layer 2: default(api) actually runs ────────────────────────────────

(defn- mock-api
  "Minimal api shape the bridge touches during default()."
  []
  (let [listeners (atom {})]
    #js {:events  #js {:on  (fn [evt h _p]
                              (swap! listeners update evt (fnil conj []) h)
                              nil)
                       :off (fn [evt h]
                              (swap! listeners update evt
                                     (fn [hs] (filterv #(not= % h) (or hs []))))
                              nil)}
         :ui       #js {:available false}
         :__listeners listeners}))

(defn ^:async test-default-activates-without-throwing []
  (let [mod (js-await (js/import (path/join dist-bridge-dir "index.mjs")))
        api (mock-api)]
    ;; default() must not throw on activation. It returns either a
    ;; deactivate fn or nil — we accept either, just confirm no
    ;; exception bubbles up.
    (let [dispose (try ((.-default mod) api) (catch :default e e))]
      (-> (expect (or (fn? dispose) (nil? dispose))) (.toBe true))
      (when (fn? dispose) (try (dispose) (catch :default _e nil))))))

(describe "bridge/load-smoke"
          (fn []
            (it "every compiled .mjs in claude_hook_bridge/ imports cleanly"
                test-every-bridge-module-loads)
            (it "default(api) activates without throwing"
                test-default-activates-without-throwing)))
