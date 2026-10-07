(ns settings-safe-write-test
  "Saving a setting must never lose the rest of the file.

   Measured before the fix: a ~/.nyma/settings.json with one trailing comma
   loaded as nil, and the next `/model` or `/theme` save wrote
   `(merge nil {key v})` — the whole file replaced by one key. And
   `:save-project` wrote back the permission-STRIPPED in-memory copy,
   deleting a committed file's `permissions` block."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.settings.manager :as sm]
            [agent.ui.theme-catalog :as tc]))

(defn- tmp-dir [] (fs/mkdtempSync (path/join (os/tmpdir) "nyma-safe-write-")))

(defn- manager [dir]
  (sm/create-settings-manager {:global-path  (path/join dir "global.json")
                               :project-path (path/join dir "project.json")}))

(def ^:private broken "{\n  \"model\": \"x\",\n  \"theme\": \"y\",\n}\n")

(describe "settings writes"
          (fn []
            (it "refuse to overwrite a file that does not parse — and leave it byte-identical"
                (fn []
                  (let [d (tmp-dir) g (path/join d "global.json")]
                    (fs/writeFileSync g broken)
                    (let [m (manager d)]
                      (-> (expect (fn [] ((:save-global m) {:model "z"}))) (.toThrow "does not parse"))
                      (-> (expect (fn [] ((:append-allow-tool! m) "bash"))) (.toThrow "does not parse")))
                    (-> (expect (fs/readFileSync g "utf8")) (.toBe broken))
                    (fs/rmSync d #js {:recursive true}))))

            (it "merge into what is on disk, so an edit made outside nyma survives"
                (fn []
                  (let [d (tmp-dir) g (path/join d "global.json") m (manager d)]
                    (fs/writeFileSync g "{\"model\": \"a\"}")
                    ((:reload m))
                    (fs/writeFileSync g "{\"model\": \"a\", \"editor\": \"vim\"}")
                    ((:save-global m) {:theme "t"})
                    (let [j (js/JSON.parse (fs/readFileSync g "utf8"))]
                      (-> (expect (.-editor j)) (.toBe "vim"))
                      (-> (expect (.-theme j)) (.toBe "t")))
                    (fs/rmSync d #js {:recursive true}))))

            (it "a missing or blank file is a fresh one"
                (fn []
                  (let [d (tmp-dir) g (path/join d "global.json") m (manager d)]
                    ((:save-global m) {:model "a"})
                    (fs/writeFileSync g "  \n")
                    ((:save-global m) {:theme "t"})
                    (-> (expect (.-theme (js/JSON.parse (fs/readFileSync g "utf8")))) (.toBe "t"))
                    (fs/rmSync d #js {:recursive true}))))

            (it ":save-project keeps the project file's permissions block"
                (fn []
                  (let [d (tmp-dir) p (path/join d "project.json")]
                    (fs/writeFileSync p "{\"permissions\": {\"allow\": [\"read\"]}, \"model\": \"a\"}")
                    (let [m (manager d)]
                      ((:save-project m) {:theme "t"}))
                    (let [j (js/JSON.parse (fs/readFileSync p "utf8"))]
                      (-> (expect (clj->js (.. j -permissions -allow))) (.toEqual #js ["read"]))
                      (-> (expect (.-theme j)) (.toBe "t")))
                    (fs/rmSync d #js {:recursive true}))))

            (it "writes atomically — no temp file left behind"
                (fn []
                  (let [d (tmp-dir) m (manager d)]
                    ((:save-global m) {:model "a"})
                    (-> (expect (vec (fs/readdirSync d))) (.toEqual ["global.json"]))
                    (fs/rmSync d #js {:recursive true}))))))

(describe "theme save"
          (fn []
            (it "does not reset an unparseable project file to {theme}"
                (fn []
                  (let [d    (tmp-dir)
                        prev (js/process.cwd)
                        f    (path/join d ".nyma" "settings.json")]
                    (fs/mkdirSync (path/join d ".nyma"))
                    (fs/writeFileSync f broken)
                    (js/process.chdir d)
                    (try
                      (-> (expect (fn [] (tc/set-theme-name! "nord"))) (.toThrow "does not parse"))
                      (-> (expect (fs/readFileSync f "utf8")) (.toBe broken))
                      (finally (js/process.chdir prev)
                               (fs/rmSync d #js {:recursive true}))))))))
