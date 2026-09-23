(ns settings-merge-policy.test
  "The settings merge used to be a top-level shallow merge with two bespoke
   exceptions (`:extensions` per key, extension defaults per section) and a
   third policy — `:roles` REPLACES — documented only in prose: a user who
   wrote one custom role silently lost all twelve built-ins, subagent roles
   included. Now one declared table says which sections merge per key."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.settings.manager :as sm]))

(def ^:private dirs (atom []))

(defn- tmp-json [m]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-merge-"))
        p   (path/join dir "settings.json")]
    (swap! dirs conj dir)
    (fs/writeFileSync p (js/JSON.stringify (clj->js m)))
    p))

(defn- mgr [global project]
  (sm/create-settings-manager {:global-path (tmp-json global) :project-path (tmp-json project)}))

(describe "settings:merge-policies" (fn []
          (afterEach (fn [] (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true})) (reset! dirs []) nil))

          (it "declares :roles and :extensions as per-key sections"
              (fn []
                (-> (expect (contains? (set sm/per-key-sections) :roles)) (.toBe true))
                (-> (expect (contains? (set sm/per-key-sections) :extensions)) (.toBe true))))

          (it "a user who adds ONE role keeps every built-in role"
              (fn []
                (let [s      ((:get (mgr {:roles {:mine {:provider "anthropic" :model "m"}}} {})))
                      roles  (:roles s)]
                  (-> (expect (:model (:mine roles))) (.toBe "m"))
                  (doseq [k [:default :fast :deep :plan :commit :scout :planner :reviewer :researcher :worker]]
                    (-> (expect (some? (get roles k))) (.toBe true))))))

          (it "a user role of the same name replaces that one role whole"
              (fn []
                (let [roles (:roles ((:get (mgr {:roles {:plan {:provider "x" :model "y"}}} {}))))]
                  (-> (expect (:model (:plan roles))) (.toBe "y"))
          ;; whole-role replace: the built-in's tool list is gone, by design
                  (-> (expect (:allowed-tools (:plan roles))) (.toBeUndefined))
                  (-> (expect (some? (:scout roles))) (.toBe true)))))

          (it "project roles layer over global roles per key"
              (fn []
                (let [roles (:roles ((:get (mgr {:roles {:a {:model "ga"} :b {:model "gb"}}}
                                                {:roles {:b {:model "pb"}}}))))]
                  (-> (expect (:model (:a roles))) (.toBe "ga"))
                  (-> (expect (:model (:b roles))) (.toBe "pb")))))

          (it ":extensions still merges per key (a project disabling one keeps the global's others)"
              (fn []
                (let [ext (:extensions ((:get (mgr {:extensions {:memory false}} {:extensions {:todos false}}))))]
                  (-> (expect (:memory ext)) (.toBe false))
                  (-> (expect (:todos ext)) (.toBe false)))))

          (it "sections outside the table are replaced whole, as before"
              (fn []
                (let [s ((:get (mgr {:compaction {:enabled true :threshold 0.5}} {:compaction {:threshold 0.9}})))]
                  (-> (expect (:threshold (:compaction s))) (.toBe 0.9))
          ;; not in the table → project's map wins whole; :enabled comes from defaults, not global
                  (-> (expect (:enabled (:compaction s))) (.toBeUndefined)))))))
