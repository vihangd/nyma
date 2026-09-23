(ns ext-reload-one.test
  "Per-extension reload and the dev watcher's file→extension mapping.
   `/reload` used to be all-or-nothing: every extension torn down and rebuilt
   to pick up one changed file."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :as loader]
            [agent.dev.ext-watch :as watch]))

(def ^:private dirs (atom []))

(defn- ext-dir! [body]
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-reload-"))
        dir  (path/join root "hot")]
    (swap! dirs conj root)
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "extension.json")
                      (js/JSON.stringify #js {:namespace "hot" :capabilities #js ["commands"]}))
    (fs/writeFileSync (path/join dir "index.ts") body)
    root))

(defn- ts-body [tag]
  (str "export default function (api) {\n"
       "  api.registerCommand('ping', { description: '" tag "', handler: () => null });\n"
       "  return () => {};\n}\n"))

(describe "extension-loader:reload-one!" (fn []
                                           (afterEach (fn [] (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true})) (reset! dirs []) nil))

                                           (it "deactivates, re-imports from disk, and the new code is live"
                                               (fn []
                                                 (let [root  (ext-dir! (ts-body "v1"))
                                                       agent (create-agent {:model "test" :system-prompt "x"})
                                                       api   (create-extension-api agent)]
                                                   (-> (loader/discover-and-load [root] api [])
                                                       (.then (fn [loaded]
                                                                (let [old (first (filter #(= "hot" (:namespace %)) loaded))]
                                                                  (-> (expect (some? old)) (.toBe true))
                                                                  (-> (expect (:description (get @(:commands agent) "hot__ping"))) (.toBe "v1"))
                         ;; change the file, reload just this one
                                                                  (fs/writeFileSync (path/join root "hot" "index.ts") (ts-body "v2"))
                                                                  (-> (loader/reload-one! api old)
                                                                      (.then (fn [new]
                                                                               (-> (expect (some? new)) (.toBe true))
                                                                               (-> (expect (:namespace new)) (.toBe "hot"))
                                                                               (-> (expect (:description (get @(:commands agent) "hot__ping"))) (.toBe "v2"))))))))))))

                                           (it "a reload that throws leaves the extension OFF and records why"
                                               (fn []
                                                 (let [root  (ext-dir! (ts-body "v1"))
                                                       agent (create-agent {:model "test" :system-prompt "x"})
                                                       api   (create-extension-api agent)]
                                                   (-> (loader/discover-and-load [root] api [])
                                                       (.then (fn [loaded]
                                                                (let [old (first (filter #(= "hot" (:namespace %)) loaded))]
                                                                  (fs/writeFileSync (path/join root "hot" "index.ts")
                                                                                    "export default function () { throw new Error('boom') }\n")
                                                                  (-> (loader/reload-one! api old)
                                                                      (.then (fn [new]
                                                                               (-> (expect new) (.toBeNil))
                                                                               (-> (expect (contains? @(:commands agent) "hot__ping")) (.toBe false))
                                                                               (-> (expect (get @loader/last-load-failures "hot")) (.toContain "boom"))))))))))))))

(describe "extension-loader:reload-one! builtin" (fn []
  (it "a builtin re-activates from its static module"
      (fn []
        (let [agent (create-agent {:model "test" :system-prompt "x"})
              api   (create-extension-api agent)
              n     (atom 0)
              mod   #js {:default (fn [a] (swap! n inc) (.registerCommand a "ping" #js {:description (str "v" @n) :handler (fn [] nil)}) (fn [] nil))}
              spec  {:namespace "fb" :module mod :manifest #js {:namespace "fb" :capabilities #js ["commands"]}}]
          (-> (loader/discover-and-load [] api [spec])
              (.then (fn [loaded]
                       (let [old (first (filter #(= "fb" (:namespace %)) loaded))]
                         (-> (expect (some? (:module old))) (.toBe true))
                         (-> (loader/reload-one! api old)
                             (.then (fn [new]
                                      (-> (expect (some? new)) (.toBe true))
                                      (-> (expect @n) (.toBe 2))
                                      (-> (expect (:description (get @(:commands agent) "fb__ping"))) (.toBe "v2"))))))))))))))

(describe "ext-watch:entry-for-file" (fn []
                                       (it "maps a changed file to the on-disk extension whose directory holds it, never a builtin"
                                           (fn []
                                             (let [entries [{:namespace "b" :path "builtin:b" :module #js {}}
                                                            {:namespace "hot" :path "/tmp/x/hot/index.ts"}]]
                                               (-> (expect (:namespace (watch/entry-for-file entries "/tmp/x/hot/lib/util.ts"))) (.toBe "hot"))
                                               (-> (expect (:namespace (watch/entry-for-file entries "/tmp/x/hot/index.ts"))) (.toBe "hot"))
                                               (-> (expect (watch/entry-for-file entries "/tmp/x/other/index.ts")) (.toBeUndefined)))))

                                       (it "is off unless opted in, and never for a one-shot run"
                                           (fn []
                                             (js-delete js/process.env "NYMA_WATCH_EXTENSIONS")
                                             (js-delete js/process.env "NYMA_ONE_SHOT")
                                             (-> (expect (watch/enabled? {})) (.toBe false))
                                             (-> (expect (watch/enabled? {:dev {:watch-extensions true}})) (.toBe true))
                                             (aset js/process.env "NYMA_ONE_SHOT" "1")
                                             (-> (expect (watch/enabled? {:dev {:watch-extensions true}})) (.toBe false))
                                             (js-delete js/process.env "NYMA_ONE_SHOT")
                                             nil))))

(describe "extension-loader:eval-expr!" (fn []
                                          (it "compiles and runs a form against the live agent"
                                              (fn []
                                                (aset js/globalThis "__nyma" #js {:answer 42})
                                                (-> (loader/eval-expr! "(+ 1 (.-answer js/globalThis.__nyma))")
                                                    (.then (fn [v] (-> (expect v) (.toBe 43)))))))))
