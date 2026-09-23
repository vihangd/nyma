(ns skills-context-delivery.test
  "An activated skill's instructions have to reach the PROVIDER, not just the
   state map. `activate-skill` stores the body as a `role \"system\"` entry
   tagged `:skill`, and `build-context` used to filter messages down to
   user/assistant/tool_call/tool_result — so the entry was dropped on its way
   to the request. `/skill` therefore applied the skill's `allowed-tools`
   grant and delivered none of its instructions, and only the `skill` TOOL
   looked like it worked, because it returns the body as its tool result too.

   Every existing skills test asserted against `:messages`, which is exactly
   why this went unseen. These assert through `build-context`."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.core :refer [create-agent]]
            [agent.context :refer [build-context]]
            [agent.resources.skills :as skills]))

(def ^:private raw-skill
  (str "---\n"
       "name: deploy\n"
       "description: Ship it\n"
       "---\n"
       "# Deploy\nRun the deploy checklist."))

(defn- record [name raw]
  (let [{:keys [frontmatter body]} (skills/parse-frontmatter raw)]
    {:dir (str "/tmp/" name) :name name :markdown raw :body body
     :description (aget frontmatter "description")
     :has-tools false}))

(def ^:private test-skills {"deploy" (record "deploy" raw-skill)})

(defn- fresh-agent [] (create-agent {:model "test-model" :system-prompt "test"}))

(defn- context-text [agent]
  (str/join "\n" (map #(str (:content %)) (build-context agent))))

(describe "skills reach the provider" (fn []

                                        (it "an activated skill's body is in build-context, not only in :messages"
                                            (fn []
                                              (let [agent (fresh-agent)]
                                                (-> (.then (skills/activate-skill test-skills "deploy" agent)
                                                           (fn [_]
                                                             (-> (expect (.includes (context-text agent) "deploy checklist")) (.toBe true))
                       ;; and it is the tagged entry that carries it
                                                             (let [entry (first (filter :skill (build-context agent)))]
                                                               (-> (expect (:skill entry)) (.toBe "deploy")))))))))

                                        (it "deactivating removes it from build-context too"
                                            (fn []
                                              (let [agent (fresh-agent)]
                                                (-> (.then (skills/activate-skill test-skills "deploy" agent)
                                                           (fn [_]
                                                             (skills/deactivate-skill "deploy" agent)
                                                             (-> (expect (.includes (context-text agent) "deploy checklist")) (.toBe false))
                                                             (-> (expect (count (filter :skill (build-context agent)))) (.toBe 0))))))))

                                        (it "a system entry with no skill tag still does NOT reach the provider"
                                            (fn []
                                              (let [agent (fresh-agent)]
                                                (swap! (:state agent) update :messages conj
                                                       {:role "system" :content "internal bookkeeping"})
                                                (-> (expect (.includes (context-text agent) "internal bookkeeping")) (.toBe false)))))

                                        (it "a skill entry tagged :local-only is still withheld"
                                            (fn []
                                              (let [agent (fresh-agent)]
                                                (swap! (:state agent) update :messages conj
                                                       {:role "system" :skill "x" :content "hidden body" :local-only true})
                                                (-> (expect (.includes (context-text agent) "hidden body")) (.toBe false)))))

                                        (it "activating twice injects one copy"
                                            (fn []
                                              (let [agent (fresh-agent)]
                                                (-> (.then (skills/activate-skill test-skills "deploy" agent)
                                                           (fn [_] (skills/activate-skill test-skills "deploy" agent)))
                                                    (.then (fn [_]
                                                             (-> (expect (count (filter :skill (build-context agent)))) (.toBe 1))))))))))
