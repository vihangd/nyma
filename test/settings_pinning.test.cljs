(ns settings-pinning.test
  "A checked-out repository's `.nyma/settings.json` cannot widen permissions:
   `permission-mode` and any allow decision in a role's policy/permissions are
   dropped from the project layer with a warning, while narrowing (deny, ask)
   is kept. The user's own file and the CLI are the only places that grant."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.settings.manager :as sm]))

(def ^:private dirs (atom []))

(defn- tmp-json [m]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pin-"))
        p   (path/join dir "settings.json")]
    (swap! dirs conj dir)
    (fs/writeFileSync p (js/JSON.stringify (clj->js m)))
    p))

(defn- mgr [global project]
  (sm/create-settings-manager {:global-path (tmp-json global) :project-path (tmp-json project)}))

(describe "settings:project pinning" (fn []
                                       (afterEach (fn [] (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true})) (reset! dirs []) nil))

                                       (it "strip-project-widening drops allow decisions and permission-mode, keeps deny/ask"
                                           (fn []
                                             (let [[out warns] (sm/strip-project-widening
                                                                {:permission-mode "full-auto"
                                                                 :roles {:plan {:policy {"write" "allow" "exec" "ask"}
                                                                                :permissions {"bash" "deny" "edit" "allow_always_project"}}
                                                                         :x    {:model "m"}}
                                                                 :compaction {:threshold 0.5}})]
                                               (-> (expect (contains? out :permission-mode)) (.toBe false))
                                               (-> (expect (get-in out [:roles :plan :policy "write"])) (.toBeUndefined))
                                               (-> (expect (get-in out [:roles :plan :policy "exec"])) (.toBe "ask"))
                                               (-> (expect (get-in out [:roles :plan :permissions "bash"])) (.toBe "deny"))
                                               (-> (expect (get-in out [:roles :plan :permissions "edit"])) (.toBeUndefined))
                                               (-> (expect (get-in out [:roles :x :model])) (.toBe "m"))
                                               (-> (expect (get-in out [:compaction :threshold])) (.toBe 0.5))
                                               (-> (expect (count warns)) (.toBe 3)))))

                                       (it "the manager applies it to the project layer only"
                                           (fn []
                                             (let [s ((:get (mgr {:roles {:mine {:policy {"write" "allow"}}}}
                                                                 {:permission-mode "full-auto"
                                                                  :roles {:mine {:policy {"write" "deny"}}
                                                                          :proj {:policy {"exec" "allow"}}}})))]
          ;; global allow survives, project's replacement of the same role is
          ;; scrubbed to its narrowing part and then replaces per key
                                               (-> (expect (get-in s [:roles :mine :policy "write"])) (.toBe "deny"))
                                               (-> (expect (get-in s [:roles :proj :policy "exec"])) (.toBeUndefined))
                                               (-> (expect (:permission-mode s)) (.toBeUndefined)))))

                                       (it "a project file's permissions.allow is dropped; deny-style keys stay"
      (fn []
        (let [[out warns] (sm/strip-project-widening {:permissions {:allow ["bash"] :deny ["rm"]}})]
          (-> (expect (get-in out [:permissions :allow])) (.toBeUndefined))
          (-> (expect (get-in out [:permissions :deny])) (.toEqual (clj->js ["rm"])))
          (-> (expect (some #(.includes % "permissions.allow") warns)) (.toBeTruthy)))))

  (it "tool-allowed? reads the user's global and per-project lists, never the project file"
      (fn []
        (let [cwd (js/process.cwd)
              m   (mgr {:permissions {:allow ["read"] :projects {cwd {:allow ["grep"]}}}}
                       {:permissions {:allow ["bash"]}})]
          (-> (expect ((:tool-allowed? m) "read")) (.toBe true))
          (-> (expect ((:tool-allowed? m) "grep")) (.toBe true))
          (-> (expect ((:tool-allowed? m) "bash")) (.toBe false)))))

  (it "a project file without permission keys is untouched"
                                           (fn []
                                             (let [[out warns] (sm/strip-project-widening {:model "x" :roles {:a {:model "m"}}})]
                                               (-> (expect (:model out)) (.toBe "x"))
                                               (-> (expect (get-in out [:roles :a :model])) (.toBe "m"))
                                               (-> (expect (count warns)) (.toBe 0)))))))
