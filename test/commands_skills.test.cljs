(ns commands-skills.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.core :refer [create-agent]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.resources.skills :as skills]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- make-mock-skills []
  {"git"    {:dir "/tmp/git"
             :markdown "# Git Suite\nAutomates git workflows.\n"
             :has-tools false}
   "python" {:dir "/tmp/python"
             :markdown "# Python\nPython coding helper.\n"
             :has-tools false}})

(defn- make-agent-with-skills [all-skills]
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})]
    (reset! (:session agent)
      {:get-session-name (fn [] nil)
       :set-session-name (fn [_n] nil)
       :get-file-path    (fn [] nil)
       :get-tree         (fn [] [])
       :build-context    (fn [] [])
       :leaf-id          (fn [] "leaf-1")
       :branch           (fn [_id] nil)
       :switch-file      (fn [_p] nil)})
    (register-builtins agent @(:session agent) {:skills all-skills})
    agent))

(defn- make-ctx
  "Mock context — captures notifications and ui.custom() calls."
  []
  (let [notifications (atom [])
        custom-calls  (atom [])]
    {:ctx #js {:ui #js {:available   true
                        :notify      (fn [msg level]
                                       (swap! notifications conj
                                              {:msg msg :level (or level "info")}))
                        :showOverlay (fn [_text] nil)
                        :custom      (fn [picker]
                                       (swap! custom-calls conj picker)
                                       (js/Promise.resolve nil))}}
     :notifications notifications
     :custom-calls  custom-calls}))

(defn- last-notification [notifications]
  (last @notifications))

;;; ─── /skill command ─────────────────────────────────────────

(describe "commands-skills:skill-command" (fn []
  (it "activates a known skill and tracks it in :active-skills"
    (fn []
      (let [mock-skills (make-mock-skills)
            agent       (make-agent-with-skills mock-skills)
            handler     (get-in @(:commands agent) ["skill" :handler])
            {:keys [ctx]} (make-ctx)
            p           (js/Promise.resolve
                          (handler ["git"] ctx))]
        (.then p (fn [_]
          (-> (expect (contains? (:active-skills @(:state agent)) "git"))
              (.toBe true)))))))

  (it "shows 'already active' if skill is already in :active-skills"
    (fn []
      (let [mock-skills (make-mock-skills)
            agent       (make-agent-with-skills mock-skills)
            ;; Pre-mark as active
            _           (swap! (:state agent) update :active-skills conj "git")
            handler     (get-in @(:commands agent) ["skill" :handler])
            {:keys [ctx notifications]} (make-ctx)]
        (handler ["git"] ctx)
        (-> (expect (:msg (last-notification notifications)))
            (.toContain "already active")))))

  (it "shows error for unknown skill"
    (fn []
      (let [agent   (make-agent-with-skills (make-mock-skills))
            handler (get-in @(:commands agent) ["skill" :handler])
            {:keys [ctx notifications]} (make-ctx)]
        (handler ["unknown-skill"] ctx)
        (-> (expect (:level (last-notification notifications)))
            (.toBe "error")))))

  (it "shows usage hint when called with no args"
    (fn []
      (let [agent   (make-agent-with-skills (make-mock-skills))
            handler (get-in @(:commands agent) ["skill" :handler])
            {:keys [ctx notifications]} (make-ctx)]
        (handler [] ctx)
        (-> (expect (:msg (last-notification notifications)))
            (.toContain "Usage")))))))

;;; ─── /skills command ─────────────────────────────────────────

(describe "commands-skills:skills-command" (fn []
  (it "shows 'No skills found' notification when skills map is empty"
    (fn []
      (let [agent   (make-agent-with-skills {})
            handler (get-in @(:commands agent) ["skills" :handler])
            {:keys [ctx notifications]} (make-ctx)]
        (handler [] ctx)
        (-> (expect (:msg (last-notification notifications)))
            (.toContain "No skills found")))))

  (it "calls ui.custom() with a picker object when skills are available"
    (fn []
      (let [agent   (make-agent-with-skills (make-mock-skills))
            handler (get-in @(:commands agent) ["skills" :handler])
            {:keys [ctx custom-calls]} (make-ctx)]
        (handler [] ctx)
        (-> (expect (count @custom-calls)) (.toBe 1))
        (-> (expect (.-render (first @custom-calls))) (.toBeDefined)))))

  (it "marks already-active skills with :active true in the picker list"
    (fn []
      (let [mock-skills (make-mock-skills)
            agent       (make-agent-with-skills mock-skills)
            ;; Pre-activate "git"
            _           (swap! (:state agent) update :active-skills conj "git")
            handler     (get-in @(:commands agent) ["skills" :handler])
            {:keys [ctx custom-calls]} (make-ctx)]
        (handler [] ctx)
        ;; Render the picker and check git appears as active (✓)
        (let [picker  (first @custom-calls)
              rendered ((.-render picker) 80 24)]
          (-> (expect (.includes rendered "\u2713")) (.toBe true))))))))

;;; ─── activate-skill unit tests ──────────────────────────────

(describe "commands-skills:activate-skill" (fn []
  (it "injects SKILL.md as a system message"
    (fn []
      (let [agent (create-agent {:model "test-model" :system-prompt "test"})
            mock-skills {"git" {:dir "/tmp/git"
                                :markdown "# Git\nInstructions here."
                                :has-tools false}}
            p     (skills/activate-skill mock-skills "git" agent)]
        (.then p (fn [_]
          (let [msgs (:messages @(:state agent))]
            (-> (expect (count (filter #(= (:role %) "system") msgs)))
                (.toBe 1))))))))

  (it "tracks activated skill in :active-skills"
    (fn []
      (let [agent (create-agent {:model "test-model" :system-prompt "test"})
            mock-skills {"git" {:dir "/tmp/git"
                                :markdown "# Git\nInstructions."
                                :has-tools false}}
            p     (skills/activate-skill mock-skills "git" agent)]
        (.then p (fn [_]
          (-> (expect (contains? (:active-skills @(:state agent)) "git"))
              (.toBe true)))))))

  (it "is a no-op if skill is already active (dedup)"
    (fn []
      (let [agent (create-agent {:model "test-model" :system-prompt "test"})
            mock-skills {"git" {:dir "/tmp/git"
                                :markdown "# Git\nInstructions."
                                :has-tools false}}
            p     (-> (skills/activate-skill mock-skills "git" agent)
                      (.then (fn [_] (skills/activate-skill mock-skills "git" agent))))]
        (.then p (fn [_]
          (let [system-msgs (filter #(and (= (:role %) "system")
                                         (= (:content %) "# Git\nInstructions."))
                                    (:messages @(:state agent)))]
            ;; Should only be injected once
            (-> (expect (count system-msgs)) (.toBe 1))))))))

  (it "returns nil for unknown skill"
    (fn []
      (let [agent (create-agent {:model "test-model" :system-prompt "test"})
            p     (skills/activate-skill {} "nonexistent" agent)]
        (.then (js/Promise.resolve p) (fn [result]
          (-> (expect result) (.toBeUndefined)))))))))

;;; ─── deactivate-skill unit tests ────────────────────────────

(describe "commands-skills:deactivate-skill" (fn []
  (it "removes skill from :active-skills"
    (fn []
      (let [agent (create-agent {:model "test-model" :system-prompt "test"})
            _     (swap! (:state agent) update :active-skills conj "git")]
        (skills/deactivate-skill "git" agent)
        (-> (expect (contains? (:active-skills @(:state agent)) "git"))
            (.toBe false)))))

  (it "is a no-op when deactivating a skill that was not active"
    (fn []
      (let [agent (create-agent {:model "test-model" :system-prompt "test"})]
        ;; Should not throw
        (skills/deactivate-skill "nonexistent" agent)
        (-> (expect (count (:active-skills @(:state agent))))
            (.toBe 0)))))))

;;; ─── first-skill-line ────────────────────────────────────────

(describe "commands-skills:first-skill-line" (fn []
  (it "returns first non-blank, non-heading line"
    (fn []
      (let [md "# Git Suite\nAutomates git workflows.\nMore text."]
        (-> (expect (skills/first-skill-line md))
            (.toBe "Automates git workflows.")))))

  (it "returns empty string for heading-only markdown"
    (fn []
      (-> (expect (skills/first-skill-line "# Title\n## Sub\n"))
          (.toBe ""))))

  (it "returns empty string for nil input"
    (fn []
      (-> (expect (skills/first-skill-line nil))
          (.toBe ""))))))
