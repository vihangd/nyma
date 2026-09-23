(ns home-dir-collisions.test
  "One bug class: a resource read from BOTH a home path and a project path,
   where the project path is a literal relative directory like `.nyma`. Run
   nyma from your home directory and the two are the same file.

   The extension dirs, the skill roots and the hook-bridge config sources were
   fixed earlier; these are the rest. Each is deduplicated by RESOLVED path,
   not by string, because HOME is used unresolved while the cwd is resolved by
   the OS — on a symlinked home the two spellings differ for one file."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.debug :as d]
            [agent.settings.manager :as sm]
            [agent.extensions.agent-shell.features.mcp-discovery :as mcp]))

(def ^:private dirs (atom []))

(defn- tmp []
  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-collide-"))]
    (swap! dirs conj d)
    d))

(afterEach (fn []
             (d/reset-logger!)
             (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
             (reset! dirs [])
             nil))

(defn- settings-file [m]
  (let [d (tmp) f (path/join d "settings.json")]
    (fs/writeFileSync f (js/JSON.stringify (clj->js m)))
    f))

;;; ─── settings: the one that destroyed data ────────────────────

(defn- test-save-project-keeps-global []
  ;; `/extensions disable <ns> --project` wrote back the STRIPPED project copy,
  ;; so permission-mode and the whole permissions block vanished from the
  ;; user's own file. Permanently, and with nothing said.
  (let [f (settings-file {:permission-mode "accept-edits"
                          :permissions {:allow ["read"] :deny ["rm"]}
                          :model "m"})
        m (sm/create-settings-manager {:global-path f :project-path f})]
    ((:save-project m) {:extensions {:memory false}})
    (let [after (js/JSON.parse (fs/readFileSync f "utf8"))]
      (-> (expect (aget after "permission-mode")) (.toBe "accept-edits"))
      (-> (expect (aget (aget after "permissions") "allow")) (.toEqual (clj->js ["read"])))
      (-> (expect (aget (aget after "extensions") "memory")) (.toBe false)))))

(defn- test-no-false-widening-warning []
  (let [lines (atom [])
        f     (settings-file {:permission-mode "accept-edits"
                              :permissions {:allow ["read"]}})]
    (d/configure-logger! (fn [l] (swap! lines conj l)))
    (sm/create-settings-manager {:global-path f :project-path f})
    (-> (expect (some #(.includes % "may not widen permissions") @lines)) (.toBeFalsy))))

(defn- test-role-grant-survives []
  ;; :roles is a per-key section, so the scrubbed project copy replaced the
  ;; global role entry and the grant was lost.
  (let [f (settings-file {:roles {:mine {:policy {"write" "allow"}}}})
        m (sm/create-settings-manager {:global-path f :project-path f})]
    (-> (expect (get-in ((:get m)) [:roles :mine :policy "write"])) (.toBe "allow"))))

(defn- test-real-project-still-stripped []
  (let [g (settings-file {:model "m"})
        p (settings-file {:permission-mode "full-auto"})
        m (sm/create-settings-manager {:global-path g :project-path p})]
    (-> (expect (:permission-mode ((:get m)))) (.toBeUndefined))))

;;; ─── the rest of the class ────────────────────────────────────

(defn- test-mcp-candidates-deduped []
  (let [home (tmp)]
    ;; from the home directory the first two candidates are one file: it was
    ;; parsed twice and a malformed one reported to the user twice
    (-> (expect (count (mcp/candidate-paths home home))) (.toBe 3))
    (-> (expect (count (mcp/candidate-paths (tmp) home))) (.toBe 4))))

(defn- test-mcp-candidates-unchanged-normally []
  (let [home (tmp) proj (tmp)
        ps   (vec (mcp/candidate-paths proj home))]
    (-> (expect (.includes (clj->js ps) (path/join home ".nyma" "mcp.json"))) (.toBe true))
    (-> (expect (.includes (clj->js ps) (path/join proj ".mcp.json"))) (.toBe true))))

(describe "settings when the project file IS the global file"
          (fn []
            (it "saving project settings does not erase the global ones" test-save-project-keeps-global)
            (it "does not warn about your own global file" test-no-false-widening-warning)
            (it "a role that grants a tool keeps the grant" test-role-grant-survives)
            (it "a genuine project file is still stripped" test-real-project-still-stripped)))

(describe "other home/project collisions"
          (fn []
            (it "MCP candidate paths list each file once" test-mcp-candidates-deduped)
            (it "MCP candidates are unchanged for a normal project" test-mcp-candidates-unchanged-normally)))
